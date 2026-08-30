//! 壳的装配。这里只做四件事:读配置 → 绑端口 → 起回环服务 → 起定时器。
//!
//! 没有第五件。壳不新增任何界面、不解释任何业务规则、不读请求体 ——
//! 它结构上不可能存下任何学习内容(docs/15 §六)。

mod config;
mod local_server;
mod platform;
mod scheduler;
mod strings;

use std::sync::Arc;

use tauri::Manager;

/// 桌面端由 `main.rs` 调,移动端由 tauri 生成的入口调。
///
/// `cfg_attr(mobile)` 是这个 crate 里唯一一处平台相关的属性,而且它不是行为分支:
/// 它只决定入口符号叫什么,函数体三端逐字相同。
/// 平台【行为】差异仍然只在 `platform/`(§4.3),由 `build.sh` 步骤 ① 强制。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let config_dir = app.path().app_config_dir()?;

            let platform: Arc<dyn platform::Platform> = Arc::from(platform::current(data_dir));
            std::fs::create_dir_all(platform.archive_dir())?;

            let cfg = config::Config::load_or_init(&config::config_path(&config_dir))
                .unwrap_or_else(|e| refuse(&e));

            // 绑不上就拒绝启动,不自动换端口(docs/15 §3.7 / R-73)。
            // 自动换端口是「让它先跑起来」的自然写法,而它的代价是静默丢数据。
            let listener = config::bind(cfg.port).unwrap_or_else(|_| {
                let who = config::occupant(cfg.port)
                    .unwrap_or_else(|| strings::OCCUPANT_UNKNOWN.to_owned());
                refuse(&strings::port_taken(cfg.port, &who))
            });

            let server = Arc::new(
                local_server::LocalServer::new(cfg.upstream()).unwrap_or_else(|e| refuse(&e)),
            );
            tauri::async_runtime::spawn(async move {
                let _ = server.serve(listener).await;
            });

            // 端口可以被改配置,窗口地址不能跟着漂。tauri.conf.json 里写死的是默认端口,
            // 改过配置就在这里把窗口带过去 —— 两处地址不一致会让页面停在一个空的 origin 上。
            if cfg.port != config::DEFAULT_PORT {
                if let Some(w) = app.get_webview_window("main") {
                    let url = format!("http://127.0.0.1:{}", cfg.port);
                    let _ = w.navigate(url.parse()?);
                }
            }

            scheduler::spawn(platform, Arc::new(scheduler::Noop));
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect(strings::START_FAILED);
}

/// 说清楚再退出。
///
/// 这里没有「先跑起来再说」的分支:壳能拒绝启动的每一件事,
/// 放行的代价都是静默地把用户本机的东西弄丢。
fn refuse(message: &str) -> ! {
    eprintln!("\n{message}\n");
    std::process::exit(1)
}
