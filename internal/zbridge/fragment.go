package zbridge

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"time"
)

// Some mobile networks reset a TCP connection as soon as the TLS ClientHello
// names a blocked site ("connection reset by peer" right after the connect).
// Their filters usually read the server name from the FIRST packet only, so
// sending the ClientHello as two TCP segments gets past them. This is an
// opt-in workaround (ZAI_FRAGMENT=1, set by the app's "Bypass blocking"
// switch); it does nothing anywhere else.

// fragmentEnabled reports whether the workaround is switched on.
func fragmentEnabled() bool { return os.Getenv("ZAI_FRAGMENT") == "1" }

// fragmentGap lets the first segment leave before the second one is written.
const fragmentGap = 10 * time.Millisecond

// fragmentCut picks where to split the first write: inside the server name
// when it can be found, otherwise right after the 5-byte TLS record header.
func fragmentCut(b []byte, host string) int {
	if host != "" {
		if i := bytes.Index(b, []byte(host)); i >= 0 {
			return i + len(host)/2
		}
	}
	return 5
}

// fragConn splits the first Write (the ClientHello) into two segments.
type fragConn struct {
	net.Conn
	host string
	mu   sync.Mutex
	done bool
}

func (c *fragConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	first := !c.done
	c.done = true
	c.mu.Unlock()
	if !first || len(b) < 16 {
		return c.Conn.Write(b)
	}
	cut := fragmentCut(b, c.host)
	if cut <= 0 || cut >= len(b) {
		return c.Conn.Write(b)
	}
	n1, err := c.Conn.Write(b[:cut])
	if err != nil {
		return n1, err
	}
	time.Sleep(fragmentGap)
	n2, err := c.Conn.Write(b[cut:])
	return n1 + n2, err
}

// ---------- local CONNECT proxy for the app's WebView ----------
//
// The Token Farm and the sign-in page run inside an Android WebView, which
// does its own TLS. The app points the WebView at this proxy (127.0.0.1 only)
// so those connections get the same treatment, and name lookups go through
// the bridge's resolver too.

// fragDial opens the upstream connection (a seam for tests).
var fragDial = func(ctx context.Context, addr string) (net.Conn, error) {
	d := net.Dialer{Timeout: 15 * time.Second}
	return d.DialContext(ctx, "tcp", addr)
}

func startFragProxy(addr string) (net.Listener, error) {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go serveFragProxyConn(c)
		}
	}()
	return ln, nil
}

func serveFragProxyConn(c net.Conn) {
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
	br := bufio.NewReader(c)
	line, err := br.ReadString('\n')
	if err != nil {
		return
	}
	parts := strings.Fields(line)
	if len(parts) < 2 || !strings.EqualFold(parts[0], "CONNECT") {
		fmt.Fprint(c, "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
		return
	}
	target := parts[1]
	host, port, err := net.SplitHostPort(target)
	if err != nil || port != "443" {
		fmt.Fprint(c, "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n")
		return
	}
	for {
		l, err := br.ReadString('\n')
		if err != nil {
			return
		}
		if strings.TrimSpace(l) == "" {
			break
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	up, err := fragDial(ctx, target)
	cancel()
	if err != nil {
		fmt.Fprint(c, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer up.Close()
	_ = c.SetReadDeadline(time.Time{})
	fmt.Fprint(c, "HTTP/1.1 200 Connection Established\r\n\r\n")

	fup := &fragConn{Conn: up, host: host}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(fup, br); done <- struct{}{} }()
	go func() { _, _ = io.Copy(c, up); done <- struct{}{} }()
	<-done
}
