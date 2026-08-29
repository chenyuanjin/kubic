# shell —— 三端共用的壳

> 把 `web/dist` 内嵌进一个 cargo crate,三端装的是**同一批字节**。
>
> 契约在**壳技术方案**(`KUBI-62`,当前在分支 `KUBI-62-tauri-shell-design` 上,
> 文件名 `docs/15-壳技术方案：Tauri 2 包现有 Web 工程.md`)。
> ⚠️ **那个编号在 `v1` 上已经被占了**(`docs/15` 是 Agent 框架,`docs/16` 是产品路线图),
> 合并进 `v1` 时它应当变成 `docs/17`。本目录里所有「壳技术方案 §x」的指代都指那份文档,
> 刻意不写死编号就是因为这个冲突还没解决。

## 现在做到哪一步

| 端 | 状态 | 议题 |
|---|---|---|
| iOS 模拟器 | ✅ **可构建、可安装、可打开首页** | `KUBI-66`(本轮) |
| macOS | ⬜ crate 骨架在,未落地 | `KUBI-65` |
| Android | ⬜ crate 骨架在,**从未构建过** | `KUBI-67` |

🔴 **`KUBI-66` 只要求「能构建、能装、能打开首页」,不做功能。**
推送、深链、原生相机、上架相关的一切都明确不做 —— 做多了反而要在后面删掉。

## 构建

```bash
./build.sh --check     # 只跑隔离校验
./build.sh ios         # iOS 模拟器
./build.sh             # macOS 桌面(KUBI-65 未落地,能编译不代表能用)
```

`build.sh ios` 会自己跑 `cargo tauri ios init`(`gen/` 不进仓库)、注入 ATS、再构建,
连跑多次结果一致。

### 前置

```bash
# 🔴 toolchain 必须是【与本机架构一致】的那个,见下面那条坑
rustup toolchain install stable-aarch64-apple-darwin
rustup target add aarch64-apple-ios-sim --toolchain stable-aarch64-apple-darwin
RUSTUP_TOOLCHAIN=stable-aarch64-apple-darwin cargo install tauri-cli --version '^2' --locked

# Xcode(含 iOS SDK)、xcodegen、cocoapods
#   ghcr.io 抽风时 brew 装不上 xcodegen,可直接取 GitHub release 的二进制
#   系统 ruby 是 2.6,cocoapods 要装 1.11.3(新版要 ruby ≥ 3)
gem install --user-install cocoapods -v 1.11.3
```

### 🔴 这一轮最贵的那个坑:cargo-tauri 自己的架构

本机默认 toolchain 是 `stable-x86_64-apple-darwin`,而 CPU 是 arm64。
用默认 toolchain `cargo install tauri-cli` 装出来的 `cargo-tauri` 是一个 **x86_64 二进制**,
而 tauri 的移动端逻辑**按自己这个二进制的架构去猜模拟器架构** —— 于是它给 xcodebuild
传 `ARCHS=x86_64`,构建出一个 x86_64 的 `.app`,装进 arm64 模拟器时报:

> “Kaodian”需要更新。此 App 需要开发者更新以在此 iOS 版本上运行。

**这句报错和真正的原因之间没有任何字面联系。** `build.sh` 因此显式检查
`cargo-tauri` 的架构并在不符时拒绝构建,把这半小时省给下一个人。

## 这个目录里的红线

| 红线 | 落点 | 谁执行 |
|---|---|---|
| 壳不调用任何外部模型 | `Cargo.toml` 无模型 SDK | `build.sh` ①b |
| 原图不出这台机器 | 无遥测 / 崩溃上报依赖;`scheduler` 不 import HTTP 客户端;反代不落盘不打日志 | `build.sh` ①b + 代码结构 |
| 三端不许分叉 | `#[cfg(target_os)]` 只在 `src/platform/` 下 | `build.sh` ①c |
| 与主业公司零交集 | cargo 源公共镜像白名单;全目录字样扫描 | `build.sh` ①a / ①d |
| 界面文案不越界 | 壳的全部用户可见字符串集中在 `src/strings.rs` 一个文件 | `web` 的 `npm run test:boundary` 扫的是 web;壳这一处**目前靠集中 + 人看** |

## 与方案的偏差(三处,都是显式的)

方案是契约,偏离要写下来而不是悄悄改。以下三处已在 `KUBI-66` 的交付评论里报给 `KUBI-62`:

**1 · 装配在 `lib.rs`,不在方案 §2.4 写的 `main.rs`。**
Tauri 2 的移动端产物是一个由 Xcode / Gradle 链接的库,入口是 `run()`,根本不经过 `main.rs`。
装配写在 `main.rs` 里,iOS 上一行都不会执行。所以装配下沉到 `lib.rs`,
`main.rs` 退化成桌面端的一行转发 —— 三端共用同一段装配,这正是方案 §4.1 要的结果。
`Cargo.toml` 因此多了一个 `[lib]` 段(`staticlib` / `cdylib` / `rlib`),这是 Tauri 2 移动端的硬性要求。

**2 · 上游默认值是「不接」,不是方案 §3.3 写的 `http://127.0.0.1:8080`。**
方案 §3.3 给的默认值是 `127.0.0.1:8080`,而 §4.2 同时要求 iOS/Android **留空**。
两条放在一起,`config.rs` 里就必须有一个按端分叉的默认值 —— 而 §4.1 明写这个文件零 `cfg`。
解法是让默认值**对三端相同(都是不接)**,由谁去接由外部显式给。
这样 iOS「不接后端」不是一条特例,而是**没人给它配**的自然结果。
`KUBI-65` 落地 macOS 时补配置文件层,把 `127.0.0.1:8080` 写在那里。

**3 · 多了一个 `Info.ios.plist`。**
壳的窗口指向 `http://127.0.0.1:17840`(明文 HTTP),而 iOS 的 App Transport Security
默认拒绝一切明文 HTTP 加载 —— 不加这个文件,iOS 上是**白屏**。
它必须在 `gen/` 之外,因为 `gen/` 是 git-ignored 的,写进去下一次 `init` 就没了。
🔴 用的是 `NSAllowsLocalNetworking`(只放开本地回环),**不是** `NSAllowsArbitraryLoads`
(那会把公网明文一起放开,等于拆掉 §3.6 的 TLS 边界)。

## 交给下游

| 给谁 | 什么 |
|---|---|
| `KUBI-65` | `local_server.rs` 的 `/api` 分支现在只有「502」这一支,**逐字节反代的九条规则一条都没实现**;`config.rs` 的配置文件持久化;`scheduler` 的常驻触发;`platform/macos.rs` |
| `KUBI-67` | `platform/android.rs` 的 `archive_dir()` 是**占位**,必须换成 `Context.getFilesDir()` 经 JNI 取的真路径 |
| `KUBI-63` / `KUBI-68` | `scheduler::tick` 是唯一的定时器注入点。🔴 定时器只负责「什么时候问」,判据留在判据层 |
| `KUBI-64` | 壳的用户可见字符串全在 `src/strings.rs`,现在只有三句失败提示 |

🔴 **iOS 真机备份排除未做。** `platform/ios.rs` 的 `archive_dir()` 落在
`Library/Application Support/` 下(默认不进「文件」app),但**仍会进 iTunes/iCloud 备份**。
本轮只上模拟器、且不写任何原图,所以不构成红线命中;
**但 iOS 一旦真的开始存原图,`isExcludedFromBackupKey` 必须先补上**,否则「绝不上云」就破了。

## 反代那九条规则,一条都不是风格问题

`local_server.rs` 现在只落地了其中三条(不 fallback、不读请求体、502 形状),
因为 iOS 不接后端,写了也没有任何东西能验证它对不对。
**`KUBI-65` 实现反代时要回到方案 §3.4 逐条对**,尤其是:

- 🔴 `Authorization` 原样透传,且不进任何级别的日志
- 🔴 请求体**流式转发,不缓冲落盘**(`R-74`:图片走 base64 内联,6 张必然超过任何默认缓冲)
- 🔴 不打印请求体与响应体的任何级别日志
- 上游超时 1500ms,短于前端的 `TIMEOUT_MS = 2000`

**契约变更必须回去改那份文档。九条里任何一条被实现绕过,是文档过期,不是实现聪明。**
