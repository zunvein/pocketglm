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
//	 "n": 200}
//
// -> {"inserted": 201, "minted": 200, "tokenCount": 204}

const (
	mintMaxN         = 1000
	mintMaxBodyBytes = 512 << 10
)

// mintFromHarvest is a seam so tests can run the handler without real
// captured vectors.
var mintFromHarvest = devmint.MintFromHarvest

type mintRequest struct {
	Log1  string `json:"log1"`
	Token string `json:"token"`
	N     int    `json:"n"`
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

	all := make([]string, 0, 1+len(minted.Tokens))
	all = append(all, minted.Genuine)
	all = append(all, minted.Tokens...)

	if err := insertTokens(all); err != nil {
		logError("mint: insert failed: " + err.Error())
		writeJSON(w, http.StatusServiceUnavailable, map[string]interface{}{
			"error": "could not write to the token pool: " + err.Error(),
		})
		return
	}
	invalidateTokenCount()

	logInfo(fmt.Sprintf("mint: inserted %d device tokens (1 genuine + %d minted)", len(all), len(minted.Tokens)))
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"inserted":   len(all),
		"minted":     len(minted.Tokens),
		"tokenCount": globalDBState.tokenCount(),
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
