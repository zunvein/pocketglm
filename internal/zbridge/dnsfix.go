package zbridge

import (
	"context"
	"log"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

// Android's pure-Go DNS story: an app process has NO /etc/resolv.conf, so
// the netgo resolver falls back to [::1]:53 and every hostname lookup dies
// with "connection refused". The Android app passes the device's current
// DNS servers (ConnectivityManager LinkProperties) via
//     ZAI_DNS=ip1,ip2,...
// and we install an http.Transport whose dialer resolves through them, with
// two public resolvers appended as last-resort fallbacks. Everywhere else
// (VPS, CI, dev box) ZAI_DNS is unset and the default transport is left
// completely untouched, so this is a no-op for the production service.

// dnsServerFallbacks is appended to the ZAI_DNS list; ordering is
// round-robin per lookup, so a dead first server does not wedge lookups.
var dnsServerFallbacks = []string{"1.1.1.1", "8.8.8.8"}

func dnsServersFromEnv() []string {
	val := strings.TrimSpace(os.Getenv("ZAI_DNS"))
	if val == "" {
		return nil
	}
	servers := []string{}
	for _, s := range strings.Split(val, ",") {
		s = strings.TrimSpace(s)
		if s != "" && net.ParseIP(s) != nil {
			servers = append(servers, s)
		}
	}
	for _, f := range dnsServerFallbacks {
		found := false
		for _, s := range servers {
			if s == f {
				found = true
				break
			}
		}
		if !found {
			servers = append(servers, f)
		}
	}
	return servers
}

// installCustomDNS points net.DefaultResolver at the ZAI_DNS servers. Call once at startup, before any outbound HTTP happens.
func installCustomDNS() {
	servers := dnsServersFromEnv()
	if len(servers) == 0 {
		return
	}

	var rr uint64
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			s := servers[atomic.AddUint64(&rr, 1)%uint64(len(servers))]
			d := net.Dialer{Timeout: 3 * time.Second}
			return d.DialContext(ctx, network, net.JoinHostPort(s, "53"))
		},
	}

	// Replace the process-wide resolver. Every net.Dialer without an
	// explicit Resolver (dialUTLS, the aliyun/image transports, the
	// default transport...) falls back to net.DefaultResolver, so this one
	// assignment covers all outbound lookups, not just http.DefaultTransport.
	net.DefaultResolver = resolver

	log.Printf("[Config] DNS: using %d explicit resolver(s) from ZAI_DNS (includes public fallbacks)", len(servers))
}
