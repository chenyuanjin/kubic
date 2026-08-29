//! macOS 实现。
//!
//! ⚠️ **这个文件属于 `KUBI-65`(macOS 落地),不属于 `KUBI-66`。**
//! 这里只填到「能编译、两个方法说的是真话」为止 —— `KUBI-66` 需要
//! `platform` 模块三端齐全才能证明隔离结构成立,不是要替 `KUBI-65` 落地 macOS。

use std::path::PathBuf;

use super::Platform;

pub struct MacOs;

impl MacOs {
    pub fn new() -> Self {
        Self
    }
}

impl Platform for MacOs {
    /// `~/Library/Application Support/<bundle-id>/archive`(壳技术方案 §4.2)。
    fn archive_dir(&self) -> PathBuf {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
        PathBuf::from(home)
            .join("Library")
            .join("Application Support")
            .join(crate::BUNDLE_ID)
            .join("archive")
    }

    /// macOS 上进程可以常驻,后台定时器成立 ——
    /// 这是这次试水**最实在的技术收益**(`KUBI-61`)。
    fn background_timer_supported(&self) -> bool {
        true
    }
}
