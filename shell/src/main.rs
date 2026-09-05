// 双击打开时不要在后面跟一个黑色控制台窗口(Windows 才需要;这里写上是零成本的对称)。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! macOS 的入口。**只有一行**,而这是有意的。
//!
//! 装配全部在 `lib.rs` 的 `run()` 里,因为移动端根本不经过这个文件:
//! Android 加载 cdylib、iOS 链 staticlib,入口由系统去库里找(KUBI-115)。
//! 这里多写一行,就是多一处 macOS 与移动端会分叉的地方。

fn main() {
    kaodian_shell_lib::run()
}
