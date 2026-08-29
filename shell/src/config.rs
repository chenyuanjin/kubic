//! 壳的配置:两个字段,一个文件。
//!
//! <h2>🔴 为什么读不出来就拒绝启动</h2>
//!
//! 这个文件里存着**端口**,而端口是页面 origin 的一部分(`http://127.0.0.1:17840`)。
//! 浏览器侧的一切本地存储都按 origin 隔离 —— 端口换一次,存的东西全部读不回来,
//! 而且不报错、不提示、看起来就像「数据没了」(R-73)。
//!
//! 所以「文件坏了就用默认值重写一份」这个最自然的写法在这里是**一次静默的数据事故**。
//! 与 `PhoneKeyGuard`(R-59)同一条纪律:**响亮地失败,不无声地毁数据。**
//!
//! 零 `#[cfg]` —— 文件路径由 `platform::Platform::data_root()` 给,本模块不知道自己在哪个系统上。

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// 固定端口 17840。
///
/// 「固定」比「哪个数」重要得多:它必须跨启动稳定(§3.7)。
/// 随机端口看起来更安全,其实两头不讨好 —— 可扫,而且每次一换就毁一次本地存储。
pub const DEFAULT_PORT: u16 = 17840;

/// 阶段 1/2 的上游是同一台机器上的另一个进程,不是另一个服务(docs/10 §2.2)。
pub const DEFAULT_UPSTREAM: &str = "http://127.0.0.1:8080";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// 回环监听口。改它等于换一次 origin,读一遍上面那段再改。
    pub port: u16,

    /// `/api/*` 转给谁。
    ///
    /// `null` 表示**不接后端**:`/api/*` 一律 502,前端会整屏回退到它自带的离线示例数据,
    /// 并红字标明原因。这是 iOS/Android 脚手架(`KUBI-66` / `KUBI-67`)的形态 ——
    /// 「不接后端不是偷懒,是不多做」(docs/15 §4.2)。
    #[serde(default = "default_upstream")]
    pub upstream: Option<String>,
}

fn default_upstream() -> Option<String> {
    Some(DEFAULT_UPSTREAM.to_string())
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: DEFAULT_PORT,
            upstream: default_upstream(),
        }
    }
}

/// 配置文件路径。与原图同根 —— 一次备份带走全部。
pub fn path_in(data_root: &Path) -> PathBuf {
    data_root.join("shell.json")
}

/// 读不出来时说清楚是哪一种读不出来。调用方据此决定文案,本模块不出文案。
#[derive(Debug)]
pub enum LoadError {
    /// 文件在,但不是合法配置。**不覆盖它** —— 里面可能存着上一个端口。
    Unreadable(String),
    /// 目录建不出来 / 首次写不进去。
    NotWritable(String),
}

impl LoadError {
    pub fn why(&self) -> &str {
        match self {
            LoadError::Unreadable(s) | LoadError::NotWritable(s) => s,
        }
    }
}

/// 有就读,没有就按默认值写一份再读。
///
/// 🔴 只有「文件不存在」这一种情况会写盘。任何其它异常都是 [`LoadError`],
/// 由调用方翻成一次拒绝启动。
pub fn load_or_init(path: &Path) -> Result<Config, LoadError> {
    match std::fs::read_to_string(path) {
        Ok(text) => {
            serde_json::from_str::<Config>(&text).map_err(|e| LoadError::Unreadable(format!("{e}")))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            let cfg = Config::default();
            if let Some(dir) = path.parent() {
                std::fs::create_dir_all(dir)
                    .map_err(|e| LoadError::NotWritable(format!("{}:{e}", dir.display())))?;
            }
            let text = serde_json::to_string_pretty(&cfg)
                .map_err(|e| LoadError::NotWritable(format!("{e}")))?;
            std::fs::write(path, text)
                .map_err(|e| LoadError::NotWritable(format!("{}:{e}", path.display())))?;
            Ok(cfg)
        }
        Err(e) => Err(LoadError::Unreadable(format!("{e}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 判据层与存储层分开(`KUBI-63` 的硬约束)在这个小模块上的形态:
    /// 「坏文件不许被覆盖」是一条判断,它能在不碰真实用户目录的情况下被测到。
    #[test]
    fn broken_config_is_never_overwritten() {
        let dir = std::env::temp_dir().join(format!("kaodian-shell-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = path_in(&dir);
        std::fs::write(&p, "{ 这不是 JSON").unwrap();

        let err = load_or_init(&p).unwrap_err();
        assert!(matches!(err, LoadError::Unreadable(_)));
        // 关键的一条:文件原样还在。覆盖了就等于把上一个端口冲掉了。
        assert_eq!(std::fs::read_to_string(&p).unwrap(), "{ 这不是 JSON");

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn first_run_writes_default_port_and_reads_back() {
        let dir = std::env::temp_dir().join(format!("kaodian-shell-init-{}", std::process::id()));
        std::fs::remove_dir_all(&dir).ok();
        let p = path_in(&dir);

        let first = load_or_init(&p).unwrap();
        assert_eq!(first.port, DEFAULT_PORT);

        // 第二次读到的必须是同一个端口 —— 「跨启动稳定」这条契约就是这一行(§3.7)。
        let second = load_or_init(&p).unwrap();
        assert_eq!(second.port, first.port);

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn upstream_null_means_no_backend() {
        let cfg: Config = serde_json::from_str(r#"{"port":17840,"upstream":null}"#).unwrap();
        assert!(cfg.upstream.is_none());
    }
}
