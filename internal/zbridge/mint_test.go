package zbridge

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"zai-api/internal/devmint"
)

// attachProdSchemaDB attaches an empty pool with the PRODUCTION schema
// (collector / Android): tokens(id, token) - no batch column.
func attachProdSchemaDB(t *testing.T) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "tokens.sqlite")
	db, err := openTokenDB(path)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if _, err := db.Exec(`CREATE TABLE tokens (
        id    INTEGER PRIMARY KEY AUTOINCREMENT,
        token TEXT    NOT NULL
    )`); err != nil {
		t.Fatalf("schema: %v", err)
	}
	attachDB(db, path)
	t.Cleanup(func() { closeDB() })
}

func withMintStub(t *testing.T, fn func(log1, token string, n int) (*devmint.Minted, error)) {
	t.Helper()
	old := mintFromHarvest
	mintFromHarvest = fn
	t.Cleanup(func() { mintFromHarvest = old })
}

func postMint(t *testing.T, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/admin/mint", strings.NewReader(body))
	rec := httptest.NewRecorder()
	mintHandler(rec, req)
	return rec
}

func TestMintHandlerInsertsGenuinePlusMinted(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, func(log1, token string, n int) (*devmint.Minted, error) {
		toks := make([]string, n)
		for i := range toks {
			toks[i] = "minted-" + string(rune('a'+i%26)) + "-" + string(rune('0'+i/26))
		}
		return &devmint.Minted{Genuine: token, Tokens: toks}, nil
	})

	rec := postMint(t, `{"log1":"L","token":"genuine","n":5}`)
	if rec.Code != 200 {
		t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
	}
	var out map[string]interface{}
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	if out["inserted"].(float64) != 6 || out["minted"].(float64) != 5 {
		t.Fatalf("response = %v", out)
	}
	if got := globalDBState.tokenCount(); got != 6 {
		t.Fatalf("tokenCount = %d, want 6 (cache must be invalidated)", got)
	}
	// the genuine token is in the pool and gets consumed like any other
	found := false
	for i := 0; i < 20 && !found; i++ {
		tok, ok := getNextToken()
		if !ok {
			break
		}
		found = tok == "genuine"
		removeToken(tok)
	}
	if !found {
		t.Error("genuine token was not inserted")
	}
}

func TestMintHandlerValidation(t *testing.T) {
	attachProdSchemaDB(t)
	called := false
	withMintStub(t, func(string, string, int) (*devmint.Minted, error) {
		called = true
		return &devmint.Minted{Genuine: "g"}, nil
	})

	cases := map[string]struct {
		body string
		code int
	}{
		"bad json":      {`{`, 400},
		"missing log1":  {`{"token":"t","n":1}`, 400},
		"missing token": {`{"log1":"l","n":1}`, 400},
		"n too big":     {`{"log1":"l","token":"t","n":100000}`, 400},
		"n negative":    {`{"log1":"l","token":"t","n":-1}`, 400},
	}
	for name, c := range cases {
		if rec := postMint(t, c.body); rec.Code != c.code {
			t.Errorf("%s: status %d, want %d", name, rec.Code, c.code)
		}
	}
	if called {
		t.Error("validation failures must not reach the minter")
	}

	req := httptest.NewRequest(http.MethodGet, "/admin/mint", nil)
	rec := httptest.NewRecorder()
	mintHandler(rec, req)
	if rec.Code != 405 {
		t.Errorf("GET: status %d, want 405", rec.Code)
	}
}

func TestMintHandlerRejectsBadHarvestWithoutEchoingSecrets(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, func(string, string, int) (*devmint.Minted, error) {
		return nil, errors.New("md5 seal mismatch")
	})
	rec := postMint(t, `{"log1":"SECRET-LOG1","token":"SECRET-TOKEN","n":3}`)
	if rec.Code != 422 {
		t.Fatalf("status %d, want 422", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "SECRET") {
		t.Error("response must not echo submitted material")
	}
	if got := globalDBState.tokenCount(); got != 0 {
		t.Errorf("pool must stay empty on rejection, has %d", got)
	}
}

func TestMintRouteRequiresAuth(t *testing.T) {
	attachProdSchemaDB(t)
	h := NewHandler()
	req := httptest.NewRequest(http.MethodPost, "/admin/mint", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != 401 {
		t.Fatalf("unauthenticated mint: status %d, want 401", rec.Code)
	}
}

func withVerifyStub(t *testing.T, fn func(token string) (string, error)) {
	t.Helper()
	old := mintVerify
	mintVerify = fn
	t.Cleanup(func() { mintVerify = old })
}

func stubBatch(n int) func(log1, token string, nn int) (*devmint.Minted, error) {
	return func(log1, token string, nn int) (*devmint.Minted, error) {
		toks := make([]string, n)
		for i := range toks {
			toks[i] = "m-" + string(rune('a'+i))
		}
		return &devmint.Minted{Genuine: token, Tokens: toks}, nil
	}
}

func TestMintVerifyOkSpendsOneProbeAndPoolsTheRest(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, stubBatch(5))
	calls := 0
	withVerifyStub(t, func(string) (string, error) { calls++; return "payload", nil })

	rec := postMint(t, `{"log1":"L","token":"genuine","n":5,"verify":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"verified":"ok"`) {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
	if calls != 1 {
		t.Fatalf("probes spent = %d, want 1", calls)
	}
	// genuine + (5 minted - 1 probe)
	if got := globalDBState.tokenCount(); got != 5 {
		t.Fatalf("pool = %d, want 5", got)
	}
}

func TestMintVerifyRejectedDropsTheBatchKeepsGenuine(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, stubBatch(5))
	withVerifyStub(t, func(string) (string, error) { return "", nil })

	rec := postMint(t, `{"log1":"L","token":"genuine","n":5,"verify":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"verified":"rejected"`) {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
	if got := globalDBState.tokenCount(); got != 0 {
		t.Fatalf("pool = %d, want 0 (batch dropped, genuine spent on the diagnosis probe)", got)
	}
	if !strings.Contains(rec.Body.String(), `"genuine":"rejected"`) {
		t.Fatalf("genuine verdict missing: %s", rec.Body.String())
	}
}

func TestMintVerifyInconclusiveStillPoolsBatch(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, stubBatch(4))
	withVerifyStub(t, func(string) (string, error) { return "", errors.New("network down") })

	rec := postMint(t, `{"log1":"L","token":"genuine","n":4,"verify":true}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"verified":"inconclusive"`) {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
	// everything errored: nothing was spent for sure, so everything is pooled
	// except the 2 minted probes that were attempted
	if got := globalDBState.tokenCount(); got != 3 {
		t.Fatalf("pool = %d, want 3 (genuine + 4 minted - 2 probes)", got)
	}
}

func TestMintWithoutVerifyNeverCallsAliyun(t *testing.T) {
	attachProdSchemaDB(t)
	withMintStub(t, stubBatch(3))
	withVerifyStub(t, func(string) (string, error) { t.Fatal("verify called"); return "", nil })

	rec := postMint(t, `{"log1":"L","token":"genuine","n":3}`)
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"verified":"skipped"`) {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
}
