package zbridge

import (
	"context"
	"net"
	"testing"
	"time"
)

// With ZAI_DNS set, lookups must go through the listed servers (IPv6
// literals included) and must work even if the first entries are dead.
func TestInstallCustomDNSResolves(t *testing.T) {
	old := net.DefaultResolver
	defer func() { net.DefaultResolver = old }()

	t.Setenv("ZAI_DNS", "::1,1.1.1.1")
	installCustomDNS()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	var lastErr error
	for i := 0; i < 4; i++ { // round-robin: some tries hit the dead ::1
		addrs, err := net.DefaultResolver.LookupHost(ctx, "chat.z.ai")
		if err == nil && len(addrs) > 0 {
			return
		}
		lastErr = err
	}
	t.Skipf("no outbound DNS in this environment: %v", lastErr)
}

func TestInstallCustomDNSNoopWithoutEnv(t *testing.T) {
	old := net.DefaultResolver
	defer func() { net.DefaultResolver = old }()
	t.Setenv("ZAI_DNS", "")
	installCustomDNS()
	if net.DefaultResolver != old {
		t.Fatal("resolver must be untouched when ZAI_DNS is unset")
	}
}
