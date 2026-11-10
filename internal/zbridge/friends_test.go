package zbridge

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func authCall(path, key string) int {
	h := authMiddleware(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	w := httptest.NewRecorder()
	h(w, req)
	return w.Code
}

func withAuthKeys(t *testing.T) {
	t.Helper()
	saved := config.Auth
	config.Auth.Enabled = true
	config.Auth.Token = "owner-key-for-tests"
	config.Auth.Extra = []string{"friend-key-for-tests"}
	t.Cleanup(func() { config.Auth = saved })
}

func TestOwnerAndFriendKeysOnChatRoutes(t *testing.T) {
	withAuthKeys(t)
	t.Setenv("FRIEND_RATE_PER_MIN", "")
	if got := authCall("/v1/models", "owner-key-for-tests"); got != 200 {
		t.Fatalf("owner on chat route: %d", got)
	}
	if got := authCall("/v1/models", "friend-key-for-tests"); got != 200 {
		t.Fatalf("friend on chat route: %d", got)
	}
	if got := authCall("/v1/models", "made-up-key"); got != 401 {
		t.Fatalf("unknown key: %d, want 401", got)
	}
	if got := authCall("/v1/models", ""); got != 401 {
		t.Fatalf("no key: %d, want 401", got)
	}
}

func TestFriendKeyCannotReachAdminRoutes(t *testing.T) {
	withAuthKeys(t)
	for _, p := range []string{"/admin/mint", "/admin/tunnel/start", "/stop", "/sqlite", "/captcha"} {
		if got := authCall(p, "friend-key-for-tests"); got != 403 {
			t.Errorf("friend on %s: %d, want 403", p, got)
		}
		if got := authCall(p, "owner-key-for-tests"); got != 200 {
			t.Errorf("owner on %s: %d, want 200", p, got)
		}
	}
}

func TestFriendKeyIsRateLimitedOwnerIsNot(t *testing.T) {
	withAuthKeys(t)
	t.Setenv("FRIEND_RATE_PER_MIN", "3")
	friendHits.Lock()
	friendHits.m = map[string][]time.Time{}
	friendHits.Unlock()

	for i := 1; i <= 3; i++ {
		if got := authCall("/v1/models", "friend-key-for-tests"); got != 200 {
			t.Fatalf("friend request %d: %d, want 200", i, got)
		}
	}
	if got := authCall("/v1/models", "friend-key-for-tests"); got != 429 {
		t.Fatalf("friend request 4: %d, want 429", got)
	}
	for i := 0; i < 10; i++ {
		if got := authCall("/v1/models", "owner-key-for-tests"); got != 200 {
			t.Fatalf("owner request %d limited: %d", i, got)
		}
	}
}

func TestFriendAllowWindowSlides(t *testing.T) {
	t.Setenv("FRIEND_RATE_PER_MIN", "2")
	key := "window-test-key"
	base := time.Now()
	friendHits.Lock()
	delete(friendHits.m, key)
	friendHits.Unlock()
	if !friendAllow(key, base) || !friendAllow(key, base.Add(10*time.Second)) {
		t.Fatal("first two should pass")
	}
	if friendAllow(key, base.Add(20*time.Second)) {
		t.Fatal("third within the minute should be refused")
	}
	if !friendAllow(key, base.Add(61*time.Second)) {
		t.Fatal("after the first hit ages out, a request should pass")
	}
}

func TestPerKeyLimitOverridesTheDefault(t *testing.T) {
	withAuthKeys(t)
	t.Setenv("FRIEND_RATE_PER_MIN", "100")
	t.Setenv("FRIEND_RATE_LIMITS", "friend-key-for-tests:2,other-key:50")
	friendHits.Lock()
	friendHits.m = map[string][]time.Time{}
	friendHits.Unlock()

	if got := friendLimitFor("friend-key-for-tests"); got != 2 {
		t.Fatalf("per-key limit = %d, want 2", got)
	}
	if got := friendLimitFor("some-other-friend"); got != 100 {
		t.Fatalf("key without an entry = %d, want the default 100", got)
	}
	for i := 1; i <= 2; i++ {
		if got := authCall("/v1/models", "friend-key-for-tests"); got != 200 {
			t.Fatalf("request %d: %d, want 200", i, got)
		}
	}
	if got := authCall("/v1/models", "friend-key-for-tests"); got != 429 {
		t.Fatalf("third request: %d, want 429 at the per-key limit", got)
	}
}

func TestBadPerKeyLimitFallsBackToDefault(t *testing.T) {
	t.Setenv("FRIEND_RATE_PER_MIN", "7")
	t.Setenv("FRIEND_RATE_LIMITS", "k1:0,k2:abc,k3,:5")
	for _, k := range []string{"k1", "k2", "k3"} {
		if got := friendLimitFor(k); got != 7 {
			t.Errorf("%s: limit = %d, want default 7", k, got)
		}
	}
}
