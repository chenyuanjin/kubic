// 双击打开时不要在后面跟一个黑色控制台窗口(Windows 才需要;这里写上是零成本的对称)。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! 桌面端的入口,只有一行。
//!
//! 装配整个在 `lib.rs` 的 `run()` 里 —— 那里也是 iOS / Android 的入口。
//! 🔴 **这个文件永远只该有这一行**:多一行,三端就多一处会分叉的地方,
//! 而移动端根本不会执行 `main()`(宿主直接调库里那个导出符号,见 `lib.rs`)。
//! 写在这里的东西在两个端上静默地不生效,那是最难查的一类差异。

fn main() {
    kaodian_shell_lib::run()
}
