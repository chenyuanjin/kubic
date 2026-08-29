#!/usr/bin/env bash
# 壳的唯一构建入口(壳技术方案 §2.3)
#
# 为什么不能直接 `cargo tauri build`:
#   Rust 是这个项目的第三条工具链,而 docs/10 §一 的隔离红线只写了 Maven 和 npm。
#   ~/.cargo/config.toml 里一行 `[source.crates-io] replace-with = "公司源"`
#   会让依赖静默走公司私服 ——【在公司网络里不会报错】,和 server/build.sh 拦的是同一件事(R-75)。
#
# 用法:
#   ./build.sh --check    # 只跑隔离校验,不构建
#   ./build.sh ios        # iOS 模拟器(KUBI-66,已验证)
#   ./build.sh            # macOS 桌面(KUBI-65 未落地,能编译不等于能用)
#   ./build.sh android    # Android(KUBI-67 未落地,本分支从未跑通过)
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:-desktop}"

# ───────────────────────── ① 隔离校验 ─────────────────────────
# 四条,一条都不能省。它们必须在【构建脚本里】,不能只写在 README 里:
# 靠自觉的约束在赶工的那一周会失效,而赶工的那一周正是它最需要生效的时候。

echo "① 隔离校验"

# 1a · cargo 依赖源白名单(R-75)
python3 - <<'PY'
import os, re, sys, urllib.parse

ALLOWED_HOSTS = {
    "crates.io", "static.crates.io", "index.crates.io",   # 官方
    "github.com",                                          # crates.io 索引的默认后端
    "mirrors.ustc.edu.cn",                                 # 中科大公共镜像
    "mirrors.tuna.tsinghua.edu.cn",                        # 清华 TUNA
    "rsproxy.cn",                                          # 字节公共镜像
}

candidates = [
    os.path.expanduser("~/.cargo/config.toml"),
    os.path.expanduser("~/.cargo/config"),
    ".cargo/config.toml",
    "../.cargo/config.toml",
]

bad = []
for path in candidates:
    if not os.path.isfile(path):
        continue
    raw = open(path, encoding="utf-8").read()
    # 只看【生效的配置】,不看注释 —— 注释里为了说明反例会出现内网地址,那不是配置
    effective = re.sub(r"(?m)^\s*#.*$", "", raw)

    for url in re.findall(r'(?:registry|replace-with)\s*=\s*"([^"]+)"', effective):
        if "://" not in url:
            # replace-with = "某个 source 名",要跟到那个 source 的 registry 上
            m = re.search(r'\[source\.%s\][^\[]*?registry\s*=\s*"([^"]+)"' % re.escape(url),
                          effective, re.S)
            if not m:
                bad.append(f"{path}: replace-with = \"{url}\",但找不到对应的 [source.{url}]")
                continue
            url = m.group(1)
        host = urllib.parse.urlparse(url).hostname or ""
        if host not in ALLOWED_HOSTS:
            bad.append(f"{path}: {url}  (host={host} 不在公共镜像白名单里)")
        if urllib.parse.urlparse(url).scheme == "http":
            bad.append(f"{path}: {url}  (明文 http,公共镜像应走 https)")

    if re.search(r"(?m)^\s*token\s*=", effective):
        bad.append(f"{path}: 出现 token —— 公共镜像不需要凭据,出现凭据说明指向了私服")

if os.path.isfile(os.path.expanduser("~/.cargo/credentials.toml")) or \
   os.path.isfile(os.path.expanduser("~/.cargo/credentials")):
    bad.append("~/.cargo/credentials* 存在 —— 公共源不需要登录,请确认它不是公司私服的凭据")

if bad:
    print("拒绝构建 —— cargo 依赖源未通过隔离校验(R-75):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
print("   ✓ cargo 依赖源在公共镜像白名单内")
PY

# 1b · 依赖黑名单:壳不调用任何外部模型,也不上报任何东西
#   模型 SDK —— AsrClient / VisionTagger 仍然只在 server 的 recognize 包里
#              (docs/13 §二:「recognize 之外不得出现 HTTP 调模型」)
#   遥测 SDK —— 它会把数据送出这台机器,而红线原话是「只存在他自己的机器上」
BANNED='openai|anthropic|langchain|ollama|replicate|huggingface|sentry|opentelemetry|datadog|bugsnag|posthog|firebase|crashlytics'
if grep -nEi "^[[:space:]]*(${BANNED})[[:space:]]*=" Cargo.toml; then
  echo "拒绝构建 —— Cargo.toml 命中依赖黑名单(壳技术方案 §六)" >&2
  exit 1
fi
echo "   ✓ 依赖黑名单零命中(无模型 SDK、无遥测/崩溃上报)"

# 1c · 平台差异越界:#[cfg(target_os)] 只允许出现在 src/platform/ 下
#
# 🔴 只看【生效的代码】,不看注释 —— 与 server/build.sh 剥 XML 注释同一条理由:
#    这几个文件的注释里正写着「本文件必须零 cfg(target_os)」,
#    照字面 grep 会把这句纪律本身判成违规。
python3 - <<'PY'
import os, re, sys

bad = []
for root, dirs, files in os.walk("src"):
    dirs[:] = [d for d in dirs if d != "platform"]
    for name in files:
        if not name.endswith(".rs"):
            continue
        path = os.path.join(root, name)
        src = open(path, encoding="utf-8").read()
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)     # 块注释
        for i, line in enumerate(src.splitlines(), 1):
            code = re.sub(r"//.*$", "", line)               # 行注释与文档注释
            if "cfg(target_os" in code:
                bad.append(f"{path}:{i}: {line.strip()}")

if bad:
    print("拒绝构建 —— #[cfg(target_os)] 越出 src/platform/(壳技术方案 §4.3):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
print("   ✓ #[cfg(target_os)] 未越出 src/platform/(已剥注释)")
PY

# 1d · 与主业公司零交集(docs/10 §1.5)
# 模式写成字符组,是为了让【这一行自己】不匹配自己 —— 否则扫描器永远扫到自己。
if grep -rniI 'f[e]nbi\|粉[笔]' . --exclude-dir=gen --exclude-dir=target --exclude-dir=icons; then
  echo "拒绝构建 —— 壳里出现主业公司字样(docs/10 §1.5)" >&2
  exit 1
fi
echo "   ✓ 与主业公司零交集"

if [ "$TARGET" = "--check" ]; then
  echo "只跑校验,到此为止。"
  exit 0
fi

# ───────────────────────── ② 构建前端 ─────────────────────────
# 🔴 调用 web 自己的 npm run build,不复制它的命令。
#    npm 读的是【当前工作目录】的 .npmrc(web 指着 npmmirror),
#    所以必须真的 cd ../web,不能用 npm --prefix。
echo "② 构建前端(走 web 自己的构建脚本)"
(cd ../web && npm ci --silent && npm run build)

# ───────────────────────── ③ 产物校验 ─────────────────────────
# ④ 的 include_dir! 是编译期读盘。dist 不存在时报的是一句 Rust 宏错误,
#   和「前端没构建」这件事对不上号。
echo "③ 产物校验"
[ -f ../web/dist/index.html ] || { echo "缺少 web/dist/index.html —— 前端没构建成功" >&2; exit 1; }
grep -q '/assets/' ../web/dist/index.html || {
  echo "web/dist/index.html 没有引用 /assets/* —— vite base 被改过?" >&2; exit 1; }
echo "   ✓ dist/index.html 存在且引用 /assets/*"

# ───────────────────── 工具链自检:架构必须对得上 ─────────────────────
#
# 🔴 这一段是 KUBI-66 花了最多时间的那个坑,留在这里是为了让下一个人不用再踩:
#
#    本机是 arm64,但 rustup 的默认 toolchain 是 stable-x86_64-apple-darwin。
#    用它 `cargo install tauri-cli` 装出来的 cargo-tauri 是一个 x86_64 二进制,
#    而 tauri 的移动端逻辑【按自己这个二进制的架构去猜模拟器架构】——
#    于是它给 xcodebuild 传 `ARCHS=x86_64`,构建出一个 x86_64 的 .app,
#    装进 arm64 模拟器时报的是「此 App 需要开发者更新以在此 iOS 版本上运行」。
#
#    那句报错和真正的原因(装错架构的 CLI)之间没有任何字面联系,
#    所以这里【显式检查并说出来】,而不是让下一个人再查一遍。
HOST_ARCH="$(uname -m)"
RUSTC_HOST="$(rustc -vV | sed -n 's/^host: //p')"
if [ "$HOST_ARCH" = "arm64" ] && [ "${RUSTC_HOST#aarch64}" = "$RUSTC_HOST" ]; then
  if rustup toolchain list | grep -q '^stable-aarch64-apple-darwin'; then
    export RUSTUP_TOOLCHAIN=stable-aarch64-apple-darwin
    echo "   ⚠ 默认 toolchain 是 $RUSTC_HOST,与本机 arm64 不符;本次改用 $RUSTUP_TOOLCHAIN"
  else
    echo "默认 toolchain 是 $RUSTC_HOST,本机是 arm64,且未安装原生 toolchain。" >&2
    echo "先装:rustup toolchain install stable-aarch64-apple-darwin" >&2
    exit 1
  fi
fi

if [ "$TARGET" = "ios" ] && ! file "$(command -v cargo-tauri)" | grep -q "$HOST_ARCH"; then
  echo "拒绝构建 —— cargo-tauri 的架构与本机($HOST_ARCH)不符。" >&2
  echo "它会据此猜错模拟器架构,构建出装不进模拟器的 .app。重装:" >&2
  echo "  RUSTUP_TOOLCHAIN=stable-aarch64-apple-darwin cargo install tauri-cli --version '^2' --locked --force" >&2
  exit 1
fi

# ───────────────────────── ④ 构建壳 ─────────────────────────
echo "④ 构建壳($TARGET)"
case "$TARGET" in
  desktop) cargo tauri build ;;

  ios)
    for tool in xcodegen pod; do
      command -v "$tool" >/dev/null || {
        echo "缺少 $tool。xcodegen 见 github.com/yonaskolb/XcodeGen/releases;" >&2
        echo "pod 用 gem install --user-install cocoapods(注意系统 ruby 2.6 要配 1.11.3)" >&2
        exit 1; }
    done

    # 首次:生成 Xcode 工程。gen/ 不进仓库,所以每台新机器都要跑一次。
    if [ ! -f gen/apple/project.yml ]; then
      echo "   · gen/apple 不存在,先 cargo tauri ios init"
      cargo tauri ios init
    fi

    # 🔴 把 Info.ios.plist 的加项灌进生成工程。
    #    不加 = iOS 白屏:窗口指向 http://127.0.0.1:17840(明文 HTTP),
    #    而 App Transport Security 默认拒绝一切明文加载。
    #    实测:Tauri 2.11 【不会】读 tauri 目录下的 Info.plist,必须自己灌。
    #
    #    只在缺失时才灌 + 重跑 xcodegen。做成幂等的理由不是省时间:
    #    Externals/ 下已经有 libapp.a 时重跑 xcodegen 会把它当成源文件收进去,
    #    与「Build Rust Code」阶段的产物撞成 "Multiple commands produce libapp.a"。
    #    init 刚跑完时 Externals 是空的,那一刻重跑才是安全的。
    if ! plutil -p gen/apple/*/Info.plist 2>/dev/null | grep -q NSAppTransportSecurity; then
      echo "   · 注入 Info.ios.plist 加项并重生成 Xcode 工程"
      if find gen/apple/Externals -name '*.a' 2>/dev/null | grep -q .; then
        echo "   · Externals 下已有 libapp.a,先清掉再重生成(否则 xcodegen 会把它收成源文件)"
        rm -rf gen/apple/Externals
      fi
      python3 - <<'PY'
import plistlib, yaml

additions = plistlib.load(open("Info.ios.plist", "rb"))
path = "gen/apple/project.yml"
project = yaml.safe_load(open(path, encoding="utf-8"))

touched = []
for name, target in (project.get("targets") or {}).items():
    if "info" not in target:
        continue
    target["info"].setdefault("properties", {}).update(additions)
    touched.append(name)

if not touched:
    raise SystemExit("project.yml 里没有带 info 段的 target —— 生成工程的结构变了")

yaml.safe_dump(project, open(path, "w", encoding="utf-8"),
               allow_unicode=True, sort_keys=False)
print(f"     ✓ 注入 {list(additions)} → {touched}")
PY
      (cd gen/apple && xcodegen generate >/dev/null)
    fi

    # 上一轮留下的 xcarchive 会让这一轮的打包步骤炸在
    # 「failed to rename app ...: Directory not empty」上 —— 报错文字与
    # 「有个旧目录没删」之间毫无字面联系,所以这里先清掉,不留给下一个人查。
    rm -rf gen/apple/build

    # 🔴 只上模拟器。模拟器不需要签名身份,本轮一分钱不花(壳技术方案 §4.4)。
    #    --target aarch64-sim 是「arm64 模拟器」,不是真机。
    cargo tauri ios build --target aarch64-sim

    APP=$(find "$HOME/Library/Developer/Xcode/DerivedData"/kaodian-shell-*/Build/Products \
            -name "Kaodian.app" -path "*iphonesimulator*" 2>/dev/null | head -1)
    echo
    echo "产物:${APP:-<未找到,去 DerivedData 里翻>}"
    echo "装进模拟器并打开:"
    echo "  xcrun simctl boot 'iPhone 17 Pro'; open -a Simulator"
    echo "  xcrun simctl install booted '$APP'"
    echo "  xcrun simctl launch  booted com.kaodian.shell"
    ;;

  android) cargo tauri android build ;;
  *)       echo "未知目标:$TARGET(可选 desktop / ios / android / --check)" >&2; exit 1 ;;
esac
