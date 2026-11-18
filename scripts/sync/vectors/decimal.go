package main

import "strconv"

// 十进制：无前导零（0 除外）、无符号、无小数与指数，范围 0 到 2^53-1。
func tryDecimal(s string) (uint64, bool) {
	if s == "" {
		return 0, false
	}
	if s[0] == '0' && s != "0" {
		return 0, false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, false
		}
	}
	n, err := strconv.ParseUint(s, 10, 64)
	if err != nil {
		return 0, false
	}
	if n > (1<<53)-1 {
		return 0, false
	}
	return n, true
}

func parseDecimal(s string) uint64 {
	n, ok := tryDecimal(s)
	if !ok {
		panic("decimal: " + s)
	}
	return n
}

func formatDecimal(n uint64) string {
	return strconv.FormatUint(n, 10)
}
