//! 壳的配置:端口与上游地址。
//!
//! 零 `#[cfg]`(docs/15 §4.1)。三端的差异不写在代码里,写在配置的默认值里,
//! 而默认值由 `build.sh` 在构建期通过环境变量注入 —— 谁构建的、注了什么,
//! 在构建日志里看得见,不用读代码猜。

use std::fs;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::{Deserialize, Serialize};

/// docs/15 §3.3:固定 17840。
///
/// 「固定」不是省事:端口是页面地址的一部分,而浏览器侧的一切本地存储都按地址隔离。
pub const DEFAULT_PORT: u16 = 17840;

/// 上游地址的构建期默认值。
///
/// `build.sh` 给 macOS 注入 `http://127.0.0.1:8080`,给 iOS / Android **不注入** ——
/// 那两端本轮不接后端(docs/15 §4.2),首屏落在前端已有的离线示例数据整屏回退上。
///
/// 用构建期变量而不是平台条件编译,是因为 §4.3 只给平台差异留了一个落点(`platform/`),
/// 而「这一轮某个端接不接后端」是排期,不是平台能力 —— 它不该长在 `platform/` 里。
const BUILD_UPSTREAM: Option<&str> = option_env!("KAODIAN_SHELL_UPSTREAM");

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// 首次运行写死在这里,之后每次读同一个。被占用时拒绝启动,不自动换(`R-73`)。
    pub port: u16,
    /// 上游后端。`None` / 空串 = 这一端不接后端。
    #[serde(default)]
    pub upstream: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            upstream: BUILD_UPSTREAM
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned),
        }
    }
}

impl Config {
    /// 读配置;没有就按默认值写一份出来。
    ///
    /// 读不动 / 内容坏了的时候【不静默回退到默认值】—— 静默回退会换掉端口,
    /// 而换端口正是 `R-73` 要挡的那件事。坏了就报错,让人来看。
    pub fn load_or_init(path: &Path) -> Result<Self, String> {
        if path.exists() {
            let raw = fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
            return serde_json::from_str(&raw).map_err(|e| format!("{}: {e}", path.display()));
        }
        let cfg = Self::default();
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        }
        let raw = serde_json::to_string_pretty(&cfg).map_err(|e| e.to_string())?;
        fs::write(path, raw).map_err(|e| format!("{}: {e}", path.display()))?;
        Ok(cfg)
    }

    /// 上游地址,已经归一化过空串。
    pub fn upstream(&self) -> Option<&str> {
        self.upstream.as_deref().map(str::trim).filter(|s| !s.is_empty())
    }
}

pub fn config_path(base: &Path) -> PathBuf {
    base.join("shell.json")
}

/// 端口能不能绑上。绑得上就把 listener 交出去 —— 中间不留窗口给别的进程抢。
pub fn bind(port: u16) -> Result<TcpListener, std::io::Error> {
    // 127.0.0.1,不是 0.0.0.0 —— 与 server 的 `server.address=127.0.0.1` 同一条理由:
    // 这段流量不该出网卡。
    TcpListener::bind(("127.0.0.1", port))
}

/// 谁占着这个端口。查不出来就说查不出来 —— 猜一个像那么回事的进程名比不说更糟。
pub fn occupant(port: u16) -> Option<String> {
    let out = Command::new("lsof")
        .args(["-nP", &format!("-iTCP:{port}"), "-sTCP:LISTEN", "-Fcp"])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    let mut pid = None;
    let mut cmd = None;
    for line in text.lines() {
        match line.as_bytes().first() {
            Some(b'p') => pid = Some(line[1..].to_owned()),
            Some(b'c') => cmd = Some(line[1..].to_owned()),
            _ => {}
        }
    }
    match (cmd, pid) {
        (Some(c), Some(p)) => Some(format!("{c} (pid {p})")),
        _ => None,
    }
}
