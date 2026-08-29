//! 壳的配置。
//!
//! 🔴 零 `#[cfg(target_os)]` —— 三端读同一份逻辑(壳技术方案 §4.1)。

/// 🔴 固定端口,不自动挑一个空闲的。
///
/// 端口是 origin 的一部分(`http://127.0.0.1:17840`),而浏览器侧的一切本地存储
/// 都按 origin 隔离。端口换一次,存的东西全部消失,而且不报错、不提示
/// (壳技术方案 §3.7 / `R-73`)。
pub const PORT: u16 = 17840;

/// 上游后端地址的环境变量名。
pub const UPSTREAM_ENV: &str = "KAODIAN_SHELL_UPSTREAM";

#[derive(Debug, Clone)]
pub struct Config {
    pub port: u16,
    /// `None` = 这个端不接后端,`/api/*` 一律 502。
    pub upstream: Option<String>,
}

impl Config {
    /// 读出配置。
    ///
    /// <h2>为什么上游默认是 `None` 而不是方案里写的 `http://127.0.0.1:8080`</h2>
    ///
    /// 因为**默认值按端分叉就是平台差异**,而差异只允许出现在 `platform/` 下。
    /// 方案 §3.3 的那个默认值是给 macOS 的,§4.2 同时又要求 iOS/Android **留空**——
    /// 两条放在一起,`config.rs` 里就必须有一个 `#[cfg]`,而 §4.1 明写这个文件零 `cfg`。
    ///
    /// 解法是让默认值**对三端相同(都是不接)**,由谁去接由外部显式给:
    /// `KUBI-65` 落地 macOS 时补上配置文件层,把 `127.0.0.1:8080` 写在那里。
    /// 这样 iOS「不接后端」不是一条特例,而是**没人给它配**的自然结果 ——
    /// 少一条特例,就少一处三端会分叉的地方。
    ///
    /// ⚠️ 这是对方案 §3.3 的一处**显式偏离**,已记在 shell/README.md「与方案的偏差」。
    ///
    /// <h2>为什么现在是环境变量而不是配置文件</h2>
    ///
    /// 配置文件要落盘,落盘要一个跨三端的目录定位,而那会给 `Platform` trait
    /// 加第三个方法 —— 方案 §4.3 明写「只有两个方法,这是有意的」。
    /// `KUBI-66` 只要「能构建、能装、能打开首页」,一个都不需要写配置。
    /// **持久化留给真正需要它的那个端(`KUBI-65`)去加。**
    pub fn resolve() -> Self {
        let upstream = std::env::var(UPSTREAM_ENV)
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());

        Self {
            port: PORT,
            upstream,
        }
    }
}
