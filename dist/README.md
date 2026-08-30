# 客户端构建产物

**这个目录下的产物不进 git**(见根 `.gitignore`)—— 单次 48MB 且每次构建都变,
git 不对二进制做增量,提交几次仓库就 clone 不动了。目录结构保留,产物自己构建。

## 重新构建

```bash
cd shell
./build.sh macos      # → target/aarch64-apple-darwin/release/bundle/macos/考点盲区.app
./build.sh android    # → gen/android/app/build/outputs/apk/universal/release/*.apk
./build.sh ios        # → 模拟器
```

`build.sh` 是唯一入口。不要直接 `cargo tauri build` —— 它在下载任何东西之前先做
隔离校验,而 `~/.cargo/config.toml` 里一行源替换会让依赖静默走公司内网,
**在公司网络里不会报错**(R-75)。

## 2026-08-30 首次构建实测(回归基准)

| 端 | 产物 | 实测 |
|---|---|---|
| macOS | `考点盲区.app` **5.7 MB** | 启动后 `GET /` → 200;只绑回环 `127.0.0.1:17840` |
| Android | APK **29 MB** | 四 ABI 全架构:`arm64-v8a` `armeabi-v7a` `x86` `x86_64` |
| | AAB 14 MB | Play 商店格式 |
| iOS | — | 模拟器可装、可开首页 |

三端构建时 `web/` 与 `server/` 的 diff **都是 0** —— 这条由 `build.sh` 构建期核,不靠自觉。

## 签名

macOS 是 **ad-hoc 签名**,自用。首次打开走一次「右键 → 打开」。
分发给别人才需要 Developer ID + 公证。

Android 自签密钥库在 `~/Desktop/kaodian-shells/kaodian-selfsign.jks`(**不在仓库里**,
`.gitignore` 挡了 `*.jks`)。**下次签名必须用同一个** —— 换了密钥 Android 会当成
另一个应用,拒绝覆盖安装,用户得先卸载,而卸载会带走他本机的原图缓存。

```bash
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/zipalign -p -f 4 未签名.apk 考点盲区.apk
$BT/apksigner sign --ks ~/Desktop/kaodian-shells/kaodian-selfsign.jks \
  --ks-pass pass:kaodian --key-pass pass:kaodian --ks-key-alias kaodian 考点盲区.apk
```
