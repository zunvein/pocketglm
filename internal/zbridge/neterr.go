package zbridge

import (
	"errors"
	"net/url"
)

// safeNetErr renders a network error without the request URL.
//
// net/http wraps failures in *url.Error, whose text embeds the full URL. The
// chat endpoint carries the account token in its query string, so passing
// err.Error() on would hand a credential to whoever sees the error: the app
// screen, the logs, and (with the public link) a remote API client.
func safeNetErr(err error) string {
	if err == nil {
		return ""
	}
	var ue *url.Error
	if errors.As(err, &ue) && ue.Err != nil {
		return ue.Op + ": " + ue.Err.Error()
	}
	return err.Error()
}
