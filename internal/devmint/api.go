// internal/devmint/api.go
//
// Exported surface of the device-token minting primitives.
//
// The primitives (feilin.go) are shared by two programs:
//
//   - cmd/token-collector (VPS, Playwright browser) and
//   - the bridge itself (POST /admin/mint), fed by the Android app's
//     WebView, which can only perform the one browser visit - the batch of
//     tokens is then produced here, in-process.

package devmint

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// SceneID is the Z.AI captcha scene the SDK has to be initialised with.
const SceneID = sceneID

// DeviceSession is the trusted device registration from the Log1 response.
type DeviceSession = deviceSession

// PayloadTemplate is the genuine collector payload captured from a browser.
type PayloadTemplate = payloadTemplate

// Fields returns the template's '#'-split payload fields.
func (t *PayloadTemplate) Fields() []string { return t.fields }

func DecryptDeviceConfig(blob string) (DeviceSession, error) { return decryptDeviceConfig(blob) }

func VerifyToken(token, sessionKey string) ([]string, bool, error) {
	return verifyToken(token, sessionKey)
}

func NewPayloadTemplate(fields []string) (*PayloadTemplate, error) {
	return newPayloadTemplate(fields)
}

func MintTokens(s DeviceSession, tpl *PayloadTemplate, n int, jitterMs int) ([]string, error) {
	return mintTokens(s, tpl, n, jitterMs)
}

// deviceConfigFrom accepts either the whole Log1 response body
// ({"ResultObject":{"DeviceConfig":"..."}}) or the bare DeviceConfig blob.
func deviceConfigFrom(log1 string) (string, error) {
	log1 = strings.TrimSpace(log1)
	if log1 == "" {
		return "", errors.New("empty Log1 response")
	}
	if !strings.HasPrefix(log1, "{") {
		return log1, nil
	}
	var lr struct {
		ResultObject struct {
			DeviceConfig string `json:"DeviceConfig"`
		} `json:"ResultObject"`
	}
	if err := json.Unmarshal([]byte(log1), &lr); err != nil {
		return "", fmt.Errorf("parse Log1 response: %w", err)
	}
	if lr.ResultObject.DeviceConfig == "" {
		return "", errors.New("Log1 response carried no DeviceConfig")
	}
	return lr.ResultObject.DeviceConfig, nil
}

// maxServerTsSkewMs bounds how far the genuine token's server timestamp may
// sit from the captured session's own. A browser visit can register the device
// more than once (the page's own captcha and the app's instance), and the token
// is then sealed against the newest registration while the first Log1 response
// is the one the WebView handed us: a small, positive skew is normal and the
// token is authoritative. A large one means the two captures are unrelated, and
// minting from that pair would produce tokens Aliyun rejects.
const maxServerTsSkewMs = 30 * 60 * 1000

// ParseHarvest turns one browser visit (the Log1 response and the one genuine
// device token the SDK produced) into a trusted session plus the payload
// template that minting needs.
func ParseHarvest(log1, token string) (DeviceSession, *PayloadTemplate, error) {
	session, tpl, _, err := parseHarvest(log1, token)
	return session, tpl, err
}

// parseHarvest is ParseHarvest plus the skew it had to absorb (0 when the
// token's server timestamp matched the captured session exactly).
func parseHarvest(log1, token string) (DeviceSession, *PayloadTemplate, int64, error) {
	var zero DeviceSession
	blob, err := deviceConfigFrom(log1)
	if err != nil {
		return zero, nil, 0, err
	}
	session, err := decryptDeviceConfig(blob)
	if err != nil {
		return zero, nil, 0, err
	}
	token = strings.TrimSpace(token)
	fields, ok, err := verifyToken(token, session.SessionKey)
	if err != nil {
		return zero, nil, 0, fmt.Errorf("decrypt captured device token: %w", err)
	}
	if !ok {
		return zero, nil, 0, errors.New("captured device token failed its md5 seal")
	}
	tpl, err := newPayloadTemplate(fields)
	if err != nil {
		return zero, nil, 0, err
	}
	// The minting index map (42=ip, 43=log, 72=init, 74=now, 87=serverTs) was
	// derived from desktop captures. A browser build with a different field
	// layout would get its wrong slots overwritten and mint tokens Aliyun
	// rejects, so check the genuine token against the session first. The
	// session timestamps are then taken from the token, which is the copy
	// Aliyun itself sealed.
	skew, err := checkLayout(&session, tpl.fields)
	if err != nil {
		return zero, nil, 0, err
	}
	// The gap between initTime and serverTs is a per-session fact: read it
	// from the genuine payload instead of assuming the desktop value.
	if init, perr := strconv.ParseInt(tpl.fields[idxInitTime], 10, 64); perr == nil {
		if gap := session.ServerTs - init; gap > 0 && gap < 120_000 {
			session.InitTime = init
		}
	}
	// The GatherCost (tC) is a per-SESSION constant; take it from the token
	// the SDK itself produced rather than inventing one.
	if raw, derr := base64.StdEncoding.DecodeString(token); derr == nil {
		if parts := strings.Split(string(raw), "#"); len(parts) == 5 {
			if gc, cerr := strconv.Atoi(parts[3]); cerr == nil && gc > 0 {
				session.GatherCost = parts[3]
			}
		}
	}
	return session, tpl, skew, nil
}

// Minted is the result of MintFromHarvest / MintFromHarvestAny.
type Minted struct {
	Genuine string   // the browser's own token (valid on its own)
	Tokens  []string // n additional tokens bound to the same session
	Layout  string   // value-free harvest description for logs
	SkewMs  int64    // token serverTs minus the captured session's (normally >= 0)
	Session int      // index of the Log1 candidate that sealed the token
	Tried   int      // how many Log1 candidates were offered
}

// MintFromHarvest validates a browser harvest and mints n further tokens.
// n == 0 only validates and returns the genuine token.
func MintFromHarvest(log1, token string, n int) (*Minted, error) {
	return MintFromHarvestAny([]string{log1}, token, n)
}

// maxHarvestCandidates caps how many Log1 bodies one visit may offer. A page
// normally produces one or two; more than that is noise and cost.
const maxHarvestCandidates = 4

// MintFromHarvestAny is MintFromHarvest for a visit that captured more than one
// Log1 response: the device can be registered more than once per page load, and
// only the registration the token was sealed against can mint. The first
// candidate that seals the token wins; the rest are ignored.
func MintFromHarvestAny(log1s []string, token string, n int) (*Minted, error) {
	if n < 0 {
		return nil, errors.New("token count must be >= 0")
	}
	cands := harvestCandidates(log1s)
	if len(cands) == 0 {
		return nil, errors.New("no Log1 response to validate against")
	}
	reasons := make([]string, 0, len(cands))
	for i, log1 := range cands {
		session, tpl, skew, err := parseHarvest(log1, token)
		if err != nil {
			reasons = appendUnique(reasons, err.Error())
			continue
		}
		out := &Minted{
			Genuine: strings.TrimSpace(token),
			Layout:  LayoutSummary(tpl),
			SkewMs:  skew,
			Session: i,
			Tried:   len(cands),
		}
		if skew != 0 {
			out.Layout += " ts-skew=" + time.Duration(skew*int64(time.Millisecond)).String()
		}
		if note := ipNote(&session, tpl.fields); note != "" {
			out.Layout += " " + note
		}
		if n == 0 {
			return out, nil
		}
		toks, err := mintTokens(session, tpl, n, 0)
		if err != nil {
			return nil, err
		}
		out.Tokens = toks
		return out, nil
	}
	return nil, fmt.Errorf("%d Log1 candidate(s) tried: %s", len(cands), strings.Join(reasons, "; "))
}

// harvestCandidates trims, drops blanks and duplicates, and caps the list.
func harvestCandidates(log1s []string) []string {
	out := make([]string, 0, len(log1s))
	seen := make(map[string]struct{}, len(log1s))
	for _, l := range log1s {
		l = strings.TrimSpace(l)
		if l == "" {
			continue
		}
		if _, dup := seen[l]; dup {
			continue
		}
		seen[l] = struct{}{}
		out = append(out, l)
		if len(out) == maxHarvestCandidates {
			break
		}
	}
	return out
}

// appendUnique keeps failure reasons short and readable in logs.
func appendUnique(list []string, s string) []string {
	for _, v := range list {
		if v == s {
			return list
		}
	}
	return append(list, s)
}

// checkLayout verifies the slots minting rewrites really hold what the index
// map says they do, and reconciles the session timestamps with the token.
//
// The probe log pins the layout, which is what minting depends on. The server
// timestamp is NOT a gate: a phone visit can register the device twice, so the
// token may carry a newer serverTs than the Log1 response the WebView handed
// us, while still being sealed by the same session key. The token is the copy
// Aliyun sealed, so it wins; only a skew too large to belong to one session is
// refused. The error names slots only, never their values.
//
// It returns the skew it absorbed in milliseconds (0 when they agreed).
func checkLayout(sess *DeviceSession, f []string) (int64, error) {
	if len(f) <= idxServerTs {
		return 0, fmt.Errorf("template layout differs: %d fields, need more than %d", len(f), idxServerTs)
	}
	entries := strings.Split(f[idxLog], "|")
	if len(entries) < 2 ||
		!strings.HasPrefix(entries[len(entries)-2], logEntry93+"-") ||
		!strings.HasPrefix(entries[len(entries)-1], logEntry94+"-") {
		return 0, fmt.Errorf("template layout differs: slot %d does not end with probe entries 93/94 (%d fields)", idxLog, len(f))
	}
	// The IP slot is deliberately not a gate: the server timestamp and the
	// probe log already pin the layout, and the slot may legitimately hold a
	// different address than the server saw (IPv4 vs IPv6 on a dual-stack
	// endpoint, mobile NAT). Minting keeps it verbatim from the genuine token.
	tokTs, err := strconv.ParseInt(strings.TrimSpace(f[idxServerTs]), 10, 64)
	if err != nil || tokTs <= 0 {
		return 0, fmt.Errorf("template layout differs: slot %d is not the server timestamp (%d fields)", idxServerTs, len(f))
	}
	skew := tokTs - sess.ServerTs
	if skew > maxServerTsSkewMs || skew < -maxServerTsSkewMs {
		return skew, fmt.Errorf("template layout differs: slot %d is %s away from the captured session (limit %s)",
			idxServerTs,
			time.Duration(skew*int64(time.Millisecond)).String(),
			time.Duration(maxServerTsSkewMs*int64(time.Millisecond)).String())
	}
	if skew != 0 {
		sess.ServerTs = tokTs
	}
	return skew, nil
}

// ipKind names the shape of an address without revealing it.
func ipKind(v string) string {
	switch {
	case v == "":
		return "empty"
	case strings.Contains(v, ":"):
		return "ipv6"
	case strings.Count(v, ".") == 3:
		return "ipv4"
	}
	return "other"
}

// LayoutSummary is a value-free description of a harvest for logs.
func LayoutSummary(tpl *PayloadTemplate) string {
	return fmt.Sprintf("fields=%d", len(tpl.fields))
}

// ipNote reports, value-free, whether the payload's IP slot agrees with the
// session. It is empty when they agree.
func ipNote(sess *DeviceSession, f []string) string {
	if len(f) <= idxIP || f[idxIP] == sess.IP {
		return ""
	}
	where := "none"
	for i, v := range f {
		if sess.IP != "" && v == sess.IP {
			where = strconv.Itoa(i)
			break
		}
	}
	return fmt.Sprintf("ip-slot=%s session-ip=%s session-ip-found-at=%s", ipKind(f[idxIP]), ipKind(sess.IP), where)
}
