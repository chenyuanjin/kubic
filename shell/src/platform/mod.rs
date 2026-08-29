//! 🔴 **唯一允许出现 `#[cfg(target_os = "...")]` 的模块。**
//!
//! 其余模块通过这里暴露的 trait 拿能力,不知道自己在哪个系统上。
//! 这条由 `build.sh` 步骤 ① 在构建期强制,不靠自觉:
//!
//! ```text
//! grep -rn 'cfg(target_os' shell/src --exclude-dir=platform   # 期望零命中
//! ```
//!
//! 靠自觉的约束在赶工的那一周会失效,而赶工的那一周正是它最需要生效的时候
//! (壳技术方案 §4.3)。

use std::path::PathBuf;

#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "ios")]
mod ios;
#[cfg(target_os = "macos")]
mod macos;

/// 三端共用的能力面。
///
/// 🔴 **现在只有两个方法,这是有意的。** 每加一个方法就是多一处三端会分叉的地方;
/// 加之前先问它服务于哪个**已成立**的需求(壳技术方案 §4.3)。
pub trait Platform {
    /// 归档目录。三端各自的沙箱位置,调用方不关心它在哪。
    ///
    /// 🔴 到期是**转归档**,不是删除 —— 归档后字节仍在本机、仍然读得出来。
    /// 用户手按的删除仍然是真删。两件事不要混(`KUBI-68`)。
    fn archive_dir(&self) -> PathBuf;

    /// 本平台是否**保证**后台定时执行。
    ///
    /// iOS / Android 返回 `false`。`scheduler` 据此决定「只在前台扫一次」还是「常驻」。
    /// 🔴 返回 `false` 不等于降级成「假装能跑」—— 做不出来的东西,界面上不留承诺。
    fn background_timer_supported(&self) -> bool;
}

/// 取当前平台实现。
///
/// 这是本模块**唯一**的对外入口 —— 别处不许再 `use crate::platform::ios` 这种具体实现。
#[cfg(target_os = "macos")]
pub fn current() -> impl Platform {
    macos::MacOs::new()
}

#[cfg(target_os = "ios")]
pub fn current() -> impl Platform {
    ios::Ios::new()
}

#[cfg(target_os = "android")]
pub fn current() -> impl Platform {
    android::Android::new()
}
