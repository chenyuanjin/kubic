#!/usr/bin/env bash
# 整条链路跑一遍:拍照 → 出文字 → 挂考点 → 覆盖度变了。
#
# 🔴 这是 KUBI-112 的完成判据本身,不是一个附赠的小工具。
#    它走的是三端将来要走的同一条路:反代 → basic_auth → 后端 → MySQL,
#    中间没有任何一步是绕过去的。跑不通就是没通,不许靠「分步手动验过了」代替。
#
# 用法(在 deploy/ 目录下):
#   ./smoke.sh                                   # 打本机 Caddy(127.0.0.1:8090)
#   BASE=http://62.234.164.41:8090 ./smoke.sh    # 打对外地址
#
# 口令从 .env + .caddy-plaintext 读。跑完会把自己造的那条记录删掉,可以反复跑。
set -euo pipefail
cd "$(dirname "$0")"

BASE="${BASE:-http://127.0.0.1:8090}"
API="$BASE/api/v1"
USER="${KAODIAN_TEST_USER:-kaodian}"
PASS="${KAODIAN_TEST_PASS:-$(cat .caddy-plaintext 2>/dev/null || true)}"
[[ -n "$PASS" ]] || { echo "缺口令:设 KAODIAN_TEST_PASS,或让 .caddy-plaintext 可读" >&2; exit 1; }
AUTH=(-u "$USER:$PASS")

j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "0. 这道门是真的:不带口令打【写】端点"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/syllabus/nodes/x/archive")
echo "   POST /syllabus/nodes/x/archive 无凭证 → $code"
[[ "$code" == "401" ]] || { echo "   ❌ 期望 401。反代的门没关上,后面的都不用看了" >&2; exit 1; }
echo "   ✅ 401 —— archive/delete/rename/order 这批无认证的写端点没有裸在公网上"

say "1. 起点覆盖度"
S0=$(curl -fsS "${AUTH[@]}" "$API/coverage/summary")
T0=$(echo "$S0" | j "d['total']"); C0=$(echo "$S0" | j "d['covered']"); P0=$(echo "$S0" | j "d['percent']")
echo "   分母 $T0 / 分子 $C0 / $P0%"

say "2. 找一个还没碰过的考点(它就是「盲区」)"
# 🔴 挑 state==EMPTY 的那个,不是 blindspots 的第一名。
# 第一名按 blindScore 排,而分最高的往往是「弱」——它【已经】计进分子了。
# 拿它跑完分子一动不动,这条自检就退化成一句「看起来没问题」。
NODE=$(curl -fsS "${AUTH[@]}" "$API/coverage/blindspots" \
  | j "[n['code'] for n in d['items'] if n['state']=='EMPTY'][0]")
echo "   选中 $NODE(state=EMPTY —— 它现在一条记录都没有)"

say "3. 记一笔(拍照那条路的落地口:先落记录,识别失败也不丢)"
TOKEN="smoke-$(date +%s)-$RANDOM"
REC=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"sourceName\":\"链路自检\",\"nodeCode\":\"$NODE\",\"kind\":\"PHOTO\",\"clientToken\":\"$TOKEN\"}" \
  "$API/records")
RID=$(echo "$REC" | j "d['record']['id']")
echo "   记录 $RID"

say "4. 拍照 → 出文字(一张真 PNG 走 /records/{id}/image;原图只在内存里过一趟,不落盘)"
PNG='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
RECOG=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"photos\":[\"$PNG\"]}" "$API/records/$RID/image")
echo "   $RECOG"
echo "   ↑ outcome 就是闭集打标的结果:要么从候选集里挑一个 id,要么「无匹配」。"
echo "     它【不生成】标签文字 —— 返回体里根本没有装文字的字段。"

say "5. 挂考点(闭集里选 id,不是写一个名字)"
curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"nodeCode\":\"$NODE\"}" "$API/records/$RID/tags" >/dev/null
echo "   已挂到 $NODE"

say "6. 覆盖度变了没有"
S1=$(curl -fsS "${AUTH[@]}" "$API/coverage/summary")
T1=$(echo "$S1" | j "d['total']"); C1=$(echo "$S1" | j "d['covered']"); P1=$(echo "$S1" | j "d['percent']")
echo "   分母 $T1 / 分子 $C1 / $P1%   (起点 $T0 / $C0 / $P0%)"
[[ "$C1" -gt "$C0" ]] && echo "   ✅ 分子从 $C0 涨到 $C1 —— 一条记录真的改变了这个产品唯一的那个数" \
  || echo "   ⚠️ 分子没动:选中的考点原本就已经碰过了(重复跑时正常),看 $NODE 的详情确认"

say "7. 幂等:同一个 client_token 再发一次(补传就是重发)"
REC2=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"sourceName\":\"链路自检\",\"nodeCode\":\"$NODE\",\"kind\":\"PHOTO\",\"clientToken\":\"$TOKEN\"}" \
  "$API/records")
RID2=$(echo "$REC2" | j "d['record']['id']")
[[ "$RID2" == "$RID" ]] && echo "   ✅ 返回同一条 $RID —— 幂等落在 uk_touch_client_token 上,没多进一条" \
  || { echo "   ❌ 出现了第二条 $RID2,分子会被数两次" >&2; exit 1; }

say "8. 收尾:删掉自检造的这条记录"
curl -fsS "${AUTH[@]}" -X DELETE "$API/records/$RID" >/dev/null
S2=$(curl -fsS "${AUTH[@]}" "$API/coverage/summary")
echo "   删除后:分母 $(echo "$S2" | j "d['total']") / 分子 $(echo "$S2" | j "d['covered']")"

say "链路自检结束 —— $BASE"
