//! iOS。本轮只要能构建、能装到模拟器、能打开首页(KUBI-66)。

use std::path::PathBuf;

use super::Platform;

pub struct Ios {
    base: PathBuf,
}

impl Ios {
    pub fn new(base: PathBuf) -> Self {
        Self { base }
    }
}

impl Platform for Ios {
    /// app 沙箱容器内。
    fn archive_dir(&self) -> PathBuf {
        self.base.join("archive")
    }

    /// 系统不保证后台执行。返回 false 之后 `scheduler` 只在前台扫一次,
    /// 并且界面上不承诺任何「到期自动处理」——
    /// 做不出来的东西界面上不留承诺,与 web/README 同一条。
    fn background_timer_supported(&self) -> bool {
        false
    }
}
