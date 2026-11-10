// internal/devmint/feilin_test.go
//
// Golden-vector tests for the device-token primitives.  The vectors come from
// .assets/resources/live_captures.md (captain-verified live traffic), so this
// file also cross-checks the Go port against the Python reference
// implementation in .assets/scripts/.
package devmint

import (
	"crypto/md5"
	"encoding/base64"
	"encoding/hex"
	"strconv"
	"strings"
	"testing"
)

// ---------- golden vectors (live_captures.md §3/§4) ----------

const (
	goldenDeviceConfig = "wN32kvF2iZuLdXqR9SvIIDy5WLu2p9xzJPVdtSxOaTAAFuQhfAzge1b2KK6jxvBs8dGMNR++31iG+CCS6kMRf9JYA4zuU4B5PpOiPliAnU23UQw5FN+DGQQeOwJ48T3HJy9LrGS4uz2g49qaDhLhatXK3dUOKqahUgwpj5hsLPx3qWfVeSCA8hKNrk54gtBkCoox4qBXZMbYM7zfOIU3rIskHnDS6ODyK+wX03GIGTRXDivevyfMLIsU73fFeBPic2vVwcgAhn6YQHo3ZPF5OvxCp39IYwlpND1oJqCg4jKJaCAzAy7FjHP+jXxansE8"
	goldenSessionKey   = "682efd18149e39a6"
	goldenSessionID    = "3795d28242a11619bc25f786f84e53d4-h-1788007723938-9472a56a25dc49bc8d669b22c6db0142"
	goldenServerTs     = int64(1788007723939)
	goldenIP           = "106.219.217.208"
	goldenToken        = "U0dfV0VCIzM3OTVkMjgyNDJhMTE2MTliYzI1Zjc4NmY4NGU1M2Q0LWgtMTc4ODAwNzcyMzkzOC05NDcyYTU2YTI1ZGM0OWJjOGQ2NjliMjJjNmRiMDE0MiN6a1hnQTBlZEliVDR0RkN2Ly94VHlvTmVQRC9YbEZzbWNUZTRVOWI3bEtDK1gxbmFmeG93a1oxYVRrWHFIRHhWUnpKYzZvcnZaUlorYmxtbzZzQ29pdDh1VEx6eWR6MzlPSGFBelpoR216TlMwcS9xYkc4R2pRS1JTdDdjTTBianpIZGE2UE52U1A1TmZvVHlNWGVadmV3a2N0OFNYSEQyWHVVWHRxanQweXFkTWdBWWVvc2w5M0k4NkVNNUc2NTVjS0hlamJnVFdSQ1dvb2xjTWVnOTlleU9qeEl5TXBXbE50SHQ5SkMybDcxOW9FandkZlpIVWpNUEJuOW9sT2JyYnd5UVdwdG5lQkNYQVJCaGpEMGNLeDIxN1VRTDBocGZ0MVMweDRROXZxZWlSSVVVN2FEN2kyNksxVUJ0T3VocVR2OWdPMUE3dVdpYzg5R0YwK2s3YWh2RjJMRWFWOEhCZkxCZHNSVEtmaG0rZXg3MWJVQ0hHWjF1WGlGUSszeTdPd0dOd2JMcVAzQkRSZUNVcWNuMStOdTBXT1kreldlZlZ0NnhrVldNQ3A1ZzZVYU1lL3VHd0d6M3crN3FCcUdIbG1DVTdGdlJOMVZiM2V6VEFQWlJtSU1ZUllzd3h2YXhheGJlMlE5Skx3UFdlOEg4bGxiWkxXZExYTFlocDNKWER1SDhGbUpES3AzRXJtVm1URGVxNkZkMkIvSFcrc0cxNExDU0V2UnBObTVFQ1FWREFZd0xQejFPZ3R3a0lqTS9zSFZpeXJGclBiY0krZys4OGxZdWQ4V3ZFZk1lc1lSVWxWZHhyb25MOHo3OGtHVWVtK25Sc2VVcVY1ZDlONjVqL2dPWnNtNm51dSsvMWt2aFgrbzdvdndzL2tDQTc0OGc1WFpSMUJCOHliZGZCOHBpcmlMUkVHR2lQSjEzYkw3WUZjUGRGSlNMRE1GV2srYmdhbDNpNXlvcHUzTGV5Y3hHRGJ3em5aQ3VyWVlhYXNtVkx4TURDU1VwOWpFYVdTOXcxOXd2TWdPYlpadHVWQXZ6eERscyt2dE82QmJ4RkZxNzJ2QjluOXVxSGJWMnhURlk0NUJxay9hMDhTQWpTVkFOMkJzZ1lKRFNBbGlmZWV4UHNPS2tDY1ZTU05wWTU1UlVxK0ozTkl4S1duST0jNDA1OCNhZjQzNDI5YzY4NjkzZTlhN2I1ZjIzMmM1OThlZGVjNQ=="
)

func TestDecryptDeviceConfig(t *testing.T) {
	s, err := decryptDeviceConfig(goldenDeviceConfig)
	if err != nil {
		t.Fatalf("decryptDeviceConfig: %v", err)
	}
	if s.SessionKey != goldenSessionKey {
		t.Errorf("sessionKey = %q, want %q", s.SessionKey, goldenSessionKey)
	}
	if s.SessionID != goldenSessionID {
		t.Errorf("sessionId = %q, want %q", s.SessionID, goldenSessionID)
	}
	if s.ServerTs != goldenServerTs {
		t.Errorf("serverTs = %d, want %d", s.ServerTs, goldenServerTs)
	}
	if s.IP != goldenIP {
		t.Errorf("ip = %q, want %q", s.IP, goldenIP)
	}
	// HC1: the sessionId embeds serverTs-1
	embedded := s.SessionID[strings.Index(s.SessionID, "-h-")+3:]
	if idx := strings.Index(embedded, "-"); idx > 0 {
		embedded = embedded[:idx]
	}
	if embedded != "1788007723938" {
		t.Errorf("HC1: embedded ts = %q, want 1788007723938", embedded)
	}
}

func TestGoldenTokenRoundTrip(t *testing.T) {
	// decrypt the golden token's payload and re-seal it byte-exactly
	raw, err := base64.StdEncoding.DecodeString(goldenToken)
	if err != nil {
		t.Fatalf("base64: %v", err)
	}
	parts := strings.Split(string(raw), "#")
	if len(parts) != 5 {
		t.Fatalf("token has %d fields, want 5", len(parts))
	}
	tF, Q, w, tC, p := parts[0], parts[1], parts[2], parts[3], parts[4]
	if tF != "SG_WEB" {
		t.Errorf("tF = %q", tF)
	}
	if Q != goldenSessionID {
		t.Errorf("Q = %q", Q)
	}
	if tC != "4058" {
		t.Errorf("tC = %q", tC)
	}
	seal := md5.Sum([]byte(strings.Join([]string{tF, Q, w, tC, md5Secret}, "#")))
	if hex.EncodeToString(seal[:]) != p {
		t.Errorf("md5 seal mismatch")
	}
	plain, err := decryptB64(goldenSessionKey, w)
	if err != nil {
		t.Fatalf("decrypt payload: %v", err)
	}
	fields := strings.Split(plain, "#")
	if len(fields) != 140 {
		t.Errorf("payload has %d fields, want 140", len(fields))
	}
	// re-encrypt must reproduce w byte-exactly
	w2, err := encryptB64(goldenSessionKey, plain)
	if err != nil {
		t.Fatalf("re-encrypt: %v", err)
	}
	if w2 != w {
		t.Errorf("re-encrypted w differs from the capture")
	}
}

func TestAESCBCRoundTrip(t *testing.T) {
	key := "0123456789abcdef"
	for _, pt := range []string{"", "a", "0123456789abcdef", strings.Repeat("x", 1000)} {
		ct, err := aesCBCEncrypt([]byte(key), []byte(pt))
		if err != nil {
			t.Fatalf("encrypt: %v", err)
		}
		if len(ct)%16 != 0 {
			t.Fatalf("ciphertext length %d is not a multiple of 16", len(ct))
		}
		out, err := aesCBCDecrypt([]byte(key), ct)
		if err != nil {
			t.Fatalf("decrypt: %v", err)
		}
		if string(out) != pt {
			t.Errorf("round trip mismatch for %q", pt)
		}
	}
}

func TestMintTokensAreDistinctAndVerifiable(t *testing.T) {
	s, err := decryptDeviceConfig(goldenDeviceConfig)
	if err != nil {
		t.Fatalf("decryptDeviceConfig: %v", err)
	}
	// use the golden payload as the template
	raw, _ := decryptB64(goldenSessionKey, mustW(t))
	tpl, err := newPayloadTemplate(strings.Split(raw, "#"))
	if err != nil {
		t.Fatalf("newPayloadTemplate: %v", err)
	}
	toks, err := mintTokens(s, tpl, 5, 0)
	if err != nil {
		t.Fatalf("mintTokens: %v", err)
	}
	if len(toks) != 5 {
		t.Fatalf("got %d tokens, want 5", len(toks))
	}
	seen := map[string]bool{}
	for i, tok := range toks {
		if seen[tok] {
			t.Errorf("token %d is a duplicate", i)
		}
		seen[tok] = true
		fields, ok, err := verifyToken(tok, s.SessionKey)
		if err != nil || !ok {
			t.Fatalf("verifyToken(%d): %v ok=%v", i, err, ok)
		}
		if len(fields) != 140 {
			t.Errorf("token %d payload has %d fields, want 140", i, len(fields))
		}
		// per-token invariants
		if fields[idxNow] == "" {
			t.Errorf("token %d: field %d empty", i, idxNow)
		}
		if fields[idxIP] != s.IP {
			t.Errorf("token %d: field %d (ip) = %q, want %q", i, idxIP, fields[idxIP], s.IP)
		}
		if fields[idxServerTs] != "1788007723939" {
			t.Errorf("token %d: field %d (serverTs) = %q", i, idxServerTs, fields[idxServerTs])
		}
		entries := strings.Split(fields[idxLog], "|")
		if len(entries) < 2 {
			t.Fatalf("token %d: field %d has no log entries", i, idxLog)
		}
		e93 := strings.SplitN(entries[len(entries)-2], "-", 2)[1]
		e94 := strings.SplitN(entries[len(entries)-1], "-", 2)[1]
		c93, _ := strconv.ParseInt(e93, 10, 64)
		c94, _ := strconv.ParseInt(e94, 10, 64)
		if c94 <= c93 {
			t.Errorf("token %d: entry 94 (%d) is not after entry 93 (%d)", i, c94, c93)
		}
		if c94-c93 > 15 {
			t.Errorf("token %d: entry 94 is %d ms after entry 93, want <= 15", i, c94-c93)
		}
		// entry-93 cost must equal field74 - field72 (the per-token arithmetic)
		now, _ := strconv.ParseInt(fields[idxNow], 10, 64)
		init, _ := strconv.ParseInt(fields[idxInitTime], 10, 64)
		if c93 != now-init {
			t.Errorf("token %d: entry 93 cost %d != field74 - field72 (%d)", i, c93, now-init)
		}
		// the payload's own 93/94 slots must stay empty
		if fields[idxEmpty93] != "" || fields[idxEmpty94] != "" {
			t.Errorf("token %d: payload slots 93/94 must stay empty", i)
		}
	}
}

func mustW(t *testing.T) string {
	t.Helper()
	raw, _ := base64.StdEncoding.DecodeString(goldenToken)
	return strings.Split(string(raw), "#")[2]
}

// ---------- harvest -> mint (the path used by POST /admin/mint) ----------

func TestParseHarvestFromBareConfigAndWrappedLog1(t *testing.T) {
	wrapped := `{"ResultObject":{"DeviceConfig":"` + goldenDeviceConfig + `"}}`
	for name, log1 := range map[string]string{"bare": goldenDeviceConfig, "wrapped": wrapped} {
		s, tpl, err := ParseHarvest(log1, goldenToken)
		if err != nil {
			t.Fatalf("%s: ParseHarvest: %v", name, err)
		}
		if s.SessionID != goldenSessionID {
			t.Errorf("%s: SessionID = %q", name, s.SessionID)
		}
		if s.GatherCost != "4058" {
			t.Errorf("%s: GatherCost = %q, want the token's own 4058", name, s.GatherCost)
		}
		if len(tpl.Fields()) < 90 {
			t.Errorf("%s: template has %d fields", name, len(tpl.Fields()))
		}
	}
}

func TestMintFromHarvestTokensVerifyAndAreDistinct(t *testing.T) {
	m, err := MintFromHarvest(goldenDeviceConfig, goldenToken, 25)
	if err != nil {
		t.Fatalf("MintFromHarvest: %v", err)
	}
	if m.Genuine != goldenToken {
		t.Error("genuine token must be returned unchanged")
	}
	if len(m.Tokens) != 25 {
		t.Fatalf("minted %d tokens, want 25", len(m.Tokens))
	}
	seen := map[string]bool{goldenToken: true}
	for i, tok := range m.Tokens {
		if seen[tok] {
			t.Fatalf("token %d duplicates an earlier one", i)
		}
		seen[tok] = true
		if _, ok, err := VerifyToken(tok, goldenSessionKey); err != nil || !ok {
			t.Fatalf("token %d does not verify: ok=%v err=%v", i, ok, err)
		}
	}
}

func TestMintFromHarvestZeroOnlyValidates(t *testing.T) {
	m, err := MintFromHarvest(goldenDeviceConfig, goldenToken, 0)
	if err != nil || len(m.Tokens) != 0 {
		t.Fatalf("n=0: tokens=%d err=%v", len(m.Tokens), err)
	}
}

func TestMintFromHarvestRejectsGarbage(t *testing.T) {
	cases := map[string][2]string{
		"empty log1":      {"", goldenToken},
		"not a config":    {"AAAA", goldenToken},
		"json w/o config": {`{"ResultObject":{}}`, goldenToken},
		"garbage token":   {goldenDeviceConfig, "not-base64!"},
		"tampered seal":   {goldenDeviceConfig, goldenToken[:len(goldenToken)-8] + "AAAAAAAA"},
	}
	for name, c := range cases {
		if _, err := MintFromHarvest(c[0], c[1], 3); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
	if _, err := MintFromHarvest(goldenDeviceConfig, goldenToken, -1); err == nil {
		t.Error("negative n must be rejected")
	}
}

// A phone's payload may carry another address in slot 42 than the one the
// server saw (dual-stack, mobile NAT). That alone must not reject the visit,
// and minting must keep the slot exactly as the genuine token had it.
func TestParseHarvestToleratesDifferentIPSlot(t *testing.T) {
	s, tpl, err := ParseHarvest(goldenDeviceConfig, goldenToken)
	if err != nil {
		t.Fatal(err)
	}
	f := append([]string(nil), tpl.Fields()...)
	f[idxIP] = "2409:40f4:1234::1"
	if err := checkLayout(&s, f); err != nil {
		t.Fatalf("checkLayout rejected a differing IP slot: %v", err)
	}
	if note := ipNote(&s, f); !strings.Contains(note, "ip-slot=ipv6") || strings.Contains(note, "2409") {
		t.Errorf("ipNote = %q", note)
	}
	tpl2, _ := newPayloadTemplate(f)
	toks, err := mintTokens(s, tpl2, 3, 0)
	if err != nil {
		t.Fatal(err)
	}
	for i, tok := range toks {
		out, ok, err := verifyToken(tok, s.SessionKey)
		if err != nil || !ok {
			t.Fatalf("token %d: ok=%v err=%v", i, ok, err)
		}
		if out[idxIP] != "2409:40f4:1234::1" {
			t.Errorf("token %d: IP slot was rewritten", i)
		}
	}
}
