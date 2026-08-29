//! 只绑回环的静态服务 + `/api` 出口。
//!
//! 存在的理由只有一条:**让 `/api/*` 这条契约在第三个部署形态里仍然只有一份。**
//! dev 是 vite proxy,生产是 Caddy,壳是它自己 —— 三个实现,一条契约,
//! 前端一个字不知道自己在哪儿跑(壳技术方案 §3.2)。
//!
//! 🔴 **这个文件必须零 `#[cfg(target_os)]`。** 三端跑的是同一份代码,
//! 由 `build.sh` 步骤 ① 在构建期强制(壳技术方案 §4.1 / §4.3)。

use std::io::Cursor;
use std::net::{Ipv4Addr, TcpListener};

use include_dir::{include_dir, Dir};
use tiny_http::{Header, Request, Response, Server};

/// 编译期把 `web/dist` 整个内嵌进二进制。
///
/// 内嵌而不是读安装包资源目录:三端拿资源的方式各不相同(macOS 在
/// `.app/Contents/Resources`,Android 要走 AssetManager),内嵌把这个差异消成零。
/// 成本只是二进制大几 MB(壳技术方案 §3.3)。
///
/// 🔴 dist 不进 git。壳依赖的是**同一次 checkout 里现构建出来的产物**,
/// 不是仓库里存着的 —— 「壳里看到的和浏览器里一致」就是靠这一条。
static DIST: Dir<'_> = include_dir!("$CARGO_MANIFEST_DIR/../web/dist");

/// 🔴 `127.0.0.1`,不是 `0.0.0.0`。
///
/// 与 `application.properties` 里 `server.address=127.0.0.1` 同一条理由:
/// 这段流量不出网卡。绑 `0.0.0.0` 会让同网段任何设备都连得上。
pub const LOOPBACK: Ipv4Addr = Ipv4Addr::new(127, 0, 0, 1);

/// 试着占住端口。
///
/// 🔴 **占不住就返回 `Err`,调用方必须拒绝启动,不许改用别的端口。**
/// 端口是 origin 的一部分,自动换端口会让浏览器侧存的东西静默全部消失,
/// 不报错、不提示,看起来就像「数据没了」(壳技术方案 §3.7 / `R-73`)。
/// 与 `PhoneKeyGuard`(`R-59`)同一条纪律:**响亮地失败,不无声地毁数据。**
pub fn bind(port: u16) -> std::io::Result<TcpListener> {
    TcpListener::bind((LOOPBACK, port))
}

/// 阻塞地跑服务。调用方自己决定放哪个线程。
///
/// `upstream` 为 `None` 表示这个端不接后端(iOS / Android 本轮即是),
/// 此时 `/api/*` 一律回 502。
pub fn serve(listener: TcpListener, upstream: Option<String>) {
    let server = match Server::from_listener(listener, None) {
        Ok(s) => s,
        // 端口已经在 bind() 里占住了,走到这里说明是别的系统级故障。
        Err(e) => {
            eprintln!("[shell] 本地服务起不来:{e}");
            return;
        }
    };

    for request in server.incoming_requests() {
        // 🔴 这里**不打印**方法、路径之外的任何东西,尤其不打印请求体与响应体。
        // 实际上这一版一行请求日志都不打 —— 图片走 base64 内联,
        // 一条日志就可能把原图字节写进日志文件(壳技术方案 §3.4 规则 5,`R-74`)。
        let url = request.url().to_string();
        let path = url.split('?').next().unwrap_or("/").to_string();

        if path == "/api" || path.starts_with("/api/") {
            respond_api(request, upstream.as_deref());
        } else {
            respond_static(request, &path);
        }
    }
}

/// 路由表的第二条:其余 → 内嵌静态。
///
/// 🔴 **没有第三条,没有 SPA fallback。**
/// 「找不到就返回 index.html」这条最常见的默认行为,恰好是 `client.ts` 里点名的那个故障:
/// 静态服务器把 index.html 当兜底返回,前端拿到「Unexpected token '<'」
/// (壳技术方案 §2.2 事实 3 / §3.4 规则 1)。
///
/// 前端**没有路由**(`MainScreen` 里一个 `view` 状态切两个视图),
/// 所以 `/` 之外的任何路径本来就不该被访问到,404 才是这里该给的回应。
fn respond_static(request: Request, path: &str) {
    let rel = match path.trim_start_matches('/') {
        "" => "index.html",
        p => p,
    };

    match DIST.get_file(rel) {
        Some(file) => {
            let body = file.contents();
            let response = Response::new(
                200.into(),
                vec![content_type_for(rel)],
                Cursor::new(body),
                Some(body.len()),
                None,
            );
            let _ = request.respond(response);
        }
        None => {
            let _ = request.respond(Response::from_string("").with_status_code(404));
        }
    }
}

/// 路由表的第一条:`/api/*`。
///
/// 本轮只有「不接后端 → 502」这一支。**逐字节反代是 `KUBI-65` 的活**
/// (壳技术方案 §3.4 的九条规则),`KUBI-66` 不替它实现 ——
/// iOS 明确不接后端,写了也没有任何东西能验证它对不对。
fn respond_api(request: Request, upstream: Option<&str>) {
    let message = match upstream {
        None => crate::strings::UPSTREAM_UNCONFIGURED,
        Some(_) => crate::strings::UPSTREAM_NOT_IMPLEMENTED,
    };

    // 🔴 状态码必须是 502,body 必须是 {code,message,traceId} 形状。
    // 前端 client.ts 已经把 502/503/504 翻译成一句人话,
    // 返回别的状态码等于让一条已经写好的错误提示失效(壳技术方案 §3.4 规则 6)。
    let body = serde_json::json!({
        "code": "SHELL_UPSTREAM_UNAVAILABLE",
        "message": message,
        "traceId": serde_json::Value::Null,
    })
    .to_string();

    let header = Header::from_bytes(
        &b"Content-Type"[..],
        &b"application/json; charset=utf-8"[..],
    )
    .expect("content-type 头是常量,构造不会失败");

    let response = Response::from_string(body)
        .with_status_code(502)
        .with_header(header);

    // 🔴 请求体一个字节都没读过。壳一旦开始读请求体,它就变成了契约的第三个参与方,
    // 而契约只有两方(壳技术方案 §3.4 规则 2)。
    let _ = request.respond(response);
}

fn content_type_for(path: &str) -> Header {
    let mime = match path.rsplit('.').next() {
        Some("html") => "text/html; charset=utf-8",
        Some("js") | Some("mjs") => "text/javascript; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("svg") => "image/svg+xml",
        Some("json") => "application/json; charset=utf-8",
        Some("png") => "image/png",
        Some("woff2") => "font/woff2",
        _ => "application/octet-stream",
    };
    Header::from_bytes(&b"Content-Type"[..], mime.as_bytes())
        .expect("mime 取自上面的固定表,构造不会失败")
}
