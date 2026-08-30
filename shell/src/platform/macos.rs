//! macOS。唯一保证后台定时执行的一端 —— docs/15 说的「这次试水最实在的技术收益」。

use std::path::PathBuf;

use super::Platform;

pub struct MacOs {
    base: PathBuf,
}

impl MacOs {
    pub fn new(base: PathBuf) -> Self {
        Self { base }
    }
}

impl Platform for MacOs {
    /// `~/Library/Application Support/<bundle-id>/archive`
    fn archive_dir(&self) -> PathBuf {
        self.base.join("archive")
    }

    /// 进程可以常驻,定时器能按时跑到。
    fn background_timer_supported(&self) -> bool {
        true
    }
}
