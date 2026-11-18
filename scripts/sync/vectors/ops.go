package main

import "sort"

type Op struct {
	Fn    string
	Name  string
	Names []string
	Key   string
	Value string
}

func canonUser(name string) (display, user string, ok bool) {
	display = trimWS(name)
	user = lowerString(display)
	if !matchUser(user) {
		return "", "", false
	}
	return display, user, true
}

func matchUser(s string) bool {
	rs := []rune(s)
	if len(rs) < 2 || len(rs) > 30 {
		return false
	}
	for _, r := range rs {
		if !((r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_') {
			return false
		}
	}
	return true
}

func matchN(s string) bool {
	rs := []rune(s)
	if len(rs) < 2 || len(rs) > 30 {
		return false
	}
	for _, r := range rs {
		if !((r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_') {
			return false
		}
	}
	return true
}

func nPayload(display, user, existing string) (string, bool) {
	pay := display
	if !matchN(display) || lowerString(display) != user {
		pay = user
	}
	if pay != user || existing != "" {
		return pay, true
	}
	return "", false
}

func applyOp(doc *Doc, st HLC, pt uint64, op Op) (*Doc, HLC, bool) {
	switch op.Fn {
	case "Curation.favoriting":
		return writeRoom(doc, st, pt, op.Name, "fav")
	case "Curation.restoringFavorite":
		return writeRoom(doc, st, pt, op.Name, "restore")
	case "Curation.unfavoriting":
		return writeRoom(doc, st, pt, op.Name, "unfav")
	case "Curation.blocking":
		return writeRoom(doc, st, pt, op.Name, "block")
	case "Curation.unblocking":
		return writeRoom(doc, st, pt, op.Name, "unblk")
	case "Curation.importing":
		return writeImport(doc, st, pt, op.Names)
	case "Taste.vetoing":
		return writeVeto(doc, st, pt, op.Key, true)
	case "Taste.restoring", "Taste.boosting", "Taste.removing":
		return writeVeto(doc, st, pt, op.Key, false)
	case "Taste.resettingProfile", "Taste.erased":
		return writeVetoClear(doc, st, pt)
	case "settings":
		return writeSetting(doc, st, pt, op.Key, op.Value)
	case "rejecting", "following", "recording", "settingPaused", "marking":
		return doc, st, false
	default:
		return doc, st, false
	}
}

func finish(orig *Doc, st0, st HLC, out *Doc, wrote bool) (*Doc, HLC, bool) {
	if !wrote {
		return orig, st0, false
	}
	sz := applySize(out, plainMax, sizeLocal)
	if sz.Result == "reject" {
		return orig, st0, false
	}
	return sz.Doc, st, true
}

func writeRoom(doc *Doc, st HLC, pt uint64, name, mode string) (*Doc, HLC, bool) {
	disp, user, ok := canonUser(name)
	if !ok {
		return doc, st, false
	}
	key := "cb:" + user
	kind := projectRoom(doc.Rooms[key])
	switch mode {
	case "unfav":
		if kind != "fav" {
			return doc, st, false
		}
	case "unblk":
		if kind != "block" {
			return doc, st, false
		}
	case "restore":
		if kind == "fav" || kind == "block" {
			return doc, st, false
		}
		mode = "fav"
	}
	st0 := st
	st = st.local(pt)
	h := wire(st.L, st.C, st.Dev)
	out := clone(doc)
	r := out.Rooms[key]
	switch mode {
	case "fav":
		r.F = h + "+"
		r.B = h + "-"
	case "block":
		r.B = h + "+"
		r.F = h + "-"
	case "unfav":
		r.F = h + "-"
	case "unblk":
		r.B = h + "-"
	}
	if mode == "fav" || mode == "block" {
		if pay, yes := nPayload(disp, user, r.N); yes {
			r.N = h + pay
		}
	}
	out.Rooms[key] = r
	return finish(doc, st0, st, out, true)
}

func writeImport(doc *Doc, st HLC, pt uint64, names []string) (*Doc, HLC, bool) {
	type item struct{ disp, user string }
	seen := map[string]bool{}
	var window []item
	for _, n := range names {
		disp, user, ok := canonUser(n)
		if !ok || seen[user] {
			continue
		}
		seen[user] = true
		window = append(window, item{disp, user})
		if len(window) == 300 {
			break
		}
	}
	var keep []item
	for _, it := range window {
		kind := projectRoom(doc.Rooms["cb:"+it.user])
		if kind == "fav" || kind == "block" {
			continue
		}
		keep = append(keep, it)
	}
	if len(keep) == 0 {
		return doc, st, false
	}
	st0 := st
	out := clone(doc)
	for i := len(keep) - 1; i >= 0; i-- {
		st = st.local(pt)
		h := wire(st.L, st.C, st.Dev)
		key := "cb:" + keep[i].user
		r := out.Rooms[key]
		r.F = h + "+"
		r.B = h + "-"
		if pay, yes := nPayload(keep[i].disp, keep[i].user, r.N); yes {
			r.N = h + pay
		}
		out.Rooms[key] = r
	}
	return finish(doc, st0, st, out, true)
}

func vetoPlusCount(d *Doc) int {
	n := 0
	for _, v := range d.Veto {
		if payloadOf(v) == "+" {
			n++
		}
	}
	return n
}

func isPlus(reg string) bool { return payloadOf(reg) == "+" }

func writeVeto(doc *Doc, st HLC, pt uint64, key string, toPlus bool) (*Doc, HLC, bool) {
	if !vetoDomain(key) {
		return doc, st, false
	}
	cur := isPlus(doc.Veto[key])
	if toPlus {
		if !cur && vetoPlusCount(doc) >= capVeto {
			return doc, st, false
		}
	} else if !cur {
		return doc, st, false
	}
	st0 := st
	st = st.local(pt)
	out := clone(doc)
	pay := "-"
	if toPlus {
		pay = "+"
	}
	out.Veto[key] = wire(st.L, st.C, st.Dev) + pay
	return finish(doc, st0, st, out, true)
}

func writeVetoClear(doc *Doc, st HLC, pt uint64) (*Doc, HLC, bool) {
	var keys []string
	for k, v := range doc.Veto {
		if isPlus(v) {
			keys = append(keys, k)
		}
	}
	if len(keys) == 0 {
		return doc, st, false
	}
	sort.Strings(keys)
	st0 := st
	st = st.local(pt)
	h := wire(st.L, st.C, st.Dev)
	out := clone(doc)
	for _, k := range keys {
		out.Veto[k] = h + "-"
	}
	return finish(doc, st0, st, out, true)
}

func writeSetting(doc *Doc, st HLC, pt uint64, key, val string) (*Doc, HLC, bool) {
	if key != "publicOnly" && key != "infoAlwaysOn" {
		return doc, st, false
	}
	if val != "0" && val != "1" {
		return doc, st, false
	}
	st0 := st
	st = st.local(pt)
	out := clone(doc)
	out.Settings[key] = wire(st.L, st.C, st.Dev) + val
	return finish(doc, st0, st, out, true)
}

type Counts struct{ N, M, V, X, Y, Z int }

type MigInput struct {
	Favorites []string
	Blocked   []string
	Vetoed    []string
	Settings  map[string]string
	PT        uint64
	Dev       uint32
	Remote    *Doc
}

func dedupeUsers(names []string) []struct{ disp, user string } {
	seen := map[string]bool{}
	var out []struct{ disp, user string }
	for _, n := range names {
		disp, user, ok := canonUser(n)
		if !ok || seen[user] {
			continue
		}
		seen[user] = true
		out = append(out, struct{ disp, user string }{disp, user})
	}
	return out
}

func migrate(in MigInput) (*Doc, Counts, HLC) {
	var c Counts
	st := HLC{Dev: in.Dev}
	remote := in.Remote
	if remote != nil {
		// 第 5.1 节：先看见远端，盖章才排在远端之后（第 12.4 节第 6 步「本机的收藏因此保留」）。
		st = st.receive(remote)
	}
	out := emptyDoc()
	favs := dedupeUsers(in.Favorites)
	for i := len(favs) - 1; i >= 0; i-- {
		key := "cb:" + favs[i].user
		kind := "absent"
		if remote != nil {
			kind = projectRoom(remote.Rooms[key])
		}
		if remote != nil && kind == "block" {
			c.Z++
			continue
		}
		if remote != nil && kind == "fav" {
			continue
		}
		st = st.local(in.PT)
		h := wire(st.L, st.C, st.Dev)
		r := out.Rooms[key]
		r.F = h + "+"
		if pay, yes := nPayload(favs[i].disp, favs[i].user, r.N); yes {
			r.N = h + pay
		}
		out.Rooms[key] = r
		c.N++
	}
	blks := dedupeUsers(in.Blocked)
	for i := len(blks) - 1; i >= 0; i-- {
		key := "cb:" + blks[i].user
		if remote != nil && projectRoom(remote.Rooms[key]) == "block" {
			continue
		}
		st = st.local(in.PT)
		h := wire(st.L, st.C, st.Dev)
		r := out.Rooms[key]
		r.B = h + "+"
		if pay, yes := nPayload(blks[i].disp, blks[i].user, r.N); yes {
			r.N = h + pay
		}
		out.Rooms[key] = r
		c.M++
	}
	var vetos []string
	seenV := map[string]bool{}
	for _, k := range in.Vetoed {
		if seenV[k] || !vetoDomain(k) {
			continue
		}
		seenV[k] = true
		vetos = append(vetos, k)
	}
	sort.Strings(vetos)
	if len(vetos) > capVeto {
		vetos = vetos[:capVeto]
	}
	for _, k := range vetos {
		if remote != nil && isPlus(remote.Veto[k]) {
			continue
		}
		st = st.local(in.PT)
		out.Veto[k] = wire(st.L, st.C, st.Dev) + "+"
		c.V++
	}
	var sk []string
	for k, v := range in.Settings {
		if (k == "publicOnly" || k == "infoAlwaysOn") && (v == "0" || v == "1") {
			sk = append(sk, k)
		}
	}
	sort.Strings(sk)
	for _, k := range sk {
		if remote != nil && remote.Settings[k] != "" {
			continue
		}
		st = st.local(in.PT)
		out.Settings[k] = wire(st.L, st.C, st.Dev) + in.Settings[k]
	}
	before := out
	if remote != nil {
		before = union(out, remote)
	}
	c.X = countKind(before, "fav") - countKind(pruneE(before), "fav")
	c.Y = countKind(before, "block") - countKind(pruneE(before), "block")
	sz := applySize(before, plainMax, sizeMerge)
	return sz.Doc, c, st
}
