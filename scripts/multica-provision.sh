#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Multica 工作空间开设脚本 · 考点盲区(NoteTool)
#
#   预演:  ./scripts/multica-provision.sh --dry-run
#   执行:  ./scripts/multica-provision.sh
#   非交互(agent/CI):
#          ./scripts/multica-provision.sh --yes
#   指定工作空间(Kubicc):
#          ./scripts/multica-provision.sh --workspace-id <uuid|slug|前缀>
#
# 幂等:agent / squad / project 已存在则跳过;issue 依赖 CLI 自带的
#      重复检测(不加 --allow-duplicate,存在活动同名议题时会被拒绝)。
#      因此本脚本可以安全重跑,只补建缺失的部分。
#
# 结构映射(来自 docs/execution/08-总路线图.md):
#   三条线      → 3 个 project + 3 个父议题
#   阶段        → --stage N 有序屏障组(整组完成才唤醒父议题 = 关卡判定)
#   父子待办树  → 子议题
#   智能体      → 按线路 + 审核/测试职能建立,编入 squad
# ─────────────────────────────────────────────────────────────
set -uo pipefail

# 目标工作空间:kubicc(slug: kubicc,issue 前缀 KUBI,创建于 2026-08-23)
# 写死是刻意的 —— 环境里的 MULTICA_WORKSPACE_ID 可能指向别的空间,
# 一旦落错空间,58 个议题要一条条删。用 --workspace-id 可显式覆盖。
KUBICC="903c14c4-e5de-4e47-b3bc-3412818f4fa6"

DRY=0
YES=0
WSID="$KUBICC"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)      DRY=1; shift ;;
    -y|--yes)       YES=1; shift ;;
    --workspace-id) WSID="${2:-}"; shift 2 ;;
    -h|--help)      sed -n '2,20p' "$0"; exit 0 ;;
    *)              echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

WSFLAG=()
[[ -n "$WSID" ]] && WSFLAG=(--workspace-id "$WSID")

# ── 输出约定:命令轨迹与提示走 stderr,JSON 结果走 stdout ──
say()  { printf '\n\033[1;36m▸ %s\033[0m\n' "$*" >&2; }
dim()  { printf '  \033[2m%s\033[0m\n' "$*" >&2; }
err()  { printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }

# fd 9 = 真正的终端 stderr。调用处用 2>&1 捕获返回值时,fd2 会被并进 fd1,
# 预演轨迹若还走 fd2 就会污染返回的 JSON。走 fd9 才不会被合并。
exec 9>&2

M() {
  if [[ $DRY == 1 ]]; then
    printf '  \033[2m$ multica %s\033[0m\n' "$*" >&9
    echo "{\"id\":\"00000000-0000-0000-0000-$(printf '%012d' $((RANDOM%999999)))\"}"
  else
    multica ${WSFLAG[@]+"${WSFLAG[@]}"} "$@"
  fi
}

# 从任意形状的返回里取出第一个 UUID 型 id。
# 关键:解析失败或返回的是错误文本时,输出空串 —— 绝不把错误文本当 id 传下去,
# 否则会变成 --parent "请求无效:..." 这种灾难。
jqid() {
  python3 -c 'import sys,json,re
U=re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
def walk(o):
    if isinstance(o,dict):
        v=o.get("id")
        if isinstance(v,str) and U.match(v.lower()): return v
        for x in o.values():
            r=walk(x)
            if r: return r
    elif isinstance(o,list):
        for x in o:
            r=walk(x)
            if r: return r
    return None
try:    print(walk(json.loads(sys.stdin.read())) or "")
except Exception: print("")'
}

UUIDRE='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
uuid_ok() { [[ "${1:-}" =~ $UUIDRE ]]; }

LIB="$(cd "$(dirname "$0")" && pwd)/multica-lib.py"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
RT="$TMP/runtimes.json"; AG="$TMP/agents.json"
SQ="$TMP/squads.json";   PJ="$TMP/projects.json"
IS="$TMP/issues.json"

# macOS 自带 bash 是 3.2,没有关联数组 —— 用制表符映射文件代替
MAP="$TMP/map.tsv"; : > "$MAP"
put() { printf '%s\t%s\n' "$1" "$2" >> "$MAP"; }
get() { awk -F'\t' -v k="$1" '$1==k{print $2; exit}' "$MAP"; }

# ══════════════ 0. 前置检查 ══════════════
# 若当前 shell 继承了 Multica task 的环境,CLI 会优先用那个 task token。
# task token 过期后会直接拒绝,且明令不得回退到 profile 凭证 —— 对 task 来说
# 这是对的(不能冒用人的身份),但你本人在自己终端里跑,就该用你自己的凭证。
# 所以这里不是"绕过",而是把身份还原成你本人。
if [[ -n "${MULTICA_TOKEN:-}" || -n "${MULTICA_TASK_CONFIG_ROOT:-}" ]]; then
  err "检测到当前 shell 继承了 Multica task 环境变量"
  dim "MULTICA_TOKEN / MULTICA_TASK_CONFIG_ROOT 会让 CLI 用那个(已过期的)task token,"
  dim "而不是你自己的登录态。请在一个干净的终端里跑,或显式清掉这几个变量:"
  dim ""
  dim "  env -u MULTICA_TOKEN -u MULTICA_TASK_CONFIG_ROOT -u MULTICA_TASK_ID \\"
  dim "      -u MULTICA_WORKSPACE_ID -u MULTICA_AGENT_ID -u MULTICA_AGENT_NAME \\"
  if [[ $DRY == 1 ]]; then dim "      ./scripts/multica-provision.sh --dry-run"
  else                     dim "      ./scripts/multica-provision.sh"; fi
  dim ""
  dim "清掉之后 CLI 走 profile 凭证(= 你本人),身份归属正确。"
  exit 1
fi

if [[ $DRY == 0 ]]; then
  if ! multica ${WSFLAG[@]+"${WSFLAG[@]}"} auth status >/dev/null 2>&1; then
    err "未认证或 token 过期。先执行: multica login"
    exit 1
  fi
  say "目标工作空间"
  if [[ "$WSID" == "$KUBICC" ]]; then dim "kubicc  $WSID"
  else dim "自定义  $WSID  ⚠ 非 kubicc,确认这是你要的空间"; fi
  multica ${WSFLAG[@]+"${WSFLAG[@]}"} workspace get "$WSID" --output table 2>&1 | head -12 >&2
  # 无 TTY 时旧写法是 `read < /dev/tty 2>/dev/null || true` —— 重定向失败后
  # `|| true` 直接放行,等于 agent/CI 里这道闸门根本不存在,58 条议题可能
  # 落进错的工作空间且无人确认。改为:非交互必须显式 --yes。
  if [[ $YES == 1 ]]; then
    dim "--yes:跳过人工确认"
  elif { true >/dev/tty; } 2>/dev/null; then
    printf '  \033[33m回车继续,Ctrl-C 中止 …\033[0m ' >&2
    read -r _ < /dev/tty || true
  else
    err "非交互环境(无 TTY),拒绝在未经确认的情况下写入工作空间"
    dim "确认上面的工作空间无误后,加 --yes 重跑:"
    dim "  ./scripts/multica-provision.sh --yes"
    exit 1
  fi
fi

# ══════════════ 0b. 读取目录,建立幂等基线 ══════════════
say "读取 runtime / agent / squad / project 目录"
# 预演也读真实目录。旧版在 --dry-run 时把五份目录清空,于是预演把每个对象
# 都列成"待创建" —— 而预演唯一值得回答的问题恰恰是"哪些已存在、还差哪些"。
# 读目录全是只读命令,预演读它们不破坏"预演不写"的语义。
if ! multica ${WSFLAG[@]+"${WSFLAG[@]}"} runtime list --output json > "$RT" 2>/dev/null; then
  if [[ $DRY == 1 ]]; then
    err "预演:runtime list 读取失败,退化为空目录 —— 下面的差异表不可信"
    for f in "$RT" "$AG" "$SQ" "$PJ" "$IS"; do echo '[]' > "$f"; done
  else
    err "multica runtime list 失败 —— 先确认已登录且工作空间正确"; exit 1
  fi
else
  multica ${WSFLAG[@]+"${WSFLAG[@]}"} agent   list --output json > "$AG" 2>/dev/null || echo '[]' > "$AG"
  multica ${WSFLAG[@]+"${WSFLAG[@]}"} squad   list --output json > "$SQ" 2>/dev/null || echo '[]' > "$SQ"
  multica ${WSFLAG[@]+"${WSFLAG[@]}"} project list --output json > "$PJ" 2>/dev/null || echo '[]' > "$PJ"
  # issue list 默认只返回 50 条,而本脚本要建 58 条 —— 不加 --limit 会漏读已存在的议题,
  # 重跑时父议题查不到 id,子议题就会全部变成无父孤儿。
  multica ${WSFLAG[@]+"${WSFLAG[@]}"} issue list --limit 500 --output json > "$IS" 2>/dev/null || echo '[]' > "$IS"
  dim "runtime $(python3 "$LIB" count "$RT") 个 / 已有 agent $(python3 "$LIB" count "$AG") 个 / 已有议题 $(python3 "$LIB" count "$IS") 条"
fi

exists() { python3 "$LIB" has "$1" "$2"; }
idof()   { python3 "$LIB" id-of "$1" "$2" 2>/dev/null; }

# ══════════════ 0c. 仓库 / 属性 / 标签 ══════════════
# 交付流水线的配置底座(docs/交付工作流 §九)。全部幂等:已存在时 CLI 自己会拒,忽略即可。
say "注册仓库、自定义属性与标签"
M repo add git@github.com:chenyuanjin/kubic.git \
  --description "考点盲区(NoteTool)· server + web + docs" --output json >/dev/null 2>&1 \
  && dim "+ repo kubic" || dim "· repo 已存在或失败,忽略"

M property create --name "闸门" --type select --icon shield \
  --description "这条议题的判决由哪一道闸给出。agent闸无裁决权,只写 metadata。" \
  --option "机器闸:#22c55e" --option "agent闸:#f59e0b" --option "关卡:#ef4444" \
  --output json >/dev/null 2>&1 && dim "+ property 闸门" || dim "· property 闸门 已存在,跳过"

M property create --name "红线命中" --type multi_select --icon flag \
  --description "命中的 总路线图 §四 🔴 条目。任一非空即进入闸3 差异人审。" \
  --option "R-01:#ef4444" --option "R-02:#ef4444" --option "R-03:#ef4444" --option "R-04:#ef4444" \
  --option "R-05:#ef4444" --option "R-06:#ef4444" --option "R-07:#ef4444" --option "R-08:#ef4444" \
  --option "R-36:#ef4444" --option "R-37:#ef4444" \
  --output json >/dev/null 2>&1 && dim "+ property 红线命中" || dim "· property 红线命中 已存在,跳过"

for lb in "需人审:#f59e0b" "已豁免:#a855f7"; do
  M label create --name "${lb%%:*}" --color "${lb##*:}" --output json >/dev/null 2>&1 \
    && dim "+ label ${lb%%:*}" || dim "· label ${lb%%:*} 已存在,跳过"
done

# ══════════════ 1. 智能体 ══════════════
# 开发=claude / 文档审核+深度测试=codex / UI审核+功能测试=opencode
# (reasonix 已按要求移除,其对抗性测试职责改由 codex 承担)
say "建立智能体"

mk_agent() { # $1=名称 $2=runtime名 $3=model(可空) $4=thinking(可空) $5=instructions
  local nm="$1" rtname="$2" model="$3" think="$4" instr="$5" rid out id
  if exists "$AG" "$nm"; then
    id="$(idof "$AG" "$nm")"; put "agent:$nm" "$id"
    dim "· $nm 已存在,跳过  ($id)"
    return 0
  fi
  if ! rid="$(python3 "$LIB" resolve "$RT" "$rtname")"; then
    if [[ $DRY == 1 ]]; then
      rid="DRY-RT-$rtname"
      dim "· 预演:runtime \"$rtname\" 未解析到,用占位符继续"
    else
      err "$nm: runtime \"$rtname\" 未解析到,跳过"
      return 0
    fi
  fi
  # 交付/收尾协议按职能追加(2026-08-27,docs/交付工作流 §九)。协议只存一份 ——
  # 改协议时不用去七个 instructions 字符串里各找一遍。
  case "$nm" in
    *开发|*执行)   instr="$instr
$PROTO_DEV" ;;
    *审核|*测试*)  instr="$instr
$PROTO_REVIEW" ;;
  esac

  local base=(agent create --name "$nm" --runtime-id "$rid"
              --max-concurrent-tasks 4 --instructions "$instr" --output json)
  local args=("${base[@]}")
  [[ -n "$model" ]] && args+=(--model "$model")
  [[ -n "$think" ]] && args+=(--thinking-level "$think")
  out="$(M "${args[@]}" 2>&1)"; id="$(printf '%s' "$out" | jqid)"

  # --model / --thinking-level 的合法值来自服务端的 runtime 模型目录,离线定不了。
  # 被拒绝时不要整个 agent 建不出来 —— 退回到 runtime 默认再试一次。
  if ! uuid_ok "$id" && [[ -n "$model$think" ]]; then
    dim "  ↺ $nm: 带 model/thinking 被拒,退回 runtime 默认重试"
    dim "     ($(printf '%s' "$out" | tr '\n' ' ' | head -c 160))"
    out="$(M "${base[@]}" 2>&1)"; id="$(printf '%s' "$out" | jqid)"
  fi

  if ! uuid_ok "$id"; then
    err "$nm 创建失败: $(printf '%s' "$out" | tr '\n' ' ' | head -c 200)"
    return 0
  fi
  put "agent:$nm" "$id"
  dim "+ $nm  ($rtname → $id)"
}

COMMON_GUARD='硬约束(违反即停,来自 docs/决策记录 与 docs/总路线图 风险登记册):
- 不判断"对不对",只做"有没有、几次、多久前"(R-05)
- 不存储任何机构课程内容,只记来源名称与时间(R-06)
- 原图仅本地短期缓存,绝不上云、不共享,含厂商图片暂存API(R-04)
- 考点标签自行命名,不沿用任何机构既有体系与措辞(R-07)
- 闭集打标:模型只从候选里选节点id或返回"无匹配",绝不生成标签文本
- 打标匹配不上就丢弃,不硬归类(宁缺毋滥)
- 线上库不存在能装下题干的字段,连预留位都不留(R-01)
- 不使用公司非公开材料/设备/上班时间(R-08)
关卡是 pass/fail,不是可调目标。数据在临界线上不要微调产品再试(R-10)。
北极星指标是"主动查看盲区的人数",不是注册数不是DAU。'

# ── 交付协议(docs/交付工作流 §九)。两段,按职能分发 ──
PROTO_DEV='
── 交付协议(硬性)──
1) 分支名必须是 KUBI-<议题号>-<英文短语>,例:KUBI-12-offline-queue。
   Multica 的 PR↔议题关联就是靠这个字符串做的。没有它,CI 结果回不到议题上,
   整条流水线断在这里 —— 这不是命名洁癖。
2) 提交前本机必须绿:
   后端 ./server/build.sh -q test    前端 (cd web && npm run lint && npm run build)
3) 仓库装了 core.hooksPath=.githooks,两道闸:
   · 密钥/账号数据/原图缓存被强加 → 拒绝(R-04 / R-59)
   · docs/decisions/ 决策层文档(四份) → 需 ALLOW_DECISION_EDIT=1,且会在提交信息里留一条 trailer
   撞上闸时先想清楚再用出路,不要顺手加环境变量绕过去。
4) 【你没有裁决权】做完把状态改 in_review,不改 done。
   multica issue status <id> in_review --no-start
5) 数据目录默认 ~/.kaodian(仓库外)。绝不把 kaodian.data.dir 指到仓库内。'

PROTO_REVIEW='
── 收尾协议(硬性。不做完 = 本次运行未完成)──
散文写在评论里,判决写在下面这些字段里。人只读字段。

1) 五个 metadata 键(键名与类型不得自创,2026-08-27 定):
   multica issue metadata set <id> --key verdict        --value true|false
   multica issue metadata set <id> --key blocking_count --value <数字>
   multica issue metadata set <id> --key repro_cmd      --type string --value "<命令>"
   multica issue metadata set <id> --key redline_hit    --type string --value "R-04,R-05"
   · redline_hit 只填 🔴 红线(R-01~R-08 / R-36 / R-37)。🟠🟡 写在评论里,不进这个字段。
   · 多条用英文逗号分隔,【不要】自己加引号 —— 用 --type string,CLI 会处理。
   · repro_cmd 必须能从【仓库根目录】直接跑:相对路径,不得出现 /Users/... 这类绝对路径,
     否则换一台机器就复现不了,这条判决等于没写。

2) 命中 🔴 时两件事,别搞混 ——【属性】和【标签】是两个东西:
   multica issue property set <id> --name "红线命中" --value "R-04,R-05"   # ← 属性
   multica issue label add <id> <「需人审」标签id>                          # ← 标签
   绝不新建标签或属性。取值域之外的值一律说明在评论里,不要为了塞进去而改名字。

3) 状态改 in_review:multica issue status <id> in_review --no-start

4) 【你没有裁决权】绝不把状态改成 done —— 那是闸3(人)与闸4(关卡)的事。
   你的结论是「输入」,不是「判决」。

【无 repro_cmd 的判决一律作废】不可复现的审核意见没有价值,写了也会被打回。
判据是 pass/fail,数据落在临界线时判 fail,不要为了让它过而放宽标准(R-10)。'

mk_agent "产品轨-开发" claude claude-opus-5 high \
"你负责 docs/总路线图 的第 1 条线(产品轨)。按 docs/详细排期 的逐周排期推进,严格遵守排期纪律:
周六=写代码(连续注意力),工作日晚上=打标(碎片),周日=复盘。周六不打标,晚上不写代码。
重点交付:阶段0 采集链路与两个数字的记录表;阶段1 骨架冷启动与自动打标(评测集先行);阶段2 账号体系与埋点。
阶段0 明令不做界面、不做账号、不做数据库设计 —— 不要提前开工。
$COMMON_GUARD"

mk_agent "合规轨-执行" claude claude-opus-5 high \
"你负责 docs/总路线图 的第 2 条线(合规轨)。时钟是审批周期,不是关卡:永远比产品轨早一档启动,但绝不越过关卡去花钱。
公司主体已存在(2026-08确认),注册类决策一律作废,不要再讨论。
关键长周期项:ICP备案首次3-5周、短信签名与模板报备1-3工作日、软著30-60天。
依赖链:域名实名(公司主体) → 大陆服务器 → ICP备案 → 小程序业务域名校验。三者主体必须一致。
经营范围若含教育培训需警惕(R-16);不含软件开发需先增项(R-15)。小程序类目选工具/效率,不选教育。
$COMMON_GUARD"

mk_agent "数据轨-开发" claude claude-opus-5 high \
"你负责 docs/总路线图 的第 3 条线(数据轨)。时钟是风险边界。
核心原则:加工厂不是仓库。真题原文只存在离线加工区,产出物只有考点树+四统计字段+题目标识留证。
抓取纪律必须写进代码:遵守robots、单站≤1req/2s、真实UA、遇403/429即停。
【绝对红线】代码中不得存在登录/cookie注入/验证码识别/指纹伪造能力 —— 不是不用,是不写(R-02)。
【绝对红线】不碰粉笔/中公/华图登录后内容(R-03)。
工时上限每周≤2小时,只用周日复盘溢出,不得挤占周六或晚上(R-11)。
越界判据:为第二个科目做过"人工校正命名"即越界(R-12)。关卡0前一条都不抓。
$COMMON_GUARD"

mk_agent "文档审核" codex gpt-5.6-sol high \
"你是文档审核员,不写实现。审 docs/ 下的变更是否违反决策层。
决策层 docs/decisions/ 下四份不可被执行层覆盖:新调研与原决策冲突时,在下游记录更正并在上游加注,不得静默改写原文。
逐条核对 docs/总路线图 §四风险登记册,尤其 🔴 红线 8 条。
检查:是否把未验证的假设写成了结论;是否擅自展开了关卡2之后的排期(04明令不细化);
'待确认'项是否被一个合理推理悄悄关闭了(推理不等于书面确认);
被推翻的数字是否原地加注而不是删除。
文档一律中文,流程图一律 Mermaid。输出问题清单,不直接改文件。"

mk_agent "UI审核" opencode "" "" \
"你是 UI 审核员。审界面稿与前端实现。
【首要】查'AI味'的骨架层,不是样式层:
  - 骨架级同质化 —— '左侧固定菜单+顶部面包屑+表格+统一按钮'的后台骨架
  - 蓝紫渐变、圆角卡片堆砌、满屏emoji当图标
  - 一字打天下(展示字与正文同族)。强制字体配对:一个展示字+一个正文字,绝不同族。
  只改配色不改骨架 = 在样式层打补丁,不算修好。
【其次】核对界面是否泄露能力边界:不得出现正确率判定/得分/排名/讲解/学习建议/艾宾浩斯提醒/打卡徽章。
  (用户自己填的练习条数不算判定,产品替他判断对错才算。)
【其三】核对图片红线在界面上的三处表达:登录同意点、原图删除倒计时、立即删除入口。
【其四】题型层要能表达'整块没碰过',不能只有考点级色块 —— 这是树相对平铺清单的全部优势。
目标用户 20-30 岁,分发渠道是知乎与小红书,截图传播能力是评审项。输出问题清单,不直接改实现。"

mk_agent "功能测试" opencode "" "" \
"你是功能测试员。覆盖采集→打标→挂树→差集→Top5 的完整链路。
必测:
- 离线队列:无网时记录动作必须不失败,联网后补处理(R-32)
- 原图过期删除:改系统时间验证到期真的删,且从未上传到任何厂商图片暂存API(R-04)
- 宁缺毋滥:低置信度必须丢弃且不计入覆盖度,不得硬凑最接近的考点
- 闭集打标:构造诱导性输入,验证模型不会生成新标签文本
- 账号合并:同一人两端登录不得把行为层拆成两半,否则盲区凭空多出来(R-33)
- 降级:ASR/OCR/打标任一不可用时,记录动作本身仍不失败,先落地后异步补
- 导出:MD/CSV/JSON 三格式无删减,且不含任何机构内容
- MCP/CLI:只读,不存在写入或采集接口
输出可复现的失败用例,不直接改实现。"

mk_agent "深度测试-推理" codex gpt-5.6-sol high \
"你做对抗性与推理型测试,补功能测试覆盖不到的部分。默认立场是怀疑,不确定时判定为未通过。
重点:
- 打标评测集的统计有效性:样本量够不够支撑'准确率85%'这个断言?抽样是否分层?
- 阈值选择:跑阈值扫描,验证'宁可丢弃率高不可准确率低'这个取向是否真的被阈值实现了
- 差集正确性:构造边界数据(整组未触达、重复来源、跨设备记录、同一考点多来源)验证覆盖度计算
- 种子标注收敛判据:'连续50题无新考点'是否被脚本量化执行,而不是凭感觉停
- 反向验证:尝试证伪'差集结果对用户是准的'这一断言 —— 这正是关卡1的判据
- 关卡数据落在临界线时,警告任何'微调产品再试一次'的提议(R-10:同一关卡失败三次即为失败)"

# ══════════════ 2. Squad ══════════════
say "建立 squad 并编入成员"

mk_squad() { # $1=名称 $2=描述 $3=leader名 $4..=成员名
  local nm="$1" desc="$2" leader="$3"; shift 3
  local sid out
  if exists "$SQ" "$nm"; then
    sid="$(idof "$SQ" "$nm")"; dim "· squad $nm 已存在,跳过创建  ($sid)"
  else
    if [[ -z "$(get "agent:$leader")" ]]; then err "squad $nm: leader $leader 不存在,跳过"; return 0; fi
    out="$(M squad create --name "$nm" --description "$desc" --leader "$leader" --output json 2>&1)"
    sid="$(printf '%s' "$out" | jqid)"
    if ! uuid_ok "$sid"; then
      err "squad $nm 创建失败: $(printf '%s' "$out" | tr '\n' ' ' | head -c 200)"; return 0
    fi
    dim "+ squad $nm  ($sid,leader=$leader)"
  fi
  uuid_ok "$sid" || { err "squad $nm 无有效 id,跳过成员编入"; return 0; }
  local m mid
  for m in "$@"; do
    mid="$(get "agent:$m")"
    if [[ -z "$mid" ]]; then err "  成员 $m 无 id,跳过"; continue; fi
    if M squad member add "$sid" --member-id "$mid" --type agent --output json >/dev/null; then
      dim "  ↳ $m"
    else
      dim "  ↳ $m (已在组内或添加失败,忽略)"
    fi
  done
}

mk_squad "开发组" "三条线的开发执行 · claude" \
  "产品轨-开发" "合规轨-执行" "数据轨-开发"
mk_squad "审核组" "文档与UI审核 · codex + opencode" \
  "文档审核" "UI审核"
mk_squad "测试组" "功能与深度测试 · opencode + codex" \
  "功能测试" "深度测试-推理"

# ══════════════ 3. Project(三条线各一) ══════════════
say "建立 project"

mk_project() { # $1=标题 $2=图标 $3=lead $4=描述
  local t="$1" icon="$2" lead="$3" desc="$4" pid out
  if exists "$PJ" "$t"; then
    pid="$(idof "$PJ" "$t")"; put "proj:$t" "$pid"; dim "· project $t 已存在  ($pid)"; return 0
  fi
  out="$(M project create --title "$t" --icon "$icon" --lead "$lead" --description "$desc" --output json 2>&1)"
  pid="$(printf '%s' "$out" | jqid)"
  if ! uuid_ok "$pid"; then
    err "project $t 创建失败(议题仍会建,只是不挂 project): $(printf '%s' "$out" | tr '\n' ' ' | head -c 200)"
    return 0
  fi
  put "proj:$t" "$pid"; dim "+ project $t  ($pid)"
}

mk_project "线1 产品轨" "🚦" "产品轨-开发" \
  "时钟=关卡。stage 1/2/3 = 阶段0/1/2,整组完成才唤醒父议题做关卡判定。"
mk_project "线2 合规轨" "📋" "合规轨-执行" \
  "时钟=审批周期。早产品轨一档启动,绝不越过关卡花钱。"
mk_project "线3 数据轨" "🗂" "数据轨-开发" \
  "时钟=风险边界。加工厂不是仓库,每周≤2小时。"

pflag() { local p; p="$(get "proj:$1")"; [[ -n "$p" ]] && printf -- '--project\n%s\n' "$p"; return 0; }

# ══════════════ 4. 议题树 ══════════════
say "建立三条线的父子议题"

# 注意 bash 3.2 + set -u:直接写 "${extra[@]}" 在数组为空时会报 unbound variable,
# 必须写成 ${extra[@]+"${extra[@]}"}。project 建失败时 extra 就是空的。
mkparent() { # $1=标题 $2=描述 $3=assignee $4=project标题
  local t="$1" pid out
  local extra=(); while IFS= read -r x; do extra+=("$x"); done < <(pflag "$4")

  # 重跑时父议题已存在,issue create 会因重复检测被拒 —— 必须改为复用已有 id,
  # 否则返回空 id,51 条子议题会全部变成无父孤儿。
  if exists "$IS" "$t"; then
    pid="$(idof "$IS" "$t")"
    if uuid_ok "$pid"; then dim "· 父议题已存在,复用  $t  ($pid)"; echo "$pid"; return 0; fi
  fi

  out="$(M issue create --title "$t" --description "$2" --assignee "$3" \
         --priority high ${extra[@]+"${extra[@]}"} --output json 2>&1)"
  pid="$(printf '%s' "$out" | jqid)"
  if ! uuid_ok "$pid"; then
    err "父议题创建失败: $t"
    err "  $(printf '%s' "$out" | tr '\n' ' ' | head -c 240)"
  fi
  echo "$pid"
}

mkchild() { # $1=父id $2=标题 $3=stage $4=assignee $5=project标题
  # 父 id 无效时必须停,绝不能拿空串或错误文本当 --parent 建出一堆孤儿议题
  uuid_ok "$1" || { err "  跳过(父议题无效): $2"; return 0; }
  # 先查目录再建。旧版直接建、靠服务端重复检测兜底,于是:
  #   1) 预演数不出"还差几条"(它把 55 条全列成待创建,而其中多数已存在)
  #   2) 真跑时"已存在"和"真失败"打印同一句,失败会被当成幂等噪音吞掉
  if exists "$IS" "$2"; then
    dim "  · 已存在,跳过: $2"
    return 0
  fi
  local extra=(); while IFS= read -r x; do extra+=("$x"); done < <(pflag "$5")
  if M issue create --title "$2" --parent "$1" --stage "$3" --assignee "$4" \
       ${extra[@]+"${extra[@]}"} --output json >/dev/null 2>&1; then
    return 0
  else
    err "  ✗ 创建失败(目录里没有同名议题,不是幂等跳过): $2"
    return 0
  fi
}

# ── 线1 产品轨 ──
P1=$(mkparent "【线1】产品轨 · 时钟=关卡" \
"docs/总路线图 第1条线。stage 1/2/3 分别对应阶段0/1/2,每个 stage 全部完成后唤醒本议题做关卡判定。
关卡0:日均≥3条且'懒得记'持续下降 → 否则停,地基不成立
关卡1:差集符合自我认知,误判可接受 → 否则回阶段0重想
关卡2:①覆盖度>40% ②点进盲区>30% ③14天回访>25% → ②不过即核心价值主张不成立,不要加功能
关卡是 pass/fail,不是可调目标(R-10)。" "产品轨-开发" "线1 产品轨")
dim "父议题 线1 = $P1"

for t in "1.1.1 语音链路(时长上限/失败兜底/成本基线)" \
         "1.1.2 拍照链路(多图合并/手写体实测)" \
         "1.1.2b 离线队列 — 无网仍可记,否则关卡0误判 🟠R-32" \
         "1.1.3 原图过期删除写死(含禁用厂商图片暂存API)🔴R-04" \
         "1.1.4 建立两列记录表 — 关卡0的全部输入 P0-6" \
         "1.1.5 自用14天" \
         "1.1.6 设备与账号隔离核对 🔴R-08"; do
  mkchild "$P1" "$t" 1 "产品轨-开发" "线1 产品轨"; done

for t in "1.2.1 真题归档(约65套)" \
         "1.2.2 种子标注至收敛(连续50题无新考点即停)" \
         "1.2.3 聚类与人工校正命名 🔴R-07" \
         "1.2.4 数据层落库(无题干字段,连预留位都不留)🔴R-01" \
         "1.2.5 自动打标能力 — 管线/评测集/阈值/成本" \
         "1.2.6 真题批量标与分层抽样校验" \
         "1.2.7 频次汇总四统计字段" \
         "1.2.8 打通闭环出Top5(含回填阶段0历史记录)" \
         "1.2.9 最小两屏 ⛔依赖设计方向 ⚪R-29" \
         "1.2.10 自用验证与误判清单"; do
  mkchild "$P1" "$t" 2 "产品轨-开发" "线1 产品轨"; done

for t in "1.3.1 账号体系(手机号注册登录/唯一性与合并策略/注销与数据删除)🟠R-33" \
         "1.3.2 Web-PWA 化(阶段2不做小程序)" \
         "1.3.3 跨设备同步与存储" \
         "1.3.4 用户协议与隐私政策" \
         "1.3.5 埋点三指标 + 北极星(主动查看盲区的人数)" \
         "1.3.6 导出 MD/CSV/JSON 全量无删减" \
         "1.3.7 可靠性与降级 — 记录动作永不失败" \
         "1.3.8 内测自检" \
         "1.3.9 招募10个真实用户" \
         "1.3.10 观察期与第一个问题记录"; do
  mkchild "$P1" "$t" 3 "产品轨-开发" "线1 产品轨"; done

# ── 线2 合规轨 ──
P2=$(mkparent "【线2】合规轨 · 时钟=审批周期" \
"docs/总路线图 第2条线。永远早产品轨一档启动,但绝不越过关卡花钱。
公司主体已存在,注册环节作废;但 ICP 备案 3-5 周的纯等待仍在,故排在关卡1之后启动。
依赖链:域名实名(公司主体)→大陆服务器→ICP备案→小程序业务域名校验。三者主体必须一致。" \
"合规轨-执行" "线2 合规轨")
dim "父议题 线2 = $P2"

for t in "2.1.1 域名注册与公司主体实名(国内注册商)" \
         "2.1.2 营业执照与经营范围核对 ⚪R-28 十分钟可做" \
         "2.1.3 律师一次问完(AI登记/统计事实商用边界/留证格式/协议)⚪R-24~26" \
         "2.1.4 短信签名与模板报备 — 卡第9周登录,现在就能办" \
         "2.1.5 境外基础设施(阶段0-2 用)"; do
  mkchild "$P2" "$t" 1 "合规轨-执行" "线2 合规轨"; done
for t in "2.2.1 大陆服务器采买" \
         "2.2.2 ICP备案(24h短信核验/避开高峰)" \
         "2.2.3 软著申请(30-60天)"; do
  mkchild "$P2" "$t" 2 "合规轨-执行" "线2 合规轨"; done
for t in "2.3.1 小程序注册" "2.3.2 主体认证" \
         "2.3.3 类目选工具/效率(不选教育)🟡R-16" \
         "2.3.4 业务域名校验" "2.3.5 服务器域名 + HTTPS" \
         "2.3.6 只做采集入口,分析跳转Web(L-C6)" \
         "2.3.7 微信登录与手机号账号打通"; do
  mkchild "$P2" "$t" 3 "合规轨-执行" "线2 合规轨"; done

# ── 线3 数据轨 ──
P3=$(mkparent "【线3】数据轨 · 时钟=风险边界" \
"docs/总路线图 第3条线。加工厂不是仓库:真题原文只存在离线加工区,产出物只有考点树+四统计字段+题目标识留证。
关卡0前一条都不抓。每周≤2小时,只用周日复盘溢出,不得挤占周六(写代码)或工作日晚上(打标)。
自检:某一周数据轨进展比产品轨大,那一周就是跑偏的(R-11)。" "数据轨-开发" "线3 数据轨")
dim "父议题 线3 = $P3"

mkchild "$P3" "3.1 关卡0前不启动(占位,勿动)" 1 "数据轨-开发" "线3 数据轨"
for t in "3.2.1 A级源清单(官方政务公告)" \
         "3.2.2 B级源清单(官方考试大纲)" \
         "3.2.3 逐站阅读 robots 与 ToS"; do
  mkchild "$P3" "$t" 2 "数据轨-开发" "线3 数据轨"; done
for t in "3.3.1 抓取纪律写进代码 🔴R-02 不写登录/绕过能力" \
         "3.3.2 三区隔离:离线加工区/中间区/线上区 🔴R-01" \
         "3.3.3 科目优先级(军队文职最后或不做 ⚪R-27)" \
         "3.3.4 工时上限每周≤2h 🟠R-11" \
         "3.3.5 越界判据:第二科目人工命名即越界 🟠R-12"; do
  mkchild "$P3" "$t" 3 "数据轨-开发" "线3 数据轨"; done

# ══════════════ 5. 横向职能议题 ══════════════
# 无父无 stage 的横向议题。和 mkchild 一样先查目录,让预演能数准。
mkflat() { # $1=标题 $2=assignee $3=priority $4=描述
  if exists "$IS" "$1"; then dim "  · 已存在,跳过: $1"; return 0; fi
  if M issue create --title "$1" --assignee "$2" --priority "$3" \
       --description "$4" --output json >/dev/null 2>&1; then
    return 0
  else
    err "  ✗ 创建失败: $1"
    return 0
  fi
}

say "建立审核与测试议题"
mkflat "【审核】文档合规巡检" "文档审核" medium \
  "定期核对 docs/ 变更是否违反决策层与风险登记册。执行层不得覆盖决策层;'待确认'项不得被推理悄悄关闭;不得展开关卡2之后的排期。"
mkflat "【审核】UI 去AI味与能力边界巡检" "UI审核" medium \
  "查骨架级同质化(左菜单+面包屑+表格+统一按钮)、字体同族、能力边界泄露(正确率判定/讲解/打卡)、图片红线三处表达、题型层是否能表达整块未触达。"
mkflat "【测试】采集到差集全链路" "功能测试" high \
  "离线队列/原图过期删除/宁缺毋滥/闭集打标/账号合并/降级/导出/MCP只读。输出可复现失败用例。"
mkflat "【测试】打标评测集与阈值对抗验证" "深度测试-推理" high \
  "样本量是否支撑准确率断言;分层抽样;阈值是否真的实现了'宁可丢弃率高不可准确率低';差集边界数据;反向证伪关卡1判据。"

say "完成"
[[ $DRY == 1 ]] && dim "(预演模式,未实际创建)"
if [[ $DRY == 0 ]]; then
  dim "核对: multica ${WSFLAG[*]:-} agent list; multica ${WSFLAG[*]:-} squad list; multica ${WSFLAG[*]:-} issue list"
fi
exit 0
