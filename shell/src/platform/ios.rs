//! iOS。
//!
//! ⚠ 本轮(`KUBI-66`)的全部目标是「能构建、能装到模拟器、能打开首页」。
//! 这个文件因此只有最小实现,**不是没写完,是不多写** —— `KUBI-66` 的原话:
//! 「做多了反而要在后面删掉」。

use std::path::PathBuf;

use super::{Platform, WindowSize};

pub struct Ios {
    /// 由 Tauri 给出的 app 沙箱容器目录。
    ///
    /// 与 macOS 不同,这里【不】写死路径:iOS 的容器路径带一段随机 UUID,
    /// 每次安装都不一样,写死是不可能的。
    data_root: PathBuf,
}

impl Ios {
    pub fn new(data_root: PathBuf) -> Self {
        Self { data_root }
    }
}

impl Platform for Ios {
    fn data_root(&self) -> PathBuf {
        self.data_root.clone()
    }

    fn archive_dir(&self) -> PathBuf {
        self.data_root.join("originals")
    }

    fn background_timer_supported(&self) -> bool {
        // 🔴 false,而且不打算变成 true。
        // iOS 不保证后台执行,正确行为是【前台启动时扫一次】,并且不在界面上承诺
        // 任何「到期自动处理」——「做不出来的东西,界面上不留承诺」(web/README.md)。
        false
    }

    fn describe_port_holder(&self, _port: u16) -> Option<String> {
        // iOS 上没有 lsof,也没有「另一个进程占着这个端口」这种局面 ——
        // 每个 app 在自己的沙箱里。查不出来就说查不出来,不编一个。
        None
    }

    fn window_size(&self) -> Option<WindowSize> {
        // 手机上视图铺满屏幕,尺寸由系统给。`None` 不是「还没定」,是这里没有这个决定。
        None
    }

    fn install_menu(&self, _app: &tauri::AppHandle) -> tauri::Result<()> {
        // 移动端没有菜单栏。空实现不是「还没写」,是这个系统上确实没有这个东西 ——
        // ⌘C / ⌘V 在这里由系统的文本选择浮层提供,不经过任何我们要装的东西。
        Ok(())
    }

    fn report_fatal(&self, title: &str, body: &str) {
        // 移动端没有 osascript,也不该为一条本轮走不到的路径引一个原生对话框能力。
        eprintln!("{title}\n{body}");
    }
}
