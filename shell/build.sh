#!/usr/bin/env bash
# 壳的唯一构建入口(docs/technical/壳技术方案-Tauri2包现有Web工程.md §2.3)。
#
# 为什么不能直接 `cargo tauri build`:
#   Rust 是这个项目的第三条工具链,而 docs/technical/INDEX.md §一 的隔离红线当时只写了 Maven 和 npm。
#   `~/.cargo/config.toml` 里一行 `[source.crates-io] replace-with = "公司源"`
#   会让依赖静默走内网 —— 与 server/build.sh 拦的是同一件事,而且【在公司网络里不会报错】(R-111)。
#
# 顺序不是风格:
#   ① 隔离校验    —— 在下载任何东西之前
#   ② 能力边界扫描 —— 便宜,先跑
#   ③ web 构建     —— 走 web 自己的 npm run build,并当场校验 web/ server/ 零改动
#   ④ 产物校验     —— include_dir! 是编译期读盘,dist 不在时报的是一句 Rust 宏错误
#   ⑤ 规范         —— fmt + clippy(要 dist 在,才编得动)
#   ⑥ 测试
#   ⑦ 打包
#
# 用法:
#   ./build.sh              三道自检 + 打包(出 .app / .dmg)
#   ./build.sh --check      只跑三道自检,不打包(CI / 提交前)
set -euo pipefail
cd "$(dirname "$0")"

SHELL_DIR="$(pwd)"
# export:步骤 ③.5 的 python 堆文档要从环境里读它。
export REPO_ROOT="$(cd .. && pwd)"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

step() { printf '\n\033[1m── %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m✗ %s\033[0m\n\n' "$*" >&2; exit 1; }

# ══════════════════════════ ① 隔离校验 ══════════════════════════
step "① 隔离校验"

# —— 1.1 cargo 依赖源白名单(R-111)——
# 只看【生效的配置】,不看注释 —— 与 server/build.sh 同一条处理:
# 注释里为了说明反例会出现内网地址,那不是配置。
python3 - <<'PY'
import os, re, sys, urllib.parse, pathlib

ALLOWED_HOSTS = {
    # 官方
    "crates.io", "index.crates.io", "static.crates.io", "github.com",
    # 公共镜像(公开匿名、人人可用,只是地理加速;≠ 公司私服)
    "mirrors.tuna.tsinghua.edu.cn",
    "mirrors.ustc.edu.cn",
    "mirrors.aliyun.com",
    "mirrors.cloud.tencent.com",
    "repo.huaweicloud.com",
    "rsproxy.cn",
}

cargo_home = os.environ.get("CARGO_HOME") or os.path.expanduser("~/.cargo")
candidates = [
    pathlib.Path(cargo_home) / "config.toml",
    pathlib.Path(cargo_home) / "config",
    pathlib.Path("../.cargo/config.toml"),
    pathlib.Path(".cargo/config.toml"),
]

bad = []
seen = []
for p in candidates:
    if not p.is_file():
        continue
    seen.append(str(p))
    raw = p.read_text(encoding="utf-8", errors="replace")
    effective = re.sub(r"#.*$", "", raw, flags=re.M)      # 剥注释
    for url in re.findall(r'["\']((?:sparse\+)?https?://[^"\']+)["\']', effective):
        clean = url[len("sparse+"):] if url.startswith("sparse+") else url
        parsed = urllib.parse.urlparse(clean)
        host = parsed.hostname or ""
        if host not in ALLOWED_HOSTS:
            bad.append(f"{p}: {url}  (host={host})")
        if parsed.scheme == "http":
            bad.append(f"{p}: {url}  (明文 http,公共镜像应走 https)")
    if re.search(r"\btoken\s*=|\[registries\.[^\]]+\]\s*\n[^\[]*token", effective):
        bad.append(f"{p}: 生效配置里出现 token —— 公共镜像不需要凭据,出现凭据说明指向了私服")

if bad:
    print("拒绝构建 —— cargo 依赖源未通过隔离校验(R-111 / docs/technical/INDEX.md §1.3):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    print("\n  公共镜像 ≠ 公司私服:前者公开匿名、人人可用;后者在内网、要公司凭据。", file=sys.stderr)
    sys.exit(1)

print(f"  ✓ cargo 依赖源:{'、'.join(seen) if seen else '无本地覆盖配置(走官方源)'}")
PY

# —— 1.2 依赖黑名单(docs/technical/壳技术方案-Tauri2包现有Web工程.md §六)——
# 同时扫 Cargo.toml 与 Cargo.lock:只扫前者的话,一个传递依赖就能绕过去。
python3 - <<'PY'
import re, sys, pathlib

# 单词段匹配(name 按 - 或 _ 切开之后逐段比),避免 "s3" 这类短串误伤。
SEGMENTS = {
    # ① 模型 SDK —— 学科判断整个外包给用户自己接的模型;调模型的地方只有
    #    server 的 recognize 与 agent.llm 两处(docs/technical/后端系统设计与组件接入.md §二)。壳不调任何模型。
    "openai", "anthropic", "claude", "gemini", "ollama", "langchain",
    "tiktoken", "dashscope", "zhipu", "qianfan", "replicate",
    # ② 崩溃上报 / 遥测 —— 它会把数据送出这台机器,而红线的原话是「只存在他自己的机器上」。
    "sentry", "opentelemetry", "otlp", "posthog", "datadog", "bugsnag",
    "crashlytics", "mixpanel", "amplitude", "rollbar", "honeycomb",
    # ③ 云存储 / 同步 —— 原图绝不上云、不同步、不共享。
    "s3", "oss", "rusoto", "qiniu", "upyun", "minio", "dropbox", "onedrive",
}
PREFIXES = ("aws-sdk", "azure_storage", "azure-storage", "google-cloud", "gcloud", "alibaba-cloud")

names = set()
lock = pathlib.Path("Cargo.lock")
if lock.is_file():
    names |= set(re.findall(r'^name = "([^"]+)"', lock.read_text(encoding="utf-8"), flags=re.M))
manifest = pathlib.Path("Cargo.toml").read_text(encoding="utf-8")
manifest_effective = re.sub(r"#.*$", "", manifest, flags=re.M)
names |= set(re.findall(r"^\s*([A-Za-z0-9_-]+)\s*=", manifest_effective, flags=re.M))

hits = []
for n in sorted(names):
    segs = set(re.split(r"[-_]", n.lower()))
    if segs & SEGMENTS or n.lower().startswith(PREFIXES):
        hits.append(n)

if hits:
    print("拒绝构建 —— 依赖黑名单命中(docs/technical/壳技术方案-Tauri2包现有Web工程.md §六):", file=sys.stderr)
    for h in hits:
        print("  ✗ " + h, file=sys.stderr)
    print("\n  壳不调用任何外部模型,不上报任何遥测,不碰任何云存储。", file=sys.stderr)
    print("  想放行某一个,要动的是这张表和它上面那段理由,不是往依赖里再加一个。", file=sys.stderr)
    sys.exit(1)
print(f"  ✓ 依赖黑名单:{len(names)} 个 crate 名,零命中")
PY

# —— 1.3 三端隔离越界 + 1.4 与主业公司零交集 ——
#
# 🔴 这两条都【先剥注释再比】,不是原始 grep。
#
# docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.3 写的判据是 `grep -rn 'cfg(target_os' shell/src --exclude-dir=platform`,
# 而这条原样照抄的 grep 会红在【本仓库自己的合规注释】上 —— src/main.rs 与
# src/local_server.rs 里各有一行「这里没有 cfg(target_os)」的声明,声明为了讲清楚
# 必然要把被禁的那个串写出来。这不是新发现:docs/ops/INDEX.md §9.10 记的三条设计教训第一条
# 就是「黑名单不能匹配本仓库自己写的合规声明」,而 server/build.sh 处理 XML 时
# 也是先剥注释再取 <url>。同一条处理,第三次出现。
#
# 剥注释【不会放过真的越界】:Rust 属性是代码,永远不可能出现在 // 之后。
python3 - <<'PY'
import re, sys, pathlib

bad = []

# ① cfg(target_os) 只允许出现在 src/platform/ 下
for p in sorted(pathlib.Path("src").rglob("*.rs")):
    if "platform" in p.parts:
        continue
    for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        code = re.sub(r"//.*$", "", line)          # 剥行注释
        if "cfg(target_os" in code:
            bad.append(f"{p}:{i}  {line.strip()}")
if bad:
    print("拒绝构建 —— cfg(target_os) 出现在 src/platform/ 之外,三端隔离已经破了(docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.3):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
print("  ✓ cfg(target_os) 只出现在 src/platform/ 下(已剥注释)")

# ② 与主业公司零交集(docs/technical/INDEX.md §1.5)
#
# 跳过 build.sh 自己:这两个词是它的模式串,扫自己必然命中。
# 与上面同一条教训 —— 断言不能红在断言本身上。
TERMS = ("fenbi", "粉笔")
SKIP_DIRS = {"target", "gen", "node_modules"}
hits = []
for p in sorted(pathlib.Path(".").rglob("*")):
    if not p.is_file() or set(p.parts) & SKIP_DIRS:
        continue
    if p.name == "build.sh":
        continue
    try:
        text = p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue                                    # 二进制(图标等)不扫
    for i, line in enumerate(text.splitlines(), 1):
        if any(t in line.lower() for t in TERMS):
            hits.append(f"{p}:{i}  {line.strip()[:120]}")
if hits:
    print("拒绝构建 —— 壳里出现了主业公司相关字样(docs/technical/INDEX.md §1.5):", file=sys.stderr)
    for h in hits:
        print("  ✗ " + h, file=sys.stderr)
    sys.exit(1)
print("  ✓ 主业公司零交集")
PY

# —— 1.6 本机原图存储的三条结构约束(KUBI-63 / R-105)——
#
# 🔴 2026-08-31 之前,壳「不读请求体」,所以它结构上不可能存下任何学习内容。
# 加上 /__local/rawimages 之后这句话【只对 /api/* 成立】—— 壳会把原图写到磁盘上。
# 换来的能力必须付代价,代价是下面这三条,它们让新增的那条路【可数、有名、跑不出去】。
#
# 与上面 1.3 / 1.4 同一条处理:先剥注释、并且只看 `#[cfg(test)]` 之前的部分。
# docs/ops/INDEX.md §9.10 那条教训第四次出现 —— 断言不能红在断言自己的说明上,
# 也不能红在【为了证明这条约束成立而写的测试】上:
# raw_image_store.rs 的测试里必然出现 expiresAt,那正是它证明「原样进、原样出」的方式。
python3 - <<'PY'
import re, sys, pathlib

def production_code(p):
    """剥掉行注释,并在第一个 #[cfg(test)] 处截断(测试模块按 Rust 惯例在文件末尾)。"""
    lines = []
    for line in p.read_text(encoding="utf-8").splitlines():
        if "#[cfg(test)]" in line:
            break
        lines.append(re.sub(r"//.*$", "", line))
    return lines

FILES = sorted(pathlib.Path("src").rglob("*.rs"))
bad = []

# ① 壳读不到 expiresAt / storedAt —— 于是「该不该转归档」在壳里【写不出来】。
#    元信息对壳是一块不透明的 JSON,它只认识 id(文件名)和 archivedAt(只写不读)。
for p in FILES:
    for i, code in enumerate(production_code(p), 1):
        for term in ("expiresAt", "storedAt"):
            if term in code:
                bad.append(f"① {p}:{i}  出现 {term} —— 判据只有 rawImageCache.ts 一份")
if bad:
    print("拒绝构建 —— 壳读到了它不该认识的字段(docs/technical/原图存储-判据层与存储层.md §9.3):", file=sys.stderr)
    for b in bad: print("  ✗ " + b, file=sys.stderr)
    print("\n  壳只认识 id 与 archivedAt。多认识一个字段,「到期判据只有一份」就不再由结构保证。", file=sys.stderr)
    sys.exit(1)
print("  ✓ 壳读不到 expiresAt / storedAt(已剥注释、已排除测试模块)")

# ② 写盘只在两个模块里。原图是壳第一次往磁盘上写用户的东西,写入点必须可数且各有其名。
WRITE_ALLOWED = {"src/raw_image_store.rs", "src/config.rs"}
WRITE_CALLS = ("File::create", "fs::write", "OpenOptions", "create_dir_all", "fs::rename")
bad = []
for p in FILES:
    if p.as_posix() in WRITE_ALLOWED:
        continue
    for i, code in enumerate(production_code(p), 1):
        for c in WRITE_CALLS:
            if c in code:
                bad.append(f"② {p}:{i}  {c}")
if bad:
    print("拒绝构建 —— 壳的写盘点跑出了那两个模块(docs/technical/原图存储-判据层与存储层.md §9.3):", file=sys.stderr)
    for b in bad: print("  ✗ " + b, file=sys.stderr)
    print("\n  写盘只允许在 raw_image_store.rs(原图)与 config.rs(端口)里。", file=sys.stderr)
    print("  多一个没人知道的写入点,「原图只在用户自己的机器上」就少一道防线。", file=sys.stderr)
    sys.exit(1)
print(f"  ✓ 写盘点只在 {' / '.join(sorted(WRITE_ALLOWED))}")

# ③ 原图那一层没有网络出口 —— 与 scheduler.rs 那条「不允许发起任何网络请求」同一手法。
#    一个能上网的原图存储层就是红线「绝不上云、不同步、不共享」的现成破口。
NET = ("hyper_util::client", "hyper::client", "reqwest", "TcpStream", "UdpSocket", "hyper_rustls")
store = pathlib.Path("src/raw_image_store.rs")
bad = [f"③ {store}:{i}  {n}" for i, code in enumerate(production_code(store), 1) for n in NET if n in code]
if bad:
    print("拒绝构建 —— 原图存储层出现了网络出口(docs/technical/原图存储-判据层与存储层.md §9.3):", file=sys.stderr)
    for b in bad: print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
print("  ✓ raw_image_store.rs 无网络出口")
PY

# —— 1.5 目标三元组的 std 装没装 ——
case "$(uname -m)" in
  arm64|aarch64) TARGET="aarch64-apple-darwin" ;;
  x86_64)        TARGET="x86_64-apple-darwin" ;;
  *) die "未知的机器架构 $(uname -m) —— 本壳的范围是 macOS(KUBI-61)" ;;
esac
if ! rustup target list --installed 2>/dev/null | grep -qx "$TARGET"; then
  die "缺 $TARGET 的标准库。装它:rustup target add $TARGET
  (不自动装:改动别人机器上的工具链应当是一次显式的决定)"
fi
echo "  ✓ 目标三元组 $TARGET"

# ══════════════════════════ ② 能力边界文案扫描 ══════════════════════════
step "② 能力边界文案扫描(R-05)"
node scripts/capability-boundary-scan.mjs

# ══════════════════════════ ③ web 构建 ══════════════════════════
step "③ web 构建(走 web 自己的构建脚本)"
# 🔴 必须真的 cd 进去,不能用 npm --prefix:npm 读的是【当前工作目录】的 .npmrc。
(
  cd "$REPO_ROOT/web"
  npm ci --silent
  npm run build
)

# 🔴 server/ 零改动 —— 这条一个字没变。
# server/ 有 diff = 选了 docs/technical/壳技术方案-Tauri2包现有Web工程.md §3.2 的 E 方案(往 CORS 白名单里加 tauri://),而那条没被选。
if [ -d "$REPO_ROOT/.git" ] || git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  DIRTY="$(git -C "$REPO_ROOT" status --porcelain -- server)"
  if [ -n "$DIRTY" ]; then
    printf '%s\n' "$DIRTY" >&2
    die "server/ 有改动 —— 壳不许要求服务端配合(docs/technical/壳技术方案-Tauri2包现有Web工程.md §十)"
  fi
  echo "  ✓ server/ 零改动"
fi

# ══════════════════════════ ③.5 形态分支可数 ══════════════════════════
#
# 🔴 2026-08-31:这一条【替换】了原先的「web/ 零改动」(docs/technical/壳技术方案-Tauri2包现有Web工程.md §十 / §2.5)。
#
# 原判据是一个【代理】:它真正想说的是「壳不许把 web 改出第二套」,
# 而当形态分支的数量是 0 时,「一行都不改」恰好等价于这句话,还便宜得多。
#
# KUBI-63 之后那个数字必须是 1 —— 壳形态要用文件系统,判据层就得知道该拿哪个后端,
# 而这件事没有任何办法在不碰 web/ 的前提下完成(壳没有 IPC,web 也不该为壳装一个)。
# 代理一旦不再等价于它代理的那句话,该做的是【换成直接断言那句话】,不是把它删掉:
#
#   旧:web/ 的 git diff 必须是空的         —— 数字是 0 时成立,是 1 时永远红
#   新:形态判断必须【恰好只在一个文件里】  —— 数字是几都成立,而且直接说的就是那句话
#
# 新判据比旧判据强的地方:旧判据挡不住「在 rawImageStore.ts 里写十个 if」,
# 也挡不住有人在界面组件里加一句 window.__TAURI__ —— 只要那次改动被一起提交。
step "③.5 形态分支可数(docs/technical/原图存储-判据层与存储层.md §3.1)"
python3 - <<'PY'
import re, sys, pathlib, os

WEB_SRC = pathlib.Path(os.environ["REPO_ROOT"]) / "web" / "src"
# 🔴 唯一允许出现形态判断的文件。它就是 docs/technical/原图存储-判据层与存储层.md §3.1 那个「唯一注入点」。
INJECTION_POINT = "lib/rawImageStore.ts"
# 线协议只允许出现在文件系统实现里。
WIRE_OWNER = "lib/rawImageFs.ts"

def code_lines(p):
    """剥掉行注释与 JSDoc —— docs/ops/INDEX.md §9.10 第五次:断言不能红在自己的说明上。
    这些文件的注释要么整行以 // 开头,要么在 /** … */ 块里以 * 开头。"""
    out = []
    for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        s = line.strip()
        if s.startswith(("//", "*", "/*")):
            continue
        out.append((i, re.sub(r"//.*$", "", line)))
    return out

# ① 形态判断:调用点必须恰好一处,而且在注入点那个文件里。
CALL = "localRawImageStoreAvailable("
callers, sniffs = [], []
# 只列【不可能有第二种用法】的串。`navigator.userAgent` 不在这里 ——
# `api/auth.ts` 拿它做登录会话的设备名(「Mac · Chrome」),那和存储形态无关,
# 而一条天天误报的断言两天内就会被关掉,等于从来没有过
# (web/scripts/capability-boundary-scan.mjs 顶部那段讲的是同一件事)。
SNIFF = ("__TAURI", "isTauriShell")
for p in sorted(WEB_SRC.rglob("*.ts")) + sorted(WEB_SRC.rglob("*.tsx")):
    rel = p.relative_to(WEB_SRC).as_posix()
    for i, code in code_lines(p):
        if CALL in code and rel != WIRE_OWNER:      # 定义处不算调用
            callers.append(f"{rel}:{i}")
        for s in SNIFF:
            if s in code:
                sniffs.append(f"{rel}:{i}  {s}")
        if "/__local/" in code and rel != WIRE_OWNER:
            sniffs.append(f"{rel}:{i}  线协议路径跑出了 {WIRE_OWNER}")

bad = []
if len(callers) != 1 or not callers[0].startswith(INJECTION_POINT + ":"):
    bad.append(f"形态判断有 {len(callers)} 处,应当恰好 1 处且在 {INJECTION_POINT}:{callers}")
bad += sniffs

# ② 真时钟只有一处 —— 这条性质随注入点一起从 rawImageDb.ts 搬过来,不因搬家而失效。
clocks = [f"{p.relative_to(WEB_SRC).as_posix()}:{i}"
          for p in sorted(WEB_SRC.glob("lib/rawImage*.ts"))
          for i, code in code_lines(p) if "Date.now()" in code]
if clocks != [c for c in clocks if c.startswith(INJECTION_POINT + ":")] or len(clocks) != 1:
    bad.append(f"原图链路上的 Date.now() 有 {len(clocks)} 处,应当恰好 1 处且在 {INJECTION_POINT}:{clocks}")

if bad:
    print("拒绝构建 —— 形态分支不再可数(docs/technical/原图存储-判据层与存储层.md §3.1):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    print("\n  壳带来的是 RawImageBackend 的第二个实现,不是第二套逻辑。", file=sys.stderr)
    print("  形态判断多一处,能力边界就少一道防线 —— 要加的话,先改 docs/technical/原图存储-判据层与存储层.md §3.2 那张穷举表。", file=sys.stderr)
    sys.exit(1)
print(f"  ✓ 形态判断恰好 1 处({callers[0]});真时钟恰好 1 处({clocks[0]})")
PY

# ③.6 判据层仍然跑得进 node —— 22+ 条断言 + 后端契约 + 迁移,一条都不许因为这次改动掉队。
(
  cd "$REPO_ROOT/web"
  npm run test:retention
)

# ══════════════════════════ ④ 产物校验 ══════════════════════════
step "④ 产物校验"
DIST="$REPO_ROOT/web/dist"
[ -f "$DIST/index.html" ] || die "找不到 $DIST/index.html —— 前端没构建出来。
  (不先校验的话,下一步 include_dir! 报的是一句 Rust 宏错误,和「前端没构建」对不上号)"
grep -q '/assets/' "$DIST/index.html" || die "dist/index.html 里没有引用 /assets/* ——
  资源路径不是根绝对路径的话,壳里的回环直出会 404(docs/technical/壳技术方案-Tauri2包现有Web工程.md §2.2 事实 1)"
echo "  ✓ dist/index.html 存在且引用 /assets/*"

# ══════════════════════════ ⑤ 规范 ══════════════════════════
step "⑤ 规范:fmt + clippy"
cargo fmt --check
cargo clippy --target "$TARGET" --all-targets -- -D warnings
echo "  ✓ fmt 与 clippy 全绿"

# ══════════════════════════ ⑥ 测试 ══════════════════════════
step "⑥ 测试"
cargo test --target "$TARGET"

if [ "$CHECK_ONLY" = 1 ]; then
  printf '\n\033[32m三道自检全绿(规范 / 构建 / 能力边界文案扫描)。未打包 —— 加 --check 时不打包。\033[0m\n\n'
  exit 0
fi

# ══════════════════════════ ⑦ 打包 ══════════════════════════
step "⑦ 打包"
# 🔴 tauri.conf.json5 里 beforeBuildCommand 是空串,所以这一步不会再跑一次 npm ——
# 那正是要的效果:构建路径只有一条,而它从 ① 开始。
cargo tauri build --target "$TARGET"

APP="$SHELL_DIR/target/$TARGET/release/bundle/macos/考点盲区.app"
[ -d "$APP" ] || die "没出 .app —— 打包这一步没有产出可安装的应用"
printf '\n\033[32m打包完成\033[0m\n  %s\n' "$APP"
printf '\n  拖进「应用程序」即可,双击能开。\n'
printf '  🔴 ad-hoc 签名,自用。首次打开走一次「右键 → 打开」。\n'
printf '     分发给别人才需要 Developer ID + 公证,而本轮没有分发(docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.4)。\n'
printf '     不出 .dmg 的理由写在 tauri.conf.json5 的 bundle.targets 旁边。\n\n'
