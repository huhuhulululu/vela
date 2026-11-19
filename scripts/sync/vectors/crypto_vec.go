package main

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"math/big"
	mrand "math/rand/v2"
	"strings"
	"sync"

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

// signEntropy 是生成器固定种子的 CSPRNG。Go 1.26 起 crypto/ecdsa.Sign 忽略传入的
// Reader（除非 GODEBUG，而该路径还会被 MaybeReadByte 打乱），金样本又必须逐字节稳定，
// 所以 k 从这条可重放的流里取，再用 crypto/ecdsa.Verify 验收。协议本身接受任意合法 k。
type signStream struct {
	mu sync.Mutex
	c  *mrand.ChaCha8
}

func (s *signStream) Read(b []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.c.Read(b)
}

var signEntropy *signStream

func resetSignEntropy() {
	var seed [32]byte
	copy(seed[:], "vela-sync/v5/vectors")
	signEntropy = &signStream{c: mrand.NewChaCha8(seed)}
}

func signWith(d32, msg []byte) []byte {
	if signEntropy == nil {
		resetSignEntropy()
	}
	sig, err := signECDSA(d32, msg, signEntropy)
	if err != nil {
		panic(err)
	}
	return sig
}

func signECDSA(d32, msg []byte, rng io.Reader) ([]byte, error) {
	n := elliptic.P256().Params().N
	sum := sha256.Sum256(msg)
	e := new(big.Int).SetBytes(sum[:])
	e.Mod(e, n)
	d := new(big.Int).SetBytes(d32)
	pub := pubFromScalar(d32)
	pk := &ecdsa.PublicKey{Curve: elliptic.P256(), X: new(big.Int).SetBytes(pub[1:33]), Y: new(big.Int).SetBytes(pub[33:65])}
	for i := 0; i < 64; i++ {
		kb := make([]byte, 32)
		if _, err := io.ReadFull(rng, kb); err != nil {
			return nil, err
		}
		k := new(big.Int).SetBytes(kb)
		if k.Sign() == 0 || k.Cmp(n) >= 0 {
			continue
		}
		k32 := make([]byte, 32)
		k.FillBytes(k32)
		rx, _ := elliptic.P256().ScalarBaseMult(k32)
		r := new(big.Int).Mod(rx, n)
		if r.Sign() == 0 {
			continue
		}
		kInv := new(big.Int).ModInverse(k, n)
		s := new(big.Int).Mul(d, r)
		s.Add(s, e)
		s.Mul(s, kInv)
		s.Mod(s, n)
		if s.Sign() == 0 {
			continue
		}
		out := make([]byte, 64)
		r.FillBytes(out[:32])
		s.FillBytes(out[32:])
		if !ecdsa.Verify(pk, sum[:], r, s) {
			return nil, errors.New("ecdsa: library rejected signature")
		}
		return out, nil
	}
	return nil, errors.New("ecdsa: k exhausted")
}

// verifySig 执行第 9 节验签前的格式检查，再交给 crypto/ecdsa。任一检查失败即 false。
func verifySig(pub, msg, sig []byte) bool {
	if len(sig) != 64 || len(pub) != 65 || pub[0] != 0x04 {
		return false
	}
	n := p256Order()
	r := new(big.Int).SetBytes(sig[:32])
	s := new(big.Int).SetBytes(sig[32:])
	if r.Sign() <= 0 || s.Sign() <= 0 || r.Cmp(n) >= 0 || s.Cmp(n) >= 0 {
		return false
	}
	x := new(big.Int).SetBytes(pub[1:33])
	y := new(big.Int).SetBytes(pub[33:65])
	if x.BitLen() > 256 || y.BitLen() > 256 || !elliptic.P256().IsOnCurve(x, y) {
		return false
	}
	pk := &ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y}
	sum := sha256.Sum256(msg)
	return ecdsa.Verify(pk, sum[:], r, s)
}

func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
