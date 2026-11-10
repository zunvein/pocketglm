package zbridge

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"

	"golang.ngrok.com/ngrok/v2"
)

// The public-link tunnel: the ngrok agent embedded in the bridge via ngrok-go,
// so the APK needs no extra binary. The Android app passes the account
// credentials over as environment variables at every bridge start, so nothing
// is stored in the repo or baked into the binary:
//
//	NGROK_AUTHTOKEN  account authtoken (required to go online)
//	NGROK_URL        the endpoint's public URL; using the account's free
//	                 static domain keeps the link stable across restarts.
//	                 Empty = a random subdomain per run.
//
// Without NGROK_AUTHTOKEN the system stays completely inert; the admin
// endpoints then only report "disabled".
//
// ngrok-go's Connect/Forward retry transient failures internally and only
// return late (or on a hard auth error), so StartTunnel never waits for the
// outcome: it spawns the dial, watches it with a timeout, and the status
// endpoint reports the result once known. If the session later drops the
// local bridge keeps running untouched — only the public link goes away.

// tunnelDial is the seam tests use to avoid dialing ngrok's real servers.
// It must behave like ngrok-go's Agent.Forward: block until online, then
// return a forwarder, or return an error.
var tunnelDial = func(ctx context.Context, u *url.URL, opts []ngrok.EndpointOption) (ngrok.EndpointForwarder, error) {
	agent := ngrok.DefaultAgent
	return agent.Forward(ctx, ngrok.WithUpstream("http://127.0.0.1:"+bridgePortStr(config.Server.Port)), opts...)
}

// tunnelDialTimeout bounds how long a start may take before it is reported
// as an error.
const tunnelDialTimeout = 45 * time.Second

type tunnelState struct {
	mu   sync.Mutex
	fwd  ngrok.EndpointForwarder
	url  string
	err  string
	busy bool // a dial is in flight
	stop context.CancelFunc
	// gen identifies the current attempt. A dial that finishes after Stop (or
	// after a newer Start) belongs to an older generation and must not touch
	// the state; if it came up anyway it is closed on the spot.
	gen uint64
}

var tunnel = &tunnelState{}

// tunnelEnabled reports whether the operator supplied credentials.
func tunnelEnabled() bool {
	return os.Getenv("NGROK_AUTHTOKEN") != ""
}

func bridgePortStr(n int) string {
	return fmt.Sprintf("%d", n)
}

// StartTunnel begins bringing the public link up. It returns immediately; the
// outcome shows up on the status endpoint. Errors: no credentials, or a link
// that is already up or coming up.
func StartTunnel() error {
	if !tunnelEnabled() {
		return errors.New("tunnel disabled: set NGROK_AUTHTOKEN")
	}
	tunnel.mu.Lock()
	defer tunnel.mu.Unlock()
	if tunnel.busy || tunnel.fwd != nil {
		return errors.New("tunnel already running or starting")
	}
	tunnel.busy = true
	tunnel.err = ""
	tunnel.gen++
	gen := tunnel.gen

	dctx, cancel := context.WithCancel(context.Background())
	tunnel.stop = cancel

	opts := []ngrok.EndpointOption{}
	if u := os.Getenv("NGROK_URL"); u != "" {
		opts = append(opts, ngrok.WithURL(u))
	}
	upstream := mustParseURL("http://127.0.0.1:" + bridgePortStr(config.Server.Port))
	dial := tunnelDial // captured now: a later swap must not affect this attempt

	// settle applies an outcome only if this attempt is still the current one.
	settle := func(fwd ngrok.EndpointForwarder, errText string) {
		tunnel.mu.Lock()
		if tunnel.gen != gen {
			tunnel.mu.Unlock()
			if fwd != nil {
				_ = fwd.Close() // came up after Stop: do not leave a live link behind
			}
			return
		}
		tunnel.busy = false
		tunnel.stop = nil
		if fwd != nil {
			tunnel.fwd = fwd
			if u := fwd.URL(); u != nil {
				tunnel.url = u.String()
			}
		} else {
			tunnel.err = errText
		}
		online := tunnel.url
		tunnel.mu.Unlock()
		if fwd != nil {
			logInfo("tunnel: online at " + online)
		} else {
			logError("tunnel: " + errText)
		}
	}

	go func() {
		type outcome struct {
			fwd ngrok.EndpointForwarder
			err error
		}
		res := make(chan outcome, 1)
		go func() {
			fwd, err := dial(dctx, upstream, opts)
			res <- outcome{fwd, err}
		}()
		select {
		case o := <-res:
			if o.err != nil {
				settle(nil, o.err.Error())
			} else {
				settle(o.fwd, "")
			}
		case <-time.After(tunnelDialTimeout):
			// ngrok-go retries transient failures internally and may never
			// return: give up on this attempt (cancelling the dial) and let
			// the user retry.
			cancel()
			settle(nil, "could not reach ngrok within "+tunnelDialTimeout.String()+" (check the authtoken and the connection)")
			// If the dial still produced a forwarder after the cancel, close it.
			go func() {
				if o := <-res; o.fwd != nil {
					_ = o.fwd.Close()
				}
			}()
		}
	}()
	return nil
}

func mustParseURL(s string) *url.URL {
	u, err := url.Parse(s)
	if err != nil {
		return &url.URL{}
	}
	return u
}

// StopTunnel brings the public link down. Idempotent.
func StopTunnel() {
	tunnel.mu.Lock()
	fwd := tunnel.fwd
	tunnel.fwd = nil
	tunnel.url = ""
	tunnel.busy = false
	tunnel.gen++ // orphan any dial still in flight
	stop := tunnel.stop
	tunnel.stop = nil
	tunnel.mu.Unlock()
	if stop != nil {
		stop()
	}
	if fwd != nil {
		_ = fwd.Close()
	}
	logInfo("tunnel: stopped")
}

// TunnelStatus describes the public link for the admin endpoints. The
// authtoken is never reported, only its presence.
type TunnelStatus struct {
	Enabled  bool   `json:"enabled"`
	Online   bool   `json:"online"`
	Starting bool   `json:"starting"`
	URL      string `json:"url,omitempty"`
	Error    string `json:"error,omitempty"`
}

// TunnelStatusNow snapshots the public link state.
func TunnelStatusNow() TunnelStatus {
	tunnel.mu.Lock()
	defer tunnel.mu.Unlock()
	return TunnelStatus{
		Enabled:  tunnelEnabled(),
		Online:   tunnel.fwd != nil,
		Starting: tunnel.busy,
		URL:      tunnel.url,
		Error:    tunnel.err,
	}
}

func tunnelStatusHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{
			"error": "use GET",
		})
		return
	}
	writeJSON(w, http.StatusOK, TunnelStatusNow())
}

func tunnelStartHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{
			"error": "use POST",
		})
		return
	}
	if err := StartTunnel(); err != nil {
		writeJSON(w, http.StatusConflict, map[string]interface{}{
			"error": err.Error(),
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{"status": "starting"})
}

func tunnelStopHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]interface{}{
			"error": "use POST",
		})
		return
	}
	StopTunnel()
	writeJSON(w, http.StatusOK, map[string]interface{}{"status": "offline"})
}
