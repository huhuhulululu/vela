package main

import (
	"bufio"
	"bytes"
	_ "embed"
	"strconv"
	"strings"
	"sync"
)

//go:embed data/UnicodeData.txt
var fileUnicodeData []byte

//go:embed data/SpecialCasing.txt
var fileSpecialCasing []byte

//go:embed data/DerivedAge.txt
var fileDerivedAge []byte

//go:embed data/PropList.txt
var filePropList []byte

//go:embed data/DerivedNormalizationProps.txt
var fileNormProps []byte

// Unicode 13.0，只覆盖 BMP。小写用无条件的完整映射（SpecialCasing），不用 Final_Sigma 等上下文规则。
var (
	uniOnce  sync.Once
	cccTab   [65536]uint8
	ageOKTab [65536]bool
	whiteTab [65536]bool
	lowerMap map[rune][]rune
	decomp   map[rune][]rune
	comp     map[[2]rune]rune
)

func ensureUnicode() {
	uniOnce.Do(loadUnicode)
}

func loadUnicode() {
	lowerMap = map[rune][]rune{}
	decomp = map[rune][]rune{}
	excl := map[rune]bool{}
	simple := map[rune]rune{}

	scanLines(fileNormProps, func(fields []string) {
		if len(fields) < 2 || strings.TrimSpace(fields[1]) != "Full_Composition_Exclusion" {
			return
		}
		for _, cp := range expandRange(fields[0]) {
			if cp <= 0xFFFF {
				excl[rune(cp)] = true
			}
		}
	})
	scanLines(filePropList, func(fields []string) {
		if len(fields) < 2 || strings.TrimSpace(fields[1]) != "White_Space" {
			return
		}
		for _, cp := range expandRange(fields[0]) {
			if cp <= 0xFFFF {
				whiteTab[cp] = true
			}
		}
	})
	scanLines(fileDerivedAge, func(fields []string) {
		if len(fields) < 2 {
			return
		}
		if !ageAtMost13(strings.TrimSpace(fields[1])) {
			return
		}
		for _, cp := range expandRange(fields[0]) {
			if cp <= 0xFFFF {
				ageOKTab[cp] = true
			}
		}
	})
	scanLines(fileUnicodeData, func(fields []string) {
		if len(fields) < 6 {
			return
		}
		cp, err := strconv.ParseUint(strings.TrimSpace(fields[0]), 16, 32)
		if err != nil || cp > 0xFFFF {
			return
		}
		if ccc, err := strconv.Atoi(strings.TrimSpace(fields[3])); err == nil && ccc > 0 {
			cccTab[cp] = uint8(ccc)
		}
		dec := strings.TrimSpace(fields[5])
		if dec != "" && !strings.HasPrefix(dec, "<") {
			parts := strings.Fields(dec)
			seq := make([]rune, 0, len(parts))
			good := true
			for _, p := range parts {
				v, err := strconv.ParseUint(p, 16, 32)
				if err != nil {
					good = false
					break
				}
				seq = append(seq, rune(v))
			}
			if good && len(seq) > 0 {
				decomp[rune(cp)] = seq
			}
		}
		if len(fields) > 13 {
			if low := strings.TrimSpace(fields[13]); low != "" {
				v, err := strconv.ParseUint(low, 16, 32)
				if err == nil {
					simple[rune(cp)] = rune(v)
				}
			}
		}
	})
	scanLines(fileSpecialCasing, func(fields []string) {
		if len(fields) < 4 {
			return
		}
		cond := ""
		if len(fields) >= 5 {
			cond = strings.TrimSpace(fields[4])
		}
		if cond != "" {
			return // 上下文或语言条件，不用
		}
		cp, err := strconv.ParseUint(strings.TrimSpace(fields[0]), 16, 32)
		if err != nil || cp > 0xFFFF {
			return
		}
		seq := parseHexSeq(fields[1])
		if len(seq) == 0 {
			return
		}
		lowerMap[rune(cp)] = seq
	})
	for cp, low := range simple {
		if _, ok := lowerMap[cp]; ok {
			continue
		}
		if low != cp {
			lowerMap[cp] = []rune{low}
		}
	}
	comp = map[[2]rune]rune{}
	for cp, seq := range decomp {
		if excl[cp] || len(seq) != 2 {
			continue
		}
		if cccOf(seq[0]) != 0 {
			continue
		}
		comp[[2]rune{seq[0], seq[1]}] = cp
	}
}

func scanLines(data []byte, fn func([]string)) {
	s := bufio.NewScanner(bytes.NewReader(data))
	s.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for s.Scan() {
		line := s.Text()
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = line[:i]
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		raw := strings.Split(line, ";")
		fn(raw)
	}
	if err := s.Err(); err != nil {
		panic(err)
	}
}

func expandRange(s string) []int {
	s = strings.TrimSpace(s)
	if i := strings.Index(s, ".."); i >= 0 {
		a, err1 := strconv.ParseUint(s[:i], 16, 32)
		b, err2 := strconv.ParseUint(s[i+2:], 16, 32)
		if err1 != nil || err2 != nil || b < a || b-a > 1<<20 {
			return nil
		}
		out := make([]int, 0, int(b-a+1))
		for c := a; c <= b; c++ {
			out = append(out, int(c))
		}
		return out
	}
	v, err := strconv.ParseUint(s, 16, 32)
	if err != nil {
		return nil
	}
	return []int{int(v)}
}

func ageAtMost13(v string) bool {
	parts := strings.Split(v, ".")
	if len(parts) == 0 {
		return false
	}
	maj, err := strconv.Atoi(parts[0])
	if err != nil {
		return false
	}
	min := 0
	if len(parts) > 1 {
		min, err = strconv.Atoi(parts[1])
		if err != nil {
			return false
		}
	}
	if maj < 13 {
		return true
	}
	return maj == 13 && min == 0
}

func parseHexSeq(s string) []rune {
	parts := strings.Fields(strings.TrimSpace(s))
	out := make([]rune, 0, len(parts))
	for _, p := range parts {
		v, err := strconv.ParseUint(p, 16, 32)
		if err != nil {
			return nil
		}
		out = append(out, rune(v))
	}
	return out
}

func cccOf(r rune) uint8 {
	if r < 0 || r > 0xFFFF {
		return 0
	}
	return cccTab[r]
}

func lowerOf(r rune) []rune {
	if s, ok := lowerMap[r]; ok {
		return s
	}
	return []rune{r}
}

func isNonChar(r rune) bool {
	if r >= 0xFDD0 && r <= 0xFDEF {
		return true
	}
	return r&0xFFFE == 0xFFFE
}

func isCc(r rune) bool {
	return r <= 0x1F || (r >= 0x7F && r <= 0x9F)
}

const (
	hangulSBase  = 0xAC00
	hangulLBase  = 0x1100
	hangulVBase  = 0x1161
	hangulTBase  = 0x11A7
	hangulLCount = 19
	hangulVCount = 21
	hangulTCount = 28
	hangulNCount = hangulVCount * hangulTCount
	hangulSCount = hangulLCount * hangulNCount
)

func isHangulS(r rune) bool {
	return r >= hangulSBase && r < hangulSBase+hangulSCount
}

func decompOne(r rune) []rune {
	if isHangulS(r) {
		s := int(r - hangulSBase)
		t := s % hangulTCount
		s /= hangulTCount
		l := rune(hangulLBase + s/hangulVCount)
		v := rune(hangulVBase + s%hangulVCount)
		if t == 0 {
			return []rune{l, v}
		}
		return []rune{l, v, rune(hangulTBase + t)}
	}
	if d, ok := decomp[r]; ok {
		var out []rune
		for _, p := range d {
			out = append(out, decompOne(p)...)
		}
		return out
	}
	return []rune{r}
}

func composeHangul(a, b rune) (rune, bool) {
	if a >= hangulLBase && a < hangulLBase+hangulLCount && b >= hangulVBase && b < hangulVBase+hangulVCount {
		li := int(a - hangulLBase)
		vi := int(b - hangulVBase)
		return rune(hangulSBase + (li*hangulVCount+vi)*hangulTCount), true
	}
	if isHangulS(a) && b > hangulTBase && b < hangulTBase+hangulTCount {
		if int(a-hangulSBase)%hangulTCount == 0 {
			return a + (b - hangulTBase), true
		}
	}
	return 0, false
}

func tryCompose(a, b rune) (rune, bool) {
	if c, ok := composeHangul(a, b); ok {
		return c, true
	}
	c, ok := comp[[2]rune{a, b}]
	return c, ok
}

func nfcString(s string) string {
	ensureUnicode()
	var nfd []rune
	for _, r := range s {
		nfd = append(nfd, decompOne(r)...)
	}
	for i := 1; i < len(nfd); i++ {
		for j := i; j > 0; j-- {
			cj, cj1 := cccOf(nfd[j]), cccOf(nfd[j-1])
			if cj == 0 || cj1 == 0 || cj1 <= cj {
				break
			}
			nfd[j], nfd[j-1] = nfd[j-1], nfd[j]
		}
	}
	if len(nfd) == 0 {
		return ""
	}
	out := []rune{nfd[0]}
	lastCCC := cccOf(nfd[0])
	starter := 0
	if lastCCC != 0 {
		starter = -1
	}
	for _, r := range nfd[1:] {
		cc := cccOf(r)
		composed := false
		if starter >= 0 && (lastCCC < cc || lastCCC == 0) {
			if cp, ok := tryCompose(out[starter], r); ok {
				out[starter] = cp
				composed = true
			}
		}
		if !composed {
			out = append(out, r)
			if cc == 0 {
				starter = len(out) - 1
			}
			lastCCC = cc
		}
	}
	return string(out)
}

func lowerString(s string) string {
	ensureUnicode()
	var b []rune
	for _, r := range s {
		b = append(b, lowerOf(r)...)
	}
	return string(b)
}

func trimWS(s string) string {
	ensureUnicode()
	rs := []rune(s)
	i, j := 0, len(rs)
	for i < j && isWhiteRune(rs[i]) {
		i++
	}
	for j > i && isWhiteRune(rs[j-1]) {
		j--
	}
	return string(rs[i:j])
}

func isWhiteRune(r rune) bool {
	if r < 0 || r > 0xFFFF {
		return false
	}
	return whiteTab[r]
}

type TagResult struct {
	Key       string
	LocalOnly bool
	Reason    string
}

func tagKey(raw string) TagResult { return tagKeyRunes([]rune(raw)) }

func tagKeyRunes(rs []rune) TagResult {
	ensureUnicode()
	local := func(reason string) TagResult { return TagResult{LocalOnly: true, Reason: reason} }
	for _, r := range rs {
		if reason := scalarReason(r); reason != "" {
			return local(reason)
		}
	}
	if len(rs) < 1 || len(rs) > 64 {
		return local("codepoint_count")
	}
	var low []rune
	for _, r := range rs {
		mapped := lowerOf(r)
		for _, m := range mapped {
			if reason := scalarReason(m); reason != "" {
				return local("lower_" + reason)
			}
			low = append(low, m)
		}
	}
	keyed := nfcString("t:" + string(low))
	if !strings.HasPrefix(keyed, "t:") {
		return local("nfc")
	}
	tag := keyed[len("t:"):]
	if len([]byte(tag)) > 128 {
		return local("too_long")
	}
	return TagResult{Key: keyed}
}

func scalarReason(r rune) string {
	if r > 0xFFFF {
		return "non_bmp"
	}
	if r >= 0xD800 && r <= 0xDFFF {
		return "surrogate"
	}
	if r >= 0xE000 && r <= 0xF8FF {
		return "private_use"
	}
	if isNonChar(r) {
		return "noncharacter"
	}
	if !ageOKTab[r] {
		return "age"
	}
	if whiteTab[r] {
		return "whitespace"
	}
	if isCc(r) {
		return "control"
	}
	if r == '"' {
		return "quote"
	}
	if r == '\\' {
		return "backslash"
	}
	return ""
}

func receiveTagOK(tag string) bool {
	ensureUnicode()
	if tag == "" || len([]byte(tag)) > 128 {
		return false
	}
	if nfcString(tag) != tag {
		return false
	}
	for _, r := range tag {
		if scalarReason(r) != "" {
			return false
		}
	}
	low := nfcString(lowerString(tag))
	return low == tag
}

func vetoDomain(key string) bool {
	if len(key) == 3 && key[0] == 'g' && key[1] == ':' && strings.ContainsRune("fmtc", rune(key[2])) {
		return true
	}
	if !strings.HasPrefix(key, "t:") {
		return false
	}
	return receiveTagOK(key[2:])
}
