#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# kubicc 全体 agent 切主干到 v1 · 2026-08-30
#
#   本仓库的主干从 main 切到 v1。改两处:
#     ① instructions —— 追加/刷新一段【主干分支】,并把旧的泛称「主干」写实成 v1
#     ② description(备注)—— 末尾挂一个可见标记「｜主干:v1」
#
#   为什么两处都写:instructions 是 agent 真正读到的规则,description 是人在
#   agent 列表里一眼能看见的备注。只写其一,要么人看不见,要么 agent 不执行。
#
#   预演: ./scripts/multica-branch-v1.sh --dry-run
#   执行: ./scripts/multica-branch-v1.sh --yes
#
#   幂等:重复跑不会叠加 —— 备注标记查重,instructions 段落按标记整段替换。
#   回退:把 TRUNK 改回 main 重跑即可(备注标记会跟着变成「｜主干:main」)。
# ─────────────────────────────────────────────────────────────
set -uo pipefail

WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6
TRUNK=v1
SWITCH_DATE=2026-08-30

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

mc()   { multica --workspace-id "$WS" "$@"; }
say()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*" >&2; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$*" >&2; }
skip() { printf '  \033[2m· %s\033[0m\n'  "$*" >&2; }
bad()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

say "读取 kubicc agent 清单"
if ! mc agent list --output json > "$TMP/agents.json" 2>"$TMP/err"; then
  bad "agent list 失败:$(head -c 200 "$TMP/err")"; exit 1
fi

# ── 计算每个 agent 的新 description / instructions ────────────
TRUNK="$TRUNK" SWITCH_DATE="$SWITCH_DATE" OUT="$TMP" python3 - "$TMP/agents.json" <<'PY'
import json, os, re, sys

TRUNK = os.environ["TRUNK"]
DATE  = os.environ["SWITCH_DATE"]
OUT   = os.environ["OUT"]

HEAD = "━━━━━━ 主干分支:%s ━━━━━━" % TRUNK
BLOCK = """
%s
本仓库的主干是 %s,不是 main(%s 切换)。凡是要落到代码上的动作,三处一律用 %s:
  1) 新需求从 origin/%s 开出 KUBI-<议题号>-<简述>,不直接在 %s 上提交
  2) PR 的目标分支是 %s
  3) 基线声明写成:基线:origin/%s @ <SHA7>,fetch 于 <时间>
已经开在别的分支上的活,先确认它接的是哪个分支再动手,不要擅自改基。
本段与上文任何提到 main、或只泛称「主干」的说法冲突时,以本段为准。""" % (
    HEAD, TRUNK, DATE, TRUNK, TRUNK, TRUNK, TRUNK, TRUNK)

DESC_MARK_RE = re.compile(r"｜主干:\S+$")
DESC_MARK    = "｜主干:%s" % TRUNK

# 旧的泛称「主干」写实成分支名 —— 只改这几个确定的说法,不做全局替换
PHRASES = [
    ("新需求从主干开 KUBI-<议题号>-<简述>,不直接改主干",
     "新需求从主干 %s 开 KUBI-<议题号>-<简述>,不直接改 %s" % (TRUNK, TRUNK)),
    ("新需求:从当前主干开出 KUBI-<议题号>-<简述>,不要直接在主干上改",
     "新需求:从主干 %s(origin/%s)开出 KUBI-<议题号>-<简述>,不要直接在 %s 上改" % (TRUNK, TRUNK, TRUNK)),
]

data = json.load(open(sys.argv[1]))
rows = data if isinstance(data, list) else next(
    (v for v in data.values() if isinstance(v, list)), [])

manifest = []
for r in rows:
    if r.get("archived_at"):
        continue
    aid, name = r["id"], r.get("name", "")
    desc = (r.get("description") or "").rstrip()
    ins  = (r.get("instructions") or "").rstrip()

    new_desc = DESC_MARK_RE.sub("", desc).rstrip() + DESC_MARK

    new_ins = ins
    for old, new in PHRASES:
        new_ins = new_ins.replace(old, new)
    # 整段替换:上一次跑留下的块从标记处截断
    cut = new_ins.find(HEAD)
    if cut != -1:
        new_ins = new_ins[:cut].rstrip()
    # 历史上可能写过别的主干名的块,一并截掉
    cut = re.search(r"━━━━━━ 主干分支:\S+ ━━━━━━", new_ins)
    if cut:
        new_ins = new_ins[:cut.start()].rstrip()
    new_ins = (new_ins + "\n" + BLOCK).strip("\n")

    open("%s/%s.desc" % (OUT, aid), "w").write(new_desc)
    open("%s/%s.ins"  % (OUT, aid), "w").write(new_ins)
    manifest.append("\t".join([
        aid, name,
        "same" if new_desc == desc else "diff",
        "same" if new_ins  == ins  else "diff",
    ]))

open("%s/manifest.tsv" % OUT, "w").write("\n".join(manifest) + "\n")
print("  计划更新 %d 个 agent" % len(manifest), file=sys.stderr)
PY
[[ -s "$TMP/manifest.tsv" ]] || { bad "没有解析出任何 agent"; exit 1; }

say "写回(instructions + 备注)"
FAIL=0
while IFS=$'\t' read -r aid name dchg ichg; do
  [[ -z "$aid" ]] && continue
  if [[ "$dchg" == same && "$ichg" == same ]]; then
    skip "$name 已是 $TRUNK,跳过"; continue
  fi
  if [[ $DRY == 1 ]]; then
    ok "[预演] $name  备注:$dchg  指令:$ichg"; continue
  fi
  if mc agent update "$aid" \
       --description  "$(cat "$TMP/$aid.desc")" \
       --instructions "$(cat "$TMP/$aid.ins")" >/dev/null 2>"$TMP/e"; then
    ok "$name"
  else
    bad "$name 更新失败:$(head -c 160 "$TMP/e")"; FAIL=1
  fi
done < "$TMP/manifest.tsv"

say "校验"
mc agent list --output json 2>/dev/null | TRUNK="$TRUNK" python3 -c '
import sys, json, os
t = os.environ["TRUNK"]
d = json.load(sys.stdin)
rows = d if isinstance(d, list) else next((v for v in d.values() if isinstance(v, list)), [])
rows = [r for r in rows if not r.get("archived_at")]
head = "━━━━━━ 主干分支:%s ━━━━━━" % t
bad = [r["name"] for r in rows
       if head not in (r.get("instructions") or "")
       or not (r.get("description") or "").endswith("｜主干:%s" % t)]
print("  %d/%d 个 agent 已标记主干 %s" % (len(rows) - len(bad), len(rows), t), file=sys.stderr)
if bad: print("  未覆盖:" + " ".join(bad), file=sys.stderr)
' || true

exit $FAIL
