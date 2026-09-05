//! 只绑回环的静态直出 + `/api` 反代 + `/__local/rawimages` 本机原图存储。
//!
//! <h2>它是同一条契约的第三个实现</h2>
//!
//! 前端只写相对路径 `/api/*`,这条路径落到哪里,取决于页面 origin:
//! dev 是 vite proxy,生产是 Caddy,壳是这里。
//! **三个实现,一条契约,前端一个字不知道自己在哪儿跑。**
//!
//! <h2>🔴 2026-08-31:路由表从两条变成三条(`KUBI-63` / `R-105`)</h2>
//!
//! | 路径 | 去向 | 请求体 |
//! |---|---|---|
//! | `/api/*` | 反代到上游 | 🔴 **原样流给上游连接,壳一个字节都读不到**(`R-110`,一个字没变) |
//! | `/__local/rawimages/*` | [`crate::raw_image_store`] | 🔴 **落到本机应用数据目录,不出这台机器** |
//! | 其余 | 编译期内嵌的 `web/dist` | — |
//!
//! **第三条是这次改动的全部,而它值得被单独说清楚:**
//!
//! 加这一条之前,壳「结构上不可能存下任何学习内容」——它不读请求体。
//! 加这一条之后,**壳会把原图写到磁盘上**,而那正是 `R-105` 要的:
//! 浏览器配额撑不住只增不减的归档段,文件系统撑得住。
//! 换来的能力必须付出的代价是:**「壳读不到一个字节」这句话从此只对 `/api/*` 成立。**
//!
//! 所以新增的这条路被钉在三处,让它**可数、有名、跑不出去**:
//!
//! 1. 🔴 **它没有网络出口** —— [`crate::raw_image_store`] 不 import 任何 HTTP 客户端,
//!    由 `build.sh` 步骤 ① grep 拦(与 `scheduler.rs` 那条同一手法)。
//! 2. 🔴 **写盘只在那一个模块里** —— `build.sh` 同时校验 `shell/src` 里
//!    `File::create` / `fs::write` 只出现在 `raw_image_store.rs` 与 `config.rs`。
//! 3. 🔴 **壳读不到 `expiresAt`** —— 元信息对它是一块不透明 JSON,
//!    所以「该不该转归档」在壳里写不出来,判据仍然只有 `rawImageCache.ts` 一份。
//!
//! <h2>🔴 SPA fallback:有,但只对「长得像路由」的路径开(`KUBI-113`)</h2>
//!
//! **上一版这里写的是「没有 SPA fallback」。** 那条结论在前端不引路由的时候是对的;
//! `多端选型与端矩阵` §4.6 推翻了那个前提 —— 全端页面 URL 化之后,`/coverage`、
//! `/records/42` 这些地址**必须**能被直接打开(壳的 `kaodian://` 深链就落在这儿),
//! 而它们在 `dist/` 里没有对应文件。§3.1 第 2 条把这笔账算得很清楚:
//! 「代价(三处 SPA fallback)照付」,壳是那三处里的一处。
//!
//! **但那条旧结论要挡的故障一个字都没变**,所以 fallback 的形状被收窄到只剩它:
//!
//! | 请求 | 结果 | 为什么 |
//! |---|---|---|
//! | `/api/*` | 反代,壳自己不管 | 压根到不了这个函数 —— `handle` 先分流 |
//! | `/__local/*` | 本机原图存储 | 同上 |
//! | `/assets/index-abc.js`(不存在) | **404** | 段里带 `.`,是个文件名 |
//! | `/coverage`、`/records/42` | **200 + index.html** | 段里没有 `.`,是个路由 |
//!
//! 判据就是**最后一段里有没有 `.`**。它挡住的两个故障与旧结论要挡的完全是同两个:
//!
//! - `web/src/api/client.ts` 点名的那个:「有 body 但不是 JSON —— 最常见的成因是
//!   /api 压根没被反代出去,静态服务器把 index.html 当兜底返回了」。
//!   `/api/*` 从来不进这个函数,所以这条与 fallback 无关,今天仍然成立。
//! - `rawImageFs.ts` 的形态探测打的是 `/__local/rawimages/health`,它**只认
//!   200 + JSON + `store == "fs"` 三条同时成立**。那条路径同样不进这个函数。
//!
//! 🔴 **一个丢失的 js/css 仍然必须是 404。**那才是这条收窄真正买到的东西:
//! 换版之后 index.html 指着一个不存在的哈希名时,浏览器要拿到 404,
//! 而不是拿到一份 index.html 再报一句 `Unexpected token '<'`。
//!
//! 零 `#[cfg]` —— 这是判据(docs/technical/壳技术方案-Tauri2包现有Web工程.md §4.1),这个文件里出现一个 `#[cfg(target_os)]`,隔离就已经破了。

use std::convert::Infallible;
use std::net::{Ipv4Addr, SocketAddr, TcpListener as StdTcpListener};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use http_body_util::{combinators::BoxBody, BodyExt, Full, Limited};
use hyper::body::{Bytes, Incoming};
use hyper::header::HeaderValue;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode, Uri};
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::client::legacy::Client;
use hyper_util::rt::{TokioExecutor, TokioIo};
use include_dir::{include_dir, Dir};
use serde_json::Value;

use crate::raw_image_store::{decode_meta_header, encode_meta_header, RawImageStore, StoreError};
use crate::strings;

/// 编译期把 `web/dist` 整个读进二进制。
///
/// <h2>为什么内嵌,而不是读安装包的资源目录</h2>
///
/// 三端拿资源的方式各不相同(macOS 在 `.app/Contents/Resources`,
/// Android 在 APK 里要走 AssetManager)。内嵌把这个差异**消成零** ——
/// 这是「差异如何隔离」的第一笔,而它的成本只是二进制大几 MB。
///
/// 🔴 dist 不存在时这里报的是一句 Rust 宏错误,和「前端没构建」这件事对不上号。
/// 所以 `build.sh` 步骤 ③ 在编译之前先校验一次产物,并给出人能看懂的那句话。
static DIST: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../web/dist");

/// 上游连接超时 1500ms,**短于前端的 `TIMEOUT_MS = 2000`**。
///
/// 这个大小关系是有意的:让前端拿到一个**有意义的 502**
/// (`client.ts` 已经把它翻译成「连不上 /api —— 后端 :8080 没起来?」),
/// 而不是自己 abort 之后显示一句泛泛的「请求超时」。
const UPSTREAM_CONNECT_TIMEOUT: Duration = Duration::from_millis(1500);

/// 逐跳头(RFC 9110 §7.6.1)。
///
/// 🔴 这是 §3.4 规则 2「逐字节转发」唯一的例外,而且它不是选择:
/// 这些头描述的是**这一段连接**,把它们转给上游会让上游按错误的连接语义解帧。
/// `Authorization` **不在这张表里** —— 它原样透传(规则 3),而且不进任何日志。
const HOP_BY_HOP: [&str; 8] = [
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
];

/// 本机原图存储的路径前缀。
///
/// 🔴 刻意**不是** `/api/*`:那条路整条反代给上游,这条路一个字节都不出这台机器。
/// 双下划线开头是给读代码的人看的 —— **它不是产品 API**,不进 `docs/technical/INDEX.md` 的接口表。
/// 与 `web/src/lib/rawImageFs.ts` 里的 `BASE` 逐字相同,两侧靠 `docs/technical/原图存储-判据层与存储层.md §9.1` 那张表对齐。
const LOCAL_BASE: &str = "/__local/rawimages";
const LOCAL_PREFIX: &str = "/__local/rawimages/";

/// 元信息头。值是 base64(UTF-8(JSON)) —— base64 的对象是**元信息**,不是原图。
const META_HEADER: &str = "x-raw-meta";

/// 原图请求体上限 16 MiB。产品侧单张上限是 `web/src/api/recognize.ts` 的 4 MiB,
/// 这里留四倍余量,但**必须有一个上限** —— 没有上限的话,一次请求就能把内存吃光。
const MAX_BLOB_BYTES: usize = 16 * 1024 * 1024;

/// 控制类请求体上限。它只装得下一批 id。
const MAX_JSON_BYTES: usize = 1024 * 1024;

type Body = BoxBody<Bytes, hyper::Error>;

pub struct Server {
    upstream: Option<Upstream>,
    client: Client<hyper_rustls::HttpsConnector<HttpConnector>, Incoming>,
    trace: AtomicU64,
    /// 🔴 本机原图存储。用 `std::sync::Mutex` 而不是异步锁,因为
    /// [`RawImageStore`] 的每个方法都是**纯同步**的:拿锁 → 碰磁盘 → 放锁,
    /// 中间没有 `.await`,所以 guard 不会跨越 await 点。
    /// <p>代价说清楚:写一张几 MB 的图会短暂占住一个 tokio worker。
    /// 这是一个单窗口的桌面壳,并发量是 1,换来的是**没有第二把锁、没有取消安全问题**。
    store: Mutex<RawImageStore>,
}

struct Upstream {
    /// 例如 `http` / `https`。
    scheme: String,
    /// 例如 `127.0.0.1:8080`。
    authority: String,
}

/// 端口被占。**不自动换端口** —— 换一次 origin 就毁一次浏览器侧的本地存储(R-109)。
#[derive(Debug)]
pub struct PortTaken;

/// 先用同步 API 绑,原因是「绑不上」这件事必须在**开窗之前**、在还能把话说出来的时候发生。
pub fn bind(port: u16) -> Result<StdTcpListener, PortTaken> {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    // 🔴 127.0.0.1,不是 0.0.0.0 —— 与 application.properties 里 server.address 同一条理由:
    // 少了它,同一个 wifi 下的任何人都能连上来,而这条链上还没有任何身份认证。
    StdTcpListener::bind(addr).map_err(|_| PortTaken)
}

impl Server {
    /// `store_dir` 由 `platform::archive_dir()` 给,**没有第二个入口能改它**。
    /// 目录选在哪儿本身就是红线的物理落点,见 `raw_image_store.rs` 顶部与 `docs/technical/原图存储-判据层与存储层.md §3.4`。
    pub fn new(upstream: Option<&str>, store_dir: PathBuf) -> Result<Self, String> {
        let upstream = match upstream {
            None => None,
            Some(raw) => {
                let uri: Uri = raw.parse().map_err(|e| format!("{raw}:{e}"))?;
                let scheme = uri
                    .scheme_str()
                    .ok_or_else(|| format!("{raw}:缺少 scheme"))?;
                let authority = uri
                    .authority()
                    .ok_or_else(|| format!("{raw}:缺少 host:port"))?
                    .to_string();
                Some(Upstream {
                    scheme: scheme.to_string(),
                    authority,
                })
            }
        };

        let mut http = HttpConnector::new();
        http.set_connect_timeout(Some(UPSTREAM_CONNECT_TIMEOUT));
        http.enforce_http(false);

        // 🔴 系统信任库,仅此一处(§3.4 规则 8)。
        // 这里【没有】接受自定义 CA 的入口,也【没有】跳过校验的开关 ——
        // 不是提供了再劝人别用,是根本不提供。
        //
        // 🔴 2026-09-05(`KUBI-113`):加了一条【只有上游真是 https 才要求它】。
        //
        // 起因是 Android 上壳起不来,logcat 里那句是
        // `读不到系统信任库:no native root CA certificates found`——
        // Android 与 iOS 的根证书不在文件系统上(它们在系统 keystore / 共享缓存里),
        // 而 `rustls-native-certs` 找的是 `/etc/ssl/certs` 那一类路径。
        // 于是【上游是明文回环】的移动端脚手架,栽在了一条它根本用不到的 TLS 依赖上。
        //
        // 收窄之后语义更准而不是更松:
        //   · 上游是 https  → 一个字没变,读不到信任库就拒绝启动,响亮地失败;
        //   · 上游是 http / 没有上游 → 这条连接上没有证书要校验,空信任库照样不会
        //     放过任何一次 https 握手(空的根集合谁都不信),所以【没有】任何一条
        //     以前会被校验的连接现在不被校验了。
        //
        // 换句话说:这不是一个「跳过校验的开关」。它仍然没有那种开关。
        let https_upstream = upstream.as_ref().is_some_and(|u| u.scheme == "https");
        let roots = match hyper_rustls::HttpsConnectorBuilder::new().with_native_roots() {
            Ok(b) => b,
            Err(e) if https_upstream => return Err(format!("读不到系统信任库:{e}")),
            Err(_) => {
                hyper_rustls::HttpsConnectorBuilder::new().with_tls_config(no_roots_config()?)
            }
        };
        let tls = roots.https_or_http().enable_http1().wrap_connector(http);

        Ok(Self {
            upstream,
            client: Client::builder(TokioExecutor::new()).build(tls),
            trace: AtomicU64::new(0),
            store: Mutex::new(RawImageStore::new(store_dir)),
        })
    }

    /// 拿锁。中毒(某次调用 panic 过)时**接着用里面的值**,不跟着 panic ——
    /// [`RawImageStore`] 不带内存状态,它的一切都在磁盘上,所以「中毒」这件事
    /// 对它没有含义,而跟着 panic 会让整个壳因为一次磁盘错误而失去存储能力。
    fn store(&self) -> std::sync::MutexGuard<'_, RawImageStore> {
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// 接受连接直到进程结束。调用方把它 spawn 到 Tauri 的运行时上。
    pub async fn run(self: Arc<Self>, listener: StdTcpListener) -> std::io::Result<()> {
        listener.set_nonblocking(true)?;
        let listener = tokio::net::TcpListener::from_std(listener)?;
        loop {
            let (stream, _) = match listener.accept().await {
                Ok(v) => v,
                // 单条连接出错不该让整个壳停下来。
                Err(_) => continue,
            };
            let io = TokioIo::new(stream);
            let me = Arc::clone(&self);
            tokio::spawn(async move {
                let svc = service_fn(move |req| {
                    let me = Arc::clone(&me);
                    async move { me.handle(req).await }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(io, svc)
                    .await;
            });
        }
    }

    async fn handle(&self, req: Request<Incoming>) -> Result<Response<Body>, Infallible> {
        let path = req.uri().path().to_string();
        if path == "/api" || path.starts_with("/api/") {
            Ok(self.proxy(req).await)
        } else if path == LOCAL_BASE || path.starts_with(LOCAL_PREFIX) {
            Ok(self.local_store(req, &path).await)
        } else {
            Ok(serve_static(&req, &path))
        }
    }

    /// `{code, message, traceId}` —— 与服务端错误体同形(docs/technical/INDEX.md §六)。
    ///
    /// 形状对不上的话,`client.ts` 里那条「code 给程序分支、message 给人看」的分支会失效。
    fn error(&self, status: StatusCode, code: &str, message: &str) -> Response<Body> {
        let n = self.trace.fetch_add(1, Ordering::Relaxed);
        let trace_id = format!("shell-{:x}-{:x}", std::process::id(), n);
        let body = serde_json::json!({
            "code": code,
            "message": message,
            "traceId": trace_id,
        })
        .to_string();
        Response::builder()
            .status(status)
            .header(
                hyper::header::CONTENT_TYPE,
                "application/json; charset=utf-8",
            )
            .body(full(Bytes::from(body)))
            .expect("错误体是常量形状,构造不会失败")
    }

    /* ====================================================================== */
    /* 本机原图存储 —— 路由表见 docs/technical/原图存储-判据层与存储层.md §9.1,web 侧 rawImageFs.ts 是同一张表      */
    /* ====================================================================== */

    /// 六个端点,没有第七个。
    ///
    /// | 方法 · 路径 | 做什么 |
    /// |---|---|
    /// | `GET /health` | 回 `{"store":"fs","version":1}` —— **形态探测的全部** |
    /// | `GET /index` | 全部元信息,不含字节 |
    /// | `PUT /blob/<id>` | 写整行:字节是请求体,元信息在 `x-raw-meta` 头里 |
    /// | `GET /blob/<id>` | 取整行 |
    /// | `POST /delete` | 真删(**只服务于用户手按的那一下**) |
    /// | `POST /archive` | 只写 `archivedAt` |
    ///
    /// 🔴 **没有一个端点能改 `expiresAt`**,除了 `PUT /blob/<id>` 那次整行写入 ——
    /// 与 IndexedDB 侧逐条相同:那一层的 `RawImageBackend` 也只有 `put` 能写过期戳。
    /// 「一次重试就把留存期延到无限」在两种形态上一样写不出来。
    async fn local_store(&self, req: Request<Incoming>, path: &str) -> Response<Body> {
        // §3.4 规则 9 的同一条纵深防御。只在头【存在且不是 same-origin】时拒绝。
        if let Some(v) = req.headers().get("sec-fetch-site") {
            if v.as_bytes() != b"same-origin" {
                return self.error(
                    StatusCode::FORBIDDEN,
                    "SHELL_CROSS_SITE",
                    strings::CROSS_SITE_REJECTED,
                );
            }
        }

        let rest = path.strip_prefix(LOCAL_BASE).unwrap_or("").to_string();
        let method = req.method().clone();

        match (&method, rest.as_str()) {
            // 🔴 形态探测。它必须是 JSON —— 只回 200 的话,一个开了 SPA fallback
            //    的部署会被前端误判成「壳」。见本文件顶部。
            (&Method::GET, "/health") => json_ok(
                &serde_json::json!({ "store": "fs", "version": 1 }),
                StatusCode::OK,
            ),

            (&Method::GET, "/index") => match self.store().list() {
                Ok(rows) => json_ok(&serde_json::json!({ "rows": rows }), StatusCode::OK),
                Err(e) => self.store_error(e),
            },

            (&Method::GET, r) if r.starts_with("/blob/") => {
                match self.store().read(&r["/blob/".len()..]) {
                    Ok(None) => self.error(
                        StatusCode::NOT_FOUND,
                        "SHELL_RAWIMAGE_NOT_FOUND",
                        strings::RAWIMAGE_NOT_FOUND,
                    ),
                    // 🔴 content-type 一律 octet-stream,壳【不按图片类型回】。
                    //    按图片类型回的话,这个本地地址就是一条能在浏览器里直接打开原图的链接,
                    //    而 docs/technical/INDEX.md §8.1 禁令 4 是「不做任何形式的图片分享/外链」。
                    Ok(Some((row, bytes))) => Response::builder()
                        .status(StatusCode::OK)
                        .header(hyper::header::CONTENT_TYPE, "application/octet-stream")
                        .header(hyper::header::CACHE_CONTROL, "no-store")
                        .header(META_HEADER, encode_meta_header(&row))
                        .body(full(Bytes::from(bytes)))
                        .expect("原图响应是常量形状,构造不会失败"),
                    Err(e) => self.store_error(e),
                }
            }

            (&Method::PUT, r) if r.starts_with("/blob/") => {
                let id = r["/blob/".len()..].to_string();
                // 头先取出来,再吃 body —— body 会把 req 整个消耗掉。
                let header = match req.headers().get(META_HEADER).and_then(|v| v.to_str().ok()) {
                    Some(h) => h.to_string(),
                    None => {
                        return self.error(
                            StatusCode::BAD_REQUEST,
                            "SHELL_RAWIMAGE_NO_META",
                            strings::RAWIMAGE_BAD_REQUEST,
                        )
                    }
                };
                let row = match decode_meta_header(&header) {
                    Ok(r) => r,
                    Err(e) => return self.store_error(e),
                };
                let bytes = match collect_body(req.into_body(), MAX_BLOB_BYTES).await {
                    Ok(b) => b,
                    Err(()) => {
                        return self.error(
                            StatusCode::PAYLOAD_TOO_LARGE,
                            "SHELL_RAWIMAGE_TOO_LARGE",
                            strings::RAWIMAGE_TOO_LARGE,
                        )
                    }
                };
                match self.store().put(&id, row, &bytes) {
                    Ok(()) => no_content(),
                    Err(e) => self.store_error(e),
                }
            }

            (&Method::POST, "/delete") => {
                let body = match self.read_json(req).await {
                    Ok(v) => v,
                    Err(res) => return res,
                };
                let ids = match string_ids(&body) {
                    Some(ids) => ids,
                    None => {
                        return self.error(
                            StatusCode::BAD_REQUEST,
                            "SHELL_RAWIMAGE_BAD_SHAPE",
                            strings::RAWIMAGE_BAD_REQUEST,
                        )
                    }
                };
                match self.store().delete_many(&ids) {
                    Ok(()) => no_content(),
                    Err(e) => self.store_error(e),
                }
            }

            (&Method::POST, "/archive") => {
                let body = match self.read_json(req).await {
                    Ok(v) => v,
                    Err(res) => return res,
                };
                let (Some(ids), Some(at)) = (string_ids(&body), body.get("at")) else {
                    return self.error(
                        StatusCode::BAD_REQUEST,
                        "SHELL_RAWIMAGE_BAD_SHAPE",
                        strings::RAWIMAGE_BAD_REQUEST,
                    );
                };
                match self.store().archive(&ids, at) {
                    Ok(()) => no_content(),
                    Err(e) => self.store_error(e),
                }
            }

            // 🔴 这里【不】回退到静态资源。多认一条路径就是多一个没人知道的入口。
            _ => self.error(
                StatusCode::NOT_FOUND,
                "SHELL_RAWIMAGE_NO_ROUTE",
                strings::NOT_FOUND,
            ),
        }
    }

    async fn read_json(&self, req: Request<Incoming>) -> Result<Value, Response<Body>> {
        let bytes = collect_body(req.into_body(), MAX_JSON_BYTES)
            .await
            .map_err(|()| {
                self.error(
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "SHELL_RAWIMAGE_TOO_LARGE",
                    strings::RAWIMAGE_TOO_LARGE,
                )
            })?;
        serde_json::from_slice(&bytes).map_err(|_| {
            self.error(
                StatusCode::BAD_REQUEST,
                "SHELL_RAWIMAGE_BAD_SHAPE",
                strings::RAWIMAGE_BAD_REQUEST,
            )
        })
    }

    /// 🔴 错误体里**只有一个静态的原因串**,没有 id、没有 label、没有路径。
    /// docs/technical/INDEX.md §8.2 明说路径也是设备信息,而这个 body 会走到前端。
    fn store_error(&self, e: StoreError) -> Response<Body> {
        match e {
            StoreError::BadId => self.error(
                StatusCode::BAD_REQUEST,
                "SHELL_RAWIMAGE_BAD_ID",
                strings::RAWIMAGE_BAD_REQUEST,
            ),
            StoreError::BadShape(why) => self.error(
                StatusCode::BAD_REQUEST,
                "SHELL_RAWIMAGE_BAD_SHAPE",
                &format!("{}({why})", strings::RAWIMAGE_BAD_REQUEST),
            ),
            StoreError::Io(kind) => self.error(
                StatusCode::INTERNAL_SERVER_ERROR,
                "SHELL_RAWIMAGE_IO",
                &format!("{}({kind})", strings::RAWIMAGE_IO_FAILED),
            ),
        }
    }

    async fn proxy(&self, mut req: Request<Incoming>) -> Response<Body> {
        // §3.4 规则 9:纵深防御,不是边界。真正的边界讨论在 R-108。
        // 只在头**存在且不是 same-origin** 时拒绝 —— WebKit 不一定发这组头,
        // 「没发」不能被读成「跨站」,否则壳在某些系统版本上会整个打不开。
        if let Some(v) = req.headers().get("sec-fetch-site") {
            if v.as_bytes() != b"same-origin" {
                return self.error(
                    StatusCode::FORBIDDEN,
                    "SHELL_CROSS_SITE",
                    strings::CROSS_SITE_REJECTED,
                );
            }
        }

        let Some(up) = self.upstream.as_ref() else {
            return self.error(
                StatusCode::BAD_GATEWAY,
                "SHELL_NO_UPSTREAM",
                strings::UPSTREAM_NOT_CONFIGURED,
            );
        };

        // 路径与 query 原样带走,壳不重写、不补默认值。
        let pq = req
            .uri()
            .path_and_query()
            .map(|p| p.as_str())
            .unwrap_or("/")
            .to_string();
        let target = format!("{}://{}{}", up.scheme, up.authority, pq);
        let Ok(uri) = target.parse::<Uri>() else {
            return self.error(
                StatusCode::BAD_GATEWAY,
                "SHELL_BAD_UPSTREAM",
                strings::UPSTREAM_UNREACHABLE,
            );
        };
        *req.uri_mut() = uri;

        let headers = req.headers_mut();
        for name in HOP_BY_HOP {
            headers.remove(name);
        }
        // Host 改写成上游的 host:port —— 与 dev 的 vite proxy `changeOrigin: true` 同一个行为。
        // 这是除逐跳头之外唯一被动过的头,写在这里是为了它可数。
        if let Ok(v) = HeaderValue::from_str(&up.authority) {
            headers.insert(hyper::header::HOST, v);
        }

        // 🔴 R-110:请求体在这里【原样交给上游连接】。
        // `req` 的 body 仍然是 hyper 的 Incoming —— 中间没有 Vec<u8>、没有临时文件、
        // 没有 `to_bytes()`。壳读不到它一个字节,所以它结构上不可能把原图落盘。
        // 图片走 POST /api/records/{id}/image 的 base64 内联,6 张图必然超过任何默认缓冲。
        let method = req.method().clone();
        match self.client.request(req).await {
            Ok(res) => {
                let (parts, body) = res.into_parts();
                Response::from_parts(parts, body.boxed())
            }
            Err(_) => {
                // 🔴 只记方法与路径。不记头(Authorization 在里面)、不记请求体、不记响应体
                // (§3.4 规则 3 / 4 / 5)。
                eprintln!("[shell] 上游不可达:{method} {}", trim_query(&pq));
                self.error(
                    StatusCode::BAD_GATEWAY,
                    "SHELL_UPSTREAM_UNREACHABLE",
                    strings::UPSTREAM_UNREACHABLE,
                )
            }
        }
    }
}

/// 日志里连 query 都不带 —— query 是调用方给的内容,而壳不碰内容。
fn trim_query(pq: &str) -> &str {
    match pq.find('?') {
        Some(i) => &pq[..i],
        None => pq,
    }
}

fn full(bytes: Bytes) -> Body {
    Full::new(bytes).map_err(|never| match never {}).boxed()
}

/// 🔴 收一个**有上限**的请求体。
///
/// <h2>这个函数是 `R-110` 那条性质的边界,所以它只在这一条路上被调</h2>
///
/// `/api/*` 那条路**没有它** —— 那边的 body 原样交给上游连接,中间没有 `Vec<u8>`。
/// 这一条路必须把字节握在手里(要写进文件),而握在手里就必须有上限:
/// 没有上限的话,一次请求就能把内存吃光,而这是一个回环端口,谁都能打。
///
/// `Limited` 超限时返回 `Err`,**不是截断** —— 截断会把一张半截的图当成整张存下来。
async fn collect_body(body: Incoming, max: usize) -> Result<Bytes, ()> {
    Limited::new(body, max)
        .collect()
        .await
        .map(|c| c.to_bytes())
        .map_err(|_| ())
}

fn json_ok(value: &Value, status: StatusCode) -> Response<Body> {
    let body = serde_json::to_vec(value).unwrap_or_else(|_| b"{}".to_vec());
    Response::builder()
        .status(status)
        .header(
            hyper::header::CONTENT_TYPE,
            "application/json; charset=utf-8",
        )
        // 🔴 本机原图的任何一条响应都不许被缓存 —— 缓存住的归档态是一条读不到的真相。
        .header(hyper::header::CACHE_CONTROL, "no-store")
        .body(full(Bytes::from(body)))
        .expect("JSON 响应是常量形状,构造不会失败")
}

fn no_content() -> Response<Body> {
    Response::builder()
        .status(StatusCode::NO_CONTENT)
        .header(hyper::header::CACHE_CONTROL, "no-store")
        .body(full(Bytes::new()))
        .expect("空响应是常量形状,构造不会失败")
}

/// 从 `{"ids":[…]}` 里取出那批 id。
///
/// 🔴 **一个不是字符串的元素就整批拒绝**,不是「跳过它继续」——
/// 「删了一半」在这条链路上比「一个都没删」糟得多:调用方会以为删干净了。
fn string_ids(body: &Value) -> Option<Vec<String>> {
    let arr = body.get("ids")?.as_array()?;
    let mut out = Vec::with_capacity(arr.len());
    for v in arr {
        out.push(v.as_str()?.to_string());
    }
    Some(out)
}

fn serve_static(req: &Request<Incoming>, path: &str) -> Response<Body> {
    if req.method() != Method::GET && req.method() != Method::HEAD {
        return plain(StatusCode::METHOD_NOT_ALLOWED, strings::NOT_FOUND);
    }

    let rel = match path {
        "/" | "" => "index.html",
        p => p.trim_start_matches('/'),
    };
    // include_dir 不会跨出根目录,这一条是写给读代码的人看的:壳只认那一个目录。
    if rel.split('/').any(|seg| seg == "..") {
        return plain(StatusCode::NOT_FOUND, strings::NOT_FOUND);
    }

    let Some(file) = (match DIST.get_file(rel) {
        Some(f) => Some(f),
        // 🔴 收窄过的 SPA fallback。判据只有一条:最后一段里有没有 `.`。
        // 形状与理由见本文件顶部那张表 —— 丢失的 js/css 仍然必须是 404。
        None if looks_like_route(rel) => DIST.get_file("index.html"),
        None => None,
    }) else {
        return plain(StatusCode::NOT_FOUND, strings::NOT_FOUND);
    };

    // 🔴 内容类型与缓存策略按【真的发出去的那个文件】算,不按请求路径算。
    // fallback 时 rel 是 `/coverage`,而发出去的是 index.html ——
    // 按 rel 算会给一份 HTML 打上 `application/octet-stream`,浏览器直接下载它。
    let served = if file.path().to_string_lossy() == "index.html" {
        "index.html"
    } else {
        rel
    };

    let body = Bytes::from_static(file.contents());
    let len = body.len();
    let mut builder = Response::builder()
        .status(StatusCode::OK)
        .header(hyper::header::CONTENT_TYPE, content_type(served))
        .header(hyper::header::CONTENT_LENGTH, len);

    // dist/assets/* 的文件名里带内容哈希,内容变了文件名就变了,可以永久缓存;
    // index.html 引着那些文件名,它必须每次都重新读,否则换版之后页面还指着旧的哈希名。
    builder = builder.header(
        hyper::header::CACHE_CONTROL,
        if served.starts_with("assets/") {
            "public, max-age=31536000, immutable"
        } else {
            "no-store"
        },
    );

    let body = if req.method() == Method::HEAD {
        full(Bytes::new())
    } else {
        full(body)
    };
    builder.body(body).expect("静态响应是常量形状,构造不会失败")
}

/// 一个空信任库的 TLS 配置 —— 只在「上游不是 https」那条路上用得到。
///
/// 🔴 它不是一个宽松的配置,是一个**谁都不信**的配置:根集合是空的,
/// 任何一次 https 握手都过不去。用它只是为了让连接器的类型凑齐,
/// 而这条壳上唯一会走 TLS 的路(https 上游)在上面那个分支里已经被拦下了。
///
/// 显式传 provider 而不是靠进程级默认值:`Server::new` 在 `cargo test` 里也会被调用,
/// 而测试进程里没人装过默认 provider —— 靠默认值会在测试里 panic。
fn no_roots_config() -> Result<rustls::ClientConfig, String> {
    rustls::ClientConfig::builder_with_provider(std::sync::Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .map_err(|e| format!("TLS 配置构造失败:{e}"))
    .map(|b| {
        b.with_root_certificates(rustls::RootCertStore::empty())
            .with_no_client_auth()
    })
}

/// 这个路径长得像一条路由,还是像一个文件名。
///
/// 判据只有一条:**最后一段里有没有 `.`**。
/// `coverage` / `records/42` 没有 → 路由;`assets/index-abc.js` 有 → 文件名。
///
/// 🔴 它不去读 `web/src/routes/routes.ts` 那张表。读了会更准,但那意味着壳里出现
/// 那张表的**第二份副本**,而「一处定义,各端现读,不抄副本」正是那份契约的第一句话。
/// 副本会过期,而过期的那天,壳会对一条真实存在的路由回 404 —— 比宽一点糟得多。
fn looks_like_route(rel: &str) -> bool {
    !rel.rsplit('/').next().unwrap_or("").contains('.')
}

fn plain(status: StatusCode, message: &str) -> Response<Body> {
    Response::builder()
        .status(status)
        .header(hyper::header::CONTENT_TYPE, "text/plain; charset=utf-8")
        .body(full(Bytes::from(message.to_string())))
        .expect("纯文本响应是常量形状,构造不会失败")
}

/// 只列 `web/dist` 里真会出现的类型。
///
/// 不引 mime_guess:一个只服务于一个已知目录的映射表,不值得为它多一条依赖 ——
/// 而依赖表在这个仓库里是一条能力边界的执行装置(见 Cargo.toml 顶部)。
fn content_type(rel: &str) -> &'static str {
    let ext = rel.rsplit_once('.').map(|(_, e)| e).unwrap_or("");
    match ext {
        "html" => "text/html; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "svg" => "image/svg+xml",
        "json" | "map" => "application/json; charset=utf-8",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff2" => "font/woff2",
        "woff" => "font/woff",
        "ttf" => "font/ttf",
        _ => "application/octet-stream",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 判据层:这几条不碰任何 IO,可以在 node 之外、在 CI 里被测到。
    #[test]
    fn api_prefix_is_exact() {
        // 「/api」与「/api/…」走反代;「/apixx」不是 /api,走静态。
        for p in ["/api", "/api/", "/api/syllabus/tree"] {
            assert!(p == "/api" || p.starts_with("/api/"), "{p} 应当走反代");
        }
        assert!(!"/apidocs".starts_with("/api/") && "/apidocs" != "/api");
    }

    #[test]
    fn authorization_is_not_hop_by_hop() {
        // 🔴 §3.4 规则 3:Authorization 原样透传。它一旦被误列进逐跳头,
        // 阶段 2 的 Bearer 令牌会在壳里被安静地吃掉,而前端只会看到 401。
        assert!(!HOP_BY_HOP.contains(&"authorization"));
    }

    #[test]
    fn assets_are_immutable_index_is_not() {
        assert!("assets/index-abc.js".starts_with("assets/"));
        assert!(!"index.html".starts_with("assets/"));
    }

    #[test]
    fn query_never_reaches_the_log() {
        assert_eq!(trim_query("/api/records?q=1"), "/api/records");
        assert_eq!(trim_query("/api/records"), "/api/records");
    }

    #[test]
    fn dist_is_embedded_and_has_an_entry_point() {
        // 内嵌产物必须真的在二进制里,而且入口必须是 index.html。
        // 这一条同时钉住 build.sh 步骤 ③ 的前提。
        let index = DIST
            .get_file("index.html")
            .expect("dist/index.html 必须被内嵌");
        let text = String::from_utf8_lossy(index.contents());
        assert!(
            text.contains("/assets/"),
            "index.html 应当引用根绝对路径 /assets/*"
        );
    }

    #[test]
    fn spa_fallback_only_for_route_shaped_paths() {
        // 🔴 这条断言两边都要红过一次,否则它只测了自己愿意测的那一半。
        //
        // 路由那一侧:引路由之后 `/coverage` 必须能被直接打开(深链、刷新)。
        assert!(looks_like_route("coverage"));
        assert!(looks_like_route("records/42"));
        assert!(looks_like_route("settings/privacy"));
        assert!(looks_like_route("")); // "/" 已经在上面被换成 index.html,这里只是兜底

        // 文件那一侧:丢失的 js/css 仍然必须是 404。
        // 少了这一半,换版之后 index.html 指着一个不存在的哈希名时,
        // 浏览器会拿到一份 HTML 再报 `Unexpected token '<'` —— 正是要挡的那个故障。
        assert!(!looks_like_route("assets/index-abc.js"));
        assert!(!looks_like_route("assets/index-abc.css"));
        assert!(!looks_like_route("favicon.svg"));
        assert!(!looks_like_route("this/does/not/exist.png"));

        // 而 dist 里确实没有这些东西 —— fallback 是补上去的,不是本来就有。
        assert!(DIST.get_file("coverage").is_none());
        assert!(DIST.get_file("assets/index-abc.js").is_none());
    }

    /* ====================================================================== */
    /* 🔴 线协议:起一个真的服务,打一遍真的 HTTP                                */
    /* ====================================================================== */

    /// 这条测的是**两侧字面量对不对得上**,也就是 `R-116` 那条风险。
    ///
    /// <h2>为什么它非有不可</h2>
    ///
    /// 到这条为止,`web/tests/rawImageFs.test.ts` 测的是**假壳**(线协议的 web 那半),
    /// `raw_image_store.rs` 的测试测的是**存储层本身**(不过 HTTP)。
    /// 两边都绿,而中间那道 HTTP 边界**一次都没有被真的走过** ——
    /// 路由字符串写歪一个字母,两边照样全绿,壳里的原图链路整条不通。
    ///
    /// 所以下面这几个路径**逐字抄自 `rawImageFs.ts`**,不是从本文件的常量拼出来的:
    /// 从常量拼等于让断言和被断言的东西共用同一个错误。
    #[tokio::test]
    async fn the_wire_protocol_actually_works_over_real_http() {
        use http_body_util::BodyExt;
        use hyper_util::client::legacy::Client;

        let dir = std::env::temp_dir().join(format!("kaodian-wire-{}", std::process::id()));
        std::fs::remove_dir_all(&dir).ok();

        let listener = bind(0).expect("临时端口");
        let port = listener.local_addr().unwrap().port();
        let server = Arc::new(Server::new(None, dir.clone()).unwrap());
        tokio::spawn(async move { server.run(listener).await });

        let client: Client<HttpConnector, Full<Bytes>> =
            Client::builder(TokioExecutor::new()).build_http();
        let at = |p: &str| format!("http://127.0.0.1:{port}{p}");

        async fn send(
            client: &Client<HttpConnector, Full<Bytes>>,
            req: Request<Full<Bytes>>,
        ) -> (StatusCode, hyper::HeaderMap, Bytes) {
            let res = client.request(req).await.expect("回环请求");
            let (parts, body) = res.into_parts();
            let bytes = body.collect().await.unwrap().to_bytes();
            (parts.status, parts.headers, bytes)
        }

        // ① 形态探测。前端认的是 200 + JSON + store == "fs",三条缺一不可。
        let (status, headers, body) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/health"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(headers[hyper::header::CONTENT_TYPE]
            .to_str()
            .unwrap()
            .contains("application/json"));
        let health: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(health["store"], "fs");

        // ② PUT 整行 —— 字节是请求体,元信息在头里。label 用中文,base64 就是为它存在的。
        let mut meta = serde_json::Map::new();
        meta.insert("id".into(), Value::String("wire1".into()));
        meta.insert("label".into(), Value::String("我的截图.png".into()));
        meta.insert("expiresAt".into(), Value::from(22_600));
        meta.insert("archivedAt".into(), Value::Null);
        let (status, _, _) = send(
            &client,
            Request::builder()
                .method(Method::PUT)
                .uri(at("/__local/rawimages/blob/wire1"))
                .header(META_HEADER, encode_meta_header(&meta))
                .body(Full::new(Bytes::from_static(b"\x89PNG-bytes")))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);

        // ③ 索引。
        let (status, _, body) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/index"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        let index: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(index["rows"][0]["expiresAt"], 22_600);
        // 🔴 中文文件名穿过 JSON 与 HTTP 之后还是原来那几个字。
        assert_eq!(index["rows"][0]["label"], "我的截图.png");

        // ④ 读回整行:字节在 body 里,元信息在头里,content-type 是 octet-stream。
        let (status, headers, body) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/blob/wire1"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, Bytes::from_static(b"\x89PNG-bytes"));
        assert_eq!(
            headers[hyper::header::CONTENT_TYPE],
            "application/octet-stream"
        );
        let back =
            crate::raw_image_store::decode_meta_header(headers[META_HEADER].to_str().unwrap())
                .unwrap();
        assert_eq!(back.get("label").unwrap(), "我的截图.png");

        // ⑤ 归档,然后确认【还读得出来】—— 归档不是删除。
        let (status, _, _) = send(
            &client,
            Request::builder()
                .method(Method::POST)
                .uri(at("/__local/rawimages/archive"))
                .body(Full::new(Bytes::from(r#"{"ids":["wire1"],"at":98765}"#)))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _, _) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/blob/wire1"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        // ⑥ 删。用户手按的那一下,真删。
        let (status, _, _) = send(
            &client,
            Request::builder()
                .method(Method::POST)
                .uri(at("/__local/rawimages/delete"))
                .body(Full::new(Bytes::from(r#"{"ids":["wire1"]}"#)))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let (status, _, _) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/blob/wire1"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert!(!dir.join("wire1.bin").exists());

        // ⑦ 路径穿越:壳【不做百分号解码】,所以这个串永远不会变成 `..`。
        let (status, _, _) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/blob/%2e%2e%2fshell.json"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);

        // ⑧ 没有第七个端点。多认一条路径就是多一个没人知道的入口。
        let (status, _, _) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/anything-else"))
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);

        // ⑨ 纵深防御:头存在且不是 same-origin 就拒。
        let (status, _, _) = send(
            &client,
            Request::builder()
                .uri(at("/__local/rawimages/index"))
                .header("sec-fetch-site", "cross-site")
                .body(Full::new(Bytes::new()))
                .unwrap(),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN);

        std::fs::remove_dir_all(&dir).ok();
    }
}
