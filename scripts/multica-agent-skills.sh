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
#      走 Web UI 用的那两个接口:
#        目录:POST /api/runtimes/<rt>/local-skills  → 轮询 .../local-skills/<reqId>
#        写入:PUT  /api/agents/<id>/runtime-skills/enabled   # 一次一个技能
#      CLI 补上命令之后,这里应当换回去。
#
#   预演: ./scripts/multica-agent-skills.sh --dry-run
#   执行: ./scripts/multica-agent-skills.sh --yes
#
#   做法是【按名单过目录】,不是硬编码清单:每个 runtime 现问一次它有哪些技能,
#   再拿下面的判据去筛。runtime 装了新技能,下次跑就会被带上。
#
#   幂等:已经关掉的跳过。只加不减 —— 不会把手工关掉的重新打开,多的只报告。
#
#   与仓库内 .claude/settings.json 的关系:那是第一道(在这个仓库里技能调不动,
#   只对 Claude 生效),这是第二道(技能在 agent 层根本不出现,四种 runtime 都管)。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6

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

say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*" >&2; }
bad() { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

say "读取 agent 清单(走 CLI)"
multica --workspace-id "$WS" agent list --output json > "$TMP/agents.json" 2>"$TMP/e" \
  || { bad "agent list 失败:$(head -c 200 "$TMP/e")"; exit 1; }

DRY="$DRY" WS="$WS" CFG="$CFG" python3 - "$TMP/agents.json" <<'PY'
import json, os, sys, time, urllib.request, urllib.error

DRY = os.environ["DRY"] == "1"
WS  = os.environ["WS"]
cfg = json.load(open(os.environ["CFG"]))
TOK, SRV = cfg["token"], cfg["server_url"].rstrip("/")

G  = lambda s: "\033[32m%s\033[0m" % s
D  = lambda s: "\033[2m%s\033[0m"  % s
R  = lambda s: "\033[31m%s\033[0m" % s
say = lambda s: print(s, file=sys.stderr)

# ══════════ 判据:关掉哪些 ══════════
# 与「跨来源学习记录工具」的交付无关的个人技能。key 在四种 runtime 下通用
# (~/.agents/skills 是共享的 universal 根,所以同一个 key 会出现在多处)。
DENY_KEYS = {
    "dreamina", "fanqie-publish", "inkos", "remotion-best-practices",
    "skill-creator", "cli-creator", "tmux-ide",
    "finance-blogger-digest", "finance-blogger-video", "finance-blogger-watch",
    "finance-blogger-youtube", "finance-daily", "finance-daily-v2",
    "finance-screener", "finance-stock-deep", "finance-weekly",
}
# 主业公司内部插件包。2026-08-31 人的决定:tech-dept / zhibo / funny-share
# 保持开启,只关 live(见 docs/08 R-113 批注)。
DENY_PLUGIN_PREFIX = ("live@",)
# 非插件根(codex 的 ~/.codex/skills、共享的 ~/.agents/skills)下的公司内部技能。
# 这是与 ai-skills 插件【不同的另一份拷贝】,2026-08-31 单独决定关掉。
# 用前缀不用清单:公司那边再加一个 fenbi-xxx,下次跑就自动带上。
DENY_KEY_PREFIX = ("fenbi-",)
# 明确留着的:agent-browser / opencli / find-skills(浏览器、外部 CLI、技能检索)。


def deny(s):
    if s.get("can_disable") is False:
        return False
    if s.get("root") == "plugin":
        return str(s.get("plugin") or "").startswith(DENY_PLUGIN_PREFIX)
    k = s.get("key") or ""
    return k in DENY_KEYS or k.startswith(DENY_KEY_PREFIX)


def fallback_instruction(a, want, prov):
    """runtime 关不掉技能时的退路:把清单写进 instructions。声明式,弱。"""
    HEAD = "━━━━━━ 不要用的技能 ━━━━━━"
    keys = sorted({s["key"] for s in want})
    ins0 = (a.get("instructions") or "").rstrip()
    if not keys:
        # 判据一个都没命中(比如技能已经从磁盘上删掉了)——
        # 那段声明式清单就成了假话,撤掉。
        cut = ins0.find(HEAD)
        if cut == -1:
            say("  " + D("· %s 该 runtime 没有命中判据的技能" % a["name"]))
            return True
        if DRY:
            say("  " + G("✓ [预演] %s 撤掉已失效的声明式清单" % a["name"]))
            return True
        try:
            api("/api/agents/%s" % a["id"], "PUT",
                dict(a, instructions=ins0[:cut].rstrip()))
            say("  " + G("✓ %s 撤掉声明式清单 —— 那些技能已经不在这个 runtime 里了" % a["name"]))
            return True
        except urllib.error.HTTPError as e:
            say("  " + R("✗ %s 撤销失败 HTTP %s" % (a["name"], e.code)))
            return False
    block = (HEAD + "\n"
             "你的 runtime(%s)不支持在平台上禁用技能,所以这一条只能靠你自己守 ——\n"
             "下面这些技能与本项目无关,任何情况下都不要调用:\n  %s\n"
             "需要浏览器就用 agent-browser,需要驱动外部 CLI 就用 opencli。"
             % (prov, "、".join(keys)))
    ins = ins0
    cut = ins.find(HEAD)
    if cut != -1:
        ins = ins[:cut].rstrip()
    new_ins = (ins + "\n\n" + block).strip("\n")
    if new_ins == (a.get("instructions") or "").rstrip():
        say("  " + D("· %s 声明式清单已在 instructions 里,跳过" % a["name"]))
        return True
    if DRY:
        say("  " + G("✓ [预演] %s 写入声明式清单 %d 条(%s 关不掉)"
                     % (a["name"], len(keys), prov)))
        return True
    try:
        api("/api/agents/%s" % a["id"], "PUT", dict(a, instructions=new_ins))
        say("  " + G("✓ %s 声明式清单 %d 条已写进 instructions(%s runtime 关不掉,只能这样)"
                     % (a["name"], len(keys), prov)))
        return True
    except urllib.error.HTTPError as e:
        say("  " + R("✗ %s instructions 写入失败 HTTP %s" % (a["name"], e.code)))
        return False


def api(path, method="GET", body=None):
    req = urllib.request.Request(
        "%s%s%sworkspace_id=%s" % (SRV, path, "&" if "?" in path else "?", WS),
        data=None if body is None else json.dumps(body).encode(), method=method,
        headers={"Authorization": "Bearer " + TOK, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=40) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def catalog(rt_id):
    """现问 runtime 一次:你有哪些本地技能。两步 —— 发起 + 轮询。"""
    job = api("/api/runtimes/%s/local-skills" % rt_id, "POST")
    for _ in range(25):
        d = api("/api/runtimes/%s/local-skills/%s" % (rt_id, job["id"]))
        if d.get("status") not in ("pending", "running"):
            return d
        time.sleep(1.5)
    return {"status": "timeout", "skills": []}


# 服务端只对这两个 provider 开放技能禁用,其余 PUT 一律 400:
#   {"error":"runtime skill controls are only supported for codex and claude"}
CONTROLLABLE = {"claude", "codex"}
rt_provider = {r["id"]: r["provider"] for r in json.loads(
    os.popen("multica --workspace-id %s runtime list --output json" % WS).read() or "[]")}

agents = [a for a in json.load(open(sys.argv[1])) if not a.get("archived_at")]
rt_ids = sorted({a["runtime_id"] for a in agents})

say("\n\033[1;36m▸ 现问每个 runtime 有哪些技能\033[0m")
cats = {}
for rid in rt_ids:
    c = catalog(rid)
    cats[rid] = c
    prov = rt_provider.get(rid, "?")
    tag = "" if prov in CONTROLLABLE else "   ← 服务端不支持禁用"
    say("  %-9s %-9s status=%-9s 技能 %2d 个,命中判据 %2d 个%s"
        % (rid[:8], prov, c.get("status"), len(c.get("skills") or []),
           len([s for s in (c.get("skills") or []) if deny(s)]), tag))

say("\n\033[1;36m▸ 写回\033[0m")
err = 0
for a in agents:
    c = cats.get(a["runtime_id"]) or {}
    want = [s for s in (c.get("skills") or []) if deny(s)]
    have = {(d.get("root"), d.get("key"), d.get("plugin") or "")
            for d in (a.get("disabled_runtime_skills") or [])}
    todo = [s for s in want if (s.get("root"), s.get("key"), s.get("plugin") or "") not in have]
    extra = have - {(s.get("root"), s.get("key"), s.get("plugin") or "") for s in want}

    prov = rt_provider.get(a["runtime_id"], "?")
    if prov not in CONTROLLABLE:
        # 结构性防线在这台 server 上不存在,退到声明式 —— 写进 instructions。
        # ⚠️ 这只是【请求 agent 别用】,不是【让它用不了】,强度差一个量级。
        if not fallback_instruction(a, want, prov):
            err = 1
        continue
    if not want and not have:
        say("  " + D("· %s 该 runtime 没有命中判据的技能" % a["name"]))
    elif not todo:
        say("  " + D("· %s 已是 %d 条,跳过" % (a["name"], len(have))))
    elif DRY:
        say("  " + G("✓ [预演] %s 补 %d 条(现有 %d)" % (a["name"], len(todo), len(have))))
    else:
        done = 0
        for s in todo:
            body = {"runtime_id": a["runtime_id"], "root": s["root"], "key": s["key"],
                    "name": s.get("name") or s["key"], "enabled": False}
            if s.get("plugin"):
                body["plugin"] = s["plugin"]
            try:
                api("/api/agents/%s/runtime-skills/enabled" % a["id"], "PUT", body); done += 1
            except urllib.error.HTTPError as e:
                say("  " + R("✗ %s / %s → HTTP %s" % (a["name"], s["key"], e.code))); err = 1
        say("  " + G("✓ %s 关掉 %d 条,共 %d 条" % (a["name"], done, len(have) + done)))
    if extra:
        say("     " + D("(另有 %d 条不在判据内,原样保留:%s)"
                        % (len(extra), ", ".join(sorted(k for _, k, _ in extra)))))
sys.exit(err)
PY
RC=$?

say "校验(走 CLI 读回)"
multica --workspace-id "$WS" agent list --output json 2>/dev/null | python3 -c '
import sys, json
for a in json.load(sys.stdin):
    if a.get("archived_at"): continue
    print("  %-14s %2d 条" % (a["name"], len(a.get("disabled_runtime_skills") or [])), file=sys.stderr)
' || true

exit $RC
