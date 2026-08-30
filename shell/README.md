# shell —— 考点盲区 · 壳

> 一份 `web/dist` + 一个 cargo crate,三端共用。
> 契约在 `docs/15-壳技术方案:Tauri 2 包现有 Web 工程.md`,本文只讲怎么跑、跑不起来看哪儿。

```bash
./build.sh check      # 只跑校验,不构建。改完代码先跑这个
./build.sh macos      # macOS 客户端(KUBI-65)
./build.sh android    # Android(KUBI-67)
./build.sh ios        # iOS(KUBI-66)
```

**`build.sh` 是唯一入口。** 直接 `cargo tauri build` 会绕过 ① 那一整段校验 ——
其中 cargo 依赖源那一条在公司网络里不会报错,静默发生(`R-75`)。

## 壳做的四件事

读配置 → 绑端口 → 起回环服务 → 起定时器。没有第五件。

壳不新增任何界面、不解释任何业务规则、不读请求体。页面地址是 `http://127.0.0.1:17840`,
于是 `/api/*` 与页面同源 —— 前端一个字都不知道自己在壳里跑。

| 模块 | 干什么 | 平台差异 |
|---|---|---|
| `config.rs` | 端口与上游地址,端口持久化 | 零 |
| `local_server.rs` | 内嵌 `web/dist` 直出 + `/api` 反代 | 零 |
| `scheduler.rs` | 唯一的后台定时器注入点 | 零 |
| `strings.rs` | 所有面向用户的字符串 | 零 |
| `platform/` | 归档目录、后台定时器支不支持 | **只有这里** |

## 自检都在 `./build.sh check` 里

| # | 检的是 | 判据 |
|---|---|---|
| ①.3 | 平台差异只在 `platform/` | `grep -rn 'cfg(target_os' src --exclude-dir=platform` 零命中 |
| ①.4 | `scheduler` 不碰网络 | 它的 `use` 列表里没有任何 HTTP 客户端 |
| ①.7 | 能力边界文案扫描 | 词表**读 `web/scripts/capability-boundary-scan.mjs`**,壳这边没有第二份 |
| ①.7 | 文案集中 | 带中文的字符串字面量只允许在 `src/strings.rs` |

**词表全仓库只有一份,而且住在 `web/` 里。** 复制一份到壳里,就等于给「改词表绕过扫描」
开了一个谁也看不出来的入口:撞词的人只改壳那一份,web 那份一个字没动。
撞词时改 `src/strings.rs` 的文案。

「文案集中」是「扫描只有一处要扫」的前提:只要别处能 `eprintln!` 一句中文,
扫描就看不见它,而那句话照样会出现在用户眼前。

## Android(`KUBI-67`)

本轮范围就三件:**能构建、能装、能打开首页。** 不做推送、深链、原生相机、上架相关的一切。

```bash
export ANDROID_HOME=/usr/local/share/android-commandlinetools
export NDK_HOME="$ANDROID_HOME/ndk/<版本>"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./build.sh android --apk --debug --target aarch64
```

三件要知道的事:

1. **不接后端。** `build.sh` 的 android 分支不注入 `KAODIAN_SHELL_UPSTREAM`,
   `/api/*` 一律 502,首屏落在前端已有的「离线示例数据」整屏回退上 ——
   一行额外代码都不用写,而且它诚实地标着自己是示例数据。
   不接不是偷懒:回环监听口在移动端对同设备其它 app 可见(`R-72`),
   那个暴露面在 macOS 上有说法(单用户机器),在移动端还没有。
2. **明文只对 `127.0.0.1` 开。** Android 9 起默认禁止明文 HTTP,而壳的页面地址就是明文回环。
   `scripts/android-allow-loopback.py` 写一份网络安全配置:除回环外明文一律禁止。
   **不开 `usesCleartextTraffic="true"`** —— 那是对全网开的口子,
   和「只对回环开」在正常情况下表现一样,区别只在出事那天。
3. **`gen/` 不进仓库。** 它是从 `tauri.conf.json` 推出来的,进了仓库就有了第二份事实。
   所以那个 manifest 补丁每次构建都要重跑一遍,脚本因此写成幂等的。

签名走 debug keystore。上架才需要真签名,而本轮没有分发(`docs/15` §4.4)。

## 与契约不一致的地方(只有一处,别静默扩大)

**`docs/15` §3.4 规则 8「上游是 https 时走系统信任库」在壳里没有实现体。**
壳没有引入任何 TLS 栈,上游写成 `https://` 时**启动就拒绝并说明原因**,不是静默连不上。

理由:阶段 1/2 的上游是本机明文回环,线上域名(`L-A1`)还没注册;
引一个 TLS 栈会带进一串传递依赖,而依赖清单本身是一条边界(`Cargo.toml` 文件头)。
接线上上游的那一天由 `KUBI-65` 补,补的时候回去改 `docs/15` —— 不是在这里加一句注释。

**其余八条反代规则都在 `local_server.rs` 里逐条落着。** 其中两条最容易被后来的人「优化」掉:

- **规则 1:没有 fallback。** 「找不到就返回 index.html」这条最常见的 SPA 默认行为,
  恰好是 `client.ts` 里点名的那个故障(前端拿到 `Unexpected token '<'`)。前端没有路由。
- **规则 4:请求体流式转发,不缓冲落盘。** 图片走 base64 内联,一次 6 张必然超过任何默认缓冲。
  壳是第三个接入层,`docs/10` §8.1 禁令 5 在它身上原样重现(`R-74`)。

## 跑不起来时看哪儿

| 现象 | 多半是 |
|---|---|
| 启动就退出,说端口被占用 | 就是被占用了。壳**不会自动换端口** —— 端口是页面地址的一部分,换一次本机存的东西全部对不上(`R-73`) |
| 窗口白屏 | `web/dist` 构建过没有?`build.sh` 步骤 ③ 会先拦一次 |
| 页面出来了但整屏是离线示例数据 | 移动端本来就是这样(见上)。macOS 上则是 `:8080` 没起来 |
| Android 白屏、logcat 里 `ERR_CLEARTEXT_NOT_PERMITTED` | manifest 补丁没跑到。`gen/` 是不是被手动删过一半 |
