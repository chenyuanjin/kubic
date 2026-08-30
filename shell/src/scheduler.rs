//! 唯一的后台定时器注入点(docs/15 §五)。
//!
//! 零 `#[cfg]`(§4.1)。
//!
//! 这是壳里唯一一个会自己动起来的东西,所以边界划得比别处更死:
//!
//! * **只有这一个定时器。** 别处不许起线程做周期任务 ——
//!   多一个没人知道的定时器,就多一处没人审过的自动行为。
//! * **不发起任何网络请求。** 一个能上网的后台定时器,就是「原图绝不上云、
//!   不同步、不共享」这条红线的现成破口。它在代码里的形态就是本文件的 use 列表:
//!   这里没有、也不许有任何 HTTP 客户端。`build.sh` 步骤 ① 检查这一条。
//! * **只负责「什么时候问」,不负责「该不该」。** 到期判据是纯逻辑、可测,
//!   属于判据层;本文件属于触发。这里出现一行 `if now > expire_at`,
//!   两层就已经合并了,而那正是 KUBI-63 明写要继续分开的东西。
//! * **拿不到后台执行保证时不假装。** `background_timer_supported() == false`
//!   的一端(iOS / Android)只在前台启动时扫一次,界面上不承诺任何自动处理。
//!
//! 扫到期时【做什么】由 KUBI-63 / KUBI-64 定,本文件不定。

use std::sync::Arc;
use std::time::Duration;

use crate::platform::Platform;

/// 常驻端的扫描间隔。一小时一次,不是为了准 —— 到期粒度是天。
const INTERVAL: Duration = Duration::from_secs(60 * 60);

/// 「该问一次了」。
///
/// 实现方拿到归档目录,自己去问判据层。定时器不看时间戳、不做比较、不下结论。
pub trait Tick: Send + Sync + 'static {
    fn tick(&self, platform: &dyn Platform);
}

/// 本轮的实现:什么都不做。
///
/// KUBI-67 的范围是「能构建、能装、能打开首页」,到期动作还没有判据层可接。
/// 留一个空实现而不是留一个 TODO,是因为空实现会被类型系统一直带着走 ——
/// 等 KUBI-63 交出判据层时,要改的地方只有这一处,而且改不了别处。
pub struct Noop;

impl Tick for Noop {
    fn tick(&self, _platform: &dyn Platform) {}
}

/// 起定时器。返回后台任务句柄的所有权由调用方持有。
pub fn spawn(platform: Arc<dyn Platform>, tick: Arc<dyn Tick>) {
    if !platform.background_timer_supported() {
        // 前台启动时扫一次,然后结束。不留一个跑不到的循环在那里假装自己会跑。
        tick.tick(platform.as_ref());
        return;
    }
    // 用 tauri 自己的运行时,不另起一个 —— 两个运行时就是两处会各自跑起来的东西。
    tauri::async_runtime::spawn(async move {
        let mut timer = tokio::time::interval(INTERVAL);
        loop {
            timer.tick().await;
            tick.tick(platform.as_ref());
        }
    });
}
