// 桌面端入口。窗口子系统交给 tauri,不留控制台窗口。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    kaodian_shell_lib::run()
}
