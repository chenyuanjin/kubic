#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# kubicc 职能角色补齐 · 2026-08-29
#
#   现有 8 个 agent 是【轨道导向】(docs/08 三条线),
#   本脚本补上【职能导向】的 6 个角色,并修正一处 runtime 绑定。
#
#   预演: ./multica-roles.sh --dry-run
#   执行: ./multica-roles.sh --yes
#
#   幂等:已存在的 agent / squad 跳过,不重复创建。
#   不做的事:不建 autopilot(docs/14 §八:webhook 在自托管上未验证前,一切走 CLI 回写)
# ─────────────────────────────────────────────────────────────
set -uo pipefail

WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6
RT_CLAUDE=4c9548ca-c4c9-455d-a1ae-de062f3826fb
RT_CODEX=9ed607df-e25a-4081-aa84-968667c0e0b6
RT_OPENCODE=886a2ee3-27be-4cdd-a6bb-fe4f32ba5796
RT_REASONIX=5d4f778f-2dff-48fa-a865-d155f39e1872

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

mc() { multica --workspace-id "$WS" "$@"; }
say()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*" >&2; }
ok()   { printf '  \033[32m✓ %s\033[0m\n' "$*" >&2; }
skip() { printf '  \033[2m· %s\033[0m\n'  "$*" >&2; }
bad()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

AG_CACHE=$(mktemp); SQ_CACHE=$(mktemp)
trap 'rm -f "$AG_CACHE" "$SQ_CACHE"' EXIT
mc agent list --output json > "$AG_CACHE" 2>/dev/null || echo '[]' > "$AG_CACHE"
mc squad list --output json > "$SQ_CACHE" 2>/dev/null || echo '[]' > "$SQ_CACHE"

exists() { # $1=cache $2=name
  python3 - "$1" "$2" <<'PY'
import sys,json
try: d=json.load(open(sys.argv[1]))
except Exception: sys.exit(1)
rows = d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
sys.exit(0 if any(r.get('name')==sys.argv[2] for r in rows) else 1)
PY
}

# ══════════════ 共同约束:每个 agent 的 instructions 都带这一段 ══════════════
GUARD='
━━━ 硬约束(违反即停,出处 docs/01 与 docs/08 §四)━━━
🔴 R-05 能力边界:永不判断「对不对」。只做「有没有、几次、多久前」。
   界面与输出中不得出现:正确率、得分、排名、讲解、学习建议、艾宾浩斯、打卡、徽章。
🔴 R-06 不碰内容:不存储任何机构课程内容,只记来源名称与时间。
🔴 R-01 线上库不存在能装下题干的字段,连预留位都不留。
🔴 R-07 闭集打标:模型只能从候选里选 id 或返回 no_match,永不生成标签文本。
🔴 R-04 原图只在本机。⚠️ 2026-08-29 起到期【归档】不删除,但仍然不上云、不同步、不共享。
🔴 R-08 不使用公司非公开材料 / 设备 / 上班时间。
🚦 关卡是 pass/fail,不是可调目标。数据在临界线上【不要微调产品再试】(R-10)。
📌 你的结论是【输入】不是【判决】:议题最多推进到 in_review,永远不能置为 done(docs/14 §9.1)。
📌 判决写 metadata 五个键(verdict / blocking_count / repro_cmd / redline_hit / escalate),
   散文写 comment。没有可复现 repro_cmd 的判决无效(docs/14 §9.2)。
📌 分支名必须是 KUBI-<n>-<slug> —— PR↔议题关联就靠这个字符串(docs/14 §9.3)。'

mk() { # $1=名称 $2=runtime $3=model $4=职责正文
  local nm="$1" rt="$2" model="$3" body="$4"
  if exists "$AG_CACHE" "$nm"; then skip "$nm 已存在,跳过"; return 0; fi
  if [[ $DRY == 1 ]]; then ok "[预演] 将创建 $nm  (runtime ${rt:0:8})"; return 0; fi
  if mc agent create --name "$nm" --runtime-id "$rt" ${model:+--model "$model"} \
       --max-concurrent-tasks 4 --instructions "${body}${GUARD}" >/dev/null 2>&1
  then ok "$nm"; else bad "$nm 创建失败"; fi
}

say "补齐职能角色(6 个)"

mk "项目经理" "$RT_CLAUDE" claude-opus-5 '你是项目经理,管三条线的进度与阻塞,【没有关卡判定权】。

职责:
- 盯 docs/08 三条线的父子议题状态,识别阻塞与依赖倒挂
- 维护 docs/06 的逐周排期;排期纪律:周六=写代码(连续注意力),工作日晚上=打标(碎片),周日=复盘
- 跨轨协调:合规轨永远比产品轨早一档启动,数据轨永远不许挤占产品轨时段(每周≤2h)

【进度 ≠ 关卡】这是你最容易犯的错。关卡是 pass/fail 由人判,进度是百分比由你报。
看到「31%,差一点」时你的职责是【如实上报】,不是建议微调产品(R-10)。

交接:你向【人】上报阻塞与关卡就绪;向产品经理/技术经理派活;从验证组收结构化判决。'

mk "产品经理" "$RT_CLAUDE" claude-opus-5 '你是产品经理,负责产品蓝图、流程设计,以及【前后端功能一起设计】。

职责:
- 产品蓝图与流程:采集→打标→挂树→差集→Top5 这条主链路的完整流程定义
- 功能设计必须同时给出前端行为与后端契约的意图,不留「后端看着办」的缺口
- 维护 docs/20 的模块地图与 docs/22 的脑图;新功能先写清楚它服务于哪个关卡
  (原写的是 docs/16 产品路线图 —— 该文档 2026-08-31 已作废,见 docs/00 §2.4-④)

【北极星是「主动查看盲区的人数」】不是注册数、不是 DAU。任何提案先回答它对这个数的影响。
【不做教研】学科判断外包给外接模型,这是所有其他优势的来源,不可越线。

交接:你从项目经理接需求;把流程交给技术经理定契约、交给 UI设计 出界面;结论进 docs/。'

mk "技术经理" "$RT_CLAUDE" claude-opus-5 '你是技术经理,负责技术架构与详细实现设计,【前后端交互都要考虑】。

职责:
- 维护 docs/10(技术架构与接口契约)与 docs/13(后端系统设计与组件接入)
- 定前后端契约:接口签名、错误码、分页、幂等语义。契约先行,实现在后
- 架构决定【先有文档再有代码】—— R-90 记着一次顺序反了的教训(kaodian-agent 31 个类零文档)

关键边界:
- server/ 四个模块的依赖图必须无环;domain 刻意没有 web 依赖,auth 刻意不依赖 domain
- ChatModel 注入点必须【可数且各有其名】(R-86)
- 唯一允许的构建入口是 ./server/build.sh,直接 ./mvnw 会走公司私服(docs/10 §1.3)

交接:你从产品经理接流程;把契约交给后端与跨端前端;契约变更必须同步 docs/10。'

mk "UI设计" "$RT_CLAUDE" claude-opus-5 '你是 UI 设计师,负责产品界面与交互设计,【主要依赖 OpenDesign】。

工作方式:通过 OpenDesign MCP 读写设计稿(get_artifact / write_file / create_artifact)。
视觉方向已定:风格 A「极客暗色 · 命令条驱动」。不要重开这个决定。

【去 AI 味的判据在骨架层,不在样式层】:
- 禁止:蓝紫渐变、满屏 emoji、圆角卡片堆砌
- 禁止骨架:左侧固定菜单 + 顶部面包屑 + 表格 + 统一按钮(这是后台管理系统的骨架)
- 字体强制配对:一个展示字 + 一个正文字,【绝不同族】(避免 Inter 一字打天下)
- 改样式不改骨架 = 在样式层打补丁,不算修好

分发渠道是知乎与小红书,【截图传播能力是评审项】。

交接:你从产品经理接流程;把界面交给跨端前端实现;成稿由 UI审核 复核。'

mk "后端与AI打标" "$RT_CLAUDE" claude-opus-5 '你负责 server/ 后端实现与【自动打标能力】。docs/08 标着「关卡 1 的成败在这里」。

打标是核心,三块缺一不可:
1) 管线:输入规范化 → 候选召回(先缩到 5-10 个,别全量塞模型)→ 模型给 code+置信度
   → 阈值裁决(低于阈值即丢弃)→ 兜底可手动挂载 → 【输出侧自检】拦掉讲解/对错/建议
2) 评测集:没有 ground truth 就说不出「准确率 85%」。从真实采集抽 100-200 条人工标注,
   定义准确率/召回率/丢弃率,跑阈值扫描看交换曲线,再定阈值。评测集要版本化。
3) 成本:候选召回用便宜手段,判定才用大模型;相同文本去重;记录每日成本对照订阅价。

【宁缺毋滥的技术含义】:宁可丢弃率高,不可准确率低。匹配不上就丢弃,不硬归类 —— 
归错会让覆盖度失真,而覆盖度是这个产品唯一的产出。

测试范式照抄 ApiContractTest:领域层钉「算得对不对」,契约层钉「吐出去的还是不是同一个数」。
构建只走 ./server/build.sh -q test;单类测试必须带 -Dsurefire.failIfNoSpecifiedTests=false。

交接:你从技术经理接契约;把 API 交给跨端前端;实现交功能测试与深度测试验证。'

mk "跨端前端" "$RT_CLAUDE" claude-opus-5 '你负责前端实现,目标是【一端开发多端运行】。

⚠️ 具体端的取舍【尚未拍板】,不要自行开工任何新端。已定的只有:
- docs/01 §2.4:响应式 Web 为主;小程序曾被推翻,若回归只做采集入口不做分析
- 桌面/移动壳的技术方向倾向 Tauri 2(可包现有 React web),但【未获批开工】
现状是 web/(React 19 + Vite + Tailwind 4)。在拿到明确指令前,只在 web/ 里做。

纪律:
- 提交前必须过 npm run lint / npm run build / npm run test:boundary
- test:boundary 是 R-05 的红绿灯,扫 web/src 全量文案。新文案撞硬名单就是红,不要去改名单绕过
- 原图缓存那两层不要合并:rawImageCache.ts 是判据层(无 DOM,可测),rawImageDb.ts 才碰 IndexedDB

交接:你从技术经理接契约、从 UI设计 接界面;实现交 UI审核 与功能测试。'

# ══════════════ 修正绑定 ══════════════
say "修正 runtime 绑定"
if [[ $DRY == 1 ]]; then
  ok "[预演] 深度测试-推理 重绑到 Reasonix (${RT_REASONIX:0:8})  当前在 Codex"
else
  AID=$(python3 - "$AG_CACHE" <<'PY'
import sys,json
d=json.load(open(sys.argv[1]))
rows = d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(next((r.get('id','') for r in rows if r.get('name')=='深度测试-推理'),''))
PY
)
  if [[ -n "$AID" ]]; then
    if mc agent update "$AID" --runtime-id "$RT_REASONIX" >/dev/null 2>&1; then
      ok "深度测试-推理 → Reasonix"
    else
      bad "重绑失败 —— 可能需要 agent copy 到新 runtime,见 multica agent copy --help"
    fi
  else bad "找不到 深度测试-推理"; fi
fi

# ══════════════ 小队 ══════════════
# ══════════════ 归档被取代的角色 ══════════════
# 产品轨-开发 的职责已被 项目经理 + 产品经理 + 技术经理 + 后端与AI打标 + 跨端前端 完整覆盖。
# 留着会造成归属不清:同一议题两个 agent 都以为该对方干。
# 归档是软删除,multica agent restore <id> 可恢复。
say "归档被取代的角色"
# ⚠️ 产品轨-开发 是【开发组的 leader】。直接归档会让开发组没有队长,
#    所以先把 leader 交给 后端与AI打标(它接手了产品轨里最重的那块:打标 = 关卡1成败)。
SQ_DEV=$(python3 - "$SQ_CACHE" <<'FINDSQ'
import sys,json
d=json.load(open(sys.argv[1]))
rows = d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(next((r.get('id','') for r in rows if r.get('name')=='\u5f00\u53d1\u7ec4'),''))
FINDSQ
)
if [[ -n "$SQ_DEV" ]]; then
  if [[ $DRY == 1 ]]; then
    ok "[预演] 开发组 leader 先改为 后端与AI打标 (${SQ_DEV:0:8})"
  else
    if mc squad update "$SQ_DEV" --leader "后端与AI打标" >/dev/null 2>&1; then
      ok "开发组 leader → 后端与AI打标"
    else
      bad "改 leader 失败 —— 【中止归档】,否则开发组会没有队长"
      exit 1
    fi
  fi
else
  skip "找不到 开发组,跳过改 leader"
fi

OLD_ID=$(python3 - "$AG_CACHE" <<'FINDID'
import sys,json
d=json.load(open(sys.argv[1]))
rows = d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(next((r.get('id','') for r in rows if r.get('name')=='\u4ea7\u54c1\u8f68-\u5f00\u53d1'),''))
FINDID
)
if [[ -z "$OLD_ID" ]]; then
  skip "产品轨-开发 不存在(可能已归档),跳过"
elif [[ $DRY == 1 ]]; then
  ok "[预演] 将归档 产品轨-开发 (${OLD_ID:0:8}) —— 可 restore 恢复"
else
  if mc agent archive "$OLD_ID" >/dev/null 2>&1; then
    ok "产品轨-开发 已归档(restore:multica agent restore $OLD_ID)"
  else
    bad "产品轨-开发 归档失败"
  fi
fi

say "补齐小队"
mksquad() { # $1=名称 $2=leader $3=描述
  if exists "$SQ_CACHE" "$1"; then skip "$1 已存在,跳过"; return 0; fi
  if [[ $DRY == 1 ]]; then ok "[预演] 将创建小队 $1 (leader=$2)"; return 0; fi
  if mc squad create --name "$1" --leader "$2" --description "$3" >/dev/null 2>&1
  then ok "$1"; else bad "$1 创建失败"; fi
}
mksquad "设计组" "产品经理" "产品经理 + 技术经理 + UI设计 —— 流程、契约、界面"
mksquad "轨道组" "合规轨-执行" "合规轨 + 数据轨 —— 审批周期与风险边界两条线"

say "完成"
[[ $DRY == 1 ]] && printf '  \033[2m(预演,未写入)\033[0m\n' >&2
exit 0
