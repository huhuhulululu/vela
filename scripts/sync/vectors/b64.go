package main

import (
	"encoding/base64"
	"errors"
	"strings"
)

var (
	errPad    = errors.New("padding")
	errSpace  = errors.New("whitespace")
	errAlpha  = errors.New("alphabet")
	errCanon  = errors.New("noncanonical")
	errB64Len = errors.New("bad_length")
)

func b64Encode(raw []byte) string {
	return strings.TrimRight(base64.URLEncoding.EncodeToString(raw), "=")
}

func isB64Char(c byte) bool {
	switch {
	case c >= 'A' && c <= 'Z', c >= 'a' && c <= 'z', c >= '0' && c <= '9', c == '-', c == '_':
		return true
	default:
		return false
	}
}

// b64Decode 拒绝填充、空白、字母表外字符，以及末位未用比特非 0 的非规范编码。
func b64Decode(s string) ([]byte, error) {
	if s == "" {
		return []byte{}, nil
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch c {
		case '=':
			return nil, errPad
		case ' ', '\t', '\n', '\r':
			return nil, errSpace
		default:
			if !isB64Char(c) {
				return nil, errAlpha
			}
		}
	}
	if len(s)%4 == 1 {
		return nil, errB64Len
	}
	pad := (4 - len(s)%4) % 4
	dec, err := base64.URLEncoding.DecodeString(s + strings.Repeat("=", pad))
	if err != nil {
		return nil, errAlpha
	}
	if b64Encode(dec) != s {
		return nil, errCanon
	}
	return dec, nil
}
