//! Android 实现。
//!
//! ⚠️ **这个文件属于 `KUBI-67`,不属于 `KUBI-66`。**
//! 本分支从未构建过 Android —— 这里存在只是为了让 `platform` 模块三端齐全,
//! 从而让「隔离结构成立」这件事可被检验。**不要把它当成已验证的实现。**

use std::path::PathBuf;

use super::Platform;

pub struct Android;

impl Android {
    pub fn new() -> Self {
        Self
    }
}

impl Platform for Android {
    /// app 私有目录下的归档目录。
    ///
    /// 🔴 **未验证。** Android 上应用私有目录要由 `Context.getFilesDir()` 经 JNI 取,
    /// 环境变量拿不到。这里的实现是**占位**,`KUBI-67` 落地时必须换成真的取法。
    /// 留成占位而不是 `unimplemented!()`,是为了让三端能一起编译通过;
    /// 留成一个显然不对的路径而不是一个看着像对的路径,是为了让它**用起来就会露馅**。
    fn archive_dir(&self) -> PathBuf {
        PathBuf::from("/data/data/__KUBI_67_未接入__/files/archive")
    }

    /// Android 同样**不保证**后台执行(壳技术方案 §4.2)。
    fn background_timer_supported(&self) -> bool {
        false
    }
}
