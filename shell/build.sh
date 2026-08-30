#!/usr/bin/env bash
# 壳的唯一构建入口(docs/15 §2.3)。
#
# 为什么不能直接 cargo tauri build:
#   Rust 是这个项目的第三条工具链,而 docs/10 §一 的隔离红线当时只写了 Maven 和 npm。
#   ~/.cargo/config.toml 里一行 [source.crates-io] replace-with = "公司源"
#   就让依赖静默走公司私服 —— 和 server/build.sh 拦的是同一件事,
#   而且【在公司网络里不会报错】(R-75)。
#
# 用法:
#   ./build.sh check              只跑校验,不构建
#   ./build.sh macos [args...]    macOS 客户端(KUBI-65)
#   ./build.sh android [args...]  Android(KUBI-67)
#   ./build.sh ios [args...]      iOS(KUBI-66)
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-check}"
shift || true

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31m拒绝构建 —— %s\033[0m\n\n' "$*" >&2; exit 1; }

# ─────────────────────────────────────────────────────────────
say "① 校验"
# ─────────────────────────────────────────────────────────────

# ①.1 cargo 依赖源:只允许公开匿名、人人可用的镜像。公共镜像 ≠ 公司私服。
CARGO_CFG="${CARGO_HOME:-$HOME/.cargo}/config.toml"
[ -f "$CARGO_CFG" ] || CARGO_CFG="${CARGO_HOME:-$HOME/.cargo}/config"
if [ -f "$CARGO_CFG" ]; then
  python3 - "$CARGO_CFG" <<'PY'
import re, sys, urllib.parse

ALLOWED = {
    "github.com",                    # crates.io 官方索引仓库
    "index.crates.io",               # crates.io 官方稀疏索引
    "static.crates.io",              # crates.io 官方
    "rsproxy.cn",                    # 字节公共镜像
    "mirrors.ustc.edu.cn",           # 中科大
    "mirrors.tuna.tsinghua.edu.cn",  # 清华 TUNA
    "mirror.sjtu.edu.cn",            # 上海交大
    "mirrors.aliyun.com",            # 阿里云公共镜像
}

raw = open(sys.argv[1], encoding="utf-8").read()
# 只看生效的配置。注释里为了说明反例会出现内网地址,那不是配置。
effective = "\n".join(re.sub(r"#.*$", "", line) for line in raw.splitlines())

bad = []
for url in re.findall(r'["\']((?:https?|git|sparse\+https?)://[^"\']+)["\']', effective):
    host = urllib.parse.urlparse(url.replace("sparse+", "")).hostname or ""
    if host not in ALLOWED:
        bad.append(f"{url}  (host={host})")
if re.search(r"\btoken\b|\bpassword\b|Authorization", effective, re.I):
    bad.append("配置里出现凭据:公共镜像不需要登录,出现凭据说明指向了私服")

if bad:
    print("cargo 依赖源未通过隔离校验(docs/15 §八 R-75):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
PY
  echo "  ✓ cargo 依赖源(${CARGO_CFG})"
else
  echo "  ✓ cargo 依赖源(无 ${CARGO_CFG},走官方 crates.io)"
fi
[ -f "${CARGO_HOME:-$HOME/.cargo}/credentials.toml" ] && \
  die "存在 cargo 凭据文件 —— 壳不发布 crate,不需要它。先确认它不是指向私服的那一份。"

# ①.2 依赖黑名单。这三类东西的共同点:它们都会把数据送出这台机器,或者把学科判断搬进壳。
if [ -f Cargo.lock ]; then
  python3 - Cargo.lock <<'PY'
import re, sys

# 模型 SDK —— 调模型只允许在 server 的 recognize 包里(docs/13 §二)。
# 崩溃上报 / 遥测 —— 红线原话是「只存在他自己的机器上」(docs/15 §六)。
# 逐段比中的「一段」——包名用 - / _ 切开之后的任意一段等于它,就算命中。
BANNED_PARTS = {
    "openai", "anthropic", "bedrock", "tiktoken", "ollama",
    "sentry", "opentelemetry", "bugsnag", "rollbar", "posthog", "mixpanel",
    "amplitude", "firebase", "crashlytics", "appcenter", "datadog",
}
# 前缀比中的「前缀」——整个包名以它开头就算命中。
BANNED_PREFIX = ("async-openai", "aws-sdk-bedrock", "sentry-", "opentelemetry-")
# 按【包名的段】比,不按子串比:子串比会把 unicode-segmentation 判成 segment,
# 而一条天天误报的断言两天内就会被关掉,等于从来没有过。
def banned(name: str) -> bool:
    parts = name.lower().replace("_", "-").split("-")
    return any(b in parts or b == name.lower().replace("_", "-") for b in BANNED_PARTS) or \
        any(name.lower().replace("_", "-").startswith(b) for b in BANNED_PREFIX)

names = re.findall(r'^name = "([^"]+)"', open(sys.argv[1], encoding="utf-8").read(), re.M)
hit = sorted({n for n in names if banned(n)})
if hit:
    print("依赖黑名单命中(docs/15 §六):", file=sys.stderr)
    for h in hit:
        print("  ✗ " + h, file=sys.stderr)
    sys.exit(1)
PY
  echo "  ✓ 依赖黑名单"
else
  echo "  ⚠ 还没有 Cargo.lock,依赖黑名单这一轮跳过(首次构建后会有)"
fi

# ①.3 平台差异只允许出现在 platform/ 下(docs/15 §4.3)。
if grep -rn 'cfg(target_os' src --exclude-dir=platform >/dev/null 2>&1; then
  grep -rn 'cfg(target_os' src --exclude-dir=platform >&2
  die "platform/ 之外出现了 cfg(target_os)。平台差异只有一个落点(docs/15 §4.3)。"
fi
echo "  ✓ cfg(target_os) 只在 platform/"

# ①.4 scheduler 不许碰网络。一个能上网的后台定时器,就是那条红线的现成破口。
if grep -nE '^[[:space:]]*use .*(hyper|reqwest|ureq|curl|isahc|local_server)' src/scheduler.rs >/dev/null 2>&1; then
  grep -nE '^[[:space:]]*use .*(hyper|reqwest|ureq|curl|isahc|local_server)' src/scheduler.rs >&2
  die "scheduler 引了网络相关的东西(docs/15 §五)。"
fi
echo "  ✓ scheduler 不引网络"

# ①.5 beforeBuildCommand 必须留空。留着它,下一个人会顺手填上 npm run build,
#      那就出现了第二条构建路径,而它绕过本脚本这一整段校验。
python3 - tauri.conf.json <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1], encoding="utf-8"))
for key in ("beforeBuildCommand", "beforeDevCommand"):
    if cfg.get("build", {}).get(key, "") != "":
        print(f"tauri.conf.json build.{key} 必须是空字符串(docs/15 §2.3)。", file=sys.stderr)
        sys.exit(1)
PY
echo "  ✓ beforeBuildCommand 留空"

# ①.6 与主业公司零交集。
# --exclude=build.sh:这个模式本身就写在这一行上,不排掉它,这条检查第一次跑就红在自己身上。
if grep -riqE 'fenbi|粉笔' . --exclude-dir=target --exclude-dir=gen --exclude=build.sh 2>/dev/null; then
  die "壳里出现了主业公司相关字样(docs/10 §1.5)。"
fi
echo "  ✓ 与主业公司零交集"

# ①.7 文案两条:能力边界扫描(词表取自 web,壳这边没有第二份)+ 文案集中。
node scripts/boundary-scan-shell.mjs | sed 's/^/  /'
python3 scripts/one-file-for-strings.py | sed 's/^/  /'

if [ "$TARGET" = "check" ]; then
  say "只跑校验,到此为止。"
  exit 0
fi

# ─────────────────────────────────────────────────────────────
say "② 构建前端 —— 走 web 自己的 npm run build,不复制它的命令"
# ─────────────────────────────────────────────────────────────
# npm 读的是【当前工作目录】的 .npmrc,所以必须真的 cd 过去,不能用 npm --prefix。
( cd ../web && npm ci --silent && npm run build )

# ─────────────────────────────────────────────────────────────
say "③ 产物校验"
# ─────────────────────────────────────────────────────────────
# ④ 的 include_dir! 是编译期读盘,dist 不存在时报的是一句 Rust 宏错误,
# 和「前端没构建」这件事对不上号。
[ -f ../web/dist/index.html ] || die "../web/dist/index.html 不存在。"
grep -q '/assets/' ../web/dist/index.html || \
  die "dist/index.html 里没有引用 /assets/* —— vite 的 base 被改过?"
echo "  ✓ dist/index.html 存在且引用 /assets/*"

# ─────────────────────────────────────────────────────────────
say "④ cargo tauri build($TARGET)"
# ─────────────────────────────────────────────────────────────
command -v cargo-tauri >/dev/null 2>&1 || \
  die "没装 tauri-cli。cargo install tauri-cli --version '^2.0' --locked"

# 图标从 web/public/favicon.svg 现生成,不进仓库 —— 壳里的视觉资产没有第二份来源。
bash scripts/make-icons.sh >/dev/null
echo "  ✓ 图标(源自 web/public/favicon.svg)"

case "$TARGET" in
  macos)
    # 上游默认值由这里注入,不写在代码里的 #[cfg(target_os)] 分支上 ——
    # 「这一轮某个端接不接后端」是排期,不是平台能力(docs/15 §4.2)。
    export KAODIAN_SHELL_UPSTREAM="${KAODIAN_SHELL_UPSTREAM:-http://127.0.0.1:8080}"
    cargo tauri build "$@"
    ;;
  android)
    # 移动端本轮不接后端:不注入上游,/api 一律 502,
    # 首屏落在前端已有的离线示例数据整屏回退上(docs/15 §4.2)。
    unset KAODIAN_SHELL_UPSTREAM
    : "${ANDROID_HOME:?需要 ANDROID_HOME}"
    : "${NDK_HOME:?需要 NDK_HOME}"
    [ -d gen/android ] || cargo tauri android init
    python3 scripts/android-allow-loopback.py
    cargo tauri android build "$@"
    ;;
  ios)
    unset KAODIAN_SHELL_UPSTREAM
    [ -d gen/apple ] || cargo tauri ios init
    cargo tauri ios build "$@"
    ;;
  *)
    die "不认识的目标 '$TARGET'。可选:check / macos / android / ios"
    ;;
esac

say "构建完成。"
