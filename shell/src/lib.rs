//! 考点盲区 · 壳。
//!
//! **只做装配:读配置 → 占端口 → 起回环服务 → 装定时器 → 开窗。**
//! 一行业务规则都没有,也不许有 —— 壳一旦开始判断业务,它就是第二个后端。
//!
//! <h2>为什么装配在 lib.rs 而不是方案 §2.4 写的 main.rs</h2>
//!
//! Tauri 2 的移动端产物是一个由 Xcode / Gradle 链接的库,入口是 `run()`,
//! 根本不经过 `main.rs`。装配写在 `main.rs` 里,iOS 上就一行都不会执行。
//! 所以装配下沉到 `lib.rs`,`main.rs` 退化成桌面端的一行转发 ——
//! **三端共用同一段装配代码**,这恰恰是方案 §4.1 要的结果。
//! 已记在 shell/README.md「与方案的偏差」。

pub mod config;
pub mod local_server;
pub mod platform;
pub mod scheduler;
pub mod strings;

/// 与 `tauri.conf.json` 的 `identifier` 必须一致。
pub const BUNDLE_ID: &str = "com.kaodian.shell";

/// 三端共用的装配入口。
///
/// 🔴 **顺序是有意的:先占端口、先起服务,再开窗。**
/// 窗口的 URL 在 `tauri.conf.json` 里写死成 `http://127.0.0.1:17840`,
/// WebView 一创建就会去连。服务没起来就开窗,首屏会是一个连接失败页 ——
/// 而那不是「离线示例数据」,是一个白屏。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let cfg = config::Config::resolve();

    // 🔴 占不住端口就**拒绝启动**,不换端口。
    // 自动换端口是「让它先跑起来」的自然写法,而它的代价是静默丢数据
    // (壳技术方案 §3.7 / `R-73`)。响亮地失败,不无声地毁数据。
    let listener = match local_server::bind(cfg.port) {
        Ok(listener) => listener,
        Err(err) => {
            eprintln!("{}", strings::port_taken(cfg.port));
            eprintln!("[shell] 系统返回:{err}");
            std::process::exit(1);
        }
    };

    let upstream = cfg.upstream.clone();
    std::thread::spawn(move || local_server::serve(listener, upstream));

    // 唯一的定时器注入点。iOS 上它只会说一句「不承诺后台处理」然后前台扫一次。
    let platform = platform::current();
    scheduler::start(&platform);

    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("壳启动失败");
}
