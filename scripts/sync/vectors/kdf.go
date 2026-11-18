package main

import (
	"crypto/ed25519"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

type derived struct {
	BlobID      string
	EncKey      []byte
	SignSeed    []byte
	Pub         []byte
	PubB64      string
	Fingerprint string
}

func derive(k []byte) derived {
	salt := make([]byte, 32)
	id, err := hkdf.Key(sha256.New, k, salt, "vela-sync/v2/id", 32)
	if err != nil {
		panic(err)
	}
	enc, err := hkdf.Key(sha256.New, k, salt, "vela-sync/v2/enc", 32)
	if err != nil {
		panic(err)
	}
	seed, err := hkdf.Key(sha256.New, k, salt, "vela-sync/v2/sign", 32)
	if err != nil {
		panic(err)
	}
	pub := []byte(ed25519.NewKeyFromSeed(seed).Public().(ed25519.PublicKey))
	blob := hex.EncodeToString(id)
	fp := strings.ToUpper(blob[:4]) + "-" + strings.ToUpper(blob[4:8])
	return derived{
		BlobID: blob, EncKey: enc, SignSeed: seed,
		Pub: pub, PubB64: b64Encode(pub), Fingerprint: fp,
	}
}

func pubcheckMessage(blobID string) []byte {
	return []byte("vela-sync/v2/pubcheck\n" + blobID)
}
