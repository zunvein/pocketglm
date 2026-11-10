package zbridge

import (
	"encoding/json"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Friend keys are extra API keys (AUTH_TOKEN after the first, comma-separated)
// the owner hands out one per person. They may use the chat API only, and each
// one is rate limited. The owner key (first in AUTH_TOKEN) is never limited and
// is the only key that reaches the admin and maintenance routes.

const defaultFriendRatePerMin = 60

var friendHits = struct {
	sync.Mutex
	m map[string][]time.Time
}{m: map[string][]time.Time{}}

// requestKey returns the key a request presented (Bearer header or x-api-key).
func requestKey(r *http.Request) string {
	h := r.Header.Get("Authorization")
	p := h
	if len(h) >= 7 && strings.EqualFold(h[:7], "Bearer ") {
		p = h[7:]
	}
	if p == "" {
		p = r.Header.Get("x-api-key")
	}
	return p
}

// isOwnerKey is true only for the first key in AUTH_TOKEN.
func isOwnerKey(p string) bool {
	return p != "" && p == config.Auth.Token
}

// isFriendKey is true for any extra key the owner has handed out.
func isFriendKey(p string) bool {
	if p == "" {
		return false
	}
	for _, k := range config.Auth.Extra {
		if k != "" && p == k {
			return true
		}
	}
	return false
}

// ownerOnlyPath lists the routes a friend key may never call.
func ownerOnlyPath(path string) bool {
	return strings.HasPrefix(path, "/admin/") || path == "/stop" || path == "/sqlite" || path == "/captcha"
}

func friendRatePerMin() int {
	if v := os.Getenv("FRIEND_RATE_PER_MIN"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	}
	return defaultFriendRatePerMin
}

// friendAllow records one request for key and reports whether it is within the
// per-minute limit. The key itself is only used as an in-memory map key.
func friendAllow(key string, now time.Time) bool {
	limit := friendRatePerMin()
	cutoff := now.Add(-time.Minute)
	friendHits.Lock()
	defer friendHits.Unlock()
	kept := friendHits.m[key][:0]
	for _, t := range friendHits.m[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= limit {
		friendHits.m[key] = kept
		return false
	}
	friendHits.m[key] = append(kept, now)
	return true
}

func writeAuthError(w http.ResponseWriter, status int, kind, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"type": "error",
		"error": map[string]interface{}{
			"type":    kind,
			"message": message,
		},
	})
}
