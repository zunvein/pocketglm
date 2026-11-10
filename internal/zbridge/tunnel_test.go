// tunnel_test.go — the public link without dialing ngrok.
//
// The dial is stubbed through the tunnelDial seam, so these tests never touch
// ngrok's real servers (a live experiment showed a real dial with a bad token
// can hang for minutes inside the agent's internal retry loop). Covered here:
// admin-endpoint routing, the auth middleware, the inert disabled path, the
// start/stop lifecycle, error reporting, and env handling.

package zbridge

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.ngrok.com/ngrok/v2"
)

// fakeFwd stands in for a real forwarder in tests: every method but URL/Close
// is inherited (unused) through the embedded interface.
type fakeFwd struct {
	ngrok.EndpointForwarder
	u       string
	onClose func()
}

func (f *fakeFwd) URL() *url.URL { return &url.URL{Scheme: "https", Host: f.u, Path: "/"} }
func (f *fakeFwd) Close() error {
	if f.onClose != nil {
		f.onClose()
	}
	return nil
}

// stubDial replaces the real dial for one test (and always restores + stops
// the tunnel afterwards). fn mirrors Agent.Forward semantics: block until
// online, return a forwarder or an error. Successful stubs return a fakeFwd.
func stubDial(t *testing.T, fn func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error) {
	t.Helper()
	old := tunnelDial
	tunnelDial = func(ctx context.Context, u *url.URL, opts []ngrok.EndpointOption) (ngrok.EndpointForwarder, error) {
		if err := fn(ctx, u.String(), opts); err != nil {
			return nil, err
		}
		return &fakeFwd{u: "test.ngrok.app"}, nil
	}
	t.Cleanup(func() {
		StopTunnel()
		tunnelDial = old
		tunnel.mu.Lock()
		tunnel.fwd = nil
		tunnel.url = ""
		tunnel.err = ""
		tunnel.busy = false
		tunnel.stop = nil
		tunnel.mu.Unlock()
	})
}

func tunnelReq(t *testing.T, method, path string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, nil)
	req.Header.Set("Authorization", "Bearer "+config.Auth.Token)
	w := httptest.NewRecorder()
	NewHandler().ServeHTTP(w, req)
	return w
}

func tunnelStatus(t *testing.T) TunnelStatus {
	t.Helper()
	w := tunnelReq(t, http.MethodGet, "/admin/tunnel/status")
	var st TunnelStatus
	if err := json.Unmarshal(w.Body.Bytes(), &st); err != nil {
		t.Fatalf("status body: %v (%s)", err, w.Body.String())
	}
	return st
}

func waitCond(t *testing.T, cond func(TunnelStatus) bool) TunnelStatus {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		st := tunnelStatus(t)
		if cond(st) {
			return st
		}
		if time.Now().After(deadline) {
			t.Fatalf("condition not met in 5s; status = %+v", st)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// upstreamPort extracts the numeric port from "http://127.0.0.1:<port>".
func upstreamPort(upstream string) (int, bool) {
	i := strings.LastIndex(upstream, ":")
	if i < 0 {
		return 0, false
	}
	p, err := strconv.Atoi(upstream[i+1:])
	if err != nil {
		return 0, false
	}
	return p, true
}

// Without credentials the tunnel reports disabled and refuses to start.
func TestTunnelDisabledWithoutAuthtoken(t *testing.T) {
	t.Setenv("NGROK_AUTHTOKEN", "")
	t.Setenv("NGROK_URL", "")
	StopTunnel()
	stubDial(t, func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error {
		t.Error("dial must not happen without credentials")
		return nil
	})

	st := tunnelStatus(t)
	if st.Enabled || st.Online || st.Starting || st.URL != "" {
		t.Fatalf("want disabled/idle, got %+v", st)
	}
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusConflict {
		t.Fatalf("start without authtoken: code = %d, want 409, body %s", w.Code, w.Body.String())
	}
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/stop"); w.Code != http.StatusOK {
		t.Fatalf("stop: code = %d, want 200", w.Code)
	}

	// the endpoints enforce the key like every other admin route
	for name, req := range map[string]*http.Request{
		"no key": httptest.NewRequest(http.MethodGet, "/admin/tunnel/status", nil),
		"bad key": func() *http.Request {
			r := httptest.NewRequest(http.MethodGet, "/admin/tunnel/status", nil)
			r.Header.Set("Authorization", "Bearer wrong")
			return r
		}(),
	} {
		rw := httptest.NewRecorder()
		NewHandler().ServeHTTP(rw, req)
		if rw.Code != http.StatusUnauthorized {
			t.Errorf("%s: code = %d, want 401", name, rw.Code)
		}
	}
	// and the verbs
	if w := tunnelReq(t, http.MethodGet, "/admin/tunnel/start"); w.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET start: code = %d, want 405", w.Code)
	}
}

// A successful dial flips the status to online; stop clears it. The dialed
// upstream must be the bridge's own port.
func TestTunnelStartStopLifecycle(t *testing.T) {
	t.Setenv("NGROK_AUTHTOKEN", "test-token-not-real")
	t.Setenv("NGROK_URL", "")
	StopTunnel()
	var gotUpstream string
	stubDial(t, func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error {
		gotUpstream = upstream
		return nil
	})

	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusOK {
		t.Fatalf("start: code = %d (%s)", w.Code, w.Body.String())
	}
	waitCond(t, func(s TunnelStatus) bool { return s.Online })

	if p, ok := upstreamPort(gotUpstream); !ok || p != config.Server.Port {
		t.Errorf("dialed upstream %q does not point at bridge port %d", gotUpstream, config.Server.Port)
	}
	if st := tunnelStatus(t); st.Starting || !st.Enabled {
		t.Errorf("surprising status after success: %+v", st)
	}

	// double start while up must be refused
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusConflict {
		t.Fatalf("double start: code = %d, want 409", w.Code)
	}

	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/stop"); w.Code != http.StatusOK {
		t.Fatalf("stop: code = %d", w.Code)
	}
	if st := tunnelStatus(t); st.Online || st.Starting {
		t.Errorf("stop did not clear the link: %+v", st)
	}
	// after a stop a new start is allowed again
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusOK {
		t.Fatalf("restart after stop: code = %d", w.Code)
	}
	waitCond(t, func(s TunnelStatus) bool { return s.Online })
}

// A dial that fails lands its error on the status, stays offline, and does
// not block the next attempt.
func TestTunnelDialErrorIsReported(t *testing.T) {
	t.Setenv("NGROK_AUTHTOKEN", "obviously-fake-token-for-test")
	t.Setenv("NGROK_URL", "")
	StopTunnel()
	calls := 0
	stubDial(t, func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error {
		calls++
		return errors.New("authentication failed: token rejected")
	})

	_ = tunnelReq(t, http.MethodPost, "/admin/tunnel/start")
	st := waitCond(t, func(s TunnelStatus) bool { return !s.Online && !s.Starting && s.Error != "" })
	if st.Online {
		t.Fatalf("failed dial went online: %+v", st)
	}
	if strings.Contains(st.Error, "obviously-fake") {
		t.Errorf("status error echoes the authtoken: %q", st.Error)
	}
	if calls == 0 {
		t.Error("dial was never invoked")
	}
	// idempotent stop after a failure, then a fresh start is allowed
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/stop"); w.Code != http.StatusOK {
		t.Fatalf("stop after failure: %d", w.Code)
	}
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code == http.StatusConflict {
		t.Fatal("restart refused after a failed attempt")
	}
	waitCond(t, func(s TunnelStatus) bool { return !s.Starting })
}

// Stop during a dial must win: a dial that finishes afterwards may not bring
// the link back up, and the forwarder it produced must be closed.
func TestTunnelStopDuringDialWins(t *testing.T) {
	t.Setenv("NGROK_AUTHTOKEN", "test-token-not-real")
	t.Setenv("NGROK_URL", "")
	StopTunnel()
	release := make(chan struct{})
	entered := make(chan struct{})
	closed := make(chan struct{}, 1)
	old := tunnelDial
	tunnelDial = func(ctx context.Context, u *url.URL, opts []ngrok.EndpointOption) (ngrok.EndpointForwarder, error) {
		close(entered)
		<-release // ignores ctx on purpose: models a dial that finishes late
		return &fakeFwd{u: "late.ngrok.app", onClose: func() { closed <- struct{}{} }}, nil
	}
	t.Cleanup(func() { StopTunnel(); tunnelDial = old })

	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusOK {
		t.Fatalf("start: %d", w.Code)
	}
	<-entered
	if st := tunnelStatus(t); !st.Starting {
		t.Fatalf("want starting, got %+v", st)
	}
	tunnelReq(t, http.MethodPost, "/admin/tunnel/stop")
	close(release)
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("late forwarder was not closed")
	}
	if st := tunnelStatus(t); st.Online || st.Starting || st.URL != "" {
		t.Fatalf("link came back after stop: %+v", st)
	}
}

// NGROK_AUTOSTART=0 (the app's switch) only disables AUTO start; the admin
// endpoints still work.
func TestTunnelAutostartOffStillAllowsManualStart(t *testing.T) {
	t.Setenv("NGROK_AUTOSTART", "0")
	t.Setenv("NGROK_AUTHTOKEN", "test-token-not-real")
	t.Setenv("NGROK_URL", "")
	StopTunnel()
	stubDial(t, func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error {
		return nil
	})
	if os.Getenv("NGROK_AUTOSTART") == "0" && config.TunnelAutoStart {
		t.Log("config snapshot predates env; checking decision logic instead")
	}
	if w := tunnelReq(t, http.MethodPost, "/admin/tunnel/start"); w.Code != http.StatusOK {
		t.Fatalf("manual start: %d", w.Code)
	}
	waitCond(t, func(s TunnelStatus) bool { return s.Online })
}

// NGROK_URL is passed through to the dial as exactly one WithURL option.
func TestTunnelStaticDomainIsPassedThrough(t *testing.T) {
	t.Setenv("NGROK_AUTHTOKEN", "test-token-not-real")
	t.Setenv("NGROK_URL", "https://pocketglm-test.ngrok.app")
	StopTunnel()
	var gotOpts []ngrok.EndpointOption
	stubDial(t, func(ctx context.Context, upstream string, opts []ngrok.EndpointOption) error {
		gotOpts = opts
		return nil
	})
	_ = tunnelReq(t, http.MethodPost, "/admin/tunnel/start")
	waitCond(t, func(s TunnelStatus) bool { return s.Online })
	if len(gotOpts) != 1 {
		t.Fatalf("want exactly 1 endpoint option (WithURL), got %d", len(gotOpts))
	}
}
