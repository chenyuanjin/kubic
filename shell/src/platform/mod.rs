//! 三端差异的唯一落点(docs/15 §4.3)。
//!
//! 规则一条:`#[cfg(target_os = "...")]` 只允许出现在本目录下。
//! 其余模块通过下面这个 trait 拿能力,不知道自己跑在哪个系统上。
//! 这条由 `build.sh` 步骤 ① 在构建期 grep 强制 —— 靠自觉的约束在赶工那一周会失效,
//! 而赶工那一周正是它最需要生效的时候。
//!
//! trait 现在只有两个方法,这是有意的:每加一个方法就是多一处三端会分叉的地方。

use std::path::PathBuf;

pub trait Platform: Send + Sync {
    /// 归档目录。三端各自的沙箱位置,调用方不关心它在哪。
    fn archive_dir(&self) -> PathBuf;

    /// 本平台是否保证后台定时执行。
    ///
    /// iOS / Android 返回 false,`scheduler` 据此决定「只在前台扫一次」还是「常驻」。
    /// 返回 false 不等于降级成假装能跑:界面上不留任何「到期自动处理」的承诺。
    fn background_timer_supported(&self) -> bool;
}

#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "ios")]
mod ios;
#[cfg(target_os = "android")]
mod android;

/// 选一个实现。
///
/// `base` 是 Tauri 解出来的 app data 目录 —— 三端各自的沙箱根,
/// 拿它这一步本身没有平台差异,所以不放进 trait。
/// trait 负责的是【在这个根下面放哪儿】和【这个平台保不保证后台执行】。
#[cfg(target_os = "macos")]
pub fn current(base: PathBuf) -> Box<dyn Platform> {
    Box::new(macos::MacOs::new(base))
}

#[cfg(target_os = "ios")]
pub fn current(base: PathBuf) -> Box<dyn Platform> {
    Box::new(ios::Ios::new(base))
}

#[cfg(target_os = "android")]
pub fn current(base: PathBuf) -> Box<dyn Platform> {
    Box::new(android::Android::new(base))
}

#[cfg(not(any(target_os = "macos", target_os = "ios", target_os = "android")))]
pub fn current(_base: PathBuf) -> Box<dyn Platform> {
    // docs/15 §九:Windows / Linux 端不做,也不评估。
    // 这里不给一个「先跑起来」的兜底实现 —— 兜底实现会让第四个端在没人拍板的情况下长出来。
    panic!("{}", crate::strings::UNSUPPORTED_TARGET)
}
