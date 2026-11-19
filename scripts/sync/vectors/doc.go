package main

import (
	"bytes"
	"sort"
	"strings"
)

const (
	capFav   = 300
	capBlock = 300
	capTomb  = 1000
	capVeto  = 450
	capVetoT = 500
	plainMax = 600_000
	sentinel = "000000000000000000000000-"
)

const (
	sizeLocal = "local"
	sizeMerge = "merge"
)

type Room struct {
	F, B, N string
}

type Doc struct {
	Rooms    map[string]Room
	Veto     map[string]string
	Settings map[string]string
}

func emptyDoc() *Doc {
	return &Doc{
		Rooms:    map[string]Room{},
		Veto:     map[string]string{},
		Settings: map[string]string{},
	}
}

func clone(d *Doc) *Doc {
	if d == nil {
		return emptyDoc()
	}
	out := emptyDoc()
	for k, r := range d.Rooms {
		out.Rooms[k] = r
	}
	for k, v := range d.Veto {
		out.Veto[k] = v
	}
	for k, v := range d.Settings {
		out.Settings[k] = v
	}
	return out
}

func putReg(d *Doc, section, key, field, value string) {
	switch section {
	case "rooms":
		r := d.Rooms[key]
		switch field {
		case "f":
			r.F = value
		case "b":
			r.B = value
		case "n":
			r.N = value
		default:
			panic("field " + field)
		}
		d.Rooms[key] = r
	case "veto":
		d.Veto[key] = value
	case "settings":
		d.Settings[key] = value
	default:
		panic("section " + section)
	}
}

func reg(d *Doc, section, key, field string) string {
	switch section {
	case "rooms":
		r := d.Rooms[key]
		switch field {
		case "f":
			return r.F
		case "b":
			return r.B
		case "n":
			return r.N
		}
	case "veto":
		return d.Veto[key]
	case "settings":
		return d.Settings[key]
	}
	return ""
}

func roomKey(i int) string { return sprintfRoom(i) }

func sprintfRoom(i int) string {
	return "cb:u" + pad4(i)
}

func pad4(i int) string {
	s := ""
	for n := 0; n < 4; n++ {
		s = string(rune('0'+i%10)) + s
		i /= 10
	}
	return s
}

func payloadOf(s string) string {
	if len(s) < 24 {
		return ""
	}
	return s[24:]
}

func hlcOf(s string) string {
	if len(s) < 24 {
		return ""
	}
	return s[:24]
}

func classify(r Room) string {
	if r.F == "" && r.B == "" && r.N == "" {
		return "absent"
	}
	fPlus := payloadOf(r.F) == "+"
	bPlus := payloadOf(r.B) == "+"
	fH, bH := hlcOf(r.F), hlcOf(r.B)
	if fPlus && (!bPlus || fH > bH) {
		return "fav"
	}
	if bPlus && (!fPlus || bH >= fH) {
		return "block"
	}
	return "tomb"
}

func maxHLC(r Room) string {
	m := ""
	for _, s := range []string{r.F, r.B, r.N} {
		h := hlcOf(s)
		if h > m {
			m = h
		}
	}
	return m
}

func favKeys(d *Doc) []string {
	var ks []string
	for k, r := range d.Rooms {
		if classify(r) == "fav" {
			ks = append(ks, k)
		}
	}
	sort.Strings(ks)
	return ks
}

func countKind(d *Doc, kind string) int {
	n := 0
	for _, r := range d.Rooms {
		if classify(r) == kind {
			n++
		}
	}
	return n
}

type ranked struct {
	key, hlc string
	room     bool
}

func pruneE(d *Doc) *Doc {
	out := clone(d)
	dropRooms := func(kind string, capN int, hlcFn func(Room) string) {
		var items []ranked
		for k, r := range out.Rooms {
			if classify(r) == kind {
				items = append(items, ranked{key: k, hlc: hlcFn(r)})
			}
		}
		sort.Slice(items, func(i, j int) bool {
			if items[i].hlc != items[j].hlc {
				return items[i].hlc > items[j].hlc
			}
			return items[i].key < items[j].key
		})
		for i := capN; i < len(items); i++ {
			delete(out.Rooms, items[i].key)
		}
	}
	dropRooms("fav", capFav, func(r Room) string { return hlcOf(r.F) })
	dropRooms("block", capBlock, func(r Room) string { return hlcOf(r.B) })
	dropRooms("tomb", capTomb, maxHLC)
	var plus, minus []ranked
	for k, v := range out.Veto {
		item := ranked{key: k, hlc: hlcOf(v)}
		if payloadOf(v) == "+" {
			plus = append(plus, item)
		} else if payloadOf(v) == "-" {
			minus = append(minus, item)
		}
	}
	sortRankDesc(plus)
	sortRankDesc(minus)
	for i := capVeto; i < len(plus); i++ {
		delete(out.Veto, plus[i].key)
	}
	for i := capVetoT; i < len(minus); i++ {
		delete(out.Veto, minus[i].key)
	}
	return out
}

func sortRankDesc(items []ranked) {
	sort.Slice(items, func(i, j int) bool {
		if items[i].hlc != items[j].hlc {
			return items[i].hlc > items[j].hlc
		}
		return items[i].key < items[j].key
	})
}

func cmpReg(a, b string) int {
	if a == "" {
		a = sentinel
	}
	if b == "" {
		b = sentinel
	}
	if a == b {
		return 0
	}
	if a > b {
		return 1
	}
	return -1
}

func pick(a, b string) string {
	if cmpReg(a, b) >= 0 {
		return a
	}
	return b
}

func union(a, b *Doc) *Doc {
	out := emptyDoc()
	keys := map[string]struct{}{}
	for k := range a.Rooms {
		keys[k] = struct{}{}
	}
	for k := range b.Rooms {
		keys[k] = struct{}{}
	}
	for k := range keys {
		ra, rb := a.Rooms[k], b.Rooms[k]
		var r Room
		r.F = pick(ra.F, rb.F)
		r.B = pick(ra.B, rb.B)
		r.N = pick(ra.N, rb.N)
		if r.F != "" || r.B != "" || r.N != "" {
			out.Rooms[k] = r
		}
	}
	vk := map[string]struct{}{}
	for k := range a.Veto {
		vk[k] = struct{}{}
	}
	for k := range b.Veto {
		vk[k] = struct{}{}
	}
	for k := range vk {
		v := pick(a.Veto[k], b.Veto[k])
		if v != "" {
			out.Veto[k] = v
		}
	}
	sk := map[string]struct{}{}
	for k := range a.Settings {
		sk[k] = struct{}{}
	}
	for k := range b.Settings {
		sk[k] = struct{}{}
	}
	for k := range sk {
		v := pick(a.Settings[k], b.Settings[k])
		if v != "" {
			out.Settings[k] = v
		}
	}
	return out
}

func mergeDocs(a, b *Doc) *Doc { return pruneE(union(a, b)) }

type sizeResult struct {
	Result            string
	TombstonesRemoved int
	Doc               *Doc
	Bytes             int
}

func oldestTombstone(d *Doc) (key string, isRoom bool, ok bool) {
	var items []ranked
	for k, r := range d.Rooms {
		if classify(r) == "tomb" {
			items = append(items, ranked{key: k, hlc: maxHLC(r), room: true})
		}
	}
	for k, v := range d.Veto {
		if payloadOf(v) == "-" {
			items = append(items, ranked{key: k, hlc: hlcOf(v), room: false})
		}
	}
	if len(items) == 0 {
		return "", false, false
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].hlc != items[j].hlc {
			return items[i].hlc < items[j].hlc
		}
		if items[i].room != items[j].room {
			return items[i].room
		}
		return items[i].key < items[j].key
	})
	return items[0].key, items[0].room, true
}

// applySize 先执行 E，再按第 6.7 节裁最旧墓碑。ceiling 在金样本里是 P；测试可降下来锁住分支。
func applySize(d *Doc, ceiling int, cause string) sizeResult {
	cur := pruneE(d)
	b := canon(cur)
	if len(b) <= ceiling {
		return sizeResult{Result: "accept", Doc: cur, Bytes: len(b)}
	}
	removed := 0
	for len(b) > ceiling {
		k, isRoom, ok := oldestTombstone(cur)
		if !ok {
			break
		}
		if isRoom {
			delete(cur.Rooms, k)
		} else {
			delete(cur.Veto, k)
		}
		removed++
		b = canon(cur)
	}
	if len(b) > ceiling {
		if cause == sizeLocal {
			return sizeResult{Result: "reject", TombstonesRemoved: removed, Doc: clone(d), Bytes: len(canon(d))}
		}
		return sizeResult{Result: "oversize", TombstonesRemoved: removed, Doc: cur, Bytes: len(b)}
	}
	return sizeResult{Result: "accept", Doc: cur, TombstonesRemoved: removed, Bytes: len(b)}
}

func canon(d *Doc) []byte {
	var buf bytes.Buffer
	buf.WriteString(`{"rooms":`)
	writeRooms(&buf, d.Rooms)
	buf.WriteString(`,"schema":2,"settings":`)
	writeMap(&buf, d.Settings)
	buf.WriteString(`,"veto":`)
	writeMap(&buf, d.Veto)
	buf.WriteByte('}')
	return buf.Bytes()
}

func writeRooms(buf *bytes.Buffer, m map[string]Room) {
	keys := make([]string, 0, len(m))
	for k, r := range m {
		if r.F == "" && r.B == "" && r.N == "" {
			continue
		}
		keys = append(keys, k)
	}
	sort.Strings(keys)
	buf.WriteByte('{')
	for i, k := range keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		writeString(buf, k)
		buf.WriteByte(':')
		r := m[k]
		buf.WriteByte('{')
		first := true
		writeField := func(name, val string) {
			if val == "" {
				return
			}
			if !first {
				buf.WriteByte(',')
			}
			first = false
			writeString(buf, name)
			buf.WriteByte(':')
			writeString(buf, val)
		}
		writeField("b", r.B)
		writeField("f", r.F)
		writeField("n", r.N)
		buf.WriteByte('}')
	}
	buf.WriteByte('}')
}

func writeMap(buf *bytes.Buffer, m map[string]string) {
	keys := make([]string, 0, len(m))
	for k, v := range m {
		if v == "" {
			continue
		}
		keys = append(keys, k)
	}
	sort.Strings(keys)
	buf.WriteByte('{')
	for i, k := range keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		writeString(buf, k)
		buf.WriteByte(':')
		writeString(buf, m[k])
	}
	buf.WriteByte('}')
}

func writeString(buf *bytes.Buffer, s string) {
	buf.WriteByte('"')
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c == '"' || c == '\\' || c < 0x20 {
			panic("canon: needs escape")
		}
	}
	buf.WriteString(s)
	buf.WriteByte('"')
}

func eachReg(doc *Doc, fn func(l uint64, c uint16, dev uint32, payload string)) {
	eachRegLoc(doc, func(_, _, _, reg string) {
		l, c, dev, payload, ok := parseWire(reg)
		if ok {
			fn(l, c, dev, payload)
		}
	})
}

func eachRegLoc(doc *Doc, fn func(sec, key, field, reg string)) {
	if doc == nil {
		return
	}
	rk := make([]string, 0, len(doc.Rooms))
	for k := range doc.Rooms {
		rk = append(rk, k)
	}
	sort.Strings(rk)
	for _, k := range rk {
		r := doc.Rooms[k]
		if r.B != "" {
			fn("rooms", k, "b", r.B)
		}
		if r.F != "" {
			fn("rooms", k, "f", r.F)
		}
		if r.N != "" {
			fn("rooms", k, "n", r.N)
		}
	}
	for _, pair := range []struct {
		sec string
		m   map[string]string
	}{{"veto", doc.Veto}, {"settings", doc.Settings}} {
		ks := make([]string, 0, len(pair.m))
		for k := range pair.m {
			ks = append(ks, k)
		}
		sort.Strings(ks)
		for _, k := range ks {
			if pair.m[k] != "" {
				fn(pair.sec, k, "", pair.m[k])
			}
		}
	}
}

func projectRoom(r Room) string { return classify(r) }

type projItem struct {
	Room  string `json:"room"`
	Name  string `json:"name"`
	Local bool   `json:"local"`
}

type projection struct {
	Favorites []projItem        `json:"favorites"`
	Blocked   []projItem        `json:"blocked"`
	Vetoed    []string          `json:"vetoed"`
	Settings  map[string]string `json:"settings"`
}

func projectDoc(d *Doc) projection {
	var fav, blk []ranked
	for k, r := range d.Rooms {
		switch classify(r) {
		case "fav":
			fav = append(fav, ranked{key: k, hlc: hlcOf(r.F)})
		case "block":
			blk = append(blk, ranked{key: k, hlc: hlcOf(r.B)})
		}
	}
	sortRankDesc(fav)
	sortRankDesc(blk)
	toItems := func(xs []ranked) []projItem {
		out := make([]projItem, 0, len(xs))
		for _, it := range xs {
			r := d.Rooms[it.key]
			name := usernameOf(it.key)
			if r.N != "" {
				name = payloadOf(r.N)
			}
			out = append(out, projItem{Room: it.key, Name: name, Local: strings.HasPrefix(it.key, "cb:")})
		}
		return out
	}
	var veto []ranked
	for k, v := range d.Veto {
		if payloadOf(v) == "+" {
			veto = append(veto, ranked{key: k, hlc: hlcOf(v)})
		}
	}
	sortRankDesc(veto)
	vk := make([]string, 0, len(veto))
	for _, it := range veto {
		vk = append(vk, it.key)
	}
	settings := map[string]string{}
	for k, v := range d.Settings {
		if v != "" {
			settings[k] = payloadOf(v)
		}
	}
	if len(settings) == 0 {
		settings = map[string]string{}
	}
	return projection{Favorites: toItems(fav), Blocked: toItems(blk), Vetoed: vk, Settings: settings}
}

func usernameOf(room string) string {
	i := strings.IndexByte(room, ':')
	if i < 0 {
		return room
	}
	return room[i+1:]
}
