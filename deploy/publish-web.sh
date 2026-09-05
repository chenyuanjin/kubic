#!/usr/bin/env bash
# 把 web 构建产物发到测试环境的静态目录,并【当场验一次发出去的就是刚构建的那份】。
#
# 🔴 这个脚本存在的理由是一次真事故的预防,不是为了少敲两行命令。
#
#    在它之前,「构建 → 同步 site/」这一步只活在某个人的 shell 历史里:
#    `deploy/site/` 按 R-08 不进仓库(这条判断是对的 —— 产物不该进仓库),
#    而 README 里只有 `mkdir -p …/site` 建了个空目录,Caddyfile 与 compose 里
#    那两句「把 web/dist 拷进来即可」都是【注释】,没有一处是可执行的。
#    后果有两个,第二个更要紧:
#      ① 别人照 README 敲一遍,site/ 还是空的,/ 还是 404 —— 复现不出来。
#      ② 仓库往前走,而 / 继续发上一次手动同步的那版旧包。构建绿、边界扫描绿、
#         集成 SHA 也对,四个端从 / 取到的却是旧界面 —— 而没有任何一条断言
#         在看「部署出去的那份」和「仓库里那份」是不是同一个。
#         摘红线项的那一轮如果撞上这个,红线在仓库里摘干净了,在端上还挂着。
#
# 🔴 判据不是「rsync 成功」,是【HTTP 上取回来的 index.html 引的就是刚构建的那个哈希】。
#    Vite 的产物名带内容哈希(/assets/index-<hash>.js),所以这一个字符串就足以
#    区分两次构建 —— 不需要再造一个版本号文件,那会多出一个可以自己漂掉的东西。
#
# 用法(在仓库任意位置):
#   ./deploy/publish-web.sh              # 构建 → 同步 → 验证
#   ./deploy/publish-web.sh --check      # 只构建 + 验证,【不】同步 —— 回答「线上那份 == 仓库这份 吗」
#
#   REMOTE=ubuntu@62.234.164.41  BASE=http://127.0.0.1:8090  ./deploy/publish-web.sh
#   BASE 走桥时填 http://192.168.3.123:8090
#
# 不需要口令:静态直出那一段本来就不在 basic_auth 后面(见 Caddyfile),
# 所以这个脚本全程不碰凭证 —— 少一个能泄漏的地方。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE="${REMOTE:-ubuntu@62.234.164.41}"
SITE="${SITE:-~/kaodian/deploy/site/}"
BASE="${BASE:-http://127.0.0.1:8090}"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1. 构建"
# cd 进 web/ 而不是 npm --prefix:npm 读的是【当前工作目录】的 .npmrc(与 shell/build.sh 同一条理由)。
(cd "$REPO/web" && npm run build >/dev/null)

# 本地这一份的身份就是 index.html 里引的那个带哈希的 js 路径。
LOCAL_JS=$(grep -oE '/assets/[A-Za-z0-9._-]+\.js' "$REPO/web/dist/index.html" | head -1)
[[ -n "$LOCAL_JS" ]] || { echo "❌ web/dist/index.html 里找不到 /assets/*.js —— 构建产物不对" >&2; exit 1; }
echo "   本地构建 $LOCAL_JS"

if (( CHECK_ONLY == 0 )); then
	say "2. 同步到 $REMOTE:$SITE"
	# --delete 不是可选项:上一次构建的哈希文件名与这一次不同,不删就会在 site/ 里
	# 越堆越多,而 index.html 只引其中一个 —— 磁盘慢慢满,且分不清哪份是活的。
	rsync -az --delete -e ssh "$REPO/web/dist/" "$REMOTE:$SITE"
	echo "   已同步"
else
	say "2. --check:跳过同步"
fi

say "3. 验:HTTP 上取回来的是不是刚构建的这一份"
code=$(curl -s -o /tmp/publish-web-index.$$ -w '%{http_code}' "$BASE/") || true
echo "   GET $BASE/ → $code"
[[ "$code" == "200" ]] || {
	echo "   ❌ 期望 200。site/ 是空的(/ 会是 404),或者反代没起来" >&2
	rm -f /tmp/publish-web-index.$$; exit 1
}

LIVE_JS=$(grep -oE '/assets/[A-Za-z0-9._-]+\.js' /tmp/publish-web-index.$$ | head -1)
rm -f /tmp/publish-web-index.$$
echo "   线上引用 ${LIVE_JS:-（没有）}"
[[ "$LIVE_JS" == "$LOCAL_JS" ]] || {
	echo "   ❌ 线上发的不是仓库这一份。" >&2
	echo "      这正是这个脚本要拦的那件事:构建绿、SHA 对,而端上拿到的是旧包。" >&2
	(( CHECK_ONLY == 1 )) && echo "      跑一次不带 --check 的本脚本即可发上去。" >&2
	exit 1
}

code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$LIVE_JS")
echo "   GET $LIVE_JS → $code"
[[ "$code" == "200" ]] || { echo "   ❌ index.html 发出去了但它引的 js 没有 —— 同步只完成了一半" >&2; exit 1; }

printf '\n\033[1m✅ 线上这一份 == 仓库这一份(%s)\033[0m\n' "$LOCAL_JS"
