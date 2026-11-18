#!/bin/bash
# selftest.sh 收尾清理的回归测试：用临时 HOME 与假的 xcodebuild / open / defaults / ps / pgrep / log，
# 不构建、不启动 App。验证：运行前已存在的站点数据目录不被删，本次新建的被删；
# 没真正启动 App 的退出路径（--check-release、构建失败、参数无效）什么都不删。兼容 bash 3.2。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d "${TMPDIR:-/tmp}/vela-fix3-selftest.XXXXXX")
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
cat >"$T/bin/xcodebuild" <<'SH'
#!/bin/bash
[ "${FAKE_XB:-ok}" = ok ]
SH
# 假 open：模拟 App 启动时在 HOME 下新建站点数据目录
cat >"$T/bin/open" <<'SH'
#!/bin/bash
mkdir -p "$HOME/Library/WebKit/com.vela.stage" "$HOME/Library/HTTPStorages/com.vela.stage.new"
exit 0
SH
printf '#!/bin/bash\nexit 1\n' >"$T/bin/defaults"
printf '#!/bin/bash\nexit 1\n' >"$T/bin/pgrep"
printf '#!/bin/bash\nexit 0\n' >"$T/bin/ps"
printf '#!/bin/bash\nexit 0\n' >"$T/bin/log"
printf '#!/bin/bash\nexit 0\n' >"$T/bin/sleep"
printf '#!/bin/bash\nexit 0\n' >"$T/bin/strings"
chmod +x "$T/bin/"*

fail=0
check() { if [ "$2" = 1 ]; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
exists() { [ -e "$1" ] && echo 1 || echo 0; }
gone() { [ -e "$1" ] && echo 0 || echo 1; }

setup_home() { # 预先造用户数据：Caches 与 HTTPStorages/com.vela.stage 已存在
    H="$T/home.$1"
    rm -rf "$H"
    mkdir -p "$H/Library/Caches/com.vela.stage" "$H/Library/HTTPStorages/com.vela.stage"
    echo keep >"$H/Library/Caches/com.vela.stage/user-data"
}
run() { # run <标签> [参数...]
    local tag="$1"; shift
    setup_home "$tag"
    HOME="$H" PATH="$T/bin:$PATH" FAKE_XB="${FAKE_XB:-ok}" SELFTEST_WAIT_MIN=0 \
        bash "$ROOT/scripts/selftest.sh" "$@" >"$T/out.$tag" 2>&1
    RC=$?
}

# 1) 真正启动了 App：预先存在的保留，本次新建的删除
run started unmute
check "启动路径：预先存在的 Caches 保留" "$(exists "$H/Library/Caches/com.vela.stage/user-data")"
check "启动路径：预先存在的 HTTPStorages 保留" "$(exists "$H/Library/HTTPStorages/com.vela.stage")"
check "启动路径：本次新建的 WebKit 目录被删" "$(gone "$H/Library/WebKit/com.vela.stage")"
check "启动路径：本次新建的 HTTPStorages 变体被删" "$(gone "$H/Library/HTTPStorages/com.vela.stage.new")"

# 2) 构建失败：什么都不删
FAKE_XB=fail run buildfail unmute
check "构建失败：退出码非 0" "$([ "$RC" != 0 ] && echo 1 || echo 0)"
check "构建失败：Caches 保留" "$(exists "$H/Library/Caches/com.vela.stage/user-data")"
check "构建失败：HTTPStorages 保留" "$(exists "$H/Library/HTTPStorages/com.vela.stage")"

# 3) 参数无效
run badarg nosuch
check "参数无效：退出码 2" "$([ "$RC" = 2 ] && echo 1 || echo 0)"
check "参数无效：Caches 保留" "$(exists "$H/Library/Caches/com.vela.stage/user-data")"
check "参数无效：HTTPStorages 保留" "$(exists "$H/Library/HTTPStorages/com.vela.stage")"

# 4) --check-release（假 xcodebuild 成功但无产物，走失败路径）
run rel --check-release
check "--check-release：Caches 保留" "$(exists "$H/Library/Caches/com.vela.stage/user-data")"
check "--check-release：HTTPStorages 保留" "$(exists "$H/Library/HTTPStorages/com.vela.stage")"

exit "$fail"
