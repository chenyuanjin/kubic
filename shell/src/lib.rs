//! 装配,只做装配:读配置 → 起回环服务 → 开窗。
//!
//! <h2>🔴 2026-09-05(`KUBI-113`):这个文件是从 `main.rs` 整段搬过来的</h2>
//!
//! 搬家的唯一理由是 **iOS / Android 没有 `main()`**。
//! 两个移动端的入口都是宿主(`UIApplicationMain` / Android 的 `Activity`)反过来调进
//! 一个 **动态库导出的符号**,而 `[[bin]]` 编不出动态库 —— 这正是 `文档规范与目录` §2.6 `E4`
//! 记的那条「没有 `[lib]` target,所以 iOS / Android 不 build」。
//! <p>
//! `tauri::mobile_entry_point` 这个宏做的就是导出那个符号。它只能挂在库里的函数上,
//! 所以 `run()` 必须住在 `lib.rs`;`main.rs` 剩下的那一行只是桌面端的一个薄壳。
//! **装配逻辑一份,三端共用** —— 这正是「一套代码多端运行」在壳这一侧的落点。
//!
//! <h2>这个文件里【没有】什么</h2>
//!
//! - 没有业务逻辑。壳不认识考点、不认识记录。
//!   ⚠️ 「壳不读请求体」这句话 **2026-08-31 起只对 `/api/*` 成立** ——
//!   `/__local/rawimages/*` 会把原图写到本机磁盘上,那正是 `R-105` 要的。
//!   代价与三处钉子写在 `local_server.rs` 顶部,不在这里重复。
//! - 没有 IPC 命令(`#[tauri::command]`)。装了 `@tauri-apps/api` 就要给 `web/` 加一个
//!   运行时依赖,而且原图过 IPC 只能先 base64 —— 那正是 `rawImageDb.ts` 选 IndexedDB
//!   而不选 localStorage 时否掉的东西。原图存储走的是回环 HTTP,见 `local_server.rs`。
//! - 没有 `#[cfg(target_os)]`。平台差异全在 `platform/` 下(§4.3),由 `build.sh` grep 拦死。
//!   🔴 **菜单栏也是平台差异**,所以它 2026-09-05 从这里搬进了 `platform/macos.rs`:
//!   `tauri::menu` 整个模块在移动端不存在,把它留在这里就必须在这里开一个 cfg。
//! - 没有托盘。托盘是那个**常驻进程**的可见表示(KUBI-64 人审裁定),
//!   而常驻进程是 `KUBI-68` 的交付物 —— 现在做一个托盘,它代表的东西还不存在。
//!
//! <h2>启动顺序不是风格问题</h2>
//!
//! 先绑端口,再开窗。反过来的话,端口被占时用户会先看到一个空白窗口,
//! 然后才(在某个他看不见的地方)知道为什么 —— 而 R-109 要的是**响亮地失败**。

mod config;
mod local_server;
mod platform;
mod raw_image_store;
mod scheduler;
mod strings;

use std::sync::Arc;

use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

/// 三端共用的装配。
///
/// `#[cfg_attr(mobile, tauri::mobile_entry_point)]`:`mobile` 是 **tauri-build 生成的
/// cfg 别名**(等价于 iOS 或 Android),不是 `target_os` —— 所以它不与 §4.3 那条
/// 「`cfg(target_os)` 只许出现在 `platform/`」冲突,`build.sh` 的 grep 也不会命中它。
/// 它区分的是「有没有 `main()`」这件事,而那是**运行形态**不是操作系统。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // rustls 要求进程级先选定一个加密后端。选 ring:纯 Rust,不拉 cmake/nasm,
    // 三端都编得出来。这一行必须在任何 TLS 连接之前。
    let _ = rustls::crypto::ring::default_provider().install_default();

    tauri::Builder::default()
        .setup(|app| {
            // ① 平台。移动端的沙箱容器路径带随机段,只能问 Tauri 要;
            //    macOS 用固定路径,理由写在 platform/macos.rs。
            let sandbox = app.path().app_data_dir().unwrap_or_default();
            let platform = platform::current(sandbox);

            // ② 配置。读不出来就拒绝启动 —— 里面存着端口,而端口是 origin 的一部分。
            let config_path = config::path_in(&platform.data_root());
            let cfg = match config::load_or_init(&config_path) {
                Ok(c) => c,
                Err(e) => {
                    platform.report_fatal(
                        strings::CONFIG_BROKEN_TITLE,
                        &strings::config_broken_body(&config_path.display().to_string(), e.why()),
                    );
                    std::process::exit(2);
                }
            };

            // ③ 端口。🔴 被占用就拒绝启动并点名占用进程,不自动换一个(R-109)。
            let listener = match local_server::bind(cfg.port) {
                Ok(l) => l,
                Err(_) => {
                    let holder = platform.describe_port_holder(cfg.port);
                    platform.report_fatal(
                        strings::PORT_TAKEN_TITLE,
                        &strings::port_taken_body(
                            cfg.port,
                            holder.as_deref(),
                            &config_path.display().to_string(),
                        ),
                    );
                    std::process::exit(3);
                }
            };

            // ③.5 🔴 原图目录的启动清理 —— 索引里没有的字节文件一律删。
            //
            // 它必须在开窗【之前】跑完:开窗之后前端随时可能 listMeta,而孤儿文件
            // 恰恰是【索引里看不见的那些】—— 晚一秒清,就多一秒有一张【没有过期戳的原图】
            // 躺在磁盘上,而没有过期戳 = 永不过期(R-04)。
            //
            // 清不掉不拒绝启动,和 `config` 那条不同:配置读不出来会毁数据(端口),
            // 而这里清不掉只是【多留了几个字节文件】,下次启动还会再试一次。
            // 🔴 一行日志都不打 —— 这条路径上的错误对象可能带着路径,而路径是设备信息。
            let store_dir = platform.archive_dir();
            let _ = raw_image_store::RawImageStore::new(store_dir.clone()).sweep_orphans();

            let server = Arc::new(local_server::Server::new(
                cfg.upstream.as_deref(),
                store_dir,
            )?);
            tauri::async_runtime::spawn(async move {
                if let Err(e) = server.run(listener).await {
                    eprintln!("[shell] 回环服务停了:{e}");
                }
            });

            // ④ 唯一的具名定时器注入点。现在它是空的,见 scheduler.rs。
            scheduler::install(platform.as_ref());

            // ④.5 菜单栏。macOS 上有三项,移动端上这个方法什么都不做 ——
            //      移动端没有菜单栏这个东西,而「没有」不是一个要在这里写 if 的分支。
            platform.install_menu(app.handle())?;

            // ⑤ 开窗。端口已经绑住了,所以这个地址此刻一定是我们自己。
            //    🔴 origin 里带着端口,而浏览器侧的本地存储按 origin 隔离 —— 见 §3.7。
            //
            //    移动端只有一个 webview,`inner_size` / `min_inner_size` 在那儿是空操作:
            //    窗口大小由系统给。这几行因此不需要分端写 —— 它们在三端都成立,
            //    只是在两端上不起作用。
            let url = format!("http://127.0.0.1:{}/", cfg.port)
                .parse()
                .expect("回环地址是常量形状");
            WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title(strings::WINDOW_TITLE)
                .inner_size(1280.0, 840.0)
                .min_inner_size(880.0, 600.0)
                .build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("壳没能启动");
}
