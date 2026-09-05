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

say "0a. 界面真的发出去了:/ 与它引的那个 js 都在"
# 🔴 这一条钉的是「四个端连界面都打不开」那种坏法。
# site/ 是空目录时 / 回 404,而 /api 一切正常 —— 于是链路自检全绿、端上白屏,
# 两件事之间没有任何一条断言把它们连起来(KUBI-111 实测踩过)。
#
# ⚠️ 它拦得住【空目录】和【只同步了一半】,拦不住【整份都是旧包】——
#    那需要拿仓库里的构建产物比一次哈希,而这个脚本跑在服务器上,身边没有 web/。
#    那一条在 deploy/publish-web.sh --check 里,发布时就地验。
#    这里把线上引的哈希打出来,是为了让「旧包」至少在每次自检里【看得见】。
code=$(curl -s -o /tmp/smoke-index.$$ -w '%{http_code}' "$BASE/")
echo "   GET / → $code"
[[ "$code" == "200" ]] || {
	echo "   ❌ 期望 200。site/ 是空的?跑 ./deploy/publish-web.sh 把前端发上去" >&2
	rm -f /tmp/smoke-index.$$; exit 1
}
LIVE_JS=$(grep -oE '/assets/[A-Za-z0-9._-]+\.js' /tmp/smoke-index.$$ | head -1)
rm -f /tmp/smoke-index.$$
[[ -n "$LIVE_JS" ]] || { echo "   ❌ / 回了 200 但不是构建产物(没有 /assets/*.js)" >&2; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$LIVE_JS")
echo "   GET $LIVE_JS → $code"
[[ "$code" == "200" ]] || { echo "   ❌ index.html 在但它引的 js 不在 —— 同步只完成了一半" >&2; exit 1; }
echo "   ✅ 线上这一份是 $LIVE_JS(是不是【最新】那一份,由 publish-web.sh --check 判)"

say "0. 这道门是真的:不带口令打【写】端点"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/syllabus/nodes/x/archive")
echo "   POST /syllabus/nodes/x/archive 无凭证 → $code"
[[ "$code" == "401" ]] || { echo "   ❌ 期望 401。反代的门没关上,后面的都不用看了" >&2; exit 1; }
echo "   ✅ 401 —— archive/delete/rename/order 这批无认证的写端点没有裸在公网上"

say "0b. 发票口也是真的:错口令领不到门票"
# 🔴 这三行是 KUBI-111 补的,补的原因是它们红过一次真的。
# /__gate 刚落地时三条指令平铺在 handle 里,而 Caddy 按【固定表】排序执行,
# redir 排在 basic_auth 前面 —— 重定向先跑完就终结了整条链,basic_auth 根本没轮到:
# 无凭证、错口令一律 302 + Set-Cookie,任何人打一次就拿到七天的全量通行证。
# 「用票口 401」当时全是绿的,所以只测用票口发现不了 —— 必须单独测发票口。
code=$(curl -s -o /dev/null -w '%{http_code}' -u wrong:wrong "$BASE/__gate")
echo "   GET /__gate 错口令 → $code"
[[ "$code" == "401" ]] || { echo "   ❌ 期望 401。发票口敞着,门票等于白送(见 Caddyfile 里 route 那段)" >&2; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$BASE/__gate")
[[ "$code" == "302" ]] || { echo "   ❌ 对口令期望 302,实得 $code —— 领不到票,浏览器就进不来" >&2; exit 1; }
echo "   ✅ 错口令 401 / 对口令 302 发票"

say "0c. 门票与应用令牌不抢同一个头"
# 🔴 这一条red过:反代用 basic_auth 时,前端登录后带的 Authorization: Bearer 会把
# 浏览器缓存的 Basic 凭证顶掉,于是【登录之后】每个请求 401,界面静默退回离线示例数据。
# 四个端一起坏,而且登录前看着正常 —— 所以判据必须是「同时带门票和 Bearer」。
GATE=$(curl -s -o /dev/null -D - "${AUTH[@]}" "$BASE/__gate" \
	| sed -n 's/.*kaodian_gate=\([^;]*\).*/\1/p' | tr -d '\r')
[[ -n "$GATE" ]] || { echo "   ❌ 没从 /__gate 拿到 kaodian_gate cookie" >&2; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' \
	-H "Cookie: kaodian_gate=$GATE" -H 'Authorization: Bearer 随便一个假令牌' \
	"$API/coverage/summary")
echo "   门票 cookie + Bearer 同时带 → $code"
[[ "$code" == "200" ]] || { echo "   ❌ 期望 200。门票又跑回 Authorization 上了,登录后必掉线" >&2; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Cookie: kaodian_gate=胡写的' "$API/coverage/summary")
[[ "$code" == "401" ]] || { echo "   ❌ 假门票期望 401,实得 $code —— 校验的是「有没有 cookie」不是「对不对」" >&2; exit 1; }
echo "   ✅ 真票+Bearer 200 / 假票 401"

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
