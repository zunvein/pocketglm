package zbridge

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestSafeNetErrDropsTheURLAndItsToken(t *testing.T) {
	inner := errors.New("write tcp 10.0.0.2:1->1.2.3.4:443: connection reset by peer")
	err := &url.Error{Op: "Post", URL: "https://chat.z.ai/api?x=1&token=eyJSECRETSECRET.payload.sig&y=2", Err: inner}
	got := safeNetErr(err)
	for _, bad := range []string{"eyJ", "token=", "https://"} {
		if strings.Contains(got, bad) {
			t.Fatalf("safeNetErr leaks %q: %s", bad, got)
		}
	}
	if !strings.Contains(got, "connection reset by peer") || !strings.HasPrefix(got, "Post:") {
		t.Fatalf("lost the useful part: %s", got)
	}
	if safeNetErr(errors.New("plain")) != "plain" || safeNetErr(nil) != "" {
		t.Fatal("non-url errors must pass through")
	}
}

func TestFragmentCutLandsInsideTheHostName(t *testing.T) {
	hello := append(bytes.Repeat([]byte{0x16}, 60), []byte("chat.z.ai")...)
	hello = append(hello, bytes.Repeat([]byte{0x01}, 40)...)
	cut := fragmentCut(hello, "chat.z.ai")
	if !bytes.HasPrefix(hello[cut-3:], []byte("t.z")) && !bytes.Contains(hello[:cut], []byte("chat")) {
		t.Fatalf("cut %d is not inside the name", cut)
	}
	if bytes.Contains(hello[:cut], []byte("chat.z.ai")) || bytes.Contains(hello[cut:], []byte("chat.z.ai")) {
		t.Fatal("either half still holds the whole name")
	}
	if got := fragmentCut(hello, "absent.example"); got != 5 {
		t.Fatalf("fallback cut = %d, want 5", got)
	}
}

// The first write must arrive as two separate segments, later writes untouched,
// and the bytes must reassemble exactly.
func TestFragConnSplitsOnlyTheFirstWrite(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	chunks := make(chan []byte, 8)
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := b.Read(buf)
			if n > 0 {
				chunks <- append([]byte(nil), buf[:n]...)
			}
			if err != nil {
				close(chunks)
				return
			}
		}
	}()
	fc := &fragConn{Conn: a, host: "chat.z.ai"}
	first := append(bytes.Repeat([]byte{0x16}, 50), []byte("chat.z.ai")...)
	first = append(first, bytes.Repeat([]byte{0x02}, 50)...)
	if n, err := fc.Write(first); err != nil || n != len(first) {
		t.Fatalf("first write: n=%d err=%v", n, err)
	}
	second := []byte("second write, left alone")
	if _, err := fc.Write(second); err != nil {
		t.Fatal(err)
	}
	var got [][]byte
	deadline := time.After(2 * time.Second)
	for len(got) < 3 {
		select {
		case c := <-chunks:
			got = append(got, c)
		case <-deadline:
			t.Fatalf("only %d chunks arrived", len(got))
		}
	}
	if !bytes.Equal(append(append([]byte{}, got[0]...), got[1]...), first) {
		t.Fatal("first write did not reassemble")
	}
	if len(got[0]) == len(first) || len(got[1]) == 0 {
		t.Fatalf("first write was not split: %d/%d", len(got[0]), len(got[1]))
	}
	if !bytes.Equal(got[2], second) {
		t.Fatal("second write was altered")
	}
}

func TestFragProxyRelaysConnectAndRefusesTheRest(t *testing.T) {
	old := fragDial
	t.Cleanup(func() { fragDial = old })
	var dialed string
	fragDial = func(ctx context.Context, addr string) (net.Conn, error) {
		dialed = addr
		c, s := net.Pipe()
		go func() { // echo server
			defer s.Close()
			_, _ = io.Copy(s, s)
		}()
		return c, nil
	}
	ln, err := startFragProxy("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()

	c, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	io.WriteString(c, "CONNECT example.test:443 HTTP/1.1\r\nHost: example.test:443\r\n\r\n")
	buf := make([]byte, 64)
	n, _ := c.Read(buf)
	if !strings.HasPrefix(string(buf[:n]), "HTTP/1.1 200") {
		t.Fatalf("reply = %q", buf[:n])
	}
	payload := bytes.Repeat([]byte("x"), 100)
	c.Write(payload)
	echo := make([]byte, 0, 100)
	for len(echo) < 100 {
		m, err := c.Read(buf)
		if err != nil {
			t.Fatalf("echo read: %v", err)
		}
		echo = append(echo, buf[:m]...)
	}
	if !bytes.Equal(echo, payload) || dialed != "example.test:443" {
		t.Fatalf("relay broken: dialed=%q", dialed)
	}

	for name, req := range map[string]string{
		"plain http": "GET http://example.test/ HTTP/1.1\r\n\r\n",
		"other port": "CONNECT example.test:8080 HTTP/1.1\r\n\r\n",
	} {
		c2, err := net.Dial("tcp", ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		_ = c2.SetDeadline(time.Now().Add(3 * time.Second))
		io.WriteString(c2, req)
		n, _ := c2.Read(buf)
		c2.Close()
		if strings.HasPrefix(string(buf[:n]), "HTTP/1.1 200") {
			t.Fatalf("%s: proxy accepted it", name)
		}
	}
}
