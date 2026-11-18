package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"strings"

	"compress/flate"
)

func deflateRaw(src []byte) []byte {
	var buf bytes.Buffer
	w, err := flate.NewWriter(&buf, 6)
	if err != nil {
		panic(err)
	}
	if _, err := w.Write(src); err != nil {
		panic(err)
	}
	if err := w.Close(); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

func encodePayload(json []byte) (flag byte, body []byte) {
	comp := deflateRaw(json)
	if len(comp) < len(json) {
		return 0x01, comp
	}
	return 0x00, append([]byte(nil), json...)
}

var (
	errOver = errors.New("over_p")
	errTrun = errors.New("truncated")
	errTail = errors.New("trailing")
	errFlag = errors.New("bad_flag")
)

func decodePayload(flag byte, body []byte) ([]byte, error) {
	switch flag {
	case 0x00:
		if len(body) > plainMax {
			return nil, errOver
		}
		return body, nil
	case 0x01:
		return inflateLimited(body, plainMax)
	default:
		return nil, errFlag
	}
}

func inflateLimited(body []byte, limit int) ([]byte, error) {
	br := bytes.NewReader(body)
	r := flate.NewReader(br)
	defer r.Close()
	var out bytes.Buffer
	buf := make([]byte, 8192)
	for {
		n, err := r.Read(buf)
		if n > 0 {
			if out.Len()+n > limit {
				return nil, errOver
			}
			out.Write(buf[:n])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, errTrun
		}
	}
	if br.Len() != 0 {
		return nil, errTail
	}
	return out.Bytes(), nil
}

func aadFor(blobID string, ver uint64) []byte {
	return []byte("vela-sync/e1|" + blobID + "|" + formatDecimal(ver))
}

func seal(encKey, nonce, payload, aad []byte) (string, []byte) {
	block, err := aes.NewCipher(encKey)
	if err != nil {
		panic(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	ct := gcm.Seal(nil, nonce, payload, aad)
	env := "e1." + b64Encode(nonce) + "." + b64Encode(ct)
	return env, ct
}

func openEnv(encKey, nonce, aad, ct []byte) ([]byte, error) {
	block, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, nonce, ct, aad)
}

func signMessage(op, blobID, epoch string, base, next uint64, line6 string) []byte {
	parts := []string{
		"vela-sync/v2/" + op,
		blobID,
		epoch,
		formatDecimal(base),
		formatDecimal(next),
		line6,
	}
	return []byte(strings.Join(parts, "\n"))
}

func signWith(seed, msg []byte) []byte {
	return ed25519.Sign(ed25519.NewKeyFromSeed(seed), msg)
}

func verifySig(pub, msg, sig []byte) bool {
	if len(pub) != ed25519.PublicKeySize || len(sig) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(pub, msg, sig)
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func pkcs8(seed []byte) []byte {
	prefix, _ := hex.DecodeString("302e020100300506032b657004220420")
	return append(prefix, seed...)
}

func x509Pub(pub []byte) []byte {
	prefix, _ := hex.DecodeString("302a300506032b6570032100")
	return append(prefix, pub...)
}
