//! 壳里所有面向用户的字符串,一个文件,没有第二处。
//!
//! <h2>为什么集中</h2>
//!
//! docs/18 §六:「能力边界文案扫描」因此只有一处要扫。
//! 散在各处的字符串意味着扫描要跟着代码走,而跟着代码走的扫描迟早漏一处 ——
//! 漏掉的那一处不会报错,它只会安安静静地在界面上说一句产品没资格说的话。
//!
//! <h2>🔴 撞词表时改文案,不改词表</h2>
//!
//! `shell/scripts/capability-boundary-scan.mjs` 扫的是 `shell/src` 全树,
//! 词表【不复制一份】,是从 `web/scripts/capability-boundary-scan.mjs` 里现读的。
//! 所以「改词表绕过」这条路在壳这边根本不存在:改了那份,web 侧的扫描当场跟着变。
//!
//! <h2>这里【没有】什么</h2>
//!
//! 没有任何一句是在评价用户。壳不认识考点、不认识记录、不读任何请求体 ——
//! 它结构上没有能力说出一句判断的话。下面每一条都只在说【壳自己】的状态:
//! 端口、上游、窗口。这不是克制,是它知道的全部。

/// 窗口标题。
///
/// 🔴 与 `web/index.html` 的 `<title>` 逐字相同。
/// 壳里和浏览器里是同一个产品,标题不一致是「第二套界面」最便宜的一种形态。
pub const WINDOW_TITLE: &str = "考点盲区 — 山东省考 · 资料分析";

/// 菜单栏三项(KUBI-64 判定:菜单栏删到只剩「考点盲区 · 编辑 · 窗口」)。
///
/// 「编辑」必须留 —— ⌘C / ⌘V 在 WebView 里靠的是菜单项上挂的系统快捷键,
/// 把这一栏删掉,复制粘贴会连同它一起消失,而那是浏览器里有、壳里没有的差异。
pub const MENU_APP: &str = "考点盲区";
pub const MENU_EDIT: &str = "编辑";
pub const MENU_WINDOW: &str = "窗口";
pub const MENU_QUIT: &str = "退出考点盲区";

/// 端口被占用 —— 拒绝启动(R-109)。
///
/// <h2>为什么这句话要说得这么长</h2>
///
/// 因为它要劝住的正是读到它的那个人下一步最想做的事:换一个端口先跑起来。
/// 端口是 origin 的一部分,换一次,浏览器侧按 origin 存的东西全部读不回来,
/// 不报错、不提示、看起来就像「数据没了」。
/// 与 `PhoneKeyGuard`(R-59)同一条纪律:**响亮地失败,不无声地毁数据。**
pub const PORT_TAKEN_TITLE: &str = "考点盲区 · 没能启动";

/// `{port}` / `{holder}` / `{config}` 三个占位由 [`port_taken_body`] 填。
pub fn port_taken_body(port: u16, holder: Option<&str>, config_path: &str) -> String {
    let who = match holder {
        Some(h) => format!("占用它的是:{h}"),
        None => "没能查出占用它的是谁(壳只会问一次 lsof,不会反复试)。".to_string(),
    };
    format!(
        "本机 {port} 端口已经被别的进程占着,所以这次没有启动。\n\n\
         {who}\n\n\
         为什么不自动换一个端口:端口是页面地址的一部分,换掉之后,\
         这台机器上按原地址存下来的东西全部读不回来 —— 而且不会有任何提示,\
         看起来就像东西自己没了。宁可现在开不起来。\n\n\
         请先结束占用该端口的进程;确实要换端口时,改配置文件里的 port:\n{config_path}"
    )
}

/// 配置文件读不动 —— 同样拒绝启动,理由同上:里面存着端口。
pub const CONFIG_BROKEN_TITLE: &str = "考点盲区 · 配置文件读不动";

pub fn config_broken_body(config_path: &str, why: &str) -> String {
    format!(
        "配置文件没能读出来,所以这次没有启动:\n{config_path}\n\n\
         原因:{why}\n\n\
         为什么不直接用默认值重写一份:里面存着端口,而端口是页面地址的一部分。\
         用默认值盖掉它,这台机器上按原地址存下来的东西就全部读不回来了。\n\n\
         请把文件改回合法的 JSON,或者把它删掉让壳重新生成 —— \
         删之前先看一眼里面的 port 是多少。"
    )
}

/// 上游不可达时回给前端的错误体 message。
///
/// 状态码固定 502:`web/src/api/client.ts` 已经把 502/503/504 翻译成
/// 「连不上 /api —— 后端 :8080 没起来?」,前端显示的是它自己那句,
/// 这一句只进错误体的 message 字段(给排查的人看),不上界面。
pub const UPSTREAM_UNREACHABLE: &str =
    "壳没能把这个请求转给后端。壳只做转发,不缓存、不改写、不落盘。";

/// 没有配置上游(移动端脚手架形态)。
pub const UPSTREAM_NOT_CONFIGURED: &str = "这个形态没有配置后端上游,/api 一律不转发。";

/// 非 same-origin 的请求(§3.4 规则 9)。纵深防御,不是边界。
pub const CROSS_SITE_REJECTED: &str = "只接受来自本窗口自己的请求。";

/// 静态资源找不到。
///
/// 🔴 这里【绝不】回退到 index.html。
/// `client.ts` 点名过这个故障:静态服务器把 index.html 当兜底返回,
/// 前端拿到的是「Unexpected token '<'」,而真正的成因是 /api 没被反代出去。
pub const NOT_FOUND: &str = "没有这个资源。";
