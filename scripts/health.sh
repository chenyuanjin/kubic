#!/usr/bin/env bash
# kubicc 定期健康检查 —— 每一项都对应一次真实踩过的坑,不是通用模板。
#   用法: ./scripts/health.sh          # 人读
#         ./scripts/health.sh --terse  # 只输出异常行,给 monitor 用
set -uo pipefail
cd "$(dirname "$0")/.."
WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6

# 🔴 不要依赖调用方的 PATH。2026-08-30 监控里 PATH 缺 /usr/local/bin,
#    multica 变成 command not found,而检查把它报成了「认证失效」——
#    把「我的工具坏了」报成「被观察对象坏了」,是这份脚本最该防的一类假告警。
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin:$PATH"
MULTICA="$(command -v multica || echo /usr/local/bin/multica)"
TERSE=0; [[ "${1:-}" == "--terse" ]] && TERSE=1
bad=0

# 🔴 告警去重:同一组告警连续出现时不重复通知,只在【变化】时报。
# 一个每 20 分钟重复喊同一句的监控,一天之后就被当成背景噪音 ——
# 那时真出新问题也没人看。等人决定的状态不该反复喊,变了才喊。
STATE_FILE="${TMPDIR:-/tmp}/kaodian-health-last.txt"
BUF=""

say(){ [[ $TERSE == 0 ]] && printf '  %s\n' "$*"; }
# $1=种类(去重键) 其余=正文
# $1=种类(去重键) 其余=正文。分隔符用真制表符($'\t'),
# 双引号里的 \t 是字面量两个字符,bash 不解释 —— 踩过。
alert(){ local k="$1"; shift; BUF="${BUF}${k}"$'\t'"⚠ $*"$'\n'; bad=1; }

# ① 本地 vs 远端分叉 —— 8-29 攒了 20 个未推提交,所有 agent 在不存在的仓库上工作
git fetch origin -q 2>/dev/null
B=$(git branch --show-current)
ahead=$(git rev-list --count "origin/$B..$B" 2>/dev/null || echo '?')
behind=$(git rev-list --count "$B..origin/$B" 2>/dev/null || echo '?')
if [[ "$ahead" == "0" && "$behind" == "0" ]]; then say "git $B 与 origin 同步"
else alert sync "git $B 领先 origin $ahead 个、落后 $behind 个 —— agent 会在错误基线上工作"; fi

# ② 未提交改动堆积
dirty=$(git status --porcelain | wc -l | tr -d ' ')
[[ "$dirty" -gt 20 ]] && alert dirty "工作区 $dirty 个未提交改动" || say "工作区 $dirty 个未提交改动"

# ③ 带宽被占 —— Android SDK 下载曾把 GitHub 拖到 5.8s,影响所有人
hog=$(ps aux | grep -iE "sdkmanager|gradle.*download" | grep -v grep | wc -l | tr -d ' ')
[[ "$hog" -gt 0 ]] && alert hog "有 $hog 个疑似占带宽的下载进程在跑" || say "无占带宽进程"
# 慢要分辨是【本机带宽被占】还是【到 GitHub 的线路劣化】——处置完全不同:
# 前者杀进程,后者只能等或换路。2026-08-30 误报过一次「疑似带宽被占」,
# 实测 github 12s 而 api.github.com / npm / 百度都 1-3s,是 GitHub 专属劣化。
gh_t=$(curl -s -o /dev/null -w '%{time_total}' -m 25 https://github.com 2>/dev/null || echo 99)
if awk -v t="$gh_t" 'BEGIN{exit !(t>8)}'; then
  ref_t=$(curl -s -o /dev/null -w '%{time_total}' -m 15 https://www.baidu.com 2>/dev/null || echo 99)
  if awk -v r="$ref_t" 'BEGIN{exit !(r>5)}'; then
    alert net "整体网络慢:github ${gh_t}s / 参照 ${ref_t}s —— 查本机是否有下载占带宽"
  else
    alert net "GitHub 线路劣化:github ${gh_t}s 而参照点 ${ref_t}s 正常 —— 非本机问题,git 操作会很慢"
  fi
else say "GitHub ${gh_t}s"; fi

# ④ Multica 可达 + 是否有活在跑
if curl -s -m 8 http://127.0.0.1:20226/health 2>/dev/null | grep -q '"status":"running"'; then
  running=$(curl -s -m 8 http://127.0.0.1:20226/health | python3 -c "import sys,json;print(json.load(sys.stdin).get('running_task_count',0))" 2>/dev/null)
  say "multica 守护进程 running,在跑任务 $running"
else alert daemon "multica 守护进程不可达"; fi
# 认证:重试一次再判,且看【实际能力】不看状态文案 ——
# 单次网络抖动不该报成「认证失效」(2026-08-30 误报一次)
if [[ ! -x "$MULTICA" ]]; then
  alert tool "找不到 multica 可执行文件($MULTICA)—— 这是【检查工具缺失】,不是认证问题"
else
  auth_ok=0; auth_out=""
  for _ in 1 2; do
    auth_out="$($MULTICA auth status 2>&1)"
    if printf '%s' "$auth_out" | grep -q "^User:"; then auth_ok=1; break; fi
    sleep 3
  done
  if [[ $auth_ok == 1 ]]; then say "multica 已认证"
  elif printf '%s' "$auth_out" | grep -qi "invalid token\|expired\|401"; then
    alert auth "multica 认证【确实失效】:$(printf '%s' "$auth_out" | head -1)"
  else
    alert auth "multica auth 读不出用户(非典型失败,原样):$(printf '%s' "$auth_out" | head -1)"
  fi
fi

# ⑤ 卡住的议题 —— blocked 堆积说明有人在等人
blocked=$("$MULTICA" --workspace-id $WS issue list --limit 300 --output json 2>/dev/null \
  | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print(-1); raise SystemExit
rows=d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(sum(1 for r in rows if r.get('status')=='blocked'))" 2>/dev/null || echo -1)
if [[ "$blocked" == "-1" ]]; then
  sleep 3
  blocked=$("$MULTICA" --workspace-id $WS issue list --limit 300 --output json 2>/dev/null \
    | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print(-1); raise SystemExit
rows=d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(sum(1 for r in rows if r.get('status')=='blocked'))" 2>/dev/null || echo -1)
fi
if [[ "$blocked" == "-1" ]]; then alert issue "读不到议题列表(已重试)"
elif [[ "$blocked" -gt 3 ]]; then alert issue "$blocked 条议题 blocked —— 有人在等人裁"
else say "blocked 议题 $blocked 条"; fi

# ⑥ 闸门 —— 只推可运行的代码,先确认它还能跑
# 🔴 红线告警必须能分辨「测试红了」和「测试没跑起来」。
#    2026-08-30 精简 PATH 下 npm 缺失,曾把它报成「R-05 失守」——
#    会误响的红线警报,几次之后就没人信了,那比不报还糟。
NPM="$(command -v npm || true)"
gate(){ # $1=脚本名 $2=人话 $3=红线号
  if [[ -z "$NPM" ]]; then
    alert "gate-$3" "跑不了 $2:找不到 npm —— 这是【检查环境缺失】,不是 $3 失守"; return
  fi
  local out rc
  out=$(cd web && "$NPM" run "$1" 2>&1); rc=$?
  if [[ $rc == 0 ]]; then say "$2 通过"; return; fi
  if printf '%s' "$out" | grep -qiE "command not found|ENOENT|Missing script|cannot find module"; then
    alert "gate-$3" "跑不了 $2(环境问题,非 $3 失守):$(printf '%s' "$out" | grep -iE 'command not found|ENOENT|Missing script|cannot find module' | head -1)"
  else
    alert "gate-$3" "$2【失败】—— $3 失守:$(printf '%s' "$out" | tail -3 | tr '\n' ' ' | cut -c1-160)"
  fi
}
gate test:boundary "能力边界扫描" "R-05"
gate test:retention "原图留存测试" "R-04"

# 输出与去重 —— 按种类各自去重。
# 整体指纹去重有缺陷:某条在阈值上下抖动时,告警集合在 {a} 与 {a,b} 之间来回,
# 每次都算「新状态」,那条稳定的告警被反复重播。按种类比对才不抖。
# 实现注意两点(都踩过):
#   · macOS 自带 bash 3.2 没有 declare -A,用文件 + comm
#   · 管道会开子 shell,while 里读不到外层变量 —— 先把 BUF 落到临时文件再处理
BUFF="$(mktemp)"; NOW="$(mktemp)"
trap 'rm -f "$BUFF" "$NOW"' EXIT
printf '%s' "$BUF" > "$BUFF"

if [[ $TERSE == 1 ]]; then
  awk -F'\t' 'NF>1{gsub(/[0-9]+(\.[0-9]+)?/,"",$2); print $1"\t"$2}' "$BUFF" | sort -u > "$NOW"
  [[ -f "$STATE_FILE" ]] || : > "$STATE_FILE"
  changed="$(comm -23 "$NOW" <(sort -u "$STATE_FILE") | cut -f1)"
  if [[ -n "$changed" ]]; then
    while IFS=$'\t' read -r k line; do
      [[ -n "$k" ]] && printf '%s\n' "$changed" | grep -qx -- "$k" && printf '%s\n' "$line"
    done < "$BUFF"
  fi
  gone="$(comm -13 <(cut -f1 "$NOW" | sort -u) <(cut -f1 "$STATE_FILE" | sort -u))"
  [[ -n "$gone" ]] && while read -r k; do
    [[ -n "$k" ]] && echo "✓ 已恢复正常:$k"
  done <<< "$gone"
  cp "$NOW" "$STATE_FILE"
else
  cut -f2- "$BUFF"
  [[ $bad == 0 ]] && echo "  ✓ 全部正常"
fi
exit $bad
