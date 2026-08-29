//! iOS 实现。
//!
//! 本轮(`KUBI-66`)只要求**能构建、能装到模拟器、能打开首页**,
//! 所以这里只把 trait 的两个方法填成真话,不多做一件事。

use std::path::PathBuf;

use super::Platform;

pub struct Ios;

impl Ios {
    pub fn new() -> Self {
        Self
    }
}

impl Platform for Ios {
    /// app 沙箱容器内的归档目录。
    ///
    /// iOS 上进程的 `HOME` 就是该 app 的容器根,容器路径每次安装都会变,
    /// 所以**不能把它记进配置**,每次现取。
    ///
    /// 放在 `Library/Application Support/` 下而不是 `Documents/`:
    /// `Documents/` 会暴露给「文件」app 并参与 iCloud 备份,
    /// 而原图**绝不上云、不同步、不共享**。`Library/` 默认不进「文件」app。
    ///
    /// 🔴 备份排除标记(`isExcludedFromBackupKey`)本轮**没有做** ——
    /// 真机上 `Library/Application Support/` 仍会进 iTunes/iCloud 备份。
    /// 本轮只上模拟器、且不写任何原图,所以这条不构成红线命中;
    /// **但 iOS 一旦真的开始存原图,这个标记必须先补上**,否则「不上云」就破了。
    /// 已在 shell/README.md「交给下游」里点名。
    fn archive_dir(&self) -> PathBuf {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
        PathBuf::from(home)
            .join("Library")
            .join("Application Support")
            .join("archive")
    }

    /// iOS **不保证**后台执行。
    ///
    /// 系统随时会挂起进程,没有任何 API 能承诺「到期那一刻一定跑到」。
    /// 所以这里是 `false`,`scheduler` 据此只在前台启动时扫一次,
    /// 并且**界面上不承诺任何「到期自动处理」**(壳技术方案 §五)。
    fn background_timer_supported(&self) -> bool {
        false
    }
}
