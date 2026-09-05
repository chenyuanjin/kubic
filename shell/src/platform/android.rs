//! Android。
//!
//! <h2>2026-09-05(`KUBI-118`):从「能打开首页」推到「六屏点得完」</h2>
//!
//! `KUBI-67` 那一轮的目标是能构建、能装、能打开首页。这一轮在模拟器上把主链路
//! 六屏逐屏点了一遍(实测记录与截图在 `shell/README.md`「Android」一节),
//! 出包收进 `scripts/android-build.sh`。
//!
//! **这个文件本身一行代码都没有变**,而那正是本轮结论的一半 ——
//! 下面两件 Android 独有的事,都不需要在这里长出一个方法:
//!
//! <h3>🔴 系统返回键不归这个文件管,归 wry 管</h3>
//!
//! 返回键的落点是 `WryActivity.setWebView()` 里注册的 `OnBackPressedCallback`:
//! `canGoBack()` 就 `goBack()`,否则才退 Activity。webview 的历史就是路由的历史
//! (`main.tsx` 用的是 `createBrowserRouter`),所以**默认行为已经等于**
//! 「返回键 → 路由回退 → 栈空才退出」,壳侧一行都不必写。
//! <p>
//! 一行不写的东西没有任何一处会在它消失时变红,所以那条契约由
//! `scripts/android-build.sh` 步骤 ④ 对生成出来的 Kotlin 做文本断言 ——
//! 扫的是构建产物,因为源码里根本没有这段逻辑可扫。
//! <p>
//! ⚠️ **覆盖层(⌘K 面板一类)不在这条链上**:它们是 `useState` 不是历史条目,
//! 返回键会越过它们去弹路由。那一半的落点在 `web/`,不在壳里(`KUBI-117`)。
//!
//! <h3>🔴 `env(safe-area-inset-bottom)` 在这个 WebView 上是 0,而手势条真实存在</h3>
//!
//! 实测(Android 14 / API 34,1080×2400 @420dpi):系统报的 `navigationBars`
//! 占 63 物理像素,而页面拿到的 `safe-area-inset-bottom` 是 `0px`
//! (`inset-top` 倒是对的,`49px` ↔ 128 物理像素)。
//! 所以**靠内边距把底栏抬起来这条路在 Android 上不成立** ——
//! `web/` 现在的做法是让屏底动作区自己去挨那条边,它 52px 的高度经得起。
//! 这条差异写在这里而不是 `web/` 里,因为它是这个系统的性质,不是那份样式的选择。
//!
//! 两件事的共同点:**Android 的特殊性这一轮全部落在壳之外**,
//! 而 `Platform` 这个 trait 一个方法都没有多出来 —— `platform/mod.rs` 那条
//! 「每加一个方法就是多一处三端会分叉的地方」在本轮的结果是加了零个。

use std::path::PathBuf;

use super::{Platform, WindowSize};

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

    fn window_size(&self) -> Option<WindowSize> {
        // 手机上视图铺满屏幕,尺寸由系统给。`None` 不是「还没定」,是这里没有这个决定。
        None
    }

    fn background_timer_supported(&self) -> bool {
        // 同 iOS:系统不保证后台执行。不假装。
        false
    }

    fn install_menu(&self, _app: &tauri::AppHandle) -> tauri::Result<()> {
        // Android 上没有菜单栏。这不是「还没做」——「文件 / 编辑 / 窗口」那一栏
        // 在这个系统上没有可以挂的地方。做不出来的东西,界面上不留承诺。
        Ok(())
    }

    fn describe_port_holder(&self, _port: u16) -> Option<String> {
        None
    }

    fn report_fatal(&self, title: &str, body: &str) {
        eprintln!("{title}\n{body}");
    }
}
