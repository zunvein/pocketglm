package zbridge

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"

	"zai-api/internal/devmint"
)

// POST /admin/mint — turn ONE browser visit into a batch of device tokens.
//
// A chat request consumes one device token, and the Android app can only
// obtain a single genuine token per WebView visit. The VPS tooling gets
// around that by capturing the visit's trusted session (the Log1 response)
// plus its genuine token and minting the rest in-process (internal/devmint).
// This endpoint lets the app do the same: it posts what the WebView saw, the
// bridge validates it, mints n more tokens and inserts everything into the
// live token pool.
//
// Auth: same API key as the other endpoints.
//
//	{"log1": "<Log1 response body or bare DeviceConfig>",
//	 "log1s": ["<every Log1 body the WebView saw>"],
//	 "token": "<the genuine device token>",
//	 "n": 200,
//	 "verify": true}
//
// A phone visit can register the device more than once, and the token is sealed
// against one of those registrations; the app therefore sends every Log1 body it
// captured and the bridge keeps the one that seals the token. "log1" alone is
// still accepted for single-capture clients.
//
// -> {"inserted": 201, "minted": 200, "tokenCount": 204, "verified": "ok"}
//
// With "verify" the bridge spends up to two of the freshly minted tokens on a
// real Aliyun verification BEFORE inserting the rest. If Aliyun rejects them,
// the minted batch is dropped (and the genuine token is probed once to tell
// "minting is wrong" from "this device is rejected") so a bad batch can never
// fill the pool with tokens that every chat request then burns through (a
// failing request burns up to 5). "verified" is one of ok | rejected | inconclusive | skipped.

const (
	mintMaxN         = 1000
	mintMaxBodyBytes = 1 << 20
)

// mintFromHarvest is a seam so tests can run the handler without real
// captured vectors.
var mintFromHarvest = devmint.MintFromHarvestAny

// mintVerify spends one token on a full Aliyun round trip (a seam for tests).
// It returns the final payload, empty when Aliyun rejected the token.
var mintVerify = tryCompute

// mintVerifyProbes is how many minted tokens a verification may spend.
const mintVerifyProbes = 2

type mintRequest struct {
	Log1   string   `json:"log1"`
	Log1s  []string `json:"log1s"`
	Token  string   `json:"token"`
	N      int      `json:"n"`
	Verify bool     `json:"verify"`
}

// log1Candidates merges "log1" and "log1s" into one list, dropping blanks and
// duplicates. The devmint side caps the length.
func log1Candidates(req mintRequest) []string {
	out := make([]string, 0, len(req.Log1s)+1)
	seen := make(map[string]struct{}, len(req.Log1s)+1)
	for _, l := range append([]string{req.Log1}, req.Log1s...) {
		if l == "" {
			continue
		}
		if _, dup := seen[l]; dup {
			continue
		}
		seen[l] = struct{}{}
		out = append(out, l)
	}
	return out
}

// verifyMinted checks the batch against Aliyun with up to mintVerifyProbes
// tokens. It returns the verdict plus how many tokens it consumed.
//
//	ok           at least one probe was accepted
//	rejected     every probe got an answer and Aliyun refused all of them
//	inconclusive no probe got a clean answer (network trouble, etc.)
func verifyMinted(tokens []string) (string, int) {
	probes := len(tokens)
	if probes > mintVerifyProbes {
		probes = mintVerifyProbes
	}
	rejected, errored := 0, 0
	for i := 0; i < probes; i++ {
		payload, err := mintVerify(tokens[i])
		switch {
		case err != nil:
			errored++
			logError("mint: verify probe errored: " + err.Error())
		case payload != "":
			return "ok", i + 1
		default:
			rejected++
		}
	}
	if rejected > 0 && errored == 0 {
		return "rejected", probes
	}
	return "inconclusive", probes
}

func mintHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{
			"error": "use POST",
		})
		return
	}

	var req mintRequest
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, mintMaxBodyBytes))
	if err != nil {
		writeJSON(w, http.StatusRequestEntityTooLarge, map[string]interface{}{
			"error": "request body too large",
		})
		return
	}
	if err := json.Unmarshal(body, &req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"error": "invalid JSON body",
		})
		return
	}
	if req.Token == "" {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"error": "log1 and token are required",
		})
		return
	}
	log1s := log1Candidates(req)
	if len(log1s) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"error": "log1 and token are required",
		})
		return
	}
	if req.N < 0 || req.N > mintMaxN {
		writeJSON(w, http.StatusBadRequest, map[string]interface{}{
			"error": fmt.Sprintf("n must be between 0 and %d", mintMaxN),
		})
		return
	}

	minted, err := mintFromHarvest(log1s, req.Token, req.N)
	if err != nil {
		// The error text names the failed validation step only; it never
		// echoes the submitted token or session material.
		writeJSON(w, http.StatusUnprocessableEntity, map[string]interface{}{
			"error": "harvest rejected: " + err.Error(),
		})
		return
	}
	if minted.Tried == 0 {
		minted.Tried = len(log1s)
	}

	verdict, genuineVerdict := "skipped", "skipped"
	genuineSpent := false
	if req.Verify && len(minted.Tokens) > 0 {
		var used int
		verdict, used = verifyMinted(minted.Tokens)
		if verdict == "rejected" {
			logError(fmt.Sprintf("mint: Aliyun rejected %d probe(s) of the minted batch; dropping it", used))
			minted.Tokens = nil
			// Diagnosis: does Aliyun accept the browser's own token? If yes the
			// minting is at fault; if no, this device's fingerprint is. The
			// probe spends that token, which is fine: a rejected batch means
			// the visit has nothing else to offer anyway.
			genuineVerdict = "inconclusive"
			if payload, err := mintVerify(minted.Genuine); err != nil {
				logError("mint: genuine probe errored: " + err.Error())
			} else if payload != "" {
				genuineVerdict, genuineSpent = "ok", true
			} else {
				genuineVerdict, genuineSpent = "rejected", true
			}
		} else if verdict == "inconclusive" {
			// Aliyun did not confirm the batch, so none of it goes in the pool.
			// The browser's own token is still kept.
			logError("mint: batch not confirmed by Aliyun; dropping it, keeping the genuine token")
			minted.Tokens = nil
		} else {
			minted.Tokens = minted.Tokens[used:]
		}
		logInfo("mint: " + minted.Layout + " session=" + strconv.Itoa(minted.Session+1) + "/" + strconv.Itoa(minted.Tried) + " minted=" + verdict + " genuine=" + genuineVerdict)
	}

	all := make([]string, 0, 1+len(minted.Tokens))
	if !genuineSpent {
		all = append(all, minted.Genuine)
	}
	all = append(all, minted.Tokens...)

	if err := insertTokens(all); err != nil {
		logError("mint: insert failed: " + err.Error())
		writeJSON(w, http.StatusServiceUnavailable, map[string]interface{}{
			"error": "could not write to the token pool: " + err.Error(),
		})
		return
	}
	invalidateTokenCount()

	logInfo(fmt.Sprintf("mint: inserted %d device tokens (%d minted) from session %d/%d, minted=%s genuine=%s",
		len(all), len(minted.Tokens), minted.Session+1, minted.Tried, verdict, genuineVerdict))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"inserted":   len(all),
		"minted":     len(minted.Tokens),
		"tokenCount": globalDBState.tokenCount(),
		"verified":   verdict,
		"genuine":    genuineVerdict,
		"sessions":   minted.Tried,
		"session":    minted.Session + 1,
		"tsSkewMs":   minted.SkewMs,
	})
}

// insertTokens appends tokens to the live pool in one transaction.
func insertTokens(tokens []string) error {
	return withTokenDB(func(h dbHandle) error {
		tx, err := h.db.Begin()
		if err != nil {
			return err
		}
		stmt, err := tx.Prepare("INSERT INTO tokens (token) VALUES (?)")
		if err != nil {
			_ = tx.Rollback()
			return err
		}
		defer stmt.Close()
		for _, t := range tokens {
			if _, err := stmt.Exec(t); err != nil {
				_ = tx.Rollback()
				return err
			}
		}
		if err := tx.Commit(); err != nil && err != sql.ErrTxDone {
			return err
		}
		return nil
	})
}
