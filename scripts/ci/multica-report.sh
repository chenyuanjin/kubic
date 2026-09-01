#!/usr/bin/env bash
# 闸 1(机器闸)判决回写 —— docs/ops/INDEX.md §9.2 五键契约 + §9.6 属性
#
#   预演:  ./scripts/ci/multica-report.sh --dry-run --branch KUBI-12-offline-queue \
#              --backend success --frontend failure
#   CI:    由 .github/workflows/ci.yml 的 report job 调用
#
# ── 为什么这个脚本【任何情况下都 exit 0】 ──
#   闸 1 的判决是测试结果本身,回写只是投递方式。
#   投递失败让 CI 变红,等于「Multica 连不上」被读成「测试没过」—— 判决被投递
#   通道污染,而这条通道今天连通不通都还没验证过(docs/ops/INDEX.md §八:GitHub Actions
#   出口能否访问 62.234.164.41:8080,登记在待确认表里)。
#   所以这里所有失败路径都是「警告 + exit 0」,没有一条是 exit 1。
#
# ── 为什么不用 set -e ──
#   set -e 会让第一条失败的 multica 命令直接终止脚本,退出码原样冒出去 = CI 变红。
#   要的恰好相反:每一步都要有机会降级。所以只开 -u 和 pipefail,退出码手工控制。
set -uo pipefail
cd "$(dirname "$0")/../.."

KUBICC="903c14c4-e5de-4e47-b3bc-3412818f4fa6"
SERVER_URL="${MULTICA_SERVER_URL:-http://62.234.164.41:8080}"
PROBE_TIMEOUT=10

DRY=0
BRANCH=""
ISSUE=""
BACKEND=""
FRONTEND=""
WSID="$KUBICC"

say()  { printf '  \033[2m%s\033[0m\n' "$*" >&2; }
warn() { printf '  \033[33m⚠ %s\033[0m\n' "$*" >&2; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)      DRY=1; shift ;;
    --branch)       BRANCH="${2:-}"; shift 2 ;;
    --issue)        ISSUE="${2:-}"; shift 2 ;;
    --backend)      BACKEND="${2:-}"; shift 2 ;;
    --frontend)     FRONTEND="${2:-}"; shift 2 ;;
    --workspace-id) WSID="${2:-}"; shift 2 ;;
    -h|--help)      sed -n '2,8p' "$0"; exit 0 ;;
    # 未知参数也不报错退出:这个脚本的合同是「绝不染红 CI」,把它破在参数校验上很蠢。
    *)              warn "忽略未知参数: $1"; shift ;;
  esac
done

# ══════════════ 1. 议题号 ══════════════
# docs/ops/INDEX.md §9.3:分支形如 KUBI-12-offline-queue。Multica 的 PR ↔ 议题关联就是靠
# 这个字符串。解析不出来是【正常情况】不是错误 —— main / v1 是明文规定的例外分支,
# 临时分支也不一定对应议题。所以这条路径干净退出,不打警告、不返回非零。
if [[ -z "$BRANCH" ]]; then
  # GITHUB_HEAD_REF 只在 pull_request 事件上有值,且是源分支;
  # 同一场景下 GITHUB_REF_NAME 是 "42/merge",里面永远没有 KUBI 号。顺序不能反。
  BRANCH="${GITHUB_HEAD_REF:-}"
  [[ -z "$BRANCH" ]] && BRANCH="${GITHUB_REF_NAME:-}"
  [[ -z "$BRANCH" ]] && BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
fi

if [[ -z "$ISSUE" ]]; then
  ISSUE="$(printf '%s' "$BRANCH" | grep -Eio 'KUBI-[0-9]+' | head -1 | tr '[:lower:]' '[:upper:]')"
fi
if [[ -z "$ISSUE" ]]; then
  # 兜底:commit-msg hook 会补 `Refs: KUBI-<n>` trailer(§9.3),分支名没带时它还在。
  ISSUE="$(git log -1 --pretty='%B' 2>/dev/null | grep -Eio 'KUBI-[0-9]+' | head -1 | tr '[:lower:]' '[:upper:]')"
fi

if [[ -z "$ISSUE" ]]; then
  say "分支「${BRANCH:-?}」没有 KUBI-<n>,不对应议题,跳过回写。"
  exit 0
fi

# ══════════════ 2. 判决 ══════════════
# 取消不是判决。run 被 cancel 时上游 job 的 result 是 cancelled —— 此时写
# verdict=false 就是把「没跑完」记成「没通过」,分诊器会照着它把议题打回开发 agent。
for r in "$BACKEND" "$FRONTEND"; do
  if [[ "$r" == "cancelled" ]]; then
    say "有 job 被取消,本次没有判决可写,跳过。"
    exit 0
  fi
done

# repro_cmd 必须能从仓库根直接跑,且不得出现绝对路径(§9.9 第 3 个口子:
# 有人写过 /Users/chenyuanjin/multica_workspaces_.../server,换台机器复现不了)。
BACKEND_CMD='./server/build.sh -q test'
FRONTEND_CMD='npm --prefix web ci && npm --prefix web run lint && npm --prefix web run build'

BLOCKING=0
FAILED_CMDS=()
[[ "$BACKEND"  != "success" ]] && { BLOCKING=$((BLOCKING+1)); FAILED_CMDS+=("$BACKEND_CMD"); }
[[ "$FRONTEND" != "success" ]] && { BLOCKING=$((BLOCKING+1)); FAILED_CMDS+=("$FRONTEND_CMD"); }

if [[ $BLOCKING -eq 0 ]]; then
  VERDICT=true
  REPRO="$BACKEND_CMD && $FRONTEND_CMD"
else
  VERDICT=false
  REPRO="${FAILED_CMDS[0]}"
  [[ ${#FAILED_CMDS[@]} -eq 2 ]] && REPRO="${FAILED_CMDS[0]} && ${FAILED_CMDS[1]}"
fi

say "议题 $ISSUE ← verdict=$VERDICT  blocking_count=$BLOCKING  (backend=${BACKEND:-?} frontend=${FRONTEND:-?})"

# ══════════════ 3. 命令表 ══════════════
# 先把要跑的命令摆出来再跑,预演与实跑走同一张表 —— 否则 --dry-run 印出来的
# 和真跑的迟早会分叉,而分叉的那天你正好在信任预演。
# repro_cmd 一律 --type string:裸值里有 / 和空格,自动类型推断不该赌它猜对(§9.2 第 2 条)。
run_multica() {
  if [[ $DRY == 1 ]]; then
    # 逐个转义,不用 printf '%q':repro_cmd 里带空格和 &&,"$*" 是复制不回去的;
    # 而 bash 3.2(macOS 自带)的 %q 会把「闸门」这种中文逐字节转成 $'\227...',
    # 命令能跑但人读不了 —— 预演的全部价值就是给人读。所以只在需要时套一层单引号。
    printf '  \033[2m$ multica --workspace-id %s ' "$WSID" >&2
    for a in "$@"; do
      case "$a" in
        *[!A-Za-z0-9_./:=-]*) printf "'%s' " "$(printf '%s' "$a" | sed "s/'/'\\\\''/g")" >&2 ;;
        *)                    printf '%s ' "$a" >&2 ;;
      esac
    done
    printf '\033[0m\n' >&2
    return 0
  fi
  multica --workspace-id "$WSID" "$@" >/dev/null 2>&1
}

do_report() {
  run_multica issue metadata set "$ISSUE" --key verdict        --value "$VERDICT"                 || return 1
  run_multica issue metadata set "$ISSUE" --key blocking_count --value "$BLOCKING"                || return 1
  run_multica issue metadata set "$ISSUE" --key repro_cmd      --type string --value "$REPRO"     || return 1
  # 属性,不是标签。§9.9 第 1 个口子:有 agent 把属性读成标签,自己新建了一个野标签。
  run_multica issue property set "$ISSUE" --name "闸门" --value "机器闸"                          || return 1
  return 0
}

# ══════════════ 4. 预演 ══════════════
# 预演放在 token / 连通性检查【之前】:它必须在任何一台机器上都能跑通,
# 包括没装 CLI、没登录、连不上 server 的那台。只能在 CI 里跑的预演等于没有预演。
if [[ $DRY == 1 ]]; then
  say "预演(不发任何请求):"
  do_report
  ok "预演结束,未改动任何议题。"
  exit 0
fi

# ══════════════ 5. 三道降级 ══════════════
if ! command -v multica >/dev/null 2>&1; then
  warn "runner 上没有 multica CLI,跳过回写(判决仍在上面两个 job 的红绿里)。"
  exit 0
fi

# token 缺失是警告不是失败:secret 没配好属于配置问题,不该被读成测试不通过。
# 也绝不硬编码兜底 token —— 那样一来仓库里就有凭据了,pre-commit 拦的正是这个。
if [[ -z "${MULTICA_TOKEN:-}" ]]; then
  warn "MULTICA_TOKEN 未设置,跳过回写。CI 用独立 token,不要复用 task token(§5.1)。"
  exit 0
fi

# 连通性单独探一次,不靠 multica 命令自己超时:CLI 的重试与超时行为没验证过,
# 打不通时可能挂满整个 job 的时限。curl --max-time 是这里唯一能自己定死的上限。
# 判据是 curl 的退出码,不是 HTTP 状态码 —— 根路径返回 404 也说明网络是通的。
if ! curl -sS -o /dev/null --max-time "$PROBE_TIMEOUT" "$SERVER_URL" >/dev/null 2>&1; then
  warn "连不上 $SERVER_URL(${PROBE_TIMEOUT}s 超时),跳过回写。"
  say "docs/ops/INDEX.md §八 登记的待确认项:GitHub Actions 出口能否访问该端口。打不通就退回本地 runner。"
  exit 0
fi

# ══════════════ 6. 回写 ══════════════
if do_report; then
  ok "已回写 $ISSUE:verdict=$VERDICT / blocking_count=$BLOCKING / 闸门=机器闸"
else
  warn "回写 $ISSUE 失败(token 无权限、议题不存在、或字段被改名),跳过。"
fi
exit 0
