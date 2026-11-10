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

// ParseHarvest turns one browser visit (the Log1 response and the one genuine
// device token the SDK produced) into a trusted session plus the payload
// template that minting needs.
func ParseHarvest(log1, token string) (DeviceSession, *PayloadTemplate, error) {
	var zero DeviceSession
	blob, err := deviceConfigFrom(log1)
	if err != nil {
		return zero, nil, err
	}
	session, err := decryptDeviceConfig(blob)
	if err != nil {
		return zero, nil, err
	}
	token = strings.TrimSpace(token)
	fields, ok, err := verifyToken(token, session.SessionKey)
	if err != nil {
		return zero, nil, fmt.Errorf("decrypt captured device token: %w", err)
	}
	if !ok {
		return zero, nil, errors.New("captured device token failed its md5 seal")
	}
	tpl, err := newPayloadTemplate(fields)
	if err != nil {
		return zero, nil, err
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
	return session, tpl, nil
}

// Minted is the result of MintFromHarvest.
type Minted struct {
	Genuine string   // the browser's own token (valid on its own)
	Tokens  []string // n additional tokens bound to the same session
}

// MintFromHarvest validates a browser harvest and mints n further tokens.
// n == 0 only validates and returns the genuine token.
func MintFromHarvest(log1, token string, n int) (*Minted, error) {
	if n < 0 {
		return nil, errors.New("token count must be >= 0")
	}
	session, tpl, err := ParseHarvest(log1, token)
	if err != nil {
		return nil, err
	}
	out := &Minted{Genuine: strings.TrimSpace(token)}
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
