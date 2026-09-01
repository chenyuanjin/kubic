#!/usr/bin/env bash
# 副业项目构建入口 —— 唯一允许的构建方式(docs/technical/INDEX.md §1.3)
#
# 为什么不能直接用 ./mvnw:
#   本机 ~/.m2/settings.xml 配着公司私服镜像。直接构建会让依赖流量走公司内网 ——
#   一次实打实的交集,而且【在公司网络里不会报错】,静默发生。
#   本脚本强制使用独立配置(~/.m2/settings-side.xml)与独立本地仓库(~/.m2/repo-side)。
#
# 镜像用国内公共镜像加速。公共镜像 ≠ 公司私服:
#   前者公开匿名、人人可用,只是 Maven Central 的地理加速;后者在内网、要公司凭据。
#   下面的白名单校验的就是这条线。
set -euo pipefail
cd "$(dirname "$0")"

SIDE_SETTINGS="$HOME/.m2/settings-side.xml"
SIDE_REPO="$HOME/.m2/repo-side"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home}"

[ -f "$SIDE_SETTINGS" ] || { echo "缺少 $SIDE_SETTINGS,见 docs/technical/INDEX.md §1.3" >&2; exit 1; }

# —— 隔离校验 ——
# 只看【生效的配置】,不看注释:注释里为了说明反例会出现内网地址,那不是配置。
# 先剥掉 XML 注释,再取所有 <url>,逐个比对公共镜像白名单。
python3 - "$SIDE_SETTINGS" <<'PY'
import re, sys, urllib.parse

ALLOWED_HOSTS = {
    "repo1.maven.org",              # Maven Central 官方
    "repo.maven.apache.org",        # Maven Central 官方
    "maven.aliyun.com",             # 阿里云公共镜像
    "mirrors.cloud.tencent.com",    # 腾讯云公共镜像
    "repo.huaweicloud.com",         # 华为云公共镜像
    "mirrors.tuna.tsinghua.edu.cn", # 清华 TUNA
}

raw = open(sys.argv[1], encoding="utf-8").read()
effective = re.sub(r"<!--.*?-->", "", raw, flags=re.S)      # 剥注释

bad = []
for url in re.findall(r"<url>\s*([^<]+?)\s*</url>", effective):
    host = urllib.parse.urlparse(url).hostname or ""
    if host not in ALLOWED_HOSTS:
        bad.append(f"{url}  (host={host})")
    if urllib.parse.urlparse(url).scheme == "http":
        bad.append(f"{url}  (明文 http,公共镜像应走 https)")

# 生效配置里出现任何凭据,一律拒绝 —— 公共镜像不需要用户名密码
if re.search(r"<username>|<password>", effective):
    bad.append("<servers> 里出现凭据:公共镜像不需要登录,出现凭据说明指向了私服")

if bad:
    print("拒绝构建 —— 依赖源未通过隔离校验(docs/technical/INDEX.md §1.3):", file=sys.stderr)
    for b in bad:
        print("  ✗ " + b, file=sys.stderr)
    sys.exit(1)
PY

exec ./mvnw -B -ntp -s "$SIDE_SETTINGS" -Dmaven.repo.local="$SIDE_REPO" "$@"
