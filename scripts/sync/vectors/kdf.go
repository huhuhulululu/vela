package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math/big"
	"strings"
)

type derived struct {
	BlobID      string
	EncKey      []byte
	SignCounter int
	D           []byte // 32 字节大端，1 ≤ d < n
	Pub         []byte // 65 字节未压缩 X9.63
	PubB64      string
	Fingerprint string
}

// P-256 的阶 n（第 4.1 节）。
func p256Order() *big.Int {
	n, ok := new(big.Int).SetString("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
	if !ok {
		panic("n")
	}
	return n
}

func scalarOK(t []byte) bool {
	if len(t) != 32 {
		return false
	}
	d := new(big.Int).SetBytes(t)
	return d.Sign() > 0 && d.Cmp(p256Order()) < 0
}

// hkdfSign 非空时按计数器注入 HKDF 输出（单测覆盖重试）；否则走真实 HKDF。
func deriveWith(k []byte, hkdfSign func(counter int) []byte) (derived, error) {
	salt := make([]byte, 32)
	id, err := hkdf.Key(sha256.New, k, salt, "vela-sync/v2/id", 32)
	if err != nil {
		return derived{}, err
	}
	enc, err := hkdf.Key(sha256.New, k, salt, "vela-sync/v2/enc", 32)
	if err != nil {
		return derived{}, err
	}
	var d32 []byte
	used := -1
	for c := 0; c <= 255; c++ {
		var t []byte
		if hkdfSign != nil {
			t = hkdfSign(c)
		} else {
			info := append([]byte("vela-sync/v2/sign"), byte(c))
			t, err = hkdf.Key(sha256.New, k, salt, string(info), 32)
			if err != nil {
				return derived{}, err
			}
		}
		if scalarOK(t) {
			d32 = append([]byte(nil), t...)
			used = c
			break
		}
	}
	if used < 0 {
		return derived{}, errors.New("sign scalar exhausted")
	}
	pub := pubFromScalar(d32)
	blob := hex.EncodeToString(id)
	fp := strings.ToUpper(blob[:4]) + "-" + strings.ToUpper(blob[4:8])
	return derived{
		BlobID: blob, EncKey: enc, SignCounter: used, D: d32,
		Pub: pub, PubB64: b64Encode(pub), Fingerprint: fp,
	}, nil
}

func derive(k []byte) derived {
	d, err := deriveWith(k, nil)
	if err != nil {
		panic(err)
	}
	return d
}

// pubFromScalar 用 crypto/elliptic 与 crypto/ecdh 由同一 d 求未压缩公钥，两者必须一致。
func pubFromScalar(d32 []byte) []byte {
	x, y := elliptic.P256().ScalarBaseMult(d32)
	if x == nil || y == nil || (x.Sign() == 0 && y.Sign() == 0) {
		panic("infinity")
	}
	out := make([]byte, 65)
	out[0] = 0x04
	x.FillBytes(out[1:33])
	y.FillBytes(out[33:65])
	ecdhPriv, err := ecdh.P256().NewPrivateKey(d32)
	if err != nil {
		panic(err)
	}
	if !bytes.Equal(out, ecdhPriv.PublicKey().Bytes()) {
		panic("elliptic/ecdh pub mismatch")
	}
	return out
}

func pubcheckMessage(blobID string) []byte {
	return []byte("vela-sync/v2/pubcheck\n" + blobID)
}
