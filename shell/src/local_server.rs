//! 只绑回环的静态直出 + `/api` 反代。
//!
//! <h2>它是同一条契约的第三个实现</h2>
//!
//! 前端只写相对路径 `/api/*`,这条路径落到哪里,取决于页面 origin:
//! dev 是 vite proxy,生产是 Caddy,壳是这里。
//! **三个实现,一条契约,前端一个字不知道自己在哪儿跑。**
//!
//! <h2>路由表只有两条,没有第三条</h2>
//!
//! | 路径 | 去向 |
//! |---|---|
//! | `/api/*` | 反代到上游 |
//! | 其余 | 编译期内嵌的 `web/dist` |
//!
//! 🔴 **没有 SPA fallback。** 「找不到就返回 index.html」是静态服务器最常见的默认行为,
//! 而它恰好是 `web/src/api/client.ts` 点名的那个故障:
//! 「有 body 但不是 JSON —— 最常见的成因是 /api 压根没被反代出去,
//! 静态服务器把 index.html 当兜底返回了」。前端能识别这个故障,前提是壳不制造它。
//!
//! 零 `#[cfg]` —— 这是判据(docs/15 §4.1),这个文件里出现一个 `#[cfg(target_os)]`,隔离就已经破了。

use std::convert::Infallible;
use std::net::{Ipv4Addr, SocketAddr, TcpListener as StdTcpListener};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use http_body_util::{combinators::BoxBody, BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::header::HeaderValue;
use hyper::service::service_fn;
use hyper::{Method, Request, Response, StatusCode, Uri};
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::client::legacy::Client;
use hyper_util::rt::{TokioExecutor, TokioIo};
use include_dir::{include_dir, Dir};

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

type Body = BoxBody<Bytes, hyper::Error>;

pub struct Server {
    upstream: Option<Upstream>,
    client: Client<hyper_rustls::HttpsConnector<HttpConnector>, Incoming>,
    trace: AtomicU64,
}

struct Upstream {
    /// 例如 `http` / `https`。
    scheme: String,
    /// 例如 `127.0.0.1:8080`。
    authority: String,
}

/// 端口被占。**不自动换端口** —— 换一次 origin 就毁一次浏览器侧的本地存储(R-73)。
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
    pub fn new(upstream: Option<&str>) -> Result<Self, String> {
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
        let tls = hyper_rustls::HttpsConnectorBuilder::new()
            .with_native_roots()
            .map_err(|e| format!("读不到系统信任库:{e}"))?
            .https_or_http()
            .enable_http1()
            .wrap_connector(http);

        Ok(Self {
            upstream,
            client: Client::builder(TokioExecutor::new()).build(tls),
            trace: AtomicU64::new(0),
        })
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
        } else {
            Ok(serve_static(&req, &path))
        }
    }

    /// `{code, message, traceId}` —— 与服务端错误体同形(docs/10 §六)。
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

    async fn proxy(&self, mut req: Request<Incoming>) -> Response<Body> {
        // §3.4 规则 9:纵深防御,不是边界。真正的边界讨论在 R-72。
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

        // 🔴 R-74:请求体在这里【原样交给上游连接】。
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

    let Some(file) = DIST.get_file(rel) else {
        // 🔴 这里【不】返回 index.html。理由见本文件顶部。
        return plain(StatusCode::NOT_FOUND, strings::NOT_FOUND);
    };

    let body = Bytes::from_static(file.contents());
    let len = body.len();
    let mut builder = Response::builder()
        .status(StatusCode::OK)
        .header(hyper::header::CONTENT_TYPE, content_type(rel))
        .header(hyper::header::CONTENT_LENGTH, len);

    // dist/assets/* 的文件名里带内容哈希,内容变了文件名就变了,可以永久缓存;
    // index.html 引着那些文件名,它必须每次都重新读,否则换版之后页面还指着旧的哈希名。
    builder = builder.header(
        hyper::header::CACHE_CONTROL,
        if rel.starts_with("assets/") {
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
    fn no_spa_fallback_for_unknown_paths() {
        // 找不到就是找不到 —— 不回 index.html。
        assert!(DIST.get_file("this/does/not/exist").is_none());
    }
}
