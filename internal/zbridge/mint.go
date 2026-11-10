package zbridge

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

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
//	 "token": "<the genuine device token>",
//	 "n": 200,
//	 "verify": true}
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
	mintMaxBodyBytes = 512 << 10
)

// mintFromHarvest is a seam so tests can run the handler without real
// captured vectors.
var mintFromHarvest = devmint.MintFromHarvest

// mintVerify spends one token on a full Aliyun round trip (a seam for tests).
// It returns the final payload, empty when Aliyun rejected the token.
var mintVerify = tryCompute

// mintVerifyProbes is how many minted tokens a verification may spend.
const mintVerifyProbes = 2

type mintRequest struct {
	Log1   string `json:"log1"`
	Token  string `json:"token"`
	N      int    `json:"n"`
	Verify bool   `json:"verify"`
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
	if req.Log1 == "" || req.Token == "" {
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

	minted, err := mintFromHarvest(req.Log1, req.Token, req.N)
	if err != nil {
		// The error text names the failed validation step only; it never
		// echoes the submitted token or session material.
		writeJSON(w, http.StatusUnprocessableEntity, map[string]interface{}{
			"error": "harvest rejected: " + err.Error(),
		})
		return
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
		logInfo("mint: " + minted.Layout + " minted=" + verdict + " genuine=" + genuineVerdict)
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

	logInfo(fmt.Sprintf("mint: inserted %d device tokens (%d minted), minted=%s genuine=%s", len(all), len(minted.Tokens), verdict, genuineVerdict))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"inserted":   len(all),
		"minted":     len(minted.Tokens),
		"tokenCount": globalDBState.tokenCount(),
		"verified":   verdict,
		"genuine":    genuineVerdict,
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
