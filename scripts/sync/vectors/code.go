package main

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

type codeErr struct{ reason string }

func (e *codeErr) Error() string { return e.reason }

func cerr(reason string) error { return &codeErr{reason: reason} }

type parsedCode struct {
	K      []byte
	Origin string
}

func formatCode(k []byte, origin string) (string, error) {
	if len(k) != 32 {
		return "", cerr("k_length")
	}
	if err := validateOrigin(origin); err != nil {
		return "", err
	}
	k43 := b64Encode(k)
	if len(k43) != 43 {
		return "", cerr("k43_length")
	}
	sum := sha256.Sum256([]byte("vela2:" + k43 + "@" + origin))
	c6 := hex.EncodeToString(sum[:3])
	return "vela2:" + k43 + "." + c6 + "@" + origin, nil
}

func parseCode(s string) (parsedCode, error) {
	if !strings.HasPrefix(s, "vela2:") {
		return parsedCode{}, cerr("prefix")
	}
	for _, r := range s {
		if r > 127 {
			return parsedCode{}, cerr("non_ascii")
		}
	}
	body := s[len("vela2:"):]
	dot := strings.IndexByte(body, '.')
	if dot < 0 {
		return parsedCode{}, cerr("k43_length")
	}
	k43 := body[:dot]
	rest := body[dot+1:]
	at := strings.IndexByte(rest, '@')
	if at < 0 {
		return parsedCode{}, cerr("checksum_length")
	}
	c6 := rest[:at]
	origin := rest[at+1:]
	if len(k43) != 43 {
		return parsedCode{}, cerr("k43_length")
	}
	kb, err := b64Decode(k43)
	if err != nil {
		switch err.Error() {
		case "alphabet", "padding", "whitespace", "bad_length":
			return parsedCode{}, cerr("k43_alphabet")
		default:
			return parsedCode{}, cerr("k43_noncanonical")
		}
	}
	if len(kb) != 32 {
		return parsedCode{}, cerr("k43_length")
	}
	if err := validateOrigin(origin); err != nil {
		return parsedCode{}, err
	}
	if len(c6) != 6 {
		return parsedCode{}, cerr("checksum_length")
	}
	for i := 0; i < len(c6); i++ {
		c := c6[i]
		if c >= 'A' && c <= 'F' {
			return parsedCode{}, cerr("checksum_case")
		}
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return parsedCode{}, cerr("checksum_length")
		}
	}
	sum := sha256.Sum256([]byte("vela2:" + k43 + "@" + origin))
	if hex.EncodeToString(sum[:3]) != c6 {
		return parsedCode{}, cerr("checksum_mismatch")
	}
	return parsedCode{K: kb, Origin: origin}, nil
}

func validateOrigin(o string) error {
	if !strings.HasPrefix(o, "https://") {
		return cerr("scheme")
	}
	rest := strings.TrimPrefix(o, "https://")
	if rest == "" {
		return cerr("host_length")
	}
	if strings.Contains(rest, "@") {
		return cerr("userinfo")
	}
	if i := strings.IndexByte(rest, '#'); i >= 0 {
		return cerr("fragment")
	}
	if i := strings.IndexByte(rest, '?'); i >= 0 {
		return cerr("query")
	}
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		return cerr("path")
	}
	if strings.HasPrefix(rest, "[") || strings.Count(rest, ":") > 1 {
		return cerr("ip")
	}
	host, port := rest, ""
	if i := strings.IndexByte(rest, ':'); i >= 0 {
		host, port = rest[:i], rest[i+1:]
		if port == "" {
			return cerr("port_empty")
		}
	}
	if isIPv4(host) {
		return cerr("ip")
	}
	if port != "" {
		if port == "443" {
			return cerr("port_443")
		}
		if len(port) > 1 && port[0] == '0' {
			return cerr("port_leading_zero")
		}
		n, ok := tryDecimal(port)
		if !ok {
			return cerr("port_format")
		}
		if n < 1 || n > 65535 {
			return cerr("port_range")
		}
	}
	return validateHost(host)
}

func isIPv4(host string) bool {
	parts := strings.Split(host, ".")
	if len(parts) != 4 {
		return false
	}
	for _, p := range parts {
		if p == "" || len(p) > 3 {
			return false
		}
		n := 0
		for i := 0; i < len(p); i++ {
			if p[i] < '0' || p[i] > '9' {
				return false
			}
			n = n*10 + int(p[i]-'0')
		}
		if n > 255 {
			return false
		}
	}
	return true
}

func validateHost(host string) error {
	if host == "" || len(host) > 253 {
		return cerr("host_length")
	}
	if strings.HasPrefix(host, ".") || strings.HasSuffix(host, ".") || strings.Contains(host, "..") {
		return cerr("empty_label")
	}
	labels := strings.Split(host, ".")
	if len(labels) < 2 {
		return cerr("single_label")
	}
	for _, lab := range labels {
		if len(lab) < 1 || len(lab) > 63 {
			return cerr("label_length")
		}
		if lab[0] == '-' || lab[len(lab)-1] == '-' {
			return cerr("label_hyphen")
		}
		for i := 0; i < len(lab); i++ {
			c := lab[i]
			if c >= 'A' && c <= 'Z' {
				return cerr("host_upper")
			}
			if c >= 0x80 {
				return cerr("non_ascii")
			}
			if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
				return cerr("host_char")
			}
		}
	}
	// 能被解析成 IP 的写法：全数字标签、0x 加十六进制标签；顶级标签首字符必须是字母（第 4.3 节、第 23 节第 10 条）。
	for _, lab := range labels {
		if isNumericLabel(lab) {
			return cerr("numeric_label")
		}
	}
	if tld := labels[len(labels)-1]; tld[0] < 'a' || tld[0] > 'z' {
		return cerr("tld_start")
	}
	return nil
}

func isNumericLabel(lab string) bool {
	digits := true
	for i := 0; i < len(lab); i++ {
		if lab[i] < '0' || lab[i] > '9' {
			digits = false
		}
	}
	if digits {
		return true
	}
	if len(lab) >= 2 && lab[0] == '0' && lab[1] == 'x' {
		for i := 2; i < len(lab); i++ {
			c := lab[i]
			if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
				return false
			}
		}
		return true
	}
	return false
}
