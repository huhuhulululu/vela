package main

import (
	"bytes"
	"unicode/utf8"
)

type jsVal struct {
	kind string
	str  string
	num  int64
	obj  map[string]jsVal
}

type parser struct {
	b   []byte
	i   int
	err string
}

func (p *parser) peek() byte {
	if p.i >= len(p.b) {
		return 0
	}
	return p.b[p.i]
}

func (p *parser) skipWS() {
	for p.i < len(p.b) {
		switch p.b[p.i] {
		case ' ', '\t', '\n', '\r':
			p.i++
		default:
			return
		}
	}
}

func checkPlain(b []byte) (status, reason string) {
	if bytes.HasPrefix(b, []byte{0xEF, 0xBB, 0xBF}) {
		return "invalid", "bom"
	}
	if !utf8.Valid(b) {
		return "invalid", "utf8"
	}
	if len(b) > plainMax {
		return "invalid", "over_p"
	}
	p := &parser{b: b, err: "syntax"}
	v, ok := p.parseValue(1, false)
	if !ok {
		return "invalid", p.err
	}
	p.skipWS()
	if p.i != len(p.b) {
		return "invalid", "trailing"
	}
	return validateValue(v)
}

func (p *parser) parseValue(depth int, schemaPos bool) (jsVal, bool) {
	p.skipWS()
	if p.i >= len(p.b) {
		p.err = "syntax"
		return jsVal{}, false
	}
	switch c := p.b[p.i]; c {
	case '{':
		return p.parseObject(depth)
	case '[':
		p.err = "array"
		return jsVal{}, false
	case '"':
		s, ok := p.parseString()
		return jsVal{kind: "str", str: s}, ok
	case 't', 'f', 'n':
		p.err = "literal"
		return jsVal{}, false
	default:
		if c == '-' || (c >= '0' && c <= '9') {
			n, ok := p.parseNumber(schemaPos)
			return jsVal{kind: "num", num: n}, ok
		}
		p.err = "syntax"
		return jsVal{}, false
	}
}

func (p *parser) parseObject(depth int) (jsVal, bool) {
	if depth > 3 {
		p.err = "nesting"
		return jsVal{}, false
	}
	if p.peek() != '{' {
		p.err = "syntax"
		return jsVal{}, false
	}
	p.i++
	obj := map[string]jsVal{}
	p.skipWS()
	if p.peek() == '}' {
		p.i++
		return jsVal{kind: "obj", obj: obj}, true
	}
	for {
		p.skipWS()
		key, ok := p.parseString()
		if !ok {
			return jsVal{}, false
		}
		if _, exists := obj[key]; exists {
			p.err = "duplicate_key"
			return jsVal{}, false
		}
		p.skipWS()
		if p.peek() != ':' {
			p.err = "syntax"
			return jsVal{}, false
		}
		p.i++
		val, ok := p.parseValue(depth+1, depth == 1 && key == "schema")
		if !ok {
			return jsVal{}, false
		}
		obj[key] = val
		p.skipWS()
		switch p.peek() {
		case ',':
			p.i++
			continue
		case '}':
			p.i++
			return jsVal{kind: "obj", obj: obj}, true
		default:
			p.err = "syntax"
			return jsVal{}, false
		}
	}
}

func (p *parser) parseString() (string, bool) {
	if p.peek() != '"' {
		p.err = "syntax"
		return "", false
	}
	p.i++
	var buf []byte
	for p.i < len(p.b) {
		c := p.b[p.i]
		if c == '"' {
			p.i++
			return string(buf), true
		}
		if c == '\\' {
			if p.i+1 < len(p.b) && p.b[p.i+1] == 'u' {
				p.err = "u_escape"
			} else {
				p.err = "escape"
			}
			return "", false
		}
		if c < 0x20 {
			p.err = "control"
			return "", false
		}
		buf = append(buf, c)
		p.i++
	}
	p.err = "syntax"
	return "", false
}

func (p *parser) parseNumber(schemaPos bool) (int64, bool) {
	start := p.i
	if p.peek() == '-' {
		p.i++
	}
	for p.i < len(p.b) {
		c := p.peek()
		if (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' {
			p.i++
			continue
		}
		break
	}
	lit := string(p.b[start:p.i])
	if !schemaPos {
		p.err = "number_not_schema"
		return 0, false
	}
	n, ok := tryDecimal(lit)
	if !ok || len(lit) == 0 {
		p.err = "schema_number"
		return 0, false
	}
	return int64(n), true
}

func validateValue(v jsVal) (string, string) {
	if v.kind != "obj" {
		return "invalid", "syntax"
	}
	if sch, ok := v.obj["schema"]; ok && sch.kind == "num" && sch.num > 2 {
		return "readonly", "schema_gt"
	}
	for _, k := range []string{"schema", "rooms", "settings", "veto"} {
		if _, ok := v.obj[k]; !ok {
			return "invalid", "missing_key"
		}
	}
	if len(v.obj) != 4 {
		return "invalid", "unknown_key"
	}
	sch := v.obj["schema"]
	if sch.kind != "num" || sch.num != 2 {
		return "invalid", "schema_lt"
	}
	rooms, ok := v.obj["rooms"]
	if !ok || rooms.kind != "obj" {
		return "invalid", "bad_type"
	}
	veto, ok := v.obj["veto"]
	if !ok || veto.kind != "obj" {
		return "invalid", "bad_type"
	}
	settings, ok := v.obj["settings"]
	if !ok || settings.kind != "obj" {
		return "invalid", "bad_type"
	}
	fav, blk, tomb := 0, 0, 0
	for key, raw := range rooms.obj {
		if !matchRoomKey(key) {
			return "invalid", "bad_room_key"
		}
		if raw.kind != "obj" || len(raw.obj) == 0 {
			return "invalid", "bad_room"
		}
		room := Room{}
		for fk, fv := range raw.obj {
			if fv.kind != "str" {
				return "invalid", "bad_type"
			}
			switch fk {
			case "f":
				if r := checkPM(fv.str); r != "" {
					return "invalid", r
				}
				room.F = fv.str
			case "b":
				if r := checkPM(fv.str); r != "" {
					return "invalid", r
				}
				room.B = fv.str
			case "n":
				if r := checkN(key, fv.str); r != "" {
					return "invalid", r
				}
				room.N = fv.str
			default:
				return "invalid", "unknown_key"
			}
		}
		switch classify(room) {
		case "fav":
			fav++
		case "block":
			blk++
		case "tomb":
			tomb++
		}
	}
	if fav > capFav || blk > capBlock || tomb > capTomb {
		return "invalid", "over_count"
	}
	plus, minus := 0, 0
	for key, raw := range veto.obj {
		if raw.kind != "str" {
			return "invalid", "bad_type"
		}
		if !vetoDomain(key) {
			return "invalid", "bad_key"
		}
		if r := checkPM(raw.str); r != "" {
			return "invalid", r
		}
		if payloadOf(raw.str) == "+" {
			plus++
		} else {
			minus++
		}
	}
	if plus > capVeto || minus > capVetoT {
		return "invalid", "over_count"
	}
	for key, raw := range settings.obj {
		if key != "publicOnly" && key != "infoAlwaysOn" {
			return "invalid", "unknown_key"
		}
		if raw.kind != "str" {
			return "invalid", "bad_type"
		}
		if r := checkBit(raw.str); r != "" {
			return "invalid", r
		}
	}
	return "ok", ""
}

func matchRoomKey(s string) bool {
	i := stringsIndex(s)
	if i < 2 || i > 8 || i+1 >= len(s) {
		return false
	}
	site, user := s[:i], s[i+1:]
	if site[0] < 'a' || site[0] > 'z' {
		return false
	}
	for _, c := range site[1:] {
		if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
			return false
		}
	}
	return matchUser(user)
}

func stringsIndex(s string) int {
	for i := 0; i < len(s); i++ {
		if s[i] == ':' {
			return i
		}
	}
	return -1
}

func checkPM(s string) string {
	l, _, dev, payload, ok := parseWire(s)
	if !ok || !hlcValid(l, dev) {
		return "bad_hlc"
	}
	if payload != "+" && payload != "-" {
		return "bad_payload"
	}
	return ""
}

func checkBit(s string) string {
	l, _, dev, payload, ok := parseWire(s)
	if !ok || !hlcValid(l, dev) {
		return "bad_hlc"
	}
	if payload != "0" && payload != "1" {
		return "bad_payload"
	}
	return ""
}

func checkN(room, s string) string {
	l, _, dev, payload, ok := parseWire(s)
	if !ok || !hlcValid(l, dev) {
		return "bad_hlc"
	}
	if !matchN(payload) || lowerString(payload) != usernameOf(room) {
		return "bad_payload"
	}
	return ""
}
