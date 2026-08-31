#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# kubicc:关掉与本项目无关的 runtime 技能 · 2026-08-31
#
#   agent 的技能不是在 Multica 里分配的(`multica skill list` 是空的),
#   是从本机 runtime 全局继承的。要关,得逐个 agent 逐个技能地关。
#
#   ⚠️ 这一项 multica CLI 没有对应命令 —— v0.4.36(已是最新)的
#      `agent update` 没有 --disabled-runtime-skills,也没有隐藏 flag;
#      `PUT /api/agents/<id>` 收下这个字段但静默丢弃。
#      唯一的写入口是 Web UI 用的那个:
#         PUT /api/agents/<id>/runtime-skills/enabled
#         {runtime_id, root, key, name, plugin?, enabled:false}   # 一次一个技能
#      所以读走 CLI,写走这个接口。CLI 补上命令之后,这里应当换回去。
#
#   预演: ./scripts/multica-agent-skills.sh --dry-run
#   执行: ./scripts/multica-agent-skills.sh --yes
#
#   幂等:已经关掉的跳过,不重复调。
#   只加不减:脚本不会把别人手工关掉的技能重新打开,多出来的只报告。
#   范围:只处理绑在 Claude runtime 上的 agent —— 另外三个 runtime
#         (Codex / Opencode / Reasonix)不读 ~/.claude/skills,技能池不同,
#         照搬这份清单没有意义,脚本会跳过并列出来。
#
#   与仓库内 .claude/settings.json 的关系:那是第一道(在这个仓库里干活时
#   技能根本调不动),这是第二道(agent 层面根本看不见这些技能)。两道都要。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6
CLAUDE_RT=4c9548ca-c4c9-455d-a1ae-de062f3826fb

DRY=0; YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    -y|--yes)  YES=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
if [[ $DRY == 0 && $YES == 0 ]]; then
  echo "✗ 拒绝执行:写操作必须显式 --yes(无 TTY 时不做交互确认)" >&2; exit 1
fi

command -v multica >/dev/null || { echo "✗ 找不到 multica CLI" >&2; exit 1; }
CFG="$HOME/.multica/config.json"
[[ -f "$CFG" ]] || { echo "✗ 找不到 $CFG" >&2; exit 1; }

say()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*" >&2; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$*" >&2; }
skip() { printf '  \033[2m· %s\033[0m\n'  "$*" >&2; }
bad()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

say "读取 agent 清单(走 CLI)"
multica --workspace-id "$WS" agent list --output json > "$TMP/agents.json" 2>"$TMP/e" \
  || { bad "agent list 失败:$(head -c 200 "$TMP/e")"; exit 1; }

DRY="$DRY" WS="$WS" CLAUDE_RT="$CLAUDE_RT" CFG="$CFG" python3 - "$TMP/agents.json" <<'PY'
import json, os, sys, urllib.request, urllib.error

DRY   = os.environ["DRY"] == "1"
WS    = os.environ["WS"]
RT    = os.environ["CLAUDE_RT"]
cfg   = json.load(open(os.environ["CFG"]))
TOK, SRV = cfg["token"], cfg["server_url"].rstrip("/")

# ── 要关掉的技能 ────────────────────────────────────────────
# 判据:与「跨来源学习记录工具」的交付无关。留下的是开发/浏览器/终端类
# (agent-browser / opencli 等)和内置技能。
PROVIDER = [                      # ~/.claude/skills 下的个人技能
    ("tmux-ide",                "tmux-ide"),
    ("dreamina",                "dreamina-cli"),
    ("fanqie-publish",          "fanqie-publish"),
    ("inkos",                   "inkos"),
    ("remotion-best-practices", "remotion-best-practices"),
    ("skill-creator",           "skill-creator"),
    ("finance-blogger-digest",  "finance-blogger-digest"),
    ("finance-blogger-video",   "finance-blogger-video"),
    ("finance-blogger-watch",   "finance-blogger-watch"),
    ("finance-blogger-youtube", "finance-blogger-youtube"),
    ("finance-daily",           "finance-daily"),
    ("finance-daily-v2",        "finance-daily-v2"),
    ("finance-screener",        "finance-screener"),
    ("finance-stock-deep",      "finance-stock-deep"),
    ("finance-weekly",          "finance-weekly"),
]
# 主业公司内部插件包。2026-08-31 人的决定:tech-dept / zhibo / funny-share
# 三个保持开启,只关 live(见 docs/08 R-113 批注)。
PLUGIN = [("live@ai-skills", k, k) for k in (
    "live:fenbi-gitlab-tori-migrate",
    "live:fenbi-live-coding-standard",
    "live:fenbi-live-jdk21-upgrade",
    "live:fenbi-live-troubleshoot",
    "live:fenbi-sentry-upgrade",
)]

WANT = ([{"root": "provider", "key": k, "name": n} for k, n in PROVIDER]
        + [{"root": "plugin", "plugin": p, "key": k, "name": n} for p, k, n in PLUGIN])


def put(agent_id, item):
    body = dict(item, runtime_id=RT, enabled=False)
    req = urllib.request.Request(
        "%s/api/agents/%s/runtime-skills/enabled?workspace_id=%s" % (SRV, agent_id, WS),
        data=json.dumps(body).encode(), method="PUT",
        headers={"Authorization": "Bearer " + TOK, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status


def ident(d):
    return (d.get("root"), d.get("key"), d.get("plugin") or "")


agents = json.load(open(sys.argv[1]))
mine   = [a for a in agents if a.get("runtime_id") == RT and not a.get("archived_at")]
other  = [a for a in agents if a.get("runtime_id") != RT and not a.get("archived_at")]

err = 0
for a in mine:
    have  = {ident(d) for d in (a.get("disabled_runtime_skills") or [])}
    todo  = [w for w in WANT if ident(w) not in have]
    extra = have - {ident(w) for w in WANT}
    if not todo:
        print("  \033[2m· %s 已是 %d 条,跳过\033[0m" % (a["name"], len(have)), file=sys.stderr)
    elif DRY:
        print("  \033[32m✓ [预演] %s 补 %d 条(现有 %d)\033[0m"
              % (a["name"], len(todo), len(have)), file=sys.stderr)
    else:
        done = 0
        for w in todo:
            try:
                put(a["id"], w); done += 1
            except urllib.error.HTTPError as e:
                print("  \033[31m✗ %s / %s → HTTP %s\033[0m" % (a["name"], w["key"], e.code),
                      file=sys.stderr); err = 1
        print("  \033[32m✓ %s 关掉 %d 条,共 %d 条\033[0m"
              % (a["name"], done, len(have) + done), file=sys.stderr)
    if extra:
        print("     \033[2m(另有 %d 条不在本清单里,原样保留:%s)\033[0m"
              % (len(extra), ", ".join(sorted(k for _, k, _ in extra))), file=sys.stderr)

if other:
    print("\n  \033[2m跳过 %d 个非 Claude runtime 的 agent(技能池不同):%s\033[0m"
          % (len(other), " / ".join(a["name"] for a in other)), file=sys.stderr)
sys.exit(err)
PY
RC=$?

say "校验(走 CLI 读回)"
multica --workspace-id "$WS" agent list --output json 2>/dev/null | CLAUDE_RT="$CLAUDE_RT" python3 -c '
import sys, json, os
RT = os.environ["CLAUDE_RT"]
for a in json.load(sys.stdin):
    if a.get("archived_at"): continue
    n = len(a.get("disabled_runtime_skills") or [])
    tag = "" if a.get("runtime_id") == RT else "   (非 Claude runtime,不适用)"
    print("  %-14s %2d 条%s" % (a["name"], n, tag), file=sys.stderr)
' || true

exit $RC
