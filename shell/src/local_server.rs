//! 只绑回环的静态直出 + `/api` 反代(docs/15 §3.3 / §3.4)。
//!
//! 零 `#[cfg]`(§4.1)。三端跑的是同一份代码,同一份 `web/dist` 字节。
//!
//! 这个模块存在的全部理由:让页面的地址是 `http://127.0.0.1:<port>`,
//! 于是 `/api/*` 与页面同源。前端因此一个字都不知道自己在壳里跑 ——
//! dev 是 vite proxy,生产是 Caddy,壳是这里,三个实现一条契约。

use std::convert::Infallible;
use std::net::TcpListener;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use http_body_util::combinators::BoxBody;
use http_body_util::{BodyExt, Full};
use hyper::body::Incoming;
use hyper::header::{HeaderName, HeaderValue};
use hyper::service::service_fn;
use hyper::{Request, Response, StatusCode, Uri};
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::client::legacy::Client;
use hyper_util::rt::{TokioExecutor, TokioIo};
use include_dir::{include_dir, Dir};

use crate::strings;

/// 编译期把 `web/dist` 整个内嵌进二进制(docs/15 §3.3)。
///
/// 为什么内嵌而不是读安装包资源目录:三端拿资源的方式各不相同
/// (macOS 在 `.app/Contents/Resources`,Android 在 APK 里要走 AssetManager)。
/// 内嵌把这个差异消成零,代价只是二进制大几 MB。
///
/// 目录不存在时这里会是一句编译错误。`build.sh` 步骤 ③ 在此之前先检查一次,
/// 因为那句宏错误和「前端没构建」这件事对不上号。
static DIST: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../web/dist");

/// 上游连接超时。刻意短于前端的 `TIMEOUT_MS = 2000`(docs/15 §3.4 规则 7):
/// 让前端拿到一个有意义的 502,而不是自己 abort 后显示一句泛泛的请求超时。
const UPSTREAM_CONNECT_TIMEOUT: Duration = Duration::from_millis(1500);

type Body = BoxBody<Bytes, hyper::Error>;

pub struct LocalServer {
    upstream: Option<Upstream>,
    client: Client<HttpConnector, Incoming>,
}

struct Upstream {
    authority: String,
}

impl LocalServer {
    /// `upstream` 为 `None` 时,`/api/*` 一律 502 —— 这一端本轮不接后端(§4.2)。
    pub fn new(upstream: Option<&str>) -> Result<Self, String> {
        let upstream = match upstream {
            None => None,
            Some(raw) => {
                let uri: Uri = raw.parse().map_err(|e| format!("shell.upstream: {e}"))?;
                match uri.scheme_str() {
                    Some("http") => {}
                    // 规则 8 那一段(https 走系统信任库)在壳里还没有实现体:
                    // 引一个 TLS 栈是一件要单独拍板的事。响亮地拒绝,不静默连不上。
                    _ => return Err(strings::upstream_https_unsupported(raw)),
                }
                let authority = uri
                    .authority()
                    .ok_or_else(|| strings::UPSTREAM_NO_HOST.to_owned())?
                    .to_string();
                Some(Upstream { authority })
            }
        };

        let mut connector = HttpConnector::new();
        connector.set_connect_timeout(Some(UPSTREAM_CONNECT_TIMEOUT));
        connector.enforce_http(true);
        let client = Client::builder(TokioExecutor::new()).build(connector);

        Ok(Self { upstream, client })
    }

    /// 一直跑。`listener` 由调用方先绑好 —— 绑与用之间不留窗口给别的进程抢。
    pub async fn serve(self: Arc<Self>, listener: TcpListener) -> std::io::Result<()> {
        listener.set_nonblocking(true)?;
        let listener = tokio::net::TcpListener::from_std(listener)?;
        loop {
            let (stream, _) = listener.accept().await?;
            let this = Arc::clone(&self);
            tokio::spawn(async move {
                let io = TokioIo::new(stream);
                let svc = service_fn(move |req| {
                    let this = Arc::clone(&this);
                    async move { this.route(req).await }
                });
                // 连接层出错只影响这一条连接。这里不打印任何请求内容(§3.4 规则 5)。
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(io, svc)
                    .await;
            });
        }
    }

    /// 路由表只有两条,没有第三条,没有 fallback(§3.4 规则 1)。
    ///
    /// 「找不到就返回 index.html」这条最常见的 SPA 默认行为,恰好是 `client.ts` 里
    /// 点名的那个故障:前端拿到「Unexpected token '<'」。前端没有路由,
    /// 所以这里也不需要 fallback —— 这一条要当规则写,不是当运气。
    async fn route(&self, req: Request<Incoming>) -> Result<Response<Body>, Infallible> {
        // 规则 9:纵深防御,不是边界。真正的边界讨论在 R-72。
        if let Some(v) = req.headers().get("sec-fetch-site") {
            if v.as_bytes() != b"same-origin" && v.as_bytes() != b"none" {
                return Ok(status_only(StatusCode::FORBIDDEN));
            }
        }

        if req.uri().path().starts_with("/api/") || req.uri().path() == "/api" {
            Ok(self.proxy(req).await)
        } else {
            Ok(serve_static(req.uri().path()))
        }
    }

    /// 逐字节转发,壳不读、不改写、不补默认值(§3.4 规则 2)。
    ///
    /// 请求体是流式的 `Incoming`,原样交给 client —— 壳不把它读进内存、不落盘。
    /// 图片走 base64 内联,一次 6 张必然超过任何默认缓冲,而那正是 `R-74`
    /// (docs/10 §8.1 禁令 5 在第三个接入层上的原样重现)。
    async fn proxy(&self, req: Request<Incoming>) -> Response<Body> {
        let Some(upstream) = self.upstream.as_ref() else {
            return bad_gateway(strings::UPSTREAM_NOT_CONFIGURED);
        };

        let (mut parts, body) = req.into_parts();
        let path_and_query = parts
            .uri
            .path_and_query()
            .map(|pq| pq.as_str().to_owned())
            .unwrap_or_else(|| parts.uri.path().to_owned());

        let uri = match Uri::builder()
            .scheme("http")
            .authority(upstream.authority.as_str())
            .path_and_query(path_and_query)
            .build()
        {
            Ok(u) => u,
            Err(_) => return bad_gateway(strings::UPSTREAM_UNREACHABLE),
        };
        parts.uri = uri;

        // Host 要跟着上游走,别的请求头一个不动 —— 包括 Authorization。
        // Authorization 原样透传,且不进任何级别的日志(规则 3):
        // 壳不签发、不缓存、不刷新令牌。
        parts.headers.remove(hyper::header::HOST);
        if let Ok(v) = HeaderValue::from_str(&upstream.authority) {
            parts.headers.insert(hyper::header::HOST, v);
        }

        match self.client.request(Request::from_parts(parts, body)).await {
            Ok(resp) => {
                let (parts, body) = resp.into_parts();
                Response::from_parts(parts, body.boxed())
            }
            // 上游连不上 / 超时 → 502,body 是 {code,message,traceId} 形状(规则 6)。
            // 换成别的状态码,等于让 client.ts 里已经写好的那句错误提示失效。
            Err(_) => bad_gateway(strings::UPSTREAM_UNREACHABLE),
        }
    }
}

fn serve_static(path: &str) -> Response<Body> {
    let rel = match path.trim_start_matches('/') {
        "" => "index.html",
        other => other,
    };
    // 内嵌目录本身就没有上级目录可去,`..` 只会查不到。不额外做路径归一,
    // 因为多一层自制的归一逻辑就是多一处会写错的地方。
    let Some(file) = DIST.get_file(rel) else {
        return status_only(StatusCode::NOT_FOUND);
    };
    let mut resp = Response::new(full(Bytes::from_static(file.contents())));
    resp.headers_mut().insert(
        hyper::header::CONTENT_TYPE,
        HeaderValue::from_static(mime_for(rel)),
    );
    resp
}

fn mime_for(path: &str) -> &'static str {
    match path.rsplit('.').next() {
        Some("html") => "text/html; charset=utf-8",
        Some("js") | Some("mjs") => "text/javascript; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("svg") => "image/svg+xml",
        Some("json") => "application/json; charset=utf-8",
        Some("png") => "image/png",
        Some("jpg") | Some("jpeg") => "image/jpeg",
        Some("webp") => "image/webp",
        Some("woff2") => "font/woff2",
        _ => "application/octet-stream",
    }
}

/// 502 的 body 形状与 server 侧的错误体一致({code,message,traceId}),
/// 前端因此不需要为壳这条路径写第二套处理。
fn bad_gateway(message: &str) -> Response<Body> {
    let payload = serde_json::json!({
        "code": "SHELL_UPSTREAM_UNAVAILABLE",
        "message": message,
        "traceId": "shell",
    });
    let mut resp = Response::new(full(Bytes::from(payload.to_string())));
    *resp.status_mut() = StatusCode::BAD_GATEWAY;
    resp.headers_mut().insert(
        HeaderName::from_static("content-type"),
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    resp
}

fn status_only(status: StatusCode) -> Response<Body> {
    let mut resp = Response::new(full(Bytes::new()));
    *resp.status_mut() = status;
    resp
}

fn full(bytes: Bytes) -> Body {
    Full::new(bytes).map_err(|never| match never {}).boxed()
}
