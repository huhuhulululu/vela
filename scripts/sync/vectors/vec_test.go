package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"math/big"
	"strings"
	"testing"
)

// 独立于生成器算过的 HKDF-SHA256（salt 为 32 个 0x00，IKM = 0x00..0x1f）。
func TestKDFKnownAnswer(t *testing.T) {
	k, err := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	if err != nil {
		t.Fatal(err)
	}
	got := derive(k)
	if got.BlobID != "e89fab0772b4dd0becc6198ca404654c2e8c1c6f7c9673ffe0911c0ee293461c" {
		t.Fatalf("blobID %s", got.BlobID)
	}
	if hex.EncodeToString(got.EncKey) != "54b3bf14c4489b29f6fc6adee04111bdc735b4132cde80b13d34dc36d9b9272c" {
		t.Fatalf("enc %x", got.EncKey)
	}
	// 第 4.1 节：info = "vela-sync/v2/sign" ∥ c，此 K 的 c=0 已落在 [1, n)。期望值用独立 HMAC 算，不走 derive。
	wantD := hkdf32(k, bytes.Repeat([]byte{0}, 32), append([]byte("vela-sync/v2/sign"), 0))
	if got.SignCounter != 0 || !bytes.Equal(got.D, wantD) {
		t.Fatalf("d counter=%d %x want %x", got.SignCounter, got.D, wantD)
	}
	if hex.EncodeToString(got.D) != "5f29b5ea117fdc29a695237548e0b52627c01e5cb220c0afe6ce7ec41bd7281f" {
		t.Fatalf("d known %x", got.D)
	}
	if got.Fingerprint != "E89F-AB07" {
		t.Fatalf("fp %s", got.Fingerprint)
	}
	if len(got.Pub) != 65 || got.Pub[0] != 0x04 || len(got.PubB64) != 87 {
		t.Fatalf("pub len %d %d", len(got.Pub), len(got.PubB64))
	}
	if !verifySig(got.Pub, pubcheckMessage(got.BlobID), signWith(got.D, pubcheckMessage(got.BlobID))) {
		t.Fatal("pubcheck")
	}
}

// hkdf32 是 RFC 5869 的一块形（与第 4.1 节安卓手写公式相同），用来对照生成器。
func hkdf32(ikm, salt, info []byte) []byte {
	mac := hmac.New(sha256.New, salt)
	mac.Write(ikm)
	prk := mac.Sum(nil)
	mac = hmac.New(sha256.New, prk)
	mac.Write(info)
	mac.Write([]byte{0x01})
	return mac.Sum(nil)
}

func TestBase64URLCanonical(t *testing.T) {
	raw := []byte{0x00, 0x01, 0x02}
	s := b64Encode(raw)
	if s != "AAEC" {
		t.Fatalf("enc %s", s)
	}
	back, err := b64Decode(s)
	if err != nil || !bytes.Equal(back, raw) {
		t.Fatalf("dec %v %v", back, err)
	}
	if _, err := b64Decode("AAEC="); err == nil {
		t.Fatal("padding should fail")
	}
	if _, err := b64Decode("AB"); err == nil { // 1 字节编码，末 4 个未用比特非 0
		t.Fatal("noncanonical should fail")
	}
	if _, err := b64Decode("AA E"); err == nil {
		t.Fatal("space should fail")
	}
	if _, err := b64Decode("AA+E"); err == nil {
		t.Fatal("alphabet should fail")
	}
}

func TestDecimal(t *testing.T) {
	if parseDecimal("0") != 0 || parseDecimal("9007199254740991") != 9007199254740991 {
		t.Fatal("valid")
	}
	for _, s := range []string{"", "00", "01", "+1", "-1", "1.2", "1e2", "9007199254740992"} {
		if _, ok := tryDecimal(s); ok {
			t.Fatalf("accepted %q", s)
		}
	}
}

func TestSyncCodeKnown(t *testing.T) {
	k, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	code, err := formatCode(k, "https://sync.example")
	if err != nil {
		t.Fatal(err)
	}
	want := "vela2:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8.429b0a@https://sync.example"
	if code != want {
		t.Fatalf("code %s", code)
	}
	if _, err := parseCode(code); err != nil {
		t.Fatal(err)
	}
	if _, err := parseCode(code + "/x"); err == nil {
		t.Fatal("path")
	}
}

func TestTagKeyRules(t *testing.T) {
	cases := []struct {
		raw   string
		key   string
		local bool
	}{
		{"abc", "t:abc", false},
		{"ABC", "t:abc", false},
		{"#Tag", "t:#tag", false},
		{"é", "t:é", false},
		{"e\u0301", "t:é", false},
		{"ΣΑΣ", "t:σασ", false},
		{"İ", "t:i\u0307", false},
		{"ß", "t:ß", false},
		{"ǅ", "t:ǆ", false},
	}
	for _, c := range cases {
		got := tagKey(c.raw)
		if c.local {
			if !got.LocalOnly {
				t.Fatalf("%q should be local", c.raw)
			}
			continue
		}
		if got.LocalOnly || got.Key != c.key {
			t.Fatalf("%q -> %+v want %s", c.raw, got, c.key)
		}
	}
	if !tagKey(strings.Repeat("a", 65)).LocalOnly {
		t.Fatal("65")
	}
	if tagKey(strings.Repeat("a", 64)).LocalOnly {
		t.Fatal("64")
	}
	if got := tagKey("\u1100\u1161"); got.LocalOnly || got.Key != "t:\uAC00" {
		t.Fatalf("hangul %+v", got)
	}
	if !tagKey("a b").LocalOnly || !tagKey("a\"b").LocalOnly || !tagKey("a\\b").LocalOnly {
		t.Fatal("forbidden")
	}
	if !tagKey("\U0001F600").LocalOnly {
		t.Fatal("emoji")
	}
	if !tagKey("\uE000").LocalOnly {
		t.Fatal("pua")
	}
	if !tagKey("\u061D").LocalOnly { // assigned in Unicode 14, absent from 13.0
		t.Fatal("age")
	}
	long := string(bytes.Repeat([]byte{0xC4, 0xB0}, 43)) // 43 × U+0130
	if !tagKey(long).LocalOnly {
		t.Fatal("expanded length")
	}
}

func TestHLCTicks(t *testing.T) {
	st := HLC{L: 1000, C: 5, Dev: 1}
	st = st.local(500) // rollback
	if st.L != 1000 || st.C != 6 {
		t.Fatalf("rollback %+v", st)
	}
	st = HLC{L: 1000, C: 5, Dev: 1}
	st = st.local(1000)
	if st.C != 6 || st.L != 1000 {
		t.Fatalf("same ms %+v", st)
	}
	st = HLC{L: 1000, C: 0xFFFF, Dev: 1}
	st = st.local(1000)
	if st.L != 1001 || st.C != 0 {
		t.Fatalf("overflow %+v", st)
	}
	st = HLC{L: 1000, C: 1, Dev: 1}
	st = st.local(2000)
	if st.L != 2000 || st.C != 0 {
		t.Fatalf("wall %+v", st)
	}
	if hlcValid(0, 1) || !hlcValid(1, 1) || !hlcValid(0xFFFF00000000, 1) || hlcValid(0xFFFF00000001, 1) || hlcValid(1, 0) {
		t.Fatal("bounds")
	}
}

func TestMergeAndE(t *testing.T) {
	a := emptyDoc()
	b := emptyDoc()
	putReg(a, "rooms", "cb:amy", "f", "000000000002000000000001+")
	putReg(b, "rooms", "cb:amy", "f", "000000000002000000000001-") // same HLC, '-' wins
	out := mergeDocs(a, b)
	if reg(out, "rooms", "cb:amy", "f") != "000000000002000000000001-" {
		t.Fatalf("tie %s", reg(out, "rooms", "cb:amy", "f"))
	}
	// 301 favorites, keep 300 newest; oldest two share HLC, larger key dropped.
	d := emptyDoc()
	for i := 0; i < 299; i++ {
		key := roomKey(i)
		h := wire(uint64(1000+i), 0, 1)
		putReg(d, "rooms", key, "f", h+"+")
	}
	putReg(d, "rooms", roomKey(299), "f", wire(10, 0, 1)+"+")
	putReg(d, "rooms", roomKey(300), "f", wire(10, 0, 1)+"+")
	e := pruneE(d)
	if _, ok := e.Rooms[roomKey(300)]; ok {
		t.Fatal("larger key at oldest HLC should be dropped")
	}
	if _, ok := e.Rooms[roomKey(299)]; !ok {
		t.Fatal("smaller key should be kept")
	}
	if len(favKeys(e)) != 300 {
		t.Fatalf("fav %d", len(favKeys(e)))
	}
}

func TestSizeTrimAndReject(t *testing.T) {
	// 合法数量上限下过不了 P。这里把天花板降到测试值，锁住裁墓碑与拒绝分支。
	d := emptyDoc()
	putReg(d, "rooms", "cb:amy", "f", wire(5, 0, 1)+"+")
	putReg(d, "rooms", "cb:old", "f", wire(1, 0, 1)+"-")
	putReg(d, "rooms", "cb:old", "b", wire(2, 0, 1)+"-")
	putReg(d, "veto", "t:zz", "", wire(3, 0, 1)+"-")
	res := applySize(d, 1, sizeLocal) // ceiling 1 byte forces trim then reject
	if res.Result != "reject" {
		t.Fatalf("reject path %+v", res.Result)
	}
	// ceiling large enough that deleting the oldest tombstones succeeds
	raw := canon(d)
	ceil := len(raw) - 1
	res = applySize(d, ceil, sizeLocal)
	if res.Result != "accept" || res.TombstonesRemoved < 1 {
		t.Fatalf("trim %+v removed %d", res.Result, res.TombstonesRemoved)
	}
	if _, ok := res.Doc.Rooms["cb:amy"]; !ok {
		t.Fatal("live room dropped")
	}
}

func TestPresend(t *testing.T) {
	const N = uint64(1_000_000)
	st := HLC{L: N + 3600001, C: 0, Dev: 1}
	d := emptyDoc()
	putReg(d, "rooms", "cb:amy", "f", wire(N+3600001, 0, 1)+"+")
	putReg(d, "rooms", "cb:amy", "b", wire(N+3600001, 0, 1)+"-")
	putReg(d, "rooms", "cb:bob", "f", wire(100, 0, 2)+"+")
	st2, d2, diag := presend(st, d, N, 0)
	if diag {
		t.Fatal("diag")
	}
	want := wire(N, 1, 1)
	if reg(d2, "rooms", "cb:amy", "f") != want+"+" || reg(d2, "rooms", "cb:amy", "b") != want+"-" {
		t.Fatalf("amy f=%s b=%s", reg(d2, "rooms", "cb:amy", "f"), reg(d2, "rooms", "cb:amy", "b"))
	}
	if reg(d2, "rooms", "cb:bob", "f") != wire(100, 0, 2)+"+" {
		t.Fatal("bob")
	}
	if st2.L != N || st2.C != 1 || st2.Dev != 1 {
		t.Fatalf("%+v", st2)
	}

	maxSent := N + 5_000_000
	d = emptyDoc()
	putReg(d, "rooms", "cb:old", "f", wire(N+4_000_000, 0, 1)+"+")
	putReg(d, "rooms", "cb:new", "f", wire(N+8_000_000, 0, 1)+"+")
	st = HLC{L: N + 8_000_000, C: 0, Dev: 1}
	st2, d2, diag = presend(st, d, N, maxSent)
	if !diag {
		t.Fatal("partial diag")
	}
	if reg(d2, "rooms", "cb:old", "f") != wire(N+4_000_000, 0, 1)+"+" {
		t.Fatal("sent register rewritten")
	}
	// 第 23 节第 3 条：候选集里只要有一个已发出，整组不重新盖章。
	if reg(d2, "rooms", "cb:new", "f") != wire(N+8_000_000, 0, 1)+"+" {
		t.Fatalf("new %s", reg(d2, "rooms", "cb:new", "f"))
	}
	if st2 != st {
		t.Fatalf("clock moved %+v", st2)
	}
}

func TestFavoritingAndImport(t *testing.T) {
	d := emptyDoc()
	st := HLC{Dev: 1}
	var wrote bool
	d, st, wrote = applyOp(d, st, 5000, Op{Fn: "Curation.favoriting", Name: "Alice"})
	if !wrote {
		t.Fatal("write")
	}
	h := wire(5000, 0, 1)
	if reg(d, "rooms", "cb:alice", "f") != h+"+" || reg(d, "rooms", "cb:alice", "b") != h+"-" || reg(d, "rooms", "cb:alice", "n") != h+"Alice" {
		t.Fatalf("f=%s b=%s n=%s", reg(d, "rooms", "cb:alice", "f"), reg(d, "rooms", "cb:alice", "b"), reg(d, "rooms", "cb:alice", "n"))
	}
	_, _, wrote = applyOp(d, st, 5000, Op{Fn: "Curation.favoriting", Name: "x"})
	if wrote {
		t.Fatal("short name")
	}
	d, st, wrote = applyOp(d, st, 5000, Op{Fn: "Curation.importing", Names: []string{"bob", "Amy", "amy", "nope!"}})
	if !wrote {
		t.Fatal("import")
	}
	// 窗口去重后 bob、Amy；从末尾往前，Amy 先盖章，bob 更新。
	if !strings.HasPrefix(reg(d, "rooms", "cb:bob", "f"), wire(5000, 2, 1)) && reg(d, "rooms", "cb:bob", "f")[:16] == "" {
		t.Fatal(reg(d, "rooms", "cb:bob", "f"))
	}
	bf := reg(d, "rooms", "cb:bob", "f")
	af := reg(d, "rooms", "cb:amy", "f")
	if bf <= af {
		t.Fatalf("bob should be newer bob=%s amy=%s", bf, af)
	}
	if reg(d, "rooms", "cb:amy", "n") == "" || !strings.HasSuffix(reg(d, "rooms", "cb:amy", "n"), "Amy") {
		t.Fatal(reg(d, "rooms", "cb:amy", "n"))
	}
}

func TestVectorsShapeAndStable(t *testing.T) {
	a := buildVectors()
	b := buildVectors()
	if !bytes.Equal(a, b) {
		t.Fatal("buildVectors not deterministic")
	}
	var top map[string][]map[string]any
	if err := json.Unmarshal(a, &top); err != nil {
		t.Fatal(err)
	}
	need := []string{
		"kdf", "base64url", "base64urlInvalid", "decimal", "decimalInvalid",
		"code", "codeInvalid", "json", "tagKey", "payload", "payloadInvalid",
		"envelope", "envelopeInvalid", "pubkey", "sign", "signInvalid", "pubcheck", "hlc", "merge",
		"size", "projection", "ops", "migration", "simulation",
	}
	for _, k := range need {
		if len(top[k]) == 0 {
			t.Fatalf("empty %s", k)
		}
	}
	if len(top["simulation"]) < 50 {
		t.Fatalf("sims %d", len(top["simulation"]))
	}
	hasStill, hasNear := false, false
	for _, e := range top["size"] {
		if e["name"] == "裁完仍超" {
			hasStill = true
		}
		if e["name"] == "接近上限" {
			hasNear = true
		}
	}
	if !hasStill || !hasNear {
		t.Fatalf("size names still=%v near=%v", hasStill, hasNear)
	}
	reasons := func(key string) map[string]bool {
		m := map[string]bool{}
		for _, e := range top[key] {
			if r, _ := e["reason"].(string); r != "" {
				m[r] = true
			}
		}
		return m
	}
	want := map[string][]string{
		"base64urlInvalid": {"padding", "whitespace", "alphabet", "noncanonical"},
		"decimalInvalid":   {"leading_zero", "plus_sign", "minus_sign", "fraction", "exponent", "out_of_range"},
		"payloadInvalid":   {"over_p", "truncated", "trailing", "bad_flag"},
		"envelopeInvalid":  {"tag", "aad_ver", "truncated", "prefix", "padding", "nonce_len"},
		"signInvalid":      {"sig_len_63", "sig_len_65", "r_zero", "s_zero", "r_eq_n", "s_eq_n", "sig_bitflip", "message_bitflip", "epoch", "pubkey", "pub_compressed", "pub_not_on_curve"},
		"json":             {"duplicate_key", "bom", "u_escape", "utf8", "unknown_key", "missing_key", "number_not_schema", "schema_lt", "schema_gt", "nesting", "over_p"},
	}
	for key, rs := range want {
		got := reasons(key)
		for _, r := range rs {
			if !got[r] {
				t.Fatalf("%s missing reason %s (have %v)", key, r, got)
			}
		}
	}
	// codeInvalid 覆盖第 4.3 节各条
	codeReasons := reasons("codeInvalid")
	for _, r := range []string{"prefix", "k43_length", "checksum_mismatch", "checksum_case", "scheme", "ip", "userinfo", "path", "query", "fragment", "port_443", "port_range", "host_upper", "single_label", "label_hyphen", "non_ascii"} {
		if !codeReasons[r] {
			t.Fatalf("codeInvalid missing %s", r)
		}
	}
	var readonly bool
	for _, e := range top["json"] {
		if e["reason"] == "schema_gt" {
			if e["readonly"] != true {
				t.Fatal("schema_gt should be readonly and not illegal")
			}
			readonly = true
		}
	}
	if !readonly {
		t.Fatal("schema_gt")
	}
}

// 合并样本的输入必须本身合法（第 7.3 节数量上限），否则接收方会整份拒收。
func TestMergeInputsAreLegal(t *testing.T) {
	for _, v := range buildMerge() {
		m := v.(map[string]any)
		for _, k := range []string{"a", "b"} {
			if st, reason := checkPlain(m[k].(json.RawMessage)); st != "ok" {
				t.Fatalf("%s.%s %s", m["name"], k, reason)
			}
		}
	}
}

// 迁移输入来自本机 Curation，收藏与屏蔽各不超过 300。
func TestMigrationInputsFitCuration(t *testing.T) {
	for _, v := range buildMigration() {
		m := v.(map[string]any)
		if favs, _ := m["favorites"].([]string); len(favs) > 300 {
			t.Fatalf("%s favorites %d", m["name"], len(favs))
		}
	}
}

// 第 23 节第 5 条：模拟里写给另一个副本即已发出；快钟副本离开设备前重新盖章，合并步带 now。
func TestSimulationMergeStepsCarryNow(t *testing.T) {
	sim := oneSim(20261118, false)
	for _, s := range sim["steps"].([]any) {
		st := s.(map[string]any)
		if st["kind"] == "merge" {
			if _, ok := st["now"]; !ok {
				t.Fatal("merge step without now")
			}
		}
	}
}

// 快钟副本（0，设备号 1）的操作 pt 至少是 base+7,200,000；最终文档里出现更小的设备 1 寄存器，说明发送前重新盖章确实生效过。
func TestSimulationExercisesRestamp(t *testing.T) {
	restamped := 0
	for i := 0; i < 48; i++ {
		sim := oneSim(20261118+i, false)
		var d Doc
		if err := json.Unmarshal(sim["final"].(json.RawMessage), &d); err != nil {
			t.Fatal(err)
		}
		eachReg(&d, func(l uint64, _ uint16, dev uint32, _ string) {
			if dev == 1 && l < 1_700_000_000_000+7_200_000 {
				restamped++
			}
		})
	}
	if restamped == 0 {
		t.Fatal("no restamp exercised")
	}
}

// 第 4.1 节：d 为 0 或不小于 n 时计数器加一。自然样本几乎碰不到，注入 HKDF 输出覆盖重试与耗尽。
func TestSignCounterRetry(t *testing.T) {
	n := p256Order()
	n32 := make([]byte, 32)
	n.FillBytes(n32)
	one := make([]byte, 32)
	one[31] = 1
	zero := make([]byte, 32)
	ff := bytes.Repeat([]byte{0xff}, 32)
	var seq []int
	got, err := deriveWith(rampK(), func(c int) []byte {
		seq = append(seq, c)
		switch c {
		case 0:
			return append([]byte(nil), zero...)
		case 1:
			return append([]byte(nil), n32...)
		case 2:
			return append([]byte(nil), ff...)
		default:
			return append([]byte(nil), one...)
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.SignCounter != 3 || !bytes.Equal(got.D, one) {
		t.Fatalf("counter %d d %x seq %v", got.SignCounter, got.D, seq)
	}
	wantG := "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
	if hex.EncodeToString(got.Pub) != wantG {
		t.Fatalf("d=1 pub %x", got.Pub)
	}

	nm1 := new(big.Int).Sub(n, big.NewInt(1))
	nm1b := make([]byte, 32)
	nm1.FillBytes(nm1b)
	got, err = deriveWith(rampK(), func(int) []byte { return append([]byte(nil), nm1b...) })
	if err != nil || got.SignCounter != 0 || !bytes.Equal(got.D, nm1b) {
		t.Fatalf("n-1 %+v %v", got.SignCounter, err)
	}
	if !bytes.Equal(got.Pub[1:33], mustDecodeHex(wantG)[1:33]) {
		t.Fatal("n-1 should negate G, X differs")
	}

	_, err = deriveWith(rampK(), func(int) []byte { return append([]byte(nil), zero...) })
	if err == nil {
		t.Fatal("256 次失败应中止")
	}
}

func TestPubkeyEdges(t *testing.T) {
	seen := map[string]string{}
	for _, v := range buildPubkey() {
		m := v.(map[string]any)
		dHex := m["d_hex"].(string)
		pub, err := b64Decode(m["pub_b64url"].(string))
		if err != nil || len(pub) != 65 || pub[0] != 0x04 {
			t.Fatalf("%s pub", dHex)
		}
		d32, err := hex.DecodeString(dHex)
		if err != nil || len(d32) != 32 {
			t.Fatalf("d %s", dHex)
		}
		if !bytes.Equal(pub, pubFromScalar(d32)) {
			t.Fatalf("recompute %s", dHex)
		}
		seen[dHex] = hex.EncodeToString(pub)
	}
	one := strings.Repeat("00", 31) + "01"
	nm1 := "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550"
	if seen[one] != "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5" {
		t.Fatalf("G %s", seen[one])
	}
	if _, ok := seen[nm1]; !ok {
		t.Fatal("missing d=n-1")
	}
}

func TestSignSamplesVerify(t *testing.T) {
	resetSignEntropy()
	a := buildSign()
	resetSignEntropy()
	b := buildSign()
	if len(a) < 1 || !bytes.Equal(mustMarshal(a), mustMarshal(b)) {
		t.Fatal("sign not deterministic")
	}
	for _, v := range a {
		m := v.(map[string]any)
		if m["valid"] != true {
			t.Fatal("valid")
		}
		pub, _ := b64Decode(m["pub_b64url"].(string))
		msg, _ := hex.DecodeString(m["message_hex"].(string))
		sig, _ := b64Decode(m["sig_b64url"].(string))
		if len(sig) != 64 || !verifySig(pub, msg, sig) {
			t.Fatal("expected verify")
		}
	}
	resetSignEntropy()
	for _, v := range buildSignInvalid() {
		m := v.(map[string]any)
		if m["valid"] != false {
			t.Fatal("valid flag")
		}
		pub, err := b64Decode(m["pub_b64url"].(string))
		msg, _ := hex.DecodeString(m["message_hex"].(string))
		sig, sigErr := b64Decode(m["sig_b64url"].(string))
		if err != nil || sigErr != nil {
			t.Fatal(err, sigErr)
		}
		if verifySig(pub, msg, sig) {
			t.Fatalf("should fail %s", m["reason"])
		}
	}
}

func mustDecodeHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func mustMarshal(v any) []byte { return marshal(v) }
