//! Android。
//!
//! ⚠ 本轮(`KUBI-67`)与 iOS 同档:能构建、能装、能打开首页,到此为止。
//! 两个脚手架合起来只回答一个问题:一套代码多端运行这条路走不走得通。

use std::path::PathBuf;

use super::Platform;

pub struct Android {
    /// app 私有目录,由 Tauri 给出(`/data/data/<pkg>/files` 一类)。
    data_root: PathBuf,
}

impl Android {
    pub fn new(data_root: PathBuf) -> Self {
        Self { data_root }
    }
}

impl Platform for Android {
    fn data_root(&self) -> PathBuf {
        self.data_root.clone()
    }

    fn archive_dir(&self) -> PathBuf {
        self.data_root.join("originals")
    }

    fn background_timer_supported(&self) -> bool {
        // 同 iOS:系统不保证后台执行。不假装。
        false
    }

    fn describe_port_holder(&self, _port: u16) -> Option<String> {
        None
    }

    fn report_fatal(&self, title: &str, body: &str) {
        eprintln!("{title}\n{body}");
    }
}
