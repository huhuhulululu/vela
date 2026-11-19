package main

import (
	"bytes"
	"crypto/elliptic"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"math/rand"
	"strings"
)

func buildVectors() []byte {
	resetSignEntropy()
	v := map[string]any{
		"kdf":              buildKDF(),
		"base64url":        buildB64(),
		"base64urlInvalid": buildB64Invalid(),
		"decimal":          buildDecimal(),
		"decimalInvalid":   buildDecimalInvalid(),
		"code":             buildCode(),
		"codeInvalid":      buildCodeInvalid(),
		"json":             buildJSONCases(),
		"tagKey":           buildTagKeys(),
		"payload":          buildPayload(),
		"payloadInvalid":   buildPayloadInvalid(),
		"envelope":         buildEnvelope(),
		"envelopeInvalid":  buildEnvelopeInvalid(),
		"pubkey":           buildPubkey(),
		"sign":             buildSign(),
		"signInvalid":      buildSignInvalid(),
		"pubcheck":         buildPubcheck(),
		"hlc":              buildHLC(),
		"merge":            buildMerge(),
		"size":             buildSize(),
		"projection":       buildProjection(),
		"ops":              buildOps(),
		"migration":        buildMigration(),
		"simulation":       buildSims(),
	}
	return marshal(v)
}

func marshal(v any) []byte {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(v); err != nil {
		panic(err)
	}
	return buf.Bytes()
}

func must(cond bool, msg string) {
	if !cond {
		panic(msg)
	}
}

func fixedK(b byte) []byte {
	k := make([]byte, 32)
	for i := range k {
		k[i] = b
	}
	return k
}

func rampK() []byte {
	k := make([]byte, 32)
	for i := range k {
		k[i] = byte(i)
	}
	return k
}

func buildKDF() []any {
	var out []any
	for _, k := range [][]byte{rampK(), fixedK(0x11), fixedK(0x00)} {
		d := derive(k)
		out = append(out, map[string]any{
			"K_hex":                hex.EncodeToString(k),
			"salt_hex":             strings.Repeat("00", 32),
			"info":                 []string{"vela-sync/v2/id", "vela-sync/v2/enc", "vela-sync/v2/sign"},
			"blobID":               d.BlobID,
			"encKey_hex":           hex.EncodeToString(d.EncKey),
			"signCounter":          d.SignCounter,
			"d_hex":                hex.EncodeToString(d.D),
			"pub_b64url":           d.PubB64,
			"fingerprint":          d.Fingerprint,
			"pubcheck_message_hex": hex.EncodeToString(pubcheckMessage(d.BlobID)),
		})
	}
	return out
}

func buildB64() []any {
	samples := [][]byte{{}, {0x00}, {0x00, 0x01}, {0x00, 0x01, 0x02}, rampK()}
	var out []any
	for _, s := range samples {
		enc := b64Encode(s)
		back, err := b64Decode(enc)
		must(err == nil && bytes.Equal(back, s), "b64 roundtrip")
		out = append(out, map[string]any{"bytes_hex": hex.EncodeToString(s), "text": enc})
	}
	return out
}

func buildB64Invalid() []any {
	// 末位未用比特：32 字节编码的最后一个 6 比特里有 2 个未用位。
	k43 := b64Encode(rampK())
	alpha := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	last := strings.IndexByte(alpha, k43[len(k43)-1])
	delta := 1
	if last&3 == 3 {
		delta = -1
	}
	flipped := k43[:len(k43)-1] + string(alpha[last+delta])
	_, err := b64Decode(flipped)
	must(err != nil && err.Error() == "noncanonical", "flip "+errString(err))
	cases := []struct{ text, reason string }{
		{"AAEC=", "padding"},
		{"AA E", "whitespace"},
		{"AA+E", "alphabet"},
		{flipped, "noncanonical"},
		{"A", "bad_length"},
	}
	var out []any
	for _, c := range cases {
		_, err := b64Decode(c.text)
		must(err != nil && err.Error() == c.reason, c.reason+" got "+errString(err))
		out = append(out, map[string]any{"text": c.text, "reason": c.reason})
	}
	return out
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func buildDecimal() []any {
	var out []any
	for _, s := range []string{"0", "1", "42", "9007199254740991"} {
		n, ok := tryDecimal(s)
		must(ok && formatDecimal(n) == s, s)
		out = append(out, map[string]any{"text": s, "value": n})
	}
	return out
}

func buildDecimalInvalid() []any {
	cases := []struct{ text, reason string }{
		{"00", "leading_zero"},
		{"01", "leading_zero"},
		{"+1", "plus_sign"},
		{"-1", "minus_sign"},
		{"1.0", "fraction"},
		{"1e2", "exponent"},
		{"9007199254740992", "out_of_range"},
		{"", "empty"},
	}
	var out []any
	for _, c := range cases {
		if _, ok := tryDecimal(c.text); ok {
			panic("accepted " + c.text)
		}
		out = append(out, map[string]any{"text": c.text, "reason": c.reason})
	}
	return out
}

func buildCode() []any {
	k := rampK()
	var out []any
	for _, origin := range []string{"https://sync.example", "https://sync.example:8443", "https://a.b"} {
		code, err := formatCode(k, origin)
		must(err == nil, errString(err))
		got, err := parseCode(code)
		must(err == nil && bytes.Equal(got.K, k) && got.Origin == origin, "parse "+origin+" "+errString(err))
		out = append(out, map[string]any{"code": code, "origin": origin, "K_hex": hex.EncodeToString(k)})
	}
	return out
}

func reasonOf(err error) string {
	if err == nil {
		return ""
	}
	if ce, ok := err.(*codeErr); ok {
		return ce.reason
	}
	return err.Error()
}

func buildCodeInvalid() []any {
	k := rampK()
	good, err := formatCode(k, "https://sync.example")
	must(err == nil, errString(err))
	k43 := b64Encode(k)
	with := func(origin string) string { return "vela2:" + k43 + ".000000@" + origin }
	alpha := "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	last := strings.IndexByte(alpha, k43[len(k43)-1])
	delta := 1
	if last&3 == 3 {
		delta = -1
	}
	badK := k43[:len(k43)-1] + string(alpha[last+delta])
	upper := good
	// c6 在 '.' 与 '@' 之间
	dot := strings.IndexByte(good, '.')
	at := strings.IndexByte(good, '@')
	upper = good[:dot+1] + strings.ToUpper(good[dot+1:at]) + good[at:]
	mismatch := good[:at-1] + "0" + good[at:]
	if mismatch == good {
		mismatch = good[:at-1] + "1" + good[at:]
	}
	longLab := strings.Repeat("a", 64)
	longHost := strings.Repeat("a", 63) + "." + strings.Repeat("b", 63) + "." + strings.Repeat("c", 63) + "." + strings.Repeat("d", 63) + ".example"
	cases := []struct{ code, reason string }{
		{"nope", "prefix"},
		{"vela2:" + k43[:42] + ".000000@https://sync.example", "k43_length"},
		{"vela2:" + k43[:10] + "+" + k43[11:] + ".000000@https://sync.example", "k43_alphabet"},
		{"vela2:" + badK + ".000000@https://sync.example", "k43_noncanonical"},
		{mismatch, "checksum_mismatch"},
		{upper, "checksum_case"},
		{with("http://sync.example"), "scheme"},
		{with("https://127.0.0.1"), "ip"},
		{with("https://[::1]"), "ip"},
		{with("https://u@sync.example"), "userinfo"},
		{with("https://sync.example/v2"), "path"},
		{with("https://sync.example/"), "path"},
		{with("https://sync.example?x=1"), "query"},
		{with("https://sync.example#x"), "fragment"},
		{with("https://sync.example:443"), "port_443"},
		{with("https://sync.example:0"), "port_range"},
		{with("https://sync.example:65536"), "port_range"},
		{with("https://sync.example:0443"), "port_leading_zero"},
		{with("https://sync.example:"), "port_empty"},
		{with("https://Sync.example"), "host_upper"},
		{with("https://localhost"), "single_label"},
		{with("https://-ab.example"), "label_hyphen"},
		{with("https://ab-.example"), "label_hyphen"},
		{with("https://sync..example"), "empty_label"},
		{with("https://" + longLab + ".example"), "label_length"},
		{with("https://" + longHost), "host_length"},
		{with("https://sy_nc.example"), "host_char"},
		{"vela2:" + k43 + ".000000@https://同步.example", "non_ascii"},
		{with("https://0x7f.0x1"), "numeric_label"},
		{with("https://0177.0.0.0x1"), "numeric_label"},
		{with("https://10.example"), "numeric_label"},
		{with("https://a.1b"), "tld_start"},
	}
	var out []any
	for _, c := range cases {
		_, err := parseCode(c.code)
		got := reasonOf(err)
		must(got == c.reason, c.reason+" got "+got+" code "+c.code)
		out = append(out, map[string]any{"code": c.code, "reason": c.reason})
	}
	return out
}

func buildJSONCases() []any {
	empty := `{"rooms":{},"schema":2,"settings":{},"veto":{}}`
	small := emptyDoc()
	putReg(small, "rooms", "cb:amy", "f", wire(5, 0, 1)+"+")
	putReg(small, "veto", "t:a", "", wire(6, 0, 1)+"+")
	putReg(small, "settings", "publicOnly", "", wire(7, 0, 1)+"1")
	rich := string(canon(small))
	st, reason := checkPlain([]byte(rich))
	must(st == "ok" && reason == "", "rich "+st+" "+reason)
	over := `{"schema":2,"pad":"` + strings.Repeat("a", plainMax) + `"}`
	must(len(over) > plainMax, "over length")
	type cse struct {
		text, hexBytes, reason, status string
		readonly                       bool
	}
	list := []cse{
		{text: empty, status: "ok"},
		{text: rich, status: "ok"},
		{text: `{"rooms":{},"schema":2,"schema":2,"settings":{},"veto":{}}`, reason: "duplicate_key", status: "invalid"},
		{text: "\uFEFF" + empty, reason: "bom", status: "invalid"},
		{text: `{"rooms":{},"schema":2,"settings":{},"veto":{"t:a":"\u0061"}}`, reason: "u_escape", status: "invalid"},
		{hexBytes: "7b22736368656d61223a32ff7d", reason: "utf8", status: "invalid"},
		{text: `{"extra":"x","rooms":{},"schema":2,"settings":{},"veto":{}}`, reason: "unknown_key", status: "invalid"},
		{text: `{"rooms":{},"schema":2,"settings":{}}`, reason: "missing_key", status: "invalid"},
		{text: `{"rooms":{},"schema":2,"settings":{"publicOnly":1},"veto":{}}`, reason: "number_not_schema", status: "invalid"},
		{text: `{"rooms":{},"schema":1,"settings":{},"veto":{}}`, reason: "schema_lt", status: "invalid"},
		{text: `{"rooms":{},"schema":3,"settings":{},"veto":{}}`, reason: "schema_gt", status: "readonly", readonly: true},
		{text: `{"rooms":{"cb:ab":{"f":{"a":"b"}}},"schema":2,"settings":{},"veto":{}}`, reason: "nesting", status: "invalid"},
		{text: over, reason: "over_p", status: "invalid"},
	}
	var out []any
	for _, c := range list {
		var raw []byte
		if c.hexBytes != "" {
			var err error
			raw, err = hex.DecodeString(c.hexBytes)
			must(err == nil, "hex")
		} else {
			raw = []byte(c.text)
		}
		st, reason := checkPlain(raw)
		must(st == c.status && reason == c.reason, fmt.Sprintf("json %s got %s/%s", c.reason, st, reason))
		m := map[string]any{"valid": st == "ok", "reason": reason}
		if c.readonly {
			m["valid"] = false
			m["readonly"] = true
			m["illegal"] = false
		}
		if c.hexBytes != "" {
			m["bytes_hex"] = c.hexBytes
		} else {
			m["text"] = c.text
		}
		out = append(out, m)
	}
	return out
}

func buildTagKeys() []any {
	type in struct {
		raw  string
		cps  []rune
		note string
	}
	long64 := strings.Repeat("a", 64)
	long65 := strings.Repeat("b", 65)
	expand := strings.Repeat("İ", 43)
	okExpand := strings.Repeat("İ", 42)
	inputs := []in{
		{raw: "abc", note: "ascii"},
		{raw: "ABC", note: "uppercase"},
		{raw: "#Tag", note: "hash"},
		{raw: "é", note: "precomposed"},
		{raw: "e\u0301", note: "decomposed"},
		{raw: "ΣΑΣ", note: "sigma"},
		{raw: "İ", note: "i-dot"},
		{raw: "ß", note: "sharp-s"},
		{raw: "ǅ", note: "dz-caron"},
		{raw: long64, note: "64"},
		{raw: long65, note: "65"},
		{raw: "\U0001F600", note: "emoji"},
		{raw: "\u061D", note: "age-after-13"},
		{raw: "\uE000", note: "private-use"},
		{raw: "a b", note: "space"},
		{raw: "a\"b", note: "quote"},
		{raw: "a\\b", note: "backslash"},
		{raw: expand, note: "lower-too-long"},
		{raw: okExpand, note: "lower-at-limit"},
		{raw: "\u1100\u1161", note: "hangul"},
		{raw: "", note: "empty"},
		{cps: []rune{0xD800}, note: "surrogate"},
		{raw: "\uFFFE", note: "noncharacter"},
		{raw: "\u0001abc", note: "control"},
	}
	var out []any
	for _, in := range inputs {
		var got TagResult
		var raw string
		m := map[string]any{"note": in.note}
		if in.cps != nil {
			got = tagKeyRunes(in.cps)
			arr := make([]int, len(in.cps))
			for i, r := range in.cps {
				arr[i] = int(r)
			}
			m["cps"] = arr
		} else {
			raw = in.raw
			got = tagKey(raw)
			m["raw"] = raw
		}
		if got.LocalOnly {
			m["local_only"] = true
			m["reason"] = got.Reason
		} else {
			m["key"] = got.Key
			// 接收方幂等
			must(strings.HasPrefix(got.Key, "t:") && receiveTagOK(got.Key[2:]), "idem "+in.note)
		}
		out = append(out, m)
	}
	return out
}

func buildPayload() []any {
	short := canon(emptyDoc())
	rep := bytes.Repeat(short, 40)
	var out []any
	for _, src := range [][]byte{short, rep} {
		flag, body := encodePayload(src)
		back, err := decodePayload(flag, body)
		must(err == nil && bytes.Equal(back, src), "payload")
		// 原文那一份永远是合法解码（flag 0）
		rawBack, err := decodePayload(0, src)
		must(err == nil && bytes.Equal(rawBack, src), "raw")
		out = append(out, map[string]any{
			"json_utf8":   string(src),
			"flag":        int(flag),
			"body_b64url": b64Encode(body),
		})
	}
	return out
}

func buildPayloadInvalid() []any {
	bombSrc := bytes.Repeat([]byte{0}, plainMax+1)
	bomb := deflateRaw(bombSrc)
	_, err := decodePayload(1, bomb)
	must(err != nil && err.Error() == "over_p", "bomb "+errString(err))
	good := deflateRaw([]byte(strings.Repeat("abc", 100)))
	trunc := good[:len(good)/2]
	_, err = decodePayload(1, trunc)
	must(err != nil && err.Error() == "truncated", "trunc "+errString(err))
	trail := append(append([]byte{}, good...), 0xFF)
	_, err = decodePayload(1, trail)
	must(err != nil && err.Error() == "trailing", "trail "+errString(err))
	_, err = decodePayload(2, []byte{1, 2, 3})
	must(err != nil && err.Error() == "bad_flag", "flag")
	return []any{
		map[string]any{"reason": "over_p", "flag": 1, "body_b64url": b64Encode(bomb)},
		map[string]any{"reason": "truncated", "flag": 1, "body_b64url": b64Encode(trunc)},
		map[string]any{"reason": "trailing", "flag": 1, "body_b64url": b64Encode(trail)},
		map[string]any{"reason": "bad_flag", "flag": 2, "body_b64url": b64Encode([]byte{1, 2, 3})},
	}
}

func sampleSeal() (derived, []byte, []byte, string, []byte) {
	d := derive(rampK())
	nonce := bytes.Repeat([]byte{0x11}, 12)
	payload := append([]byte{0x00}, canon(emptyDoc())...)
	aad := aadFor(d.BlobID, 1)
	env, ct := seal(d.EncKey, nonce, payload, aad)
	pt, err := openEnv(d.EncKey, nonce, aad, ct)
	must(err == nil && bytes.Equal(pt, payload), "open")
	return d, nonce, payload, env, ct
}

func buildEnvelope() []any {
	d, nonce, payload, env, _ := sampleSeal()
	// 再来一条压缩载荷
	big := bytes.Repeat(canon(emptyDoc()), 30)
	flag, body := encodePayload(big)
	pay := append([]byte{flag}, body...)
	nonce2 := bytes.Repeat([]byte{0x22}, 12)
	aad := aadFor(d.BlobID, 7)
	env2, ct2 := seal(d.EncKey, nonce2, pay, aad)
	pt, err := openEnv(d.EncKey, nonce2, aad, ct2)
	must(err == nil && bytes.Equal(pt, pay), "open2")
	one := func(ver uint64, nonce, payload []byte, env string) map[string]any {
		return map[string]any{
			"encKey_hex":  hex.EncodeToString(d.EncKey),
			"blobID":      d.BlobID,
			"ver":         ver,
			"nonce_hex":   hex.EncodeToString(nonce),
			"payload_hex": hex.EncodeToString(payload),
			"aad_hex":     hex.EncodeToString(aadFor(d.BlobID, ver)),
			"envelope":    env,
		}
	}
	_ = env
	return []any{one(1, nonce, payload, env), one(7, nonce2, pay, env2)}
}

func buildEnvelopeInvalid() []any {
	d, nonce, payload, env, ct := sampleSeal()
	badCT := append([]byte(nil), ct...)
	badCT[len(badCT)-1] ^= 0x01
	badEnv := "e1." + b64Encode(nonce) + "." + b64Encode(badCT)
	_, err := openEnv(d.EncKey, nonce, aadFor(d.BlobID, 1), badCT)
	must(err != nil, "tag should fail")
	// 用 ver=2 的 AAD 解 ver=1 的信封
	_, err = openEnv(d.EncKey, nonce, aadFor(d.BlobID, 2), ct)
	must(err != nil, "aad")
	trunc := env[:len(env)-5]
	padded := env + "="
	shortNonce := bytes.Repeat([]byte{0x33}, 11)
	nonceEnv := "e1." + b64Encode(shortNonce) + "." + b64Encode(ct)
	prefixed := "e2." + env[3:]
	return []any{
		map[string]any{"reason": "tag", "encKey_hex": hex.EncodeToString(d.EncKey), "blobID": d.BlobID, "ver": 1, "nonce_hex": hex.EncodeToString(nonce), "payload_hex": hex.EncodeToString(payload), "aad_hex": hex.EncodeToString(aadFor(d.BlobID, 1)), "envelope": badEnv},
		map[string]any{"reason": "aad_ver", "encKey_hex": hex.EncodeToString(d.EncKey), "blobID": d.BlobID, "ver": 2, "sealed_ver": 1, "nonce_hex": hex.EncodeToString(nonce), "payload_hex": hex.EncodeToString(payload), "aad_hex": hex.EncodeToString(aadFor(d.BlobID, 2)), "envelope": env},
		map[string]any{"reason": "truncated", "envelope": trunc},
		map[string]any{"reason": "prefix", "envelope": prefixed},
		map[string]any{"reason": "padding", "envelope": padded},
		map[string]any{"reason": "nonce_len", "envelope": nonceEnv, "nonce_hex": hex.EncodeToString(shortNonce)},
	}
}

func fixedEpoch() string { return "0123456789abcdeffedcba9876543210" }

func signFields(valid bool, reason, pub, op, blobID, epoch, line6 string, base, next uint64, msg, sig []byte) map[string]any {
	m := map[string]any{
		"pub_b64url":          pub,
		"op":                  op,
		"blobID":              blobID,
		"epoch":               epoch,
		"base":                base,
		"next":                next,
		"envelope_sha256_hex": line6,
		"message_hex":         hex.EncodeToString(msg),
		"sig_b64url":          b64Encode(sig),
		"valid":               valid,
	}
	if reason != "" {
		m["reason"] = reason
	}
	return m
}

func buildPubkey() []any {
	nm1, _ := hex.DecodeString("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550")
	one := make([]byte, 32)
	one[31] = 1
	two := make([]byte, 32)
	two[31] = 2
	scalars := [][]byte{one, nm1, two, derive(rampK()).D, derive(fixedK(0x11)).D}
	var out []any
	for _, d32 := range scalars {
		pub := pubFromScalar(d32)
		must(len(pub) == 65 && pub[0] == 0x04, "pubkey")
		out = append(out, map[string]any{
			"d_hex":      hex.EncodeToString(d32),
			"pub_b64url": b64Encode(pub),
		})
	}
	return out
}

func buildSign() []any {
	d := derive(rampK())
	_, _, _, env, _ := sampleSeal()
	sum := sha256Hex([]byte(env))
	msg := signMessage("put", d.BlobID, fixedEpoch(), 0, 1, sum)
	sig := signWith(d.D, msg)
	must(verifySig(d.Pub, msg, sig), "sign put")
	msg2 := signMessage("delete", d.BlobID, fixedEpoch(), 4, 5, "-")
	sig2 := signWith(d.D, msg2)
	must(verifySig(d.Pub, msg2, sig2), "sign del")
	// S2 回填（第 17 节）：CryptoKit 用随机 k 生成的一组签名，字面量收录；Go 验签通过才写出，三端都只做验签比对。
	sum3 := sha256Hex([]byte("e1.cryptokit-sample"))
	msg3 := signMessage("put", d.BlobID, fixedEpoch(), 7, 8, sum3)
	sig3 := mustB64("YUZ1-S62K6wCahiG8WOPzXR2Bm4_57chg8DXAyOhl5WE7wJihV8J4AQcc3n4x-ISZ88aM27j-jTo-MxyTZ1ZOw")
	must(verifySig(d.Pub, msg3, sig3), "sign cryptokit")
	return []any{
		signFields(true, "", d.PubB64, "put", d.BlobID, fixedEpoch(), sum, 0, 1, msg, sig),
		signFields(true, "", d.PubB64, "delete", d.BlobID, fixedEpoch(), "-", 4, 5, msg2, sig2),
		signFields(true, "", d.PubB64, "put", d.BlobID, fixedEpoch(), sum3, 7, 8, msg3, sig3),
	}
}

func buildSignInvalid() []any {
	d := derive(rampK())
	other := derive(fixedK(0x11))
	line6 := strings.Repeat("ab", 32)
	msg := signMessage("put", d.BlobID, fixedEpoch(), 0, 1, line6)
	sig := signWith(d.D, msg)
	must(verifySig(d.Pub, msg, sig), "base sig")
	n := make([]byte, 32)
	p256Order().FillBytes(n)
	one := make([]byte, 32)
	one[31] = 1
	zero := make([]byte, 32)
	put := func(reason, pub, epoch, line string, message, sg []byte) map[string]any {
		must(!verifySig(mustB64(pub), message, sg), reason)
		return signFields(false, reason, pub, "put", d.BlobID, epoch, line, 0, 1, message, sg)
	}
	len63 := sig[:63]
	len65 := append(append([]byte(nil), sig...), 0x00)
	rZero := append(append([]byte(nil), zero...), one...)
	sZero := append(append([]byte(nil), one...), zero...)
	rN := append(append([]byte(nil), n...), one...)
	sN := append(append([]byte(nil), one...), n...)
	flipped := append([]byte(nil), sig...)
	flippedOK := false
	for i := 0; i < len(flipped); i++ {
		flipped[i] ^= 0x01
		r := new(big.Int).SetBytes(flipped[:32])
		s := new(big.Int).SetBytes(flipped[32:])
		nOrd := p256Order()
		if r.Sign() > 0 && s.Sign() > 0 && r.Cmp(nOrd) < 0 && s.Cmp(nOrd) < 0 && !verifySig(d.Pub, msg, flipped) {
			flippedOK = true
			break
		}
		flipped[i] ^= 0x01
	}
	must(flippedOK, "sig bitflip")
	badLine := line6[:len(line6)-1] + string(line6[len(line6)-1]^0x01)
	badMsg := signMessage("put", d.BlobID, fixedEpoch(), 0, 1, badLine)
	must(!verifySig(d.Pub, badMsg, sig), "message")
	wrongEpoch := strings.Repeat("00", 16)
	msgEpoch := signMessage("put", d.BlobID, wrongEpoch, 0, 1, line6)
	must(!verifySig(d.Pub, msgEpoch, sig), "epoch")
	comp := append([]byte{0x02}, d.Pub[1:33]...)
	must(len(comp) == 33 && comp[0] == 0x02, "compressed")
	off := append([]byte(nil), d.Pub...)
	offCurve := false
	for i := 64; i >= 33; i-- {
		off[i] ^= 0x01
		if _, err := ecdsaParsePub(off); err != nil {
			offCurve = true
			break
		}
		off[i] ^= 0x01
	}
	must(offCurve, "not on curve")
	return []any{
		put("sig_len_63", d.PubB64, fixedEpoch(), line6, msg, len63),
		put("sig_len_65", d.PubB64, fixedEpoch(), line6, msg, len65),
		put("r_zero", d.PubB64, fixedEpoch(), line6, msg, rZero),
		put("s_zero", d.PubB64, fixedEpoch(), line6, msg, sZero),
		put("r_eq_n", d.PubB64, fixedEpoch(), line6, msg, rN),
		put("s_eq_n", d.PubB64, fixedEpoch(), line6, msg, sN),
		put("sig_bitflip", d.PubB64, fixedEpoch(), line6, msg, flipped),
		put("message_bitflip", d.PubB64, fixedEpoch(), badLine, badMsg, sig),
		put("epoch", d.PubB64, wrongEpoch, line6, msgEpoch, sig),
		put("pubkey", other.PubB64, fixedEpoch(), line6, msg, sig),
		put("pub_compressed", b64Encode(comp), fixedEpoch(), line6, msg, sig),
		put("pub_not_on_curve", b64Encode(off), fixedEpoch(), line6, msg, sig),
	}
}

func mustB64(s string) []byte {
	b, err := b64Decode(s)
	if err != nil {
		panic(err)
	}
	return b
}

func ecdsaParsePub(pub []byte) (bool, error) {
	if len(pub) != 65 || pub[0] != 0x04 {
		return false, errors.New("form")
	}
	x := new(big.Int).SetBytes(pub[1:33])
	y := new(big.Int).SetBytes(pub[33:])
	if !elliptic.P256().IsOnCurve(x, y) {
		return false, errors.New("curve")
	}
	return true, nil
}

func buildPubcheck() []any {
	var out []any
	for _, k := range [][]byte{rampK(), fixedK(0x11)} {
		d := derive(k)
		msg := pubcheckMessage(d.BlobID)
		sig := signWith(d.D, msg)
		must(bytes.Equal(msg, []byte("vela-sync/v2/pubcheck\n"+d.BlobID)), "pubcheck text")
		must(verifySig(d.Pub, msg, sig), "pubcheck")
		out = append(out, map[string]any{
			"blobID":      d.BlobID,
			"message_hex": hex.EncodeToString(msg),
			"pub_b64url":  d.PubB64,
			"sig_b64url":  b64Encode(sig),
			"valid":       true,
		})
	}
	bad := out[0].(map[string]any)
	sig, _ := b64Decode(bad["sig_b64url"].(string))
	sig = append([]byte(nil), sig...)
	sig[63] ^= 0x01
	msg, _ := hex.DecodeString(bad["message_hex"].(string))
	must(!verifySig(mustB64(bad["pub_b64url"].(string)), msg, sig), "pubcheck bad")
	out = append(out, map[string]any{
		"blobID":      bad["blobID"],
		"message_hex": bad["message_hex"],
		"pub_b64url":  bad["pub_b64url"],
		"sig_b64url":  b64Encode(sig),
		"valid":       false,
	})
	return out
}

func hlcObj(h HLC) map[string]any {
	return map[string]any{"l": h.L, "c": h.C, "dev": h.Dev}
}

func buildHLC() []any {
	replay := func(st HLC, events []map[string]any) {
		for _, ev := range events {
			switch ev["type"] {
			case "local":
				pt, _ := ev["pt"].(uint64)
				st = st.local(pt)
				exp := ev["expected"].(map[string]any)
				must(st.L == exp["l"] && uint64(st.C) == exp["c"].(uint64) && uint64(st.Dev) == exp["dev"].(uint64), "local")
			case "receive":
				// checked at construction
			case "presend":
			}
		}
	}
	_ = replay
	ev := func(st HLC, pt uint64) map[string]any {
		nxt := st.local(pt)
		return map[string]any{"type": "local", "pt": pt, "expected": hlcObj(nxt)}
	}
	st := HLC{L: 1000, C: 5, Dev: 1}
	rollback := []map[string]any{ev(st, 500)}
	st2 := HLC{L: 1000, C: 5, Dev: 1}
	same := []map[string]any{ev(st2, 1000)}
	st3 := HLC{L: 1000, C: 0xFFFF, Dev: 1}
	over := []map[string]any{ev(st3, 1000)}
	st4 := HLC{L: 1000, C: 1, Dev: 1}
	wall := []map[string]any{ev(st4, 2000)}

	// receive
	doc := emptyDoc()
	putReg(doc, "rooms", "cb:amy", "f", wire(50, 2, 2)+"+")
	putReg(doc, "rooms", "cb:bob", "f", wire(80, 0, 3)+"+")
	recv := HLC{L: 10, C: 0, Dev: 1}.receive(doc)
	must(recv.L == 80 && recv.C == 0, "recv")

	const N = uint64(1_000_000)
	fast := emptyDoc()
	putReg(fast, "rooms", "cb:amy", "f", wire(N+3600001, 0, 1)+"+")
	putReg(fast, "rooms", "cb:amy", "b", wire(N+3600001, 0, 1)+"-")
	putReg(fast, "rooms", "cb:bob", "f", wire(100, 0, 2)+"+")
	stF := HLC{L: N + 3600001, C: 0, Dev: 1}
	stF2, docF, diag := presend(stF, fast, N, 0)
	must(!diag && stF2.L == N && stF2.C == 1, "presend all")

	part := emptyDoc()
	putReg(part, "rooms", "cb:old", "f", wire(N+4_000_000, 0, 1)+"+")
	putReg(part, "rooms", "cb:new", "f", wire(N+8_000_000, 0, 1)+"+")
	stP := HLC{L: N + 8_000_000, C: 0, Dev: 1}
	stP2, docP, diagP := presend(stP, part, N, N+5_000_000)
	must(diagP && reg(docP, "rooms", "cb:old", "f") == wire(N+4_000_000, 0, 1)+"+" && reg(docP, "rooms", "cb:new", "f") == wire(N+8_000_000, 0, 1)+"+" && stP2 == stP, "partial")

	bounds := []any{
		map[string]any{"wire": wire(1, 0, 1), "valid": true},
		map[string]any{"wire": wire(0, 1, 1), "valid": false, "reason": "l_min"},
		map[string]any{"wire": wire(0xFFFF00000000, 0, 1), "valid": true},
		map[string]any{"wire": wire(0xFFFF00000001, 0, 1), "valid": false, "reason": "l_max"},
		map[string]any{"wire": wire(1, 0, 0), "valid": false, "reason": "dev_zero"},
	}
	for _, b := range bounds {
		m := b.(map[string]any)
		l, _, dev, _, ok := parseWire(m["wire"].(string))
		must(ok && hlcValid(l, dev) == m["valid"].(bool), "bound")
	}
	return []any{
		map[string]any{"name": "rollback", "state": hlcObj(HLC{L: 1000, C: 5, Dev: 1}), "events": rollback},
		map[string]any{"name": "same-millisecond", "state": hlcObj(HLC{L: 1000, C: 5, Dev: 1}), "events": same},
		map[string]any{"name": "counter-overflow", "state": hlcObj(HLC{L: 1000, C: 0xFFFF, Dev: 1}), "events": over},
		map[string]any{"name": "wall-advance", "state": hlcObj(HLC{L: 1000, C: 1, Dev: 1}), "events": wall},
		map[string]any{"name": "receive", "state": hlcObj(HLC{L: 10, C: 0, Dev: 1}), "events": []any{map[string]any{"type": "receive", "doc": json.RawMessage(canon(doc)), "expected": hlcObj(recv)}}},
		map[string]any{"name": "presend-all-unsent", "state": hlcObj(stF), "events": []any{map[string]any{"type": "presend", "N": N, "maxSentL": uint64(0), "doc": json.RawMessage(canon(fast)), "expected_state": hlcObj(stF2), "expected_doc": json.RawMessage(canon(docF)), "diagnostic": false}}},
		map[string]any{"name": "presend-partial", "state": hlcObj(stP), "events": []any{map[string]any{"type": "presend", "N": N, "maxSentL": N + 5_000_000, "doc": json.RawMessage(canon(part)), "expected_state": hlcObj(stP2), "expected_doc": json.RawMessage(canon(docP)), "diagnostic": true}}},
		map[string]any{"name": "static-bounds", "state": hlcObj(HLC{L: 1, C: 0, Dev: 1}), "events": []any{}, "bounds": bounds},
	}
}

func rawDoc(d *Doc) json.RawMessage { return json.RawMessage(canon(d)) }

func buildMerge() []any {
	// 同 HLC，'-' 胜
	a := emptyDoc()
	b := emptyDoc()
	putReg(a, "rooms", "cb:amy", "f", wire(2, 0, 1)+"+")
	putReg(b, "rooms", "cb:amy", "f", wire(2, 0, 1)+"-")
	// 缺项
	c := emptyDoc()
	putReg(c, "rooms", "cb:bob", "b", wire(3, 0, 2)+"+")
	putReg(c, "veto", "g:f", "", wire(4, 0, 2)+"+")
	// 超上限：301 个收藏，最旧的两个 HLC 相同，键大的被丢掉
	big := emptyDoc()
	for i := 0; i < 299; i++ {
		putReg(big, "rooms", roomKey(i), "f", wire(uint64(1000+i), 0, 1)+"+")
	}
	putReg(big, "rooms", roomKey(299), "f", wire(10, 0, 1)+"+")
	other := emptyDoc()
	putReg(other, "rooms", roomKey(300), "f", wire(10, 0, 1)+"+")
	merged := mergeDocs(big, other)
	must(len(favKeys(merged)) == 300, "cap")
	_, ok := merged.Rooms[roomKey(300)]
	must(!ok, "drop larger")
	// 屏蔽上限平局
	blk := emptyDoc()
	for i := 0; i < 299; i++ {
		putReg(blk, "rooms", roomKey(i), "b", wire(uint64(5000+i), 0, 1)+"+")
	}
	blk2 := emptyDoc()
	putReg(blk2, "rooms", roomKey(400), "b", wire(10, 0, 1)+"+")
	putReg(blk, "rooms", roomKey(401), "b", wire(10, 0, 1)+"+")
	mb := mergeDocs(blk, blk2)
	must(countKind(mb, "block") == 300, "block cap")
	// 墓碑上限
	tombs := emptyDoc()
	for i := 0; i < 1000; i++ {
		putReg(tombs, "rooms", roomKey(i), "f", wire(uint64(100+i), 0, 1)+"-")
	}
	tombs2 := emptyDoc()
	putReg(tombs2, "rooms", roomKey(1500), "f", wire(1, 0, 1)+"-")
	mt := mergeDocs(tombs, tombs2)
	must(countKind(mt, "tomb") == 1000, "tomb cap")
	must(mt.Rooms[roomKey(1500)].F == "", "oldest tomb dropped")
	// veto 上限
	v := emptyDoc()
	for i := 0; i < 450; i++ {
		putReg(v, "veto", fmt.Sprintf("t:a%04d", i), "", wire(uint64(100+i), 0, 1)+"+")
	}
	v2 := emptyDoc()
	putReg(v2, "veto", "t:zzzz", "", wire(1, 0, 1)+"+")
	mv := mergeDocs(v, v2)
	must(vetoPlusCount(mv) == 450 && mv.Veto["t:zzzz"] == "", "veto cap")
	// 设置同刻 1 胜
	s1 := emptyDoc()
	s2 := emptyDoc()
	putReg(s1, "settings", "publicOnly", "", wire(9, 0, 1)+"0")
	putReg(s2, "settings", "publicOnly", "", wire(9, 0, 1)+"1")
	ms := mergeDocs(s1, s2)
	must(payloadOf(ms.Settings["publicOnly"]) == "1", "setting tie")
	pair := func(name string, x, y *Doc) map[string]any {
		for _, in := range []*Doc{x, y} {
			st, reason := checkPlain(canon(in))
			must(st == "ok", name+" input "+reason)
		}
		exp := mergeDocs(x, y)
		st, reason := checkPlain(canon(exp))
		must(st == "ok", name+" "+reason)
		return map[string]any{"name": name, "a": rawDoc(x), "b": rawDoc(y), "expected": rawDoc(exp)}
	}
	return []any{
		pair("same-hlc-delete-wins", a, b),
		pair("missing-side", a, c),
		pair("favorite-cap", big, other),
		pair("block-cap", blk, blk2),
		pair("tombstone-cap", tombs, tombs2),
		pair("veto-cap", v, v2),
		pair("setting-tie", s1, s2),
		pair("idempotent", a, clone(a)),
	}
}

func maxUser(i int) string { return fmt.Sprintf("u%029d", i) }

func putLiveRoom(d *Doc, i int, fav bool) {
	key := "cb:" + maxUser(i)
	hi := wire(uint64(1000+i), 0, 1)
	lo := wire(1, 0, 1)
	n := wire(uint64(1000+i), 1, 1) + maxUser(i)
	if fav {
		putReg(d, "rooms", key, "f", hi+"+")
		putReg(d, "rooms", key, "b", lo+"-")
	} else {
		putReg(d, "rooms", key, "b", hi+"+")
		putReg(d, "rooms", key, "f", lo+"-")
	}
	putReg(d, "rooms", key, "n", n)
}

func makeSizedDoc(tombs bool) *Doc {
	d := emptyDoc()
	for i := 0; i < 300; i++ {
		putLiveRoom(d, i, true)
	}
	for i := 300; i < 600; i++ {
		putLiveRoom(d, i, false)
	}
	if tombs {
		for i := 600; i < 1600; i++ {
			key := "cb:" + maxUser(i)
			h := wire(uint64(1000+i), 0, 1)
			putReg(d, "rooms", key, "f", h+"-")
			putReg(d, "rooms", key, "b", h+"-")
			putReg(d, "rooms", key, "n", wire(uint64(1000+i), 1, 1)+maxUser(i))
		}
	}
	nv := 450
	if tombs {
		nv = 950
	}
	for i := 0; i < nv; i++ {
		tag := strings.Repeat("a", 120) + fmt.Sprintf("%08d", i)
		pay := "+"
		if i >= 450 {
			pay = "-"
		}
		putReg(d, "veto", "t:"+tag, "", wire(uint64(2000+i), 0, 1)+pay)
	}
	putReg(d, "settings", "infoAlwaysOn", "", wire(9, 0, 1)+"0")
	putReg(d, "settings", "publicOnly", "", wire(9, 1, 1)+"1")
	return d
}

func typicalDoc() *Doc {
	d := emptyDoc()
	for i := 0; i < 30; i++ {
		name := fmt.Sprintf("fav%02d", i)
		h := wire(uint64(100+i), 0, 1)
		putReg(d, "rooms", "cb:"+name, "f", h+"+")
		putReg(d, "rooms", "cb:"+name, "b", wire(uint64(50+i), 0, 1)+"-")
		if i%2 == 0 {
			putReg(d, "rooms", "cb:"+name, "n", h+strings.ToUpper(name[:1])+name[1:])
		}
	}
	for i := 0; i < 10; i++ {
		name := fmt.Sprintf("blk%02d", i)
		h := wire(uint64(300+i), 0, 1)
		putReg(d, "rooms", "cb:"+name, "b", h+"+")
		putReg(d, "rooms", "cb:"+name, "f", wire(uint64(10+i), 0, 1)+"-")
	}
	for i := 0; i < 20; i++ {
		putReg(d, "veto", fmt.Sprintf("t:tag%02d", i), "", wire(uint64(400+i), 0, 1)+"+")
	}
	putReg(d, "veto", "g:f", "", wire(500, 0, 1)+"+")
	putReg(d, "settings", "publicOnly", "", wire(600, 0, 1)+"1")
	putReg(d, "settings", "infoAlwaysOn", "", wire(601, 0, 1)+"0")
	return d
}

func buildSize() []any {
	full := makeSizedDoc(true)
	live := makeSizedDoc(false)
	fullB := canon(full)
	liveB := canon(live)
	st, reason := checkPlain(fullB)
	must(st == "ok", "full plain "+reason+" bytes "+fmt.Sprint(len(fullB)))
	st, reason = checkPlain(liveB)
	must(st == "ok", "live plain "+reason)
	fullSz := applySize(full, plainMax, sizeLocal)
	liveSz := applySize(live, plainMax, sizeLocal)
	must(fullSz.Result == "accept" && fullSz.TombstonesRemoved == 0, "full accept")
	must(liveSz.Result == "accept" && liveSz.TombstonesRemoved == 0, "live accept")
	typ := typicalDoc()
	typB := canon(typ)
	comp := deflateRaw(typB)
	return []any{
		map[string]any{
			"name": "接近上限", "cause": "local", "result": "accept",
			"bytes": len(fullB), "p": plainMax, "tombstones_removed": 0,
			"doc": rawDoc(full), "expected": rawDoc(fullSz.Doc),
		},
		map[string]any{
			"name": "裁完仍超", "cause": "local", "result": liveSz.Result,
			"reachable": false, "bytes": len(liveB), "p": plainMax,
			"tombstones_removed": 0,
			"rule":               "删尽墓碑后仍超过 P 时，本地操作拒绝（文档不变），合并则保留结果并停止推送",
			"doc":                rawDoc(live),
			"expected":           rawDoc(liveSz.Doc),
			"note":               "数量上限内、墓碑已尽的最大明文仍小于 P，拒绝分支在现行上限下不可达",
		},
		map[string]any{
			"name": "实测", "with_tombstones": len(fullB), "live_only": len(liveB),
			"typical": len(typB), "typical_deflate": len(comp), "p": plainMax,
		},
	}
}

func buildProjection() []any {
	d := emptyDoc()
	putReg(d, "rooms", "cb:amy", "f", wire(10, 0, 1)+"+")
	putReg(d, "rooms", "cb:amy", "n", wire(10, 0, 1)+"Amy")
	putReg(d, "rooms", "cb:bob", "b", wire(12, 0, 1)+"+")
	putReg(d, "rooms", "cb:cara", "f", wire(12, 0, 1)+"+")
	putReg(d, "rooms", "cb:cara", "b", wire(12, 0, 1)+"+") // 同刻屏蔽胜
	putReg(d, "rooms", "xx:zoe", "f", wire(11, 0, 1)+"+")
	putReg(d, "veto", "t:a", "", wire(3, 0, 1)+"+")
	putReg(d, "veto", "g:m", "", wire(4, 0, 1)+"-")
	putReg(d, "veto", "t:b", "", wire(8, 0, 1)+"+")
	putReg(d, "settings", "publicOnly", "", wire(1, 0, 1)+"1")
	p := projectDoc(d)
	// 收藏：amy（有显示名）、xx:zoe（无 n，用用户名）。cara 是屏蔽。
	must(len(p.Favorites) == 2 && p.Favorites[0].Room == "xx:zoe" && p.Favorites[1].Name == "Amy", "proj fav")
	must(len(p.Blocked) == 2, "proj blk")
	must(len(p.Vetoed) == 2 && p.Vetoed[0] == "t:b", "proj veto")
	must(p.Settings["publicOnly"] == "1" && p.Settings["infoAlwaysOn"] == "", "proj set")
	return []any{
		map[string]any{"name": "mixed", "doc": rawDoc(d), "expected": p},
	}
}

func opEntry(name string, doc *Doc, st HLC, pt uint64, ops []Op) map[string]any {
	cur, h := clone(doc), st
	var steps []any
	for _, op := range ops {
		next, nh, wrote := applyOp(cur, h, pt, op)
		steps = append(steps, map[string]any{
			"fn": op.Fn, "name": op.Name, "names": op.Names, "key": op.Key, "value": op.Value,
			"wrote": wrote, "doc": rawDoc(next), "hlc": hlcObj(nh),
		})
		cur, h = next, nh
	}
	return map[string]any{
		"name": name, "doc": rawDoc(doc), "hlc": hlcObj(st), "pt": pt,
		"ops": steps, "expected": rawDoc(cur), "expected_hlc": hlcObj(h),
	}
}

func buildOps() []any {
	st := HLC{Dev: 1}
	pt := uint64(5_000)
	empty := emptyDoc()
	seq := opEntry("basic", empty, st, pt, []Op{
		{Fn: "Curation.favoriting", Name: "Alice"},
		{Fn: "Curation.favoriting", Name: "x"},
		{Fn: "Curation.favoriting", Name: "bob"},
		{Fn: "Curation.unfavoriting", Name: "bob"},
		{Fn: "Curation.unfavoriting", Name: "nobody"},
		{Fn: "Curation.blocking", Name: "Cara"},
		{Fn: "Curation.unblocking", Name: "Cara"},
		{Fn: "Curation.restoringFavorite", Name: "bob"},
		{Fn: "Curation.restoringFavorite", Name: "Alice"},
		{Fn: "Curation.importing", Names: []string{"Dan", "dan", "Eve", "bad name", "zz"}},
		{Fn: "Curation.importing", Names: []string{"Dan", "dan", "Eve", "bad name", "zz"}},
		{Fn: "Taste.vetoing", Key: "t:asian"},
		{Fn: "Taste.vetoing", Key: "t:NOT GOOD"},
		{Fn: "Taste.restoring", Key: "t:asian"},
		{Fn: "Taste.vetoing", Key: "g:m"},
		{Fn: "Taste.boosting", Key: "g:m"},
		{Fn: "Taste.removing", Key: "g:m"},
		{Fn: "Taste.vetoing", Key: "t:cat"},
		{Fn: "Taste.vetoing", Key: "g:f"},
		{Fn: "Taste.resettingProfile"},
		{Fn: "settings", Key: "publicOnly", Value: "1"},
		{Fn: "settings", Key: "infoAlwaysOn", Value: "0"},
		{Fn: "marking", Name: "alice"},
		{Fn: "rejecting"},
		{Fn: "following"},
		{Fn: "recording"},
		{Fn: "settingPaused"},
		{Fn: "Taste.erased"},
	})
	return []any{seq}
}

func buildMigration() []any {
	create := MigInput{
		Favorites: []string{"  Alice  ", "bob", "x", "bad name", "ALICE"},
		Blocked:   []string{"Carol", "carol"},
		Vetoed:    []string{"t:zebra", "g:m", "t:NOT GOOD", "nope", "t:asian"},
		Settings:  map[string]string{"publicOnly": "1", "infoAlwaysOn": "0"},
		PT:        1_700_000_000_000, Dev: 7,
	}
	doc, counts, _ := migrate(create)
	st, reason := checkPlain(canon(doc))
	must(st == "ok", "mig "+reason)
	// 加入：远端把 amy 屏蔽、cara 已收藏、publicOnly 已有值
	remote := emptyDoc()
	putReg(remote, "rooms", "cb:amy", "b", wire(10, 0, 2)+"+")
	putReg(remote, "rooms", "cb:cara", "f", wire(11, 0, 2)+"+")
	putReg(remote, "rooms", "cb:erin", "b", wire(12, 0, 2)+"+")
	putReg(remote, "veto", "t:asian", "", wire(13, 0, 2)+"+")
	putReg(remote, "settings", "publicOnly", "", wire(14, 0, 2)+"0")
	join := MigInput{
		Favorites: []string{"Amy", "bob", "Cara"},
		Blocked:   []string{"Dave", "Erin"},
		Vetoed:    []string{"t:asian", "t:bb", "g:f"},
		Settings:  map[string]string{"publicOnly": "1", "infoAlwaysOn": "1"},
		PT:        1_700_000_000_000, Dev: 7,
		Remote: remote,
	}
	jdoc, jc, _ := migrate(join)
	must(jc.Z == 1 && jc.N == 1 && jc.M == 1 && jc.V == 2, fmt.Sprintf("join counts %+v", jc))
	// 加入时合并超过 300：本机 300 个收藏（Curation 上限）加远端 3 个更旧的收藏，裁掉远端的 3 个（第 23 节第 6 条：X 计总数）。
	names := make([]string, 300)
	for i := range names {
		names[i] = fmt.Sprintf("u%04d", i)
	}
	farRemote := emptyDoc()
	for i := 0; i < 3; i++ {
		putReg(farRemote, "rooms", fmt.Sprintf("cb:r%03d", i), "f", wire(uint64(20+i), 0, 2)+"+")
	}
	over := MigInput{Favorites: names, PT: 1_700_000_000_000, Dev: 3, Remote: farRemote}
	odoc, oc, _ := migrate(over)
	must(oc.N == 300 && oc.X == 3 && countKind(odoc, "fav") == 300, fmt.Sprintf("over %+v", oc))
	// 远端墓碑比本机墙钟新：先看见远端再盖章，本机收藏保留。
	ahead := emptyDoc()
	putReg(ahead, "rooms", "cb:amy", "f", wire(1_800_000_000_000, 0, 2)+"-")
	putReg(ahead, "rooms", "cb:amy", "b", wire(1_800_000_000_000, 0, 2)+"-")
	tomb := MigInput{Favorites: []string{"amy"}, PT: 1_700_000_000_000, Dev: 3, Remote: ahead}
	tdoc, tc, _ := migrate(tomb)
	must(tc.N == 1 && projectRoom(tdoc.Rooms["cb:amy"]) == "fav", fmt.Sprintf("tomb ahead %+v", tc))
	pack := func(name string, in MigInput, doc *Doc, c Counts) map[string]any {
		m := map[string]any{
			"name": name, "favorites": in.Favorites, "blocked": in.Blocked, "vetoed": in.Vetoed,
			"settings": in.Settings, "pt": in.PT, "dev": in.Dev,
			"expected": rawDoc(doc),
			"counts":   map[string]int{"N": c.N, "M": c.M, "V": c.V, "X": c.X, "Y": c.Y, "Z": c.Z},
		}
		if in.Remote != nil {
			m["remote"] = rawDoc(in.Remote)
		}
		return m
	}
	return []any{pack("create", create, doc, counts), pack("join", join, jdoc, jc), pack("join-over-favorite", over, odoc, oc), pack("join-tombstone-ahead", tomb, tdoc, tc)}
}

func buildSims() []any {
	var out []any
	for i := 0; i < 48; i++ {
		out = append(out, oneSim(20261118+i, false))
	}
	out = append(out, oneSim(20261118+100, true))
	out = append(out, oneSim(20261118+101, true))
	must(len(out) >= 50, "sims")
	return out
}

func oneSim(seed int, big bool) map[string]any {
	rng := rand.New(rand.NewSource(int64(seed)))
	n := 2 + rng.Intn(5)
	if big {
		n = 2
	}
	docs := make([]*Doc, n)
	hs := make([]HLC, n)
	// 各副本已发出寄存器的最大 l（第 5.2 节 maxSentL）；模拟里写给另一个副本即已发出（第 23 节第 5 条）。
	sent := make([]uint64, n)
	for i := 0; i < n; i++ {
		docs[i] = emptyDoc()
		hs[i] = HLC{Dev: uint32(i + 1)}
	}
	names := []string{"amy", "bob", "cara", "dan", "eve", "finn"}
	if big {
		names = make([]string, 320)
		for i := range names {
			names[i] = fmt.Sprintf("u%04d", i)
		}
	}
	tags := []string{"t:a", "t:bb", "g:f", "g:m", "t:NOT GOOD"}
	stepsN := 8 + rng.Intn(5)
	if big {
		stepsN = 3
	}
	base := uint64(1_700_000_000_000)
	var steps []any
	record := func(m map[string]any) {
		raws := make([]json.RawMessage, n)
		for i := range docs {
			raws[i] = rawDoc(docs[i])
		}
		m["docs"] = raws
		steps = append(steps, m)
	}
	for s := 0; s < stepsN; s++ {
		ptFor := func(rep int) uint64 {
			if rep == 0 {
				return base + 7_200_000 + uint64(s)
			}
			return base + uint64(s)*1000
		}
		if big {
			var op Op
			switch s {
			case 0:
				op = Op{Fn: "Curation.importing", Names: append([]string{}, names[:300]...)}
			case 1:
				op = Op{Fn: "Curation.importing", Names: append([]string{}, names[20:320]...)}
			default:
				op = Op{Fn: "Curation.unfavoriting", Name: names[rng.Intn(len(names))]}
			}
			rep := s % n
			docs[rep], hs[rep], _ = applyOp(docs[rep], hs[rep], ptFor(rep), op)
			record(map[string]any{"kind": "op", "replica": rep, "fn": op.Fn, "name": op.Name, "names": op.Names, "pt": ptFor(rep)})
			continue
		}
		if rng.Intn(5) == 0 {
			i := rng.Intn(n)
			j := rng.Intn(n - 1)
			if j >= i {
				j++
			}
			write := "i"
			if rng.Intn(2) == 0 {
				write = "both"
			}
			now := base + uint64(s)*1000
			docs, hs = doMerge(docs, hs, sent, now, i, j, write)
			record(map[string]any{"kind": "merge", "i": i, "j": j, "write": write, "now": now})
			continue
		}
		rep := rng.Intn(n)
		var op Op
		switch rng.Intn(8) {
		case 0:
			op = Op{Fn: "Curation.favoriting", Name: names[rng.Intn(len(names))]}
		case 1:
			op = Op{Fn: "Curation.unfavoriting", Name: names[rng.Intn(len(names))]}
		case 2:
			op = Op{Fn: "Curation.blocking", Name: names[rng.Intn(len(names))]}
		case 3:
			op = Op{Fn: "Curation.unblocking", Name: names[rng.Intn(len(names))]}
		case 4:
			op = Op{Fn: "Taste.vetoing", Key: tags[rng.Intn(len(tags))]}
		case 5:
			op = Op{Fn: "Taste.restoring", Key: tags[rng.Intn(len(tags))]}
		case 6:
			op = Op{Fn: "settings", Key: "publicOnly", Value: []string{"0", "1"}[rng.Intn(2)]}
		default:
			op = Op{Fn: "marking", Name: names[rng.Intn(len(names))]}
		}
		docs[rep], hs[rep], _ = applyOp(docs[rep], hs[rep], ptFor(rep), op)
		record(map[string]any{"kind": "op", "replica": rep, "fn": op.Fn, "name": op.Name, "names": op.Names, "key": op.Key, "value": op.Value, "pt": ptFor(rep)})
	}
	idle, guard := 0, 0
	for idle < 2 {
		guard++
		if guard > 40 {
			panic(fmt.Sprintf("sim %d did not converge", seed))
		}
		changed := false
		for i := 1; i < n; i++ {
			b0, bi := canon(docs[0]), canon(docs[i])
			now := base + uint64(stepsN+guard)*1000
			docs, hs = doMerge(docs, hs, sent, now, 0, i, "both")
			record(map[string]any{"kind": "merge", "i": 0, "j": i, "write": "both", "phase": "converge", "now": now})
			if !bytes.Equal(canon(docs[0]), b0) || !bytes.Equal(canon(docs[i]), bi) {
				changed = true
			}
		}
		if changed {
			idle = 0
		} else {
			idle++
		}
	}
	fin := canon(docs[0])
	for i := 1; i < n; i++ {
		if !bytes.Equal(canon(docs[i]), fin) {
			panic(fmt.Sprintf("sim %d replicas differ", seed))
		}
	}
	must(countKind(docs[0], "fav") <= capFav && countKind(docs[0], "block") <= capBlock, "sim cap")
	st, reason := checkPlain(fin)
	must(st == "ok", fmt.Sprintf("sim %d plain %s", seed, reason))
	return map[string]any{
		"seed": seed, "replicas": n, "fast": []int{0},
		"steps": steps, "final": json.RawMessage(fin),
	}
}

// doMerge 合并 i 与 j。离开设备的一方（write=i 时是 j，both 时是双方）先以真实时间 now 执行
// 发送前重新盖章，再把它的寄存器记为已发出；随后合并并写回。
func doMerge(docs []*Doc, hs []HLC, sent []uint64, now uint64, i, j int, write string) ([]*Doc, []HLC) {
	var leaving []int
	switch write {
	case "both":
		leaving = []int{i, j}
	case "i":
		leaving = []int{j}
	default:
		leaving = []int{i}
	}
	for _, s := range leaving {
		hs[s], docs[s], _ = presend(hs[s], docs[s], now, sent[s])
		eachReg(docs[s], func(l uint64, _ uint16, _ uint32, _ string) {
			if l > sent[s] {
				sent[s] = l
			}
		})
	}
	di, dj := docs[i], docs[j]
	merged := mergeDocs(di, dj)
	if write == "both" || write == "i" {
		hs[i] = hs[i].receive(dj)
		docs[i] = clone(merged)
	}
	if write == "both" || write == "j" {
		hs[j] = hs[j].receive(di)
		docs[j] = clone(merged)
	}
	return docs, hs
}

func probeVectors() []byte {
	resetSignEntropy()
	k := fixedK(0x42)
	d := derive(k)
	msg1 := []byte("vela-sync-probe/v1")
	// 探针自己签的两条消息不进金样本；金样本的 sign/pubcheck 由下面的构建函数按同一条熵流重放。
	obj := map[string]any{
		"k_hex":            hex.EncodeToString(k),
		"d_hex":            hex.EncodeToString(d.D),
		"pub_hex":          hex.EncodeToString(d.Pub),
		"blobID":           d.BlobID,
		"sign_msg_hex":     hex.EncodeToString(msg1),
		"pubcheck_msg_hex": hex.EncodeToString(pubcheckMessage(d.BlobID)),
		"pubkey":           buildPubkey(),
		"sign":             buildSign(),
		"signInvalid":      buildSignInvalid(),
		"pubcheck":         buildPubcheck(),
	}
	return marshal(obj)
}
