#!/usr/bin/env bash
# Android 出包的唯一入口(`KUBI-118`)。
#
# 为什么不直接敲 `cargo tauri android build`:
#   那条命令是【第二条构建路径,而它绕过 build.sh 步骤 ① 的隔离校验】——
#   与 tauri.conf.json5 里 beforeBuildCommand 留空要挡的是同一件事(`R-111`)。
#   Android 出包不该因为「它是移动端」就少走一遍那三道自检。
#   所以这个脚本自己不做任何校验,它只做两件事:
#     ① 把 `./build.sh --check` 原样跑一遍(共享的那几道闸,一条都不关)
#     ② 补上 build.sh 覆盖不到的、只有 Android 才有的前置与断言
#
# 🔴 它【不】改 build.sh。build.sh 是 macOS 与 iOS 也在读的共享文件,
#    而 Android 这一轮的边界是「只动 shell/ 的 Android 处」。
#
# 用法:
#   ./scripts/android-build.sh                 出 debug apk(默认 aarch64,真机与 arm64 模拟器)
#   ANDROID_TARGET=x86_64 ./scripts/android-build.sh    x86_64 模拟器
set -euo pipefail
cd "$(dirname "$0")/.."

step() { printf '\n\033[1m── %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m✗ %s\033[0m\n\n' "$*" >&2; exit 1; }

TARGET="${ANDROID_TARGET:-aarch64}"

# ══════════════════════ ⓪ 前置:三条工具链都得在 ══════════════════════
#
# 🔴 一条都不自动装 —— 与 README 里那张表同一条理由:
# 改动别人机器上的工具链应当是一次显式的决定。这里只负责【说清楚缺哪一个】,
# 因为「照着命令敲能出同一个 apk」的第一个障碍从来不是命令写错,是环境差一件。

step "⓪ Android 工具链"

[ -n "${ANDROID_HOME:-}" ] || die "ANDROID_HOME 没设。
  装法:brew install --cask android-commandlinetools
  然后 export ANDROID_HOME=/usr/local/share/android-commandlinetools
  再用 sdkmanager 装上 platforms;android-34 与 build-tools;34.0.0"
[ -d "$ANDROID_HOME" ] || die "ANDROID_HOME 指向的目录不存在:$ANDROID_HOME"

# NDK 版本号是这条链上最容易「在我机器上是好的」的一处:
# 它带着一串补丁号,写死在文档里的那一串换台机器就不对。
# 所以这里【自己找】,并且在找到零个或多个时停下来问,不猜一个。
if [ -z "${NDK_HOME:-}" ]; then
  NDK_CANDIDATES=("$ANDROID_HOME"/ndk/*/)
  if [ ! -d "${NDK_CANDIDATES[0]}" ]; then
    die "$ANDROID_HOME/ndk 下一个 NDK 都没有。
  装:sdkmanager --install 'ndk;26.3.11579264'"
  elif [ "${#NDK_CANDIDATES[@]}" -gt 1 ]; then
    die "$ANDROID_HOME/ndk 下有多个 NDK,不替你选:
$(printf '    %s\n' "${NDK_CANDIDATES[@]}")
  用 NDK_HOME=<其中一个> 再跑一次。"
  fi
  NDK_HOME="${NDK_CANDIDATES[0]%/}"
  export NDK_HOME
fi
[ -d "$NDK_HOME" ] || die "NDK_HOME 指向的目录不存在:$NDK_HOME"

# JDK 21。Android Gradle Plugin 8.x 要 17+,而本机装了三个 JDK(含两个 8),
# 不显式指到 21 会挑到 1.8 然后在 Gradle 那一步才炸,报的还是一句与 JDK 无关的话。
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" \
    || die "找不到 JDK 21。装:brew install --cask oracle-jdk 或 temurin@21"
  export JAVA_HOME
fi

RUST_TARGET="$TARGET-linux-android"
[ "$TARGET" = "armv7" ] && RUST_TARGET="armv7-linux-androideabi"
rustup target list --installed | grep -qx "$RUST_TARGET" \
  || die "缺 $RUST_TARGET 的标准库。装它:rustup target add $RUST_TARGET"

command -v cargo-tauri >/dev/null 2>&1 || cargo tauri --version >/dev/null 2>&1 \
  || die "缺 cargo-tauri。装:cargo install tauri-cli --version \"^2.0\" --locked"

printf '  ✓ ANDROID_HOME %s\n  ✓ NDK          %s\n  ✓ JAVA_HOME    %s\n  ✓ rust target  %s\n  ✓ %s\n' \
  "$ANDROID_HOME" "$(basename "$NDK_HOME")" "$JAVA_HOME" "$RUST_TARGET" "$(cargo tauri --version)"

# ══════════════════════ ① 共享自检,一条不关 ══════════════════════
step "① 共享自检(./build.sh --check)"
./build.sh --check

# ══════════════════════ ② 生成 Android 工程 ══════════════════════
#
# `gen/` 是 gitignore 的(见 .gitignore),所以这一步每台机器都要跑,而且它是幂等的。
# 🔴 由此得到一条纪律:**任何改动都不许落在 `gen/` 里** —— 下一次 init 会把它抹掉,
#    而抹掉的那一刻不会有任何东西变红。要改 Android 的行为,改的是 Rust 侧或这个脚本。
step "② 生成 Android 工程(幂等)"
cargo tauri android init

# ══════════════════════ ③ 出包 ══════════════════════
step "③ 出包(debug apk · $TARGET)"
cargo tauri android build --debug --apk --target "$TARGET" --ci

# ══════════════════════ ④ 返回键契约 ══════════════════════
#
# 🔴 这条断言是本脚本存在的第二个理由。
#
# Android 的系统返回键交给 webview,这件事【不是我们写的代码】——
# 它是 wry 的 `WryActivity.setWebView()` 里那个 `OnBackPressedCallback`:
# `canGoBack()` 就 `goBack()`,否则才退出 Activity。
# 我们什么都没写,正好就是想要的语义(返回键 → 路由回退 → 栈空才退出)。
#
# 而「什么都没写」的东西没有任何一处会在它消失时变红:
# wry 换个版本把 `handleBackNavigation` 的默认值改成 false,或者有人在
# `gen/` 里 override 掉它,表现是【返回键从任何一屏都直接退出整个 app】——
# 一个不报错、不崩溃、只是行为悄悄退化的故障。
#
# 所以这里对生成出来的那份 Kotlin 做一次文本断言。它扫的是构建产物不是源码,
# 这正是它能成立的原因:源码里根本没有这段逻辑可扫。
step "④ 返回键契约(系统返回键 → webview 回退)"
WRY_ACTIVITY="$(find gen/android/app/src/main/java -name WryActivity.kt -path '*/generated/*' | head -1)"
[ -n "$WRY_ACTIVITY" ] || die "找不到生成的 WryActivity.kt —— 返回键的落点验不了,不当作通过。"
# `canGoBack()` 在这份文件里【只出现一次】,就在那个返回键回调里(实测 wry 0.55.1)。
# 所以这一条 grep 就是那条契约本身,不是它的近似。
# 🔴 只留这一条:再补一条 `handleBackNavigation` 的 grep 是同一件事的第二次断言 ——
#    wry 真要把返回键交还给系统,这两个词是一起消失的。
grep -q 'canGoBack()' "$WRY_ACTIVITY" \
  || die "生成的 WryActivity.kt 不再调 canGoBack() —— 返回键多半已经变成
  「从任何一屏一按就退出整个 app」。这不是可以先出包再说的事:
  先去 shell/README.md「Android · 返回键」核对 wry 换了什么。"
# MainActivity 是模板文件,人可以改它。改成 false 就把上面那段整个关掉了。
if grep -RIqs 'handleBackNavigation' gen/android/app/src/main/java --include=MainActivity.kt; then
  die "MainActivity.kt 覆写了 handleBackNavigation —— 而 gen/ 不进仓库,
  这次覆写只存在于这台机器上。要改返回键行为,改这个脚本旁边的说明,不要改 gen/。"
fi
echo "  ✓ 系统返回键落到 webview 的历史上(wry WryActivity),栈空才退出"

# ══════════════════════ ⑤ 产物 ══════════════════════
APK="gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
[ -f "$APK" ] || die "没出 apk —— 出包这一步没有产出可安装的东西"
printf '\n\033[32m出包完成\033[0m\n  %s\n' "$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
cat <<'TIP'

  装到已连接的设备 / 模拟器:
    adb install -r shell/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
    adb reverse tcp:8080 tcp:8080     # 壳反代的上游在宿主机上,不加这条 /api 一律 502

  🔴 debug 包,自用。它开着 webview 远程调试(chrome://inspect),
     所以【不要发给任何人】—— 分发要走 --release 并签名,而本轮没有分发。
TIP
