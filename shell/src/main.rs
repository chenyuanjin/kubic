// 双击打开时不要在后面跟一个黑色控制台窗口(Windows 才需要;这里写上是零成本的对称)。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! 装配,只做装配:读配置 → 起回环服务 → 开窗。
//!
//! <h2>这个文件里【没有】什么</h2>
//!
//! - 没有业务逻辑。壳不读请求体,所以它结构上不可能存下任何学习内容。
//! - 没有 IPC 命令(`#[tauri::command]`)。装了 `@tauri-apps/api` 就要改 `web/package.json`,
//!   而「现有 web 工程一行不改」是这份方案的约束(docs/18 §2.5)。
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
mod scheduler;
mod strings;

use std::sync::Arc;

use tauri::menu::{AboutMetadata, Menu, PredefinedMenuItem, Submenu};
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

fn main() {
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

            let server = Arc::new(local_server::Server::new(cfg.upstream.as_deref())?);
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
            app.set_menu(build_menu(app.handle())?)?;
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

/// 菜单栏三项:考点盲区 · 编辑 · 窗口(KUBI-64 判定)。
///
/// <h2>为什么「编辑」必须留</h2>
///
/// ⌘C / ⌘V 在 WebView 里靠的是菜单项上挂着的系统快捷键。把这一栏删干净,
/// 复制粘贴会连同它一起消失 —— 那是**浏览器里有、壳里没有**的差异,
/// 而「壳不引入第二套界面」这条约束管的正是这种差异。
///
/// <h2>为什么其余的删掉</h2>
///
/// Tauri 的默认菜单里有「文件 / 视图 / 帮助」,每一项都指向壳里不存在的东西:
/// 没有文件要新建,没有视图要切,没有帮助文档。留着它们等于在界面上开三个空口袋。
fn build_menu<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> tauri::Result<Menu<R>> {
    let app_menu = Submenu::with_items(
        app,
        strings::MENU_APP,
        true,
        &[
            &PredefinedMenuItem::about(app, None, Some(AboutMetadata::default()))?,
            &PredefinedMenuItem::separator(app)?,
            // 用系统预置的「退出」而不是自己挂一个 id:预置项自带 ⌘Q 与系统本地化,
            // 自己实现要多一个事件处理器,而那是一处能写出 bug 的地方,换来的是同一个行为。
            &PredefinedMenuItem::quit(app, Some(strings::MENU_QUIT))?,
        ],
    )?;

    let edit_menu = Submenu::with_items(
        app,
        strings::MENU_EDIT,
        true,
        &[
            &PredefinedMenuItem::undo(app, None)?,
            &PredefinedMenuItem::redo(app, None)?,
            &PredefinedMenuItem::separator(app)?,
            &PredefinedMenuItem::cut(app, None)?,
            &PredefinedMenuItem::copy(app, None)?,
            &PredefinedMenuItem::paste(app, None)?,
            &PredefinedMenuItem::select_all(app, None)?,
        ],
    )?;

    let window_menu = Submenu::with_items(
        app,
        strings::MENU_WINDOW,
        true,
        &[
            &PredefinedMenuItem::minimize(app, None)?,
            &PredefinedMenuItem::close_window(app, None)?,
        ],
    )?;

    Menu::with_items(app, &[&app_menu, &edit_menu, &window_menu])
}
