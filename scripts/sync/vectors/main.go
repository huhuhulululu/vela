package main

import (
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
)

func main() {
	out := flag.String("o", "", "写入 vectors.json 的路径")
	probe := flag.String("probe", "", "写入安卓探针输入的路径")
	verify := flag.String("verify-android", "", "校验探针输出的签名")
	flag.Parse()
	if *verify != "" {
		os.Exit(verifyAndroid(*verify))
	}
	body := buildVectors()
	if *out != "" {
		if err := os.WriteFile(*out, body, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	} else {
		os.Stdout.Write(body)
	}
	if *probe != "" {
		if err := os.WriteFile(*probe, probeVectors(), 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
}

func verifyAndroid(path string) int {
	b, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	text := string(b)
	k := fixedK(0x42)
	d := derive(k)
	lines := map[string]string{}
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || !strings.Contains(line, " ") {
			continue
		}
		i := strings.IndexByte(line, ' ')
		lines[line[:i]] = strings.TrimSpace(line[i+1:])
	}
	okAll := true
	check := func(name, msg string, sigB64 string) {
		sig, err := b64Decode(sigB64)
		if err != nil {
			fmt.Printf("VERIFY_ANDROID_ON_GO %s FAIL decode\n", name)
			okAll = false
			return
		}
		if ed25519.Verify(d.Pub, []byte(msg), sig) {
			fmt.Printf("VERIFY_ANDROID_ON_GO %s PASS\n", name)
		} else {
			fmt.Printf("VERIFY_ANDROID_ON_GO %s FAIL\n", name)
			okAll = false
		}
	}
	if s := lines["ANDROID_SIG_SIGN"]; s != "" {
		check("sign", "vela-sync-probe/v1", s)
	} else {
		fmt.Println("VERIFY_ANDROID_ON_GO sign FAIL missing")
		okAll = false
	}
	if s := lines["ANDROID_SIG_PUBCHECK"]; s != "" {
		check("pubcheck", string(pubcheckMessage(d.BlobID)), s)
	} else {
		fmt.Println("VERIFY_ANDROID_ON_GO pubcheck FAIL missing")
		okAll = false
	}
	_ = json.Marshal
	_ = hex.EncodeToString
	if okAll {
		return 0
	}
	return 1
}
