//! 装配,只做装配:读配置 → 起回环服务 → 开窗。
//!
//! <h2>为什么装配在 lib 里而不在 main 里(KUBI-115)</h2>
//!
//! macOS 的入口是我们自己的 `main()`;移动端【不是】——
//! Android 由 `MainActivity` 加载 cdylib,iOS 由 Xcode 工程链 staticlib,
//! 两边都是**系统来找入口**,而不是我们去调它。
//! 装配放在 lib 的 `run()` 里,三端才是同一份;`main.rs` 因此只剩一行。
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
// 🔴 `pub` 不是为了给谁调用,是因为**菜单文案在移动端确实用不到**(KUBI-115)。
// MENU_* 只有 platform/macos.rs 读,所以 iOS / Android 构建时它们是 dead_code,
// 而 clippy 跑的是 `-D warnings` —— 一条真实的警告会把移动端的构建染红。
// 两条能消掉它的路都更差:给常量加 `#[cfg(target_os = "macos")]` 会把平台判断
// 写进 strings.rs(§4.3 只许在 platform/ 下);把 MENU_* 搬去 macos.rs 会破掉
// 这个文件开头那句「所有面向用户的字符串,一个文件,没有第二处」——
// 而那句话是能力边界文案扫描只需要扫一处的全部依据。
pub mod strings;

use std::sync::Arc;

use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

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

            // ⑤ 开窗。端口已经绑住了,所以这个地址此刻一定是我们自己。
            //    🔴 origin 里带着端口,而浏览器侧的本地存储按 origin 隔离 —— 见 §3.7。
            let url = format!("http://127.0.0.1:{}/", cfg.port)
                .parse()
                .expect("回环地址是常量形状");
            platform.install_menu(app.handle())?;
            // 🔴 尺寸问平台要,不写死。移动端返回 None —— 手机上没有「窗口尺寸」这回事,
            // 按桌面尺寸建窗会让 webview 拿到一块比屏幕大的画布(KUBI-115,现象是整屏黑)。
            let mut window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title(strings::WINDOW_TITLE);
            if let Some(size) = platform.window_size() {
                window = window
                    .inner_size(size.width, size.height)
                    .min_inner_size(size.min_width, size.min_height);
            }
            window.build()?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("壳没能启动");
}
