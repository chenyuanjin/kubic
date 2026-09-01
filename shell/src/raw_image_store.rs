//! 原图的**文件系统存储层** —— `RawImageBackend` 的壳侧那一半。
//!
//! <h2>🔴 这个文件里【没有】判断,和 `web/src/lib/rawImageDb.ts` 是同一条纪律</h2>
//!
//! 「到期了没有」「该归档哪几张」「上限到了先归档谁」全在
//! `web/src/lib/rawImageCache.ts` —— 那一层跑得进 node,拨得动时钟,22 条断言盯着。
//! 这一层碰真实磁盘,所以它**不许有可以出错的逻辑**。
//!
//! 这条纪律在 Rust 这一侧有一个比注释更硬的形态:
//!
//! 🔴 **本模块把一行的元信息当成一块不透明的 JSON 对象**([`Row`])。
//! 它只从里面读一个字段:`id`(拿来当文件名)。它**读不到 `expiresAt`** ——
//! 所以「壳自己判一下有没有过期」这件事在这里**写不出来**,不是「说好了不写」。
//! 唯一被它认识的第二个字段名是 `archivedAt`,而它只会**写**这一个,永远不读。
//! 这一条由 `build.sh` 步骤 ① grep 拦:`shell/src` 里出现 `expiresAt` 即拒绝构建。
//!
//! <h2>🔴 这是壳第一次往磁盘上写用户的东西,所以写入点必须可数</h2>
//!
//! `local_server.rs` 顶上那条 `R-110`(请求体原样流给上游、壳读不到一个字节)
//! **对 `/api/*` 仍然成立**,一个字都没变。变的是壳多了一条**不去上游**的路:
//! `/__local/rawimages/*` 的请求体落到这个目录里,哪儿也不去。
//!
//! | | `/api/*` | `/__local/rawimages/*` |
//! |---|---|---|
//! | 请求体去哪 | 原样交给上游连接,壳不缓冲 | 落到本机 [`RawImageStore::dir`],**不出这台机器** |
//! | 有没有网络出口 | 有(上游) | 🔴 **没有** —— 本模块不 import 任何 HTTP 客户端 |
//!
//! 第二行是红线「原图绝不上云、不同步、不共享」在这一层的执行装置,
//! 与 `scheduler.rs` 那条「不允许发起任何网络请求」是同一手法,同样由 `build.sh` grep 拦。
//!
//! <h2>目录布局</h2>
//!
//! ```text
//! <archive_dir>/            ← platform::archive_dir(),macOS 是 ~/Library/Application Support/kaodian/originals
//!   index.json              ← 全部元信息,一个文件,原子重写
//!   <id>.bin                ← 一张图的字节,一个文件
//!   <id>.tmp / index.tmp    ← 写到一半的残留,启动清理
//! ```
//!
//! 🔴 **目录本身就是红线的物理落点**(`docs/原图存储 §3.4`):它落在应用数据目录,
//! 不在 `~/Documents` / `~/Desktop` / `~/Pictures` —— macOS 的 iCloud「桌面与文稿」
//! 同步默认可开,开着就等于原图自动上云,而且**不报错、不出现在任何 review 里**。
//! 本模块**没有**接受外部目录的入口:`new` 只被 `main.rs` 调一次,参数来自 `platform`。
//! 「让用户自己选目录」是一次刻意的功能删减,不是还没做。

use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};

use serde_json::{Map, Value};

/// 一行的元信息 —— **一块不透明的 JSON 对象**。
///
/// 🔴 这里刻意**不是**一个带字段的 struct。写成 struct 就意味着壳认识
/// `expiresAt` / `storedAt` / `archivedAt` 的含义,而认识它们的下一步
/// 永远是「顺手判一下」。判据只有一份,在 web 那一层。
pub type Row = Map<String, Value>;

/// 索引文件名。与字节文件同目录 —— 一次备份带走全部。
const INDEX_FILE: &str = "index.json";
/// 字节文件后缀。
const BIN_EXT: &str = "bin";
/// 写到一半的后缀。`id` 的字符集里没有 `.`,所以它和 `<id>.bin` 不会混。
const TMP_EXT: &str = "tmp";

/// 索引文件的形状版本。改它之前先想清楚旧文件怎么办 —— 现在只有一个版本。
const INDEX_VERSION: u64 = 1;

/// 🔴 壳认识的**第二个也是最后一个**字段名,而且它只写、不读。
const ARCHIVED_AT: &str = "archivedAt";

/// 出错的三种。调用方据此决定状态码,本模块不出状态码、不出文案。
#[derive(Debug)]
pub enum StoreError {
    /// id 不在字符集白名单里。**这条在碰任何路径之前就返回。**
    BadId,
    /// 元信息不是一个带 `id` 的 JSON 对象,或者请求体不是预期的形状。
    BadShape(&'static str),
    /// 磁盘。
    Io(String),
}

impl StoreError {
    fn io(e: std::io::Error) -> Self {
        // 🔴 只留 io::Error 自己的描述,**不拼路径**。
        // docs/技术架构 §8.2 明说路径也是设备信息,而这个字符串会进错误体。
        StoreError::Io(e.kind().to_string())
    }
}

/// 🔴 id 字符集白名单 —— `[A-Za-z0-9_-]{1,64}`。
///
/// <h2>为什么是白名单,而且为什么壳【不做百分号解码】</h2>
///
/// 调用方把 id 放进 URL 之前 `encodeURIComponent` 过一次,而合法 id 在这个字符集里
/// 编码前后逐字节相同。所以壳**拿到什么就校验什么,一次解码都不做** ——
/// 解码会让 `%2e%2e%2f` 先变成 `../`,然后才轮到检查,而那种顺序历史上塌过无数次。
/// 不解码 = 那个串永远不会变成 `..`,它只会因为含 `%` 而当场被拒。
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn row_id(row: &Row) -> Option<&str> {
    row.get("id")?.as_str()
}

/// 原子写:临时文件 → `fsync` → `rename`。
///
/// 🔴 `rename` 在同一个文件系统内是原子的,所以读者只会看到**旧的整份**或**新的整份**,
/// 永远看不到一份写了一半的索引。`sync_all` 在 `rename` 之前 ——
/// 少了它,断电后可能 rename 已经生效而内容还在页缓存里,于是索引变成一个空文件。
fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), StoreError> {
    let tmp = path.with_extension(TMP_EXT);
    {
        let mut f = File::create(&tmp).map_err(StoreError::io)?;
        f.write_all(bytes).map_err(StoreError::io)?;
        f.sync_all().map_err(StoreError::io)?;
    }
    fs::rename(&tmp, path).map_err(StoreError::io)?;
    // 目录项本身也 fsync 一次,让 rename 落盘。拿不到目录句柄不算错 ——
    // 这一步是加固,不是正确性的前提(前提是上面那个 sync_all)。
    if let Some(parent) = path.parent() {
        if let Ok(d) = File::open(parent) {
            let _ = d.sync_all();
        }
    }
    Ok(())
}

pub struct RawImageStore {
    dir: PathBuf,
}

impl RawImageStore {
    /// 目录由 `platform::archive_dir()` 给,本模块不知道自己在哪个系统上,
    /// 也**没有**任何别的入口能改这个值。
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    fn index_path(&self) -> PathBuf {
        self.dir.join(INDEX_FILE)
    }

    fn bin_path(&self, id: &str) -> PathBuf {
        self.dir.join(format!("{id}.{BIN_EXT}"))
    }

    fn ensure_dir(&self) -> Result<(), StoreError> {
        fs::create_dir_all(&self.dir).map_err(StoreError::io)
    }

    /// 读索引。**文件不在 = 空**;文件在但读不动 = 报错,**绝不当成空**。
    ///
    /// 🔴 后半句是 `config.rs` 那条纪律的第二次出现:**响亮地失败,不无声地毁数据**。
    /// 把一份读不动的索引当成空的,下一步 [`Self::sweep_orphans`] 就会把
    /// **全部** `.bin` 当孤儿删光 —— 一次静默的、不可逆的数据事故。
    fn load_index(&self) -> Result<Vec<Row>, StoreError> {
        let text = match fs::read_to_string(self.index_path()) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(StoreError::io(e)),
        };
        let parsed: Value =
            serde_json::from_str(&text).map_err(|_| StoreError::BadShape("index_unreadable"))?;
        let rows = parsed
            .get("rows")
            .and_then(Value::as_array)
            .ok_or(StoreError::BadShape("index_unreadable"))?;
        let mut out = Vec::with_capacity(rows.len());
        for r in rows {
            let obj = r
                .as_object()
                .ok_or(StoreError::BadShape("index_unreadable"))?;
            if row_id(obj).is_none() {
                return Err(StoreError::BadShape("index_unreadable"));
            }
            out.push(obj.clone());
        }
        Ok(out)
    }

    fn save_index(&self, rows: &[Row]) -> Result<(), StoreError> {
        self.ensure_dir()?;
        let doc = serde_json::json!({ "version": INDEX_VERSION, "rows": rows });
        let text =
            serde_json::to_vec(&doc).map_err(|_| StoreError::BadShape("index_unwritable"))?;
        write_atomic(&self.index_path(), &text)
    }

    /// 全部元信息,不含字节。顺序原样,判据层自己排。
    pub fn list(&self) -> Result<Vec<Row>, StoreError> {
        self.load_index()
    }

    /// 🔴 写一行 —— **字节先落盘,索引后提交**。
    ///
    /// <h2>顺序不可调换,而它换来的缺口由 [`Self::sweep_orphans`] 补</h2>
    ///
    /// ```text
    /// ① 写 <id>.tmp → fsync → rename 成 <id>.bin
    /// ② 索引加一行(含过期戳)→ 原子重写
    /// ```
    ///
    /// ①②之间崩溃,留下一个**索引里没有的字节文件** —— 那正好是 `R-04` 要防的终局:
    /// 没有过期戳 = 永不过期。所以启动时必须清一次孤儿,这是文件系统形态
    /// **自己引入的缺口,自己补上**(IndexedDB 那侧由事务保证,不需要这一步)。
    ///
    /// 反过来(先索引后字节)崩溃留下的是「索引有行、字节不在」,`read` 返回 `None`,
    /// 判据层照常处理 —— 不危险,但它让**一张没有字节的图带着倒计时显示在界面上**,
    /// 而上面那个缺口是可以被一行启动清理彻底关掉的。所以选这一边。
    ///
    /// <h2>同 id 重复写:覆盖</h2>
    ///
    /// 与 IndexedDB 实现逐字相同(`store.put(row)` 就是覆盖)。
    /// 🔴 刻意**不**在这一侧加一条「已存在就拒绝」—— 那会造出**第三处形态差异**,
    /// 而 `docs/原图存储 §3.2` 把允许存在的差异穷举成了两处。两个实现行为一致比这一侧更聪明重要。
    pub fn put(&self, id: &str, row: Row, bytes: &[u8]) -> Result<(), StoreError> {
        if !valid_id(id) {
            return Err(StoreError::BadId);
        }
        // 元信息里的 id 必须和 URL 上的那个一致,否则索引和文件名会指向两个东西。
        if row_id(&row) != Some(id) {
            return Err(StoreError::BadShape("meta_id_mismatch"));
        }
        self.ensure_dir()?;

        write_atomic(&self.bin_path(id), bytes)?;

        let mut rows = self.load_index()?;
        match rows.iter_mut().find(|r| row_id(r) == Some(id)) {
            Some(slot) => *slot = row,
            None => rows.push(row),
        }
        self.save_index(&rows)
    }

    /// 取一行(元信息 + 字节)。索引里没有、或者字节文件不在,都返回 `None`。
    ///
    /// 🔴 **归档的行照样读得出来** —— 这一层根本不看归档态,`archivedAt` 对它只是索引里的一个键。
    /// 「归档的还能不能读」这个判断只在 `rawImageCache.read` 里有一份。
    pub fn read(&self, id: &str) -> Result<Option<(Row, Vec<u8>)>, StoreError> {
        if !valid_id(id) {
            return Err(StoreError::BadId);
        }
        let rows = self.load_index()?;
        let Some(row) = rows.into_iter().find(|r| row_id(r) == Some(id)) else {
            return Ok(None);
        };
        match fs::read(self.bin_path(id)) {
            Ok(bytes) => Ok(Some((row, bytes))),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(StoreError::io(e)),
        }
    }

    /// 🔴 真删 —— **字节先删,索引后改**。
    ///
    /// 这个顺序和 [`Self::put`] 是反的,理由是这条路上**用户已经按下了那一下**:
    ///
    /// | 顺序 | 中途崩溃留下 | 判 |
    /// |---|---|---|
    /// | 先改索引后删字节 | **字节还在磁盘上**,要等下次启动清孤儿 | ❌ 用户按了「删」,而字节还在 |
    /// | **先删字节后改索引** | 索引里几行读不出字节的残行 | ✅ `read` 返回 `None`,判据层照常处理 |
    ///
    /// 「用户手按的删就是真删」是这条链路上唯一不能打折的承诺,
    /// 所以选**字节先走**的那一边;留下的元信息残行是 `docs/原图存储 §2.4` 已经判过不危险的那一种。
    ///
    /// 删不存在的 id 不算错(幂等)。
    pub fn delete_many(&self, ids: &[String]) -> Result<(), StoreError> {
        if ids.is_empty() {
            return Ok(());
        }
        for id in ids {
            if !valid_id(id) {
                return Err(StoreError::BadId);
            }
        }
        for id in ids {
            match fs::remove_file(self.bin_path(id)) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => return Err(StoreError::io(e)),
            }
        }
        let mut rows = self.load_index()?;
        rows.retain(|r| !row_id(r).is_some_and(|i| ids.iter().any(|x| x == i)));
        self.save_index(&rows)
    }

    /// 🔴 归档 —— **只往索引里写 `archivedAt` 一个键,字节文件一个字节都不动**。
    ///
    /// 这是文件系统形态比 IndexedDB 干净的一处:IndexedDB 没有部分更新,
    /// 归档要把整行(含 Blob)读出来再写回去;这里只重写一个索引文件。
    ///
    /// 🔴 **已归档的跳过,不重复推后时刻**(与 `rawImageDb.ts` L185 同一条)。
    /// 少了它,反复 sweep 会把 `archivedAt` 一路往后推,归档时间就不再是它当初到期的那一刻。
    ///
    /// `at` 原样存进去,**本模块不解释它**:壳不知道它是一个时刻,只知道它是调用方给的一个数。
    pub fn archive(&self, ids: &[String], at: &Value) -> Result<(), StoreError> {
        if ids.is_empty() {
            return Ok(());
        }
        if !at.is_number() {
            return Err(StoreError::BadShape("archive_at_not_number"));
        }
        for id in ids {
            if !valid_id(id) {
                return Err(StoreError::BadId);
            }
        }
        let mut rows = self.load_index()?;
        let mut touched = false;
        for row in rows.iter_mut() {
            let Some(id) = row_id(row).map(str::to_string) else {
                continue;
            };
            if !ids.contains(&id) {
                continue;
            }
            // 已经有一个非 null 的 archivedAt 就跳过 —— 归档是一次性事件。
            if row.get(ARCHIVED_AT).is_some_and(|v| !v.is_null()) {
                continue;
            }
            row.insert(ARCHIVED_AT.to_string(), at.clone());
            touched = true;
        }
        if !touched {
            return Ok(());
        }
        self.save_index(&rows)
    }

    /// 🔴 启动清理:**索引里没有的 `.bin`,以及写到一半的 `.tmp`,一律删**。
    ///
    /// 这是 [`Self::put`] 那个顺序引入的缺口的唯一补丁 ——
    /// 一个索引里没有的字节文件就是**一张没有过期戳的原图**,而没有过期戳 = 永不过期(`R-04`)。
    ///
    /// 🔴 **索引读不动时它什么都不做并报错**,不把「读不出索引」当成「索引是空的」——
    /// 后者会把这个目录里的每一张图都当孤儿删掉。这是本模块里唯一一处
    /// 「顺手写会造成不可逆损失」的地方,所以它被单独写成一句 early return。
    ///
    /// 返回删掉几个,只为让调用方与测试能断言。**不含任何 id。**
    pub fn sweep_orphans(&self) -> Result<usize, StoreError> {
        if !self.dir.exists() {
            return Ok(0);
        }
        // 🔴 先读索引。读不动就整个放弃 —— 见上。
        let known: Vec<String> = self
            .load_index()?
            .iter()
            .filter_map(|r| row_id(r).map(str::to_string))
            .collect();

        let mut removed = 0usize;
        for entry in fs::read_dir(&self.dir).map_err(StoreError::io)?.flatten() {
            let path = entry.path();
            let Some(ext) = path.extension().and_then(|e| e.to_str()) else {
                continue;
            };
            let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
            let doomed = match ext {
                TMP_EXT => true,
                BIN_EXT => !known.iter().any(|k| k == stem),
                _ => false,
            };
            if doomed && fs::remove_file(&path).is_ok() {
                removed += 1;
            }
        }
        Ok(removed)
    }
}

/* ========================================================================== */
/* base64 —— 元信息过 HTTP 头用,不是给原图用的                                  */
/* ========================================================================== */

/// 元信息走 HTTP 头,而 HTTP 头只装得下 ISO-8859-1,`label` 是用户本机的文件名(可能是中文)。
///
/// 🔴 base64 的对象是**元信息**(不到 1 KB),**不是原图**:
/// 原图走请求体,一个字节都没被 base64 过 —— 那正是 `rawImageDb.ts` 选 IndexedDB
/// 而不选 localStorage 的头号理由,换一个存储介质不该把它丢掉。
///
/// 手写而不是加一个 crate:依赖表在这个仓库里是一条能力边界的执行装置,
/// 为二十行纯函数多一条依赖,等于把那条人工纪律的成本抬高一次。而它是纯函数,测得起。
const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn b64_encode(input: &[u8]) -> String {
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64[(n >> 18) as usize & 63] as char);
        out.push(B64[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            B64[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            B64[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

pub fn b64_decode(input: &str) -> Option<Vec<u8>> {
    let mut acc = 0u32;
    let mut bits = 0u32;
    let mut out = Vec::with_capacity(input.len() / 4 * 3);
    for c in input.bytes() {
        if c == b'=' {
            break;
        }
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            // 🔴 空白也不放过:宽松的解码器是攻击面,而这个串是我们自己发的,不需要宽松。
            _ => return None,
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}

/// 从 `x-raw-meta` 头解出一行元信息。
///
/// 只保证两件事:**是个 JSON 对象**、**带一个合法的 `id`**。
/// 其余的键一个都不看 —— 见本文件顶部那段「不透明」。
pub fn decode_meta_header(header: &str) -> Result<Row, StoreError> {
    let bytes = b64_decode(header).ok_or(StoreError::BadShape("meta_not_base64"))?;
    let text = String::from_utf8(bytes).map_err(|_| StoreError::BadShape("meta_not_utf8"))?;
    let value: Value =
        serde_json::from_str(&text).map_err(|_| StoreError::BadShape("meta_not_json"))?;
    let obj = value
        .as_object()
        .ok_or(StoreError::BadShape("meta_not_object"))?;
    match row_id(obj) {
        Some(id) if valid_id(id) => Ok(obj.clone()),
        Some(_) => Err(StoreError::BadId),
        None => Err(StoreError::BadShape("meta_no_id")),
    }
}

/// 把一行元信息编成 `x-raw-meta` 头的值。
pub fn encode_meta_header(row: &Row) -> String {
    b64_encode(Value::Object(row.clone()).to_string().as_bytes())
}

/* ========================================================================== */
/* 测试 —— 判据在 web 那一层,这里测的是【存储层自己的承诺】                      */
/* ========================================================================== */

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "kaodian-rawimage-{}-{}-{:?}",
            tag,
            std::process::id(),
            std::thread::current().id()
        ));
        fs::remove_dir_all(&d).ok();
        d
    }

    /// 造一行元信息。刻意带上 `expiresAt` —— 本模块**从头到尾不读它**,
    /// 而下面几条断言证明它原样进、原样出。
    fn row(id: &str, expires_at: i64, archived_at: Option<i64>) -> Row {
        let mut m = Map::new();
        m.insert("id".into(), Value::String(id.into()));
        m.insert("label".into(), Value::String("我的截图.png".into()));
        m.insert("mime".into(), Value::String("image/png".into()));
        m.insert("expiresAt".into(), Value::from(expires_at));
        m.insert(
            ARCHIVED_AT.into(),
            match archived_at {
                Some(v) => Value::from(v),
                None => Value::Null,
            },
        );
        m
    }

    #[test]
    fn put_then_list_and_read_round_trip() {
        let dir = tmp_dir("roundtrip");
        let s = RawImageStore::new(dir.clone());
        s.put("abc123", row("abc123", 999, None), b"\x89PNG\r\n")
            .unwrap();

        let rows = s.list().unwrap();
        assert_eq!(rows.len(), 1);
        // 🔴 元信息原样出来 —— 尤其是壳读不懂的那几个键。
        assert_eq!(rows[0].get("expiresAt"), Some(&Value::from(999)));
        assert_eq!(
            rows[0].get("label"),
            Some(&Value::String("我的截图.png".into()))
        );

        let (meta, bytes) = s.read("abc123").unwrap().unwrap();
        assert_eq!(bytes, b"\x89PNG\r\n");
        assert_eq!(meta.get("expiresAt"), Some(&Value::from(999)));

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn archive_only_touches_archived_at_and_never_the_bytes() {
        let dir = tmp_dir("archive");
        let s = RawImageStore::new(dir.clone());
        s.put("a1", row("a1", 100, None), b"bytes-a1").unwrap();

        s.archive(&["a1".to_string()], &Value::from(777)).unwrap();

        let (meta, bytes) = s.read("a1").unwrap().unwrap();
        assert_eq!(meta.get(ARCHIVED_AT), Some(&Value::from(777)));
        // 🔴 过期戳没被碰过,字节没被碰过。这两条是 archive 全部的承诺。
        assert_eq!(meta.get("expiresAt"), Some(&Value::from(100)));
        assert_eq!(bytes, b"bytes-a1");

        // 🔴 归档是一次性的:再来一次不会把时刻往后推。
        s.archive(&["a1".to_string()], &Value::from(888)).unwrap();
        let (meta2, _) = s.read("a1").unwrap().unwrap();
        assert_eq!(meta2.get(ARCHIVED_AT), Some(&Value::from(777)));

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn archived_rows_are_still_readable() {
        // 归档不是删除。读不出来的归档等于删除。
        let dir = tmp_dir("readarchived");
        let s = RawImageStore::new(dir.clone());
        s.put("z9", row("z9", 5, Some(6)), b"still-here").unwrap();
        let (_, bytes) = s.read("z9").unwrap().unwrap();
        assert_eq!(bytes, b"still-here");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn delete_removes_bytes_and_row_and_is_idempotent() {
        let dir = tmp_dir("delete");
        let s = RawImageStore::new(dir.clone());
        s.put("d1", row("d1", 1, None), b"x").unwrap();
        s.delete_many(&["d1".to_string()]).unwrap();

        assert!(s.list().unwrap().is_empty());
        assert!(s.read("d1").unwrap().is_none());
        // 🔴 字节真的不在磁盘上了 —— 「用户手按的删是真删」在这一行上成立。
        assert!(!dir.join("d1.bin").exists());
        // 删不存在的 id 不算错。
        s.delete_many(&["d1".to_string(), "nope".to_string()])
            .unwrap();

        fs::remove_dir_all(&dir).ok();
    }

    /// 让下一次索引重写必然失败:在 `index.tmp` 那个位置放一个**目录**,
    /// 于是 `File::create` 报 `Is a directory`。
    ///
    /// 🔴 这是本文件里唯一一种能把「中途崩溃」变成断言的手法。
    /// 少了它,`put` / `delete_many` 那两段关于**顺序**的注释就只是一句主张。
    fn block_index_write(dir: &Path) {
        fs::create_dir_all(dir.join("index.tmp")).unwrap();
    }

    #[test]
    fn put_writes_bytes_before_the_index_so_a_crash_leaves_an_orphan_not_a_stampless_row() {
        // 🔴 这条钉的是 put 的顺序:①字节 → ②索引。
        let dir = tmp_dir("putorder");
        let s = RawImageStore::new(dir.clone());
        s.put("first", row("first", 1, None), b"ok").unwrap();

        block_index_write(&dir);
        // ①成功、②失败 —— 也就是①②之间「崩溃」。
        assert!(s.put("second", row("second", 2, None), b"lost").is_err());

        // 字节在,索引里没有它 —— 这正是那个孤儿。
        assert!(dir.join("second.bin").exists());
        assert_eq!(s.list().unwrap().len(), 1);

        // 而它会被启动清理收走,于是「一张没有过期戳的原图」活不过下一次启动。
        fs::remove_dir_all(dir.join("index.tmp")).unwrap();
        assert_eq!(s.sweep_orphans().unwrap(), 1);
        assert!(!dir.join("second.bin").exists());
        assert!(dir.join("first.bin").exists());

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn delete_removes_bytes_before_the_index_so_a_crash_never_leaves_the_bytes_behind() {
        // 🔴 这条钉的是 delete 的顺序,而它和 put 是**反的**:①字节 → ②索引,
        //    但这里「①先跑完」意味着**用户按下的那一下已经生效**。
        //    顺序写反的话,崩溃会留下一份用户以为已经删掉的原图。
        let dir = tmp_dir("deleteorder");
        let s = RawImageStore::new(dir.clone());
        s.put("bye", row("bye", 1, None), b"secret").unwrap();

        block_index_write(&dir);
        assert!(s.delete_many(&["bye".to_string()]).is_err());

        // 🔴 字节已经不在磁盘上了 —— 即使索引这一步没能提交。
        assert!(!dir.join("bye.bin").exists());
        // 索引里还留着那一行,而它 read 不出来 —— docs/原图存储 §2.4 判过的那种「不危险的残留」。
        fs::remove_dir_all(dir.join("index.tmp")).unwrap();
        assert_eq!(s.list().unwrap().len(), 1);
        assert!(s.read("bye").unwrap().is_none());

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn sweep_deletes_orphan_bytes_and_half_written_temps() {
        let dir = tmp_dir("sweep");
        let s = RawImageStore::new(dir.clone());
        s.put("keep", row("keep", 1, None), b"keep").unwrap();

        // ①②之间崩溃留下的那个东西:索引里没有的字节文件。
        fs::write(dir.join("orphan.bin"), b"no-expiry-stamp").unwrap();
        // 写到一半的残留。
        fs::write(dir.join("half.tmp"), b"half").unwrap();

        assert_eq!(s.sweep_orphans().unwrap(), 2);
        assert!(!dir.join("orphan.bin").exists());
        assert!(!dir.join("half.tmp").exists());
        // 🔴 索引里有的那个一根汗毛都没动。
        assert!(dir.join("keep.bin").exists());
        assert_eq!(s.list().unwrap().len(), 1);

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_broken_index_never_becomes_an_empty_one() {
        // 🔴 这条测的是本模块唯一一处「顺手写会造成不可逆损失」的地方。
        let dir = tmp_dir("brokenindex");
        let s = RawImageStore::new(dir.clone());
        s.put("v1", row("v1", 1, None), b"precious").unwrap();
        fs::write(dir.join(INDEX_FILE), "{ 这不是 JSON").unwrap();

        assert!(matches!(s.list(), Err(StoreError::BadShape(_))));
        // 清理必须拒绝跑 —— 跑了的话 precious 会被当成孤儿删掉。
        assert!(s.sweep_orphans().is_err());
        assert!(dir.join("v1.bin").exists());

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn path_traversal_is_rejected_before_any_path_is_built() {
        let dir = tmp_dir("traversal");
        let s = RawImageStore::new(dir.clone());
        for bad in ["../etc/passwd", "a/b", "a.bin", "", "%2e%2e", "a b"] {
            assert!(matches!(s.read(bad), Err(StoreError::BadId)), "{bad}");
            assert!(
                matches!(s.put(bad, Map::new(), b""), Err(StoreError::BadId)),
                "{bad}"
            );
            assert!(
                matches!(s.delete_many(&[bad.to_string()]), Err(StoreError::BadId)),
                "{bad}"
            );
        }
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn meta_id_must_match_the_url_id() {
        let dir = tmp_dir("mismatch");
        let s = RawImageStore::new(dir.clone());
        // 索引写 b1、文件名写 a1 的话,两边指向两个东西,而且不会有人发现。
        assert!(matches!(
            s.put("a1", row("b1", 1, None), b"x"),
            Err(StoreError::BadShape(_))
        ));
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn base64_round_trips_including_non_ascii_labels() {
        for s in ["", "a", "ab", "abc", "我的截图.png", "{\"id\":\"x\"}"] {
            let enc = b64_encode(s.as_bytes());
            assert_eq!(b64_decode(&enc).unwrap(), s.as_bytes(), "{s}");
        }
        // 已知向量,防止「自己和自己对得上、和世界对不上」。
        assert_eq!(b64_encode(b"Man"), "TWFu");
        assert_eq!(b64_encode(b"Ma"), "TWE=");
        assert_eq!(b64_encode(b"M"), "TQ==");
        assert!(b64_decode("not base64!").is_none());
    }

    #[test]
    fn meta_header_round_trips_and_rejects_a_bad_id() {
        let r = row("ok-1", 42, None);
        let decoded = decode_meta_header(&encode_meta_header(&r)).unwrap();
        assert_eq!(decoded.get("expiresAt"), Some(&Value::from(42)));

        let bad = row("../x", 1, None);
        assert!(matches!(
            decode_meta_header(&encode_meta_header(&bad)),
            Err(StoreError::BadId)
        ));
    }

    #[test]
    fn put_overwrites_the_same_id_instead_of_duplicating_it() {
        // 与 IndexedDB 实现行为一致 —— 差异要可穷举,而 docs/原图存储 §3.2 只列了两处。
        let dir = tmp_dir("overwrite");
        let s = RawImageStore::new(dir.clone());
        s.put("same", row("same", 1, None), b"first").unwrap();
        s.put("same", row("same", 2, None), b"second").unwrap();
        assert_eq!(s.list().unwrap().len(), 1);
        let (meta, bytes) = s.read("same").unwrap().unwrap();
        assert_eq!(meta.get("expiresAt"), Some(&Value::from(2)));
        assert_eq!(bytes, b"second");
        fs::remove_dir_all(&dir).ok();
    }
}
