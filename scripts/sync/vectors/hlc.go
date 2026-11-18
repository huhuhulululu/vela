package main

import (
	"fmt"
	"sort"
	"strconv"
)

// HLC 状态。(l, c, dev)，l 为毫秒，c 为 16 位计数，dev 为设备号。
type HLC struct {
	L   uint64
	C   uint16
	Dev uint32
}

func wire(l uint64, c uint16, dev uint32) string {
	return fmt.Sprintf("%012x%04x%08x", l, c, dev)
}

func hlcValid(l uint64, dev uint32) bool {
	return l >= 1 && l <= 0xFFFF00000000 && dev != 0
}

func parseWire(s string) (l uint64, c uint16, dev uint32, payload string, ok bool) {
	if len(s) < 24 {
		return
	}
	for i := 0; i < 24; i++ {
		ch := s[i]
		if !((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')) {
			return
		}
	}
	l, err1 := strconv.ParseUint(s[:12], 16, 64)
	c64, err2 := strconv.ParseUint(s[12:16], 16, 16)
	d64, err3 := strconv.ParseUint(s[16:24], 16, 32)
	if err1 != nil || err2 != nil || err3 != nil {
		return
	}
	return l, uint16(c64), uint32(d64), s[24:], true
}

func (h HLC) tick() HLC {
	if h.C < 0xFFFF {
		h.C++
		return h
	}
	h.L++
	h.C = 0
	return h
}

func (h HLC) local(pt uint64) HLC {
	if pt > h.L {
		h.L = pt
		h.C = 0
		return h
	}
	return h.tick()
}

func (h HLC) receive(doc *Doc) HLC {
	eachReg(doc, func(l uint64, c uint16, _ uint32, _ string) {
		if l > h.L || (l == h.L && c > h.C) {
			h.L, h.C = l, c
		}
	})
	return h
}

// presend 执行第 5.2 节发送前重新盖章。
// 部分已发出（第 23 节第 3 条）：候选集里只要有一个已发出，整组不重新盖章，照常推送并记 diagnostic。
// 时钟基准取「不在原 R 里的寄存器」与 (N, 0) 的较大者，避免被已发出的毒化时间戳抬走。
func presend(st HLC, doc *Doc, N, maxSentL uint64) (HLC, *Doc, bool) {
	type ref struct {
		sec, key, field, payload, hlc string
		l                             uint64
		c                             uint16
	}
	var all, R []ref
	limit := N + 3_600_000
	eachRegLoc(doc, func(sec, key, field, reg string) {
		l, c, dev, payload, ok := parseWire(reg)
		if !ok {
			return
		}
		r := ref{sec, key, field, payload, reg[:24], l, c}
		all = append(all, r)
		if dev == st.Dev && l > limit {
			R = append(R, r)
		}
	})
	if len(R) == 0 {
		return st, clone(doc), false
	}
	inR := map[string]bool{}
	var move []ref
	fixed := 0
	for _, r := range R {
		inR[r.sec+"\x00"+r.key+"\x00"+r.field] = true
		if r.l > maxSentL {
			move = append(move, r)
		} else {
			fixed++
		}
	}
	if fixed > 0 || len(move) == 0 {
		return st, clone(doc), fixed > 0
	}
	baseL, baseC := N, uint16(0)
	for _, r := range all {
		if inR[r.sec+"\x00"+r.key+"\x00"+r.field] {
			continue
		}
		if r.l > baseL || (r.l == baseL && r.c > baseC) {
			baseL, baseC = r.l, r.c
		}
	}
	out := clone(doc)
	st.L, st.C = baseL, baseC
	groups := map[string][]ref{}
	var order []string
	for _, r := range move {
		if _, ok := groups[r.hlc]; !ok {
			order = append(order, r.hlc)
		}
		groups[r.hlc] = append(groups[r.hlc], r)
	}
	sort.Strings(order)
	for _, h := range order {
		st = st.tick()
		nw := wire(st.L, st.C, st.Dev)
		for _, r := range groups[h] {
			putReg(out, r.sec, r.key, r.field, nw+r.payload)
		}
	}
	return st, out, fixed > 0
}
