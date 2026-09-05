//! 三端差异的唯一落点。
//!
//! 🔴 `#[cfg(target_os = ...)]` 只允许出现在这个目录下(docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.3)。
//! 这一条由 `build.sh` 步骤 ① 在构建期 grep 拦死,不靠自觉 ——
//! 靠自觉的约束在赶工的那一周会失效,而赶工的那一周正是它最需要生效的时候。
//!
//! 其余模块(`local_server` / `config` / `scheduler` / `strings`)通过下面这个 trait
//! 拿能力,不知道自己在哪个系统上。

use std::path::PathBuf;

#[cfg(target_os = "macos")]
mod macos;

#[cfg(target_os = "ios")]
mod ios;

#[cfg(target_os = "android")]
mod android;

#[cfg(not(any(target_os = "macos", target_os = "ios", target_os = "android")))]
compile_error!(
    "本壳的范围是 macOS + iOS/Android 脚手架(KUBI-61)。\
     要加一个端,先加 platform/<os>.rs,不要在别处开 cfg。"
);

/// 平台能力面。
///
/// <h2>为什么方法这么少</h2>
///
/// docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.3 定的原始签名只有两个方法,并写明:
/// 「每加一个方法就是多一处三端会分叉的地方;加之前先问它服务于哪个已成立的需求。」
///
/// 本议题(KUBI-65)加了三个。三个各有一条已经成立的需求,没有一个是「顺手留着以后用」:
///
/// | 新增 | 服务于 | 为什么不能放在别处 |
/// |---|---|---|
/// | [`Platform::data_root`] | 配置文件要存端口(R-109),而存哪儿是平台决定 | `main.rs` 拿到的是 `dyn Platform`,取不到某个具体平台的私有方法 |
/// | [`Platform::describe_port_holder`] | R-109「点名占用进程」 | 查占用者要调 `lsof`,而 `lsof` 在 Android 上不存在 |
/// | [`Platform::report_fatal`] | R-109「拒绝启动」要**响亮** | 双击打开的 .app 没有 stderr。只往 stderr 写 = 静默失败,恰好是这条纪律要挡的那件事 |
///
/// 🔴 后两个不是产品能力,是【失败路径的能力】。加方法之前先问它是不是也属于这一类。
pub trait Platform: Send + Sync {
    /// 这个平台上属于本产品的数据根目录。
    ///
    /// 壳自己只往里放一个文件:`shell.json`(端口 + 上游)。
    /// 原图放哪儿是 `KUBI-63` 的事,本文件只保证它们【同根】—— 一次备份带走全部。
    fn data_root(&self) -> PathBuf;

    /// 原图目录。三端各自的沙箱位置,调用方不关心它在哪。
    ///
    /// 🔴 **2026-08-31:`allow(dead_code)` 已经删掉 —— `KUBI-63` 接上了。**
    /// 调用点只有一个:`main.rs` 把它交给 `local_server::Server::new`,
    /// 再往下就只有 `raw_image_store::RawImageStore` 一个消费方。
    /// 没有第二个入口能改这个值,**尤其没有「让用户自己选目录」的入口**。
    ///
    /// 🔴 **目录选在哪儿本身就是红线的物理落点**(`docs/technical/原图存储-判据层与存储层.md §3.4`):
    /// 不得落在 `~/Documents` / `~/Desktop` / `~/Pictures` ——
    /// macOS 的 iCloud「桌面与文稿」同步默认可开,开着就等于原图自动上云,
    /// 而且不报错、不出现在任何 review 里。macOS 上它是
    /// `~/Library/Application Support/kaodian/originals`,不在任何默认同步范围内。
    fn archive_dir(&self) -> PathBuf;

    /// 本平台是否保证后台定时执行。iOS/Android 返回 false,
    /// scheduler 据此决定「只在前台扫一次」还是「常驻」。
    ///
    /// 🔴 返回 false 不等于降级成「假装能跑」—— 见 `scheduler.rs`。
    fn background_timer_supported(&self) -> bool;

    /// 谁占着这个端口。查不出来返回 `None`。
    ///
    /// 只在【拒绝启动】那条路径上被调用一次,不在任何循环里。
    fn describe_port_holder(&self, port: u16) -> Option<String>;

    /// 装菜单栏。
    ///
    /// 🔴 它在这个 trait 里,不在 `lib.rs` 里,理由与其余五个方法不同:
    /// **`tauri::menu` 这个模块在 iOS / Android 上根本不存在**。
    /// 把菜单留在装配代码里,就必须在装配代码里开一个 `cfg` —— 而 §4.3 说的正是
    /// 「平台差异只允许出现在 `platform/` 与 Tailwind 断点两处」。
    /// <p>
    /// 移动端的实现是空的,而**空实现不是降级**:那两个系统上没有菜单栏这个东西,
    /// 不是「有但我们没做」。
    fn install_menu(&self, app: &tauri::AppHandle) -> tauri::Result<()>;

    /// 把一条致命错误说到用户眼前,然后调用方退出。
    ///
    /// 🔴 它不是通知,也不是产品的任何一部分:它只在壳【没能启动】时出现一次。
    /// KUBI-64 判定的「原生通知零条」说的是运行中的提醒,与这条不是同一件事 ——
    /// 一个开不起来的应用必须说出它为什么开不起来,否则用户看到的是「双击了没反应」。
    fn report_fatal(&self, title: &str, body: &str);
}

/// 当前平台。
pub fn current(sandbox_data_dir: PathBuf) -> Box<dyn Platform> {
    #[cfg(target_os = "macos")]
    {
        let _ = &sandbox_data_dir; // macOS 用固定路径,见 macos.rs 里的理由
        Box::new(macos::MacOs::new())
    }
    #[cfg(target_os = "ios")]
    {
        Box::new(ios::Ios::new(sandbox_data_dir))
    }
    #[cfg(target_os = "android")]
    {
        Box::new(android::Android::new(sandbox_data_dir))
    }
}
