//! Android。本轮只要能构建、能装、能打开首页(KUBI-67)。

use std::path::PathBuf;

use super::Platform;

pub struct Android {
    base: PathBuf,
}

impl Android {
    pub fn new(base: PathBuf) -> Self {
        Self { base }
    }
}

impl Platform for Android {
    /// app 私有目录内(`/data/data/<pkg>/files/...`),不落在外部存储。
    ///
    /// 外部存储对其它 app 可读,而红线的原话是「只存在他自己的机器上」——
    /// 同一台机器上的另一个 app 也是「别人」。
    fn archive_dir(&self) -> PathBuf {
        self.base.join("archive")
    }

    /// 系统不保证后台执行(Doze / 后台限制)。同 iOS:只在前台扫一次,界面上不留承诺。
    fn background_timer_supported(&self) -> bool {
        false
    }
}
