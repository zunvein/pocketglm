// internal/devmint/harvest_skew_test.go
//
// A phone visit can register the device more than once per page load (the page
// has its own captcha, the app initialises a second instance). The token is
// sealed against one of those registrations, while the WebView can only hand us
// the Log1 body it saw, so the token's server timestamp may be newer than the
// captured session's. That pair used to be rejected outright ("slot 87 is not
// the server timestamp"), which threw away the whole visit's batch. These tests
// pin the behaviour that replaced it: the token wins inside a sane window, an
// unrelated registration is still refused, and a visit that captured several
// Log1 bodies mints from the one that actually seals the token.
package devmint

import (
	"strconv"
	"strings"
	"testing"
)

// skewedToken re-seals the golden token's payload with another per-session
// server timestamp, the way a second registration on the same device does.
func skewedToken(t *testing.T, skewMs int64) string {
	t.Helper()
	fields, ok, err := verifyToken(goldenToken, goldenSessionKey)
	if err != nil || !ok {
		t.Fatalf("golden token: ok=%v err=%v", ok, err)
	}
	f := append([]string(nil), fields...)
	ts := goldenServerTs + skewMs
	f[idxServerTs] = strconv.FormatInt(ts, 10)
	f[idxInitTime] = strconv.FormatInt(ts-6606, 10)
	s := deviceSession{
		SessionKey: goldenSessionKey,
		SessionID:  goldenSessionID,
		ServerTs:   ts,
		InitTime:   ts - 6606,
		Region:     "SG_WEB",
		GatherCost: "4058",
	}
	tok, err := assembleToken(s, f)
	if err != nil {
		t.Fatalf("assembleToken: %v", err)
	}
	return tok
}

// A newer server timestamp in the genuine token must be absorbed, not rejected,
// and the whole batch must be minted against it.
func TestParseHarvestReconcilesNewerServerTs(t *testing.T) {
	const skew = int64(4127)
	tok := skewedToken(t, skew)

	s, _, err := ParseHarvest(goldenDeviceConfig, tok)
	if err != nil {
		t.Fatalf("ParseHarvest rejected a same-session skew: %v", err)
	}
	if want := goldenServerTs + skew; s.ServerTs != want {
		t.Fatalf("ServerTs = %d, want the token's %d", s.ServerTs, want)
	}

	m, err := MintFromHarvestAny([]string{goldenDeviceConfig}, tok, 5)
	if err != nil {
		t.Fatalf("MintFromHarvestAny: %v", err)
	}
	if m.SkewMs != skew {
		t.Errorf("SkewMs = %d, want %d", m.SkewMs, skew)
	}
	if !strings.Contains(m.Layout, "ts-skew=") {
		t.Errorf("layout summary should report the skew it absorbed: %q", m.Layout)
	}
	if len(m.Tokens) != 5 {
		t.Fatalf("minted %d tokens, want 5", len(m.Tokens))
	}
	for i, out := range m.Tokens {
		f, ok, err := verifyToken(out, goldenSessionKey)
		if err != nil || !ok {
			t.Fatalf("token %d: ok=%v err=%v", i, ok, err)
		}
		if f[idxServerTs] != strconv.FormatInt(goldenServerTs+skew, 10) {
			t.Errorf("token %d: serverTs slot = %q, want the reconciled session value", i, f[idxServerTs])
		}
	}
}

// An older token timestamp is just as valid a registration, as long as the skew
// stays inside the window.
func TestParseHarvestReconcilesOlderServerTs(t *testing.T) {
	tok := skewedToken(t, -2500)
	if _, _, err := ParseHarvest(goldenDeviceConfig, tok); err != nil {
		t.Fatalf("ParseHarvest rejected a same-session skew: %v", err)
	}
}

// A token whose server timestamp is hours away cannot belong to the captured
// session: minting from that pair would produce tokens Aliyun refuses, so the
// visit is still rejected — with the skew named, never the values.
func TestParseHarvestRejectsUnrelatedServerTs(t *testing.T) {
	tok := skewedToken(t, 2*60*60*1000)
	_, _, err := ParseHarvest(goldenDeviceConfig, tok)
	if err == nil {
		t.Fatal("expected a rejection for a 2h skew")
	}
	msg := err.Error()
	if !strings.Contains(msg, "slot 87") || !strings.Contains(msg, "away from the captured session") {
		t.Fatalf("error should name the slot and the skew: %q", msg)
	}
	if strings.Contains(msg, strconv.FormatInt(goldenServerTs, 10)) {
		t.Fatalf("error must not leak timestamp values: %q", msg)
	}
}

// The visit offers several Log1 bodies; only the one whose session key seals the
// token can mint, and it is found by trying them in order.
func TestMintFromHarvestAnyPicksTheSealingLog1(t *testing.T) {
	bad := `{"ResultObject":{"DeviceConfig":"AAAA"}}`
	m, err := MintFromHarvestAny([]string{bad, goldenDeviceConfig}, goldenToken, 0)
	if err != nil {
		t.Fatalf("MintFromHarvestAny: %v", err)
	}
	if m.Session != 1 {
		t.Errorf("Session = %d, want 1 (the second candidate)", m.Session)
	}
	if m.Tried != 2 {
		t.Errorf("Tried = %d, want 2", m.Tried)
	}
	if m.Genuine != goldenToken {
		t.Error("genuine token must be returned unchanged")
	}
}

// Duplicates collapse and the candidate list is capped, so a noisy page cannot
// make one request validate an unbounded number of sessions.
func TestMintFromHarvestAnyDedupesAndCaps(t *testing.T) {
	m, err := MintFromHarvestAny([]string{goldenDeviceConfig, goldenDeviceConfig, " "}, goldenToken, 0)
	if err != nil {
		t.Fatalf("MintFromHarvestAny: %v", err)
	}
	if m.Tried != 1 {
		t.Errorf("Tried = %d, want 1 after dedupe", m.Tried)
	}

	many := []string{`{"ResultObject":{"DeviceConfig":"AAAA"}}`,
		`{"ResultObject":{"DeviceConfig":"BBBB"}}`,
		`{"ResultObject":{"DeviceConfig":"CCCC"}}`,
		`{"ResultObject":{"DeviceConfig":"DDDD"}}`,
		`{"ResultObject":{"DeviceConfig":"EEEE"}}`}
	_, err = MintFromHarvestAny(many, goldenToken, 0)
	if err == nil {
		t.Fatal("expected a rejection")
	}
	if !strings.HasPrefix(err.Error(), "4 Log1 candidate(s) tried:") {
		t.Fatalf("candidate cap not applied: %q", err.Error())
	}
}

// Several candidates that all fail must produce one readable reason, not a
// repeated one per candidate.
func TestMintFromHarvestAnyReportsDistinctReasons(t *testing.T) {
	_, err := MintFromHarvestAny([]string{"AAAA", "BBBB"}, goldenToken, 1)
	if err == nil {
		t.Fatal("expected a rejection")
	}
	if strings.Count(err.Error(), ";") > 1 {
		t.Errorf("duplicate reasons should collapse: %q", err.Error())
	}
	if !strings.Contains(err.Error(), "2 Log1 candidate(s) tried:") {
		t.Errorf("error should report how many candidates failed: %q", err.Error())
	}
}
