> 桌面壳。**它把现有的 `web/dist` 装进一个窗口,不做别的。**
>
> 契约在 `docs/18-壳技术方案:Tauri 2 包现有 Web 工程.md`(`KUBI-62`),本文件只讲怎么跑、
> 以及代码里那几个「看起来像漏了」的地方为什么是有意的。**契约变更必须回去改那份文档** ——
> 反代那九条里任何一条被实现绕过,是文档过期,不是实现聪明。
>
> ✅ **那个编号冲突已在并入 `v1` 时解掉(2026-08-30):** 壳技术方案由 `15` 改号为 `18`,
> 主干原有的 `docs/15-Agent框架与能力边界.md` 保号。本目录里所有 `docs/18 §x` 指的都是壳技术方案。
> 同批解掉的还有风险号:壳的五条风险由 `R-72…R-76` 改为 **`R-108…R-112`**(旧号在主干上早已另有所属)。
>
> ⚠ 这个目录**对最近那个关卡的贡献是零**。关卡 0 要的是「自用两周、每天记两个数」,
> 而壳一个数都不产生。它是后续阶段的前置准备,由项目所有者直接拍板开工(`KUBI-61`)。
> 在这里待着很舒服 —— 可量化、有完成感、完全不需要面对真实用户,正是 `03` §盲区二 点名的那种事。

## 跑起来

```bash
./shell/build.sh            # 三道自检 + 打包,出 .app / .dmg
./shell/build.sh --check    # 只跑三道自检,不打包(提交前 / CI)
```

**这是唯一允许的构建方式。** 直接 `cargo tauri build` 会绕过步骤 ① 的隔离校验,
而绕过隔离校验的构建路径和没有隔离校验是一回事(`R-111`)。

前置:

| 需要 | 装法 | 为什么不自动装 |
|---|---|---|
| Rust + 目标平台 std | `rustup target add aarch64-apple-darwin` | 改动别人机器上的工具链应当是一次显式的决定 |
| `cargo-tauri` | `cargo install tauri-cli --version "^2.0" --locked` | 同上 |
| Node | 走 `web/` 已有的那套 | 壳自己**不引入任何 npm 依赖** |

装好之后双击 `target/<triple>/release/bundle/macos/考点盲区.app`。
**ad-hoc 签名,自用**,首次打开走一次「右键 → 打开」。分发给别人才需要
Developer ID + 公证,而本轮没有分发(`docs/18` §4.4)。

## 它是怎么工作的

```mermaid
flowchart LR
    subgraph SHELL["壳进程 · 一个二进制"]
        W["WebView<br/>origin = http://127.0.0.1:17840"]
        L["local_server<br/>只绑 127.0.0.1"]
        E["内嵌的 web/dist<br/>include_dir! 编译期打进二进制"]
        S["scheduler<br/>🔴 本议题里是空的"]
    end
    U["上游<br/>默认 http://127.0.0.1:8080"]

    W -->|"GET /、/assets/*"| L
    W -->|"GET/POST /api/*"| L
    L --> E
    L -->|"逐字节流式转发"| U

    style L fill:#2d3a4a,color:#fff
    style S fill:#4a2d2d,color:#fff
```

前端只写相对路径 `/api/*`。这条路径解析成什么取决于页面 origin ——
dev 是 vite proxy,生产是 Caddy,壳是 `local_server.rs`。
**三个实现,一条契约,前端一个字不知道自己在哪儿跑。**

`server/` 一个字节都没改:页面 origin 与 `/api` 同源,所以**不存在跨域**。

## 那几个「看起来像漏了」的地方

| 看起来 | 其实 | 在哪写着理由 |
|---|---|---|
| `beforeBuildCommand` 是空串 | 填上它就出现第二条构建路径,而它绕过隔离校验 | `tauri.conf.json5` |
| 没配 `frontendDist` | 壳不走 `tauri://` 资源协议;配上会让同一批字节存两份、并多出一个永不访问的 origin | `tauri.conf.json5` |
| `windows: []` | 窗口在 `setup()` 里建,因为它必须发生在**端口绑定成功之后** | `tauri.conf.json5` / `src/main.rs` |
| `capabilities/*.json` 的 `permissions` 是空的 | 页面从来不发 IPC,没有 core 命令需要授权 | `capabilities/desktop.json` |
| `scheduler.rs` 里什么都没有 | 后台定时器是 `KUBI-68`,它还卡在 `KUBI-63` 上;但**具名的位置现在就要有** | `src/scheduler.rs` |
| 没有托盘 | 托盘是那个**常驻进程**的可见表示,而常驻进程还不存在 | `src/main.rs` |
| 端口被占时直接不启动 | 换端口 = 换 origin = 浏览器侧本地存储静默全失(`R-109`) | `src/strings.rs` |

## 三端隔离

`#[cfg(target_os = ...)]` **只允许出现在 `src/platform/` 下**,由 `build.sh` 步骤 ① grep 拦死:

```bash
grep -rn 'cfg(target_os' shell/src --exclude-dir=platform   # 期望零命中
```

`local_server.rs` / `config.rs` / `scheduler.rs` / `strings.rs` 四个文件里出现一个 `#[cfg(target_os)]`,
隔离就已经破了。这条不靠自觉 —— **靠自觉的约束在赶工的那一周会失效,
而赶工的那一周正是它最需要生效的时候。**

## 能力边界文案扫描

`scripts/capability-boundary-scan.mjs`,扫 `src` 全树 + `capabilities/` + `tauri.conf.json5`。

🔴 **词表不在壳里,是从 `web/scripts/capability-boundary-scan.mjs` 现读的。**
这不是省事:壳这边复制一份词表,「改词表绕过」就从一件所有人都会看见的事,
变成一件只有壳的人知道的事。现在改词表只有一处可改,而那一处同时管着 web 的 30 个文件。

代价是它要按文本去读另一个脚本里的两个数组。读不出来时**报错退出**,
不静默降级成「零命中,通过」—— 一条会假绿的断言比没有断言更糟。

## 图标

由 `web/public/favicon.svg` 生成,不新画一个 —— **壳不出新视觉方向**。
底色是页面底 `#0e0f11`(`web/src/index.css` 的 `--color-bg`)。重新生成:

```bash
# 1024 见方的 icon.png 已在 icons/ 里;只在 favicon 改了之后才需要重跑
cd shell/icons && mkdir -p icon.iconset
for s in 16 32 128 256 512; do
  sips -z $s $s icon.png --out icon.iconset/icon_${s}x${s}.png
  sips -z $((s*2)) $((s*2)) icon.png --out icon.iconset/icon_${s}x${s}@2x.png
done
iconutil -c icns icon.iconset -o icon.icns && rm -rf icon.iconset
```

⚪ **待 UI 审核判**:现在是一个满幅方形图标(产品底色 + 现有标记),
没有 macOS 惯例的圆角矩形外形。加圆角是一次视觉决定,不由壳来做。

## 不做什么

| 不做 | 为什么 | 何时重新评估 |
|---|---|---|
| 全局快捷键 / 监听截图目录 / 悬浮窗 | 撞 `web/README.md` 的「替你听、替你截,不行」;监听截图目录还绕过「同意那一刻」(`KUBI-64` 人审确认) | 永不(前两个) |
| 壳内 IPC(`invoke`) | 会长出第二套契约和第二个后端 | 出现一个 HTTP 表达不了、且不能放在 server 侧的本地能力 |
| 自动更新 | 新增签名密钥 + 托管端点 | 有第二个使用者 |
| 崩溃上报 / 遥测 | 它会把数据送出这台机器 | 永不 |
| 本地 TLS | 自签根证书与私钥入包都是安全倒退,而它要挡的同机进程威胁 TLS 本来也挡不住 | 永不 |
| 壳内缓存 API 响应 | 前端已有 TanStack Query 与「四个端点要么全真要么全假」的整屏纪律。壳再加一层 = 同屏两个来源 | 不评估 |
