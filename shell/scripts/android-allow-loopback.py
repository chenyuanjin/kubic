#!/usr/bin/env python3
"""让 Android 的 WebView 能打开 http://127.0.0.1:<port>,并且只能打开它。

Android 9 起默认禁止明文 HTTP。壳的页面地址就是明文回环 —— 这不是将就:
回环段不做本地 TLS,两种实现都是安全倒退(docs/15 §3.6),而这段流量不出网卡。

所以这里不开 usesCleartextTraffic="true"(那是对全网开的口子),
而是写一份网络安全配置:除 127.0.0.1 外,明文一律禁止。
「只对回环开」和「对所有地址开」在正常情况下表现一样,区别只在出事那天。

gen/ 是 tauri android init 生成的、不进仓库(docs/15 §2.4),
所以这一步必须每次构建都跑一遍,而不是改一次就算数。写成幂等的。
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
GEN = pathlib.Path(__file__).resolve().parents[1] / "gen" / "android"
MANIFEST = GEN / "app" / "src" / "main" / "AndroidManifest.xml"
CONFIG = GEN / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"

CONFIG_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 shell/scripts/android-allow-loopback.py 生成,改这里没用,改脚本。 -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
"""

if not MANIFEST.exists():
    print(f"找不到 {MANIFEST} —— 先跑 cargo tauri android init。", file=sys.stderr)
    sys.exit(1)

CONFIG.parent.mkdir(parents=True, exist_ok=True)
CONFIG.write_text(CONFIG_XML, encoding="utf-8")

raw = MANIFEST.read_text(encoding="utf-8")
if "networkSecurityConfig" in raw:
    print("android 网络安全配置:已在 manifest 里,跳过")
    sys.exit(0)

# 只往 <application 这一个标签上加一个属性。用文本插入而不是重写整棵树,
# 是因为 ElementTree 会把 android: 前缀重排成 ns0:,那份 manifest 就废了。
patched, n = re.subn(
    r"(<application\b)",
    r'\1\n        android:networkSecurityConfig="@xml/network_security_config"',
    raw,
    count=1,
)
if n != 1:
    print("manifest 里找不到 <application> —— 生成物的形状变了,先看一眼再改脚本。", file=sys.stderr)
    sys.exit(1)

MANIFEST.write_text(patched, encoding="utf-8")
ET.fromstring(patched)  # 改完得还是一份合法 XML,不合法就在这里炸,不留到 gradle
print("android 网络安全配置:已写入(明文只对 127.0.0.1 开)")
