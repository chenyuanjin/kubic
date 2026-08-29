//! macOS。本轮唯一一个「可用的客户端」形态(KUBI-65)。

use std::path::PathBuf;
use std::process::Command;

use super::Platform;

/// 数据根目录。
///
/// <h2>🔴 为什么写死 `kaodian`,而不是用 bundle identifier 推出来的那个目录</h2>
///
/// Tauri 的 `app_data_dir()` 是 `~/Library/Application Support/<bundle-id>/`。
/// 用它意味着**改一次 bundle identifier 就换一次数据目录**,而 identifier 现在
/// 一定会改:签名主体要与域名实名一致(docs/15 §4.4),而域名(`L-A1`)还没注册。
/// 到那天,用户的原图会安静地留在旧目录里,应用则在新目录里看到「一张都没有」。
///
/// 所以数据路径**不挂在 identifier 上**。这同时让 KUBI-64 写进设计稿的那句
/// 真实路径(`~/Library/…/kaodian/originals/`)成为一个可以照着念的常量,
/// 而不是一个会随构建配置漂移的推导结果。
const DATA_DIR_NAME: &str = "kaodian";

pub struct MacOs {
    data_root: PathBuf,
}

impl MacOs {
    pub fn new() -> Self {
        // HOME 拿不到的情况在 macOS 上只发生于没有登录会话的进程,而壳一定是被用户点开的。
        // 真出现了就落到当前目录 —— 不静默换一个「看起来对」的路径。
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .unwrap_or_default();
        Self {
            data_root: home
                .join("Library")
                .join("Application Support")
                .join(DATA_DIR_NAME),
        }
    }
}

impl Platform for MacOs {
    fn data_root(&self) -> PathBuf {
        self.data_root.clone()
    }

    fn archive_dir(&self) -> PathBuf {
        self.data_root.join("originals")
    }

    fn background_timer_supported(&self) -> bool {
        // 这是这次试水最实在的技术收益:进程在后台也能按时处理到期原图。
        // 🔴 返回 true 只表示【系统允许】,不表示壳已经在做 —— 定时器本身是 KUBI-68。
        true
    }

    fn describe_port_holder(&self, port: u16) -> Option<String> {
        // `-F pc` 是 lsof 的机器可读格式:每行一个字段,p=pid,c=command。
        // 只在拒绝启动那条路径上调一次,不在任何循环里。
        let out = Command::new("lsof")
            .args(["-nP", &format!("-iTCP:{port}"), "-sTCP:LISTEN", "-Fpc"])
            .output()
            .ok()?;
        if !out.status.success() {
            return None;
        }
        let text = String::from_utf8_lossy(&out.stdout);
        let mut pid = None;
        let mut command = None;
        for line in text.lines() {
            match line.as_bytes().first() {
                Some(b'p') => pid = Some(line[1..].to_string()),
                Some(b'c') => command = Some(line[1..].to_string()),
                _ => {}
            }
            if pid.is_some() && command.is_some() {
                break;
            }
        }
        match (command, pid) {
            (Some(c), Some(p)) => Some(format!("{c}(进程号 {p})")),
            _ => None,
        }
    }

    fn report_fatal(&self, title: &str, body: &str) {
        // stderr 先写一份:从终端跑的时候这一份才是能被复制的那份。
        eprintln!("{title}\n{body}");

        // 再弹一个系统对话框。
        //
        // 🔴 为什么不引 tauri-plugin-dialog:多一个插件就多一处三端要各自对齐的原生能力面,
        // 而这条路径只在 macOS 上会走到(移动端没有「端口被占」这回事)。
        // 差异留在 platform/ 里,正是 §4.3 说的那个位置。
        //
        // `giving up after 300`:五分钟没人点就自己收掉。
        // 没有这一句的话,一个【没有窗口的进程】会一直挂着等一个可能没人在看的对话框 ——
        // 用户看到的是「双击了没反应」,而那恰好是这条路径要消灭的现象。
        let script = format!(
            "display alert \"{}\" message \"{}\" as critical giving up after 300",
            escape_applescript(title),
            escape_applescript(body),
        );
        let _ = Command::new("osascript").args(["-e", &script]).status();
    }
}

/// AppleScript 字符串转义。
///
/// 只有两个字符需要处理,换行用 `\n` 转义序列(AppleScript 认它)。
/// 文案是本仓库自己写的常量,这里防的不是注入,是【引号把脚本截断成语法错误】——
/// 那会让一条本该响亮的失败变回静默失败。
fn escape_applescript(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 16);
    for ch in s.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            _ => out.push(ch),
        }
    }
    out
}
