fn main() {
    // 只做 Tauri 的资源生成。
    //
    // 🔴 这里【不】调 npm。前端构建是 build.sh 步骤 ② 的事,而 build.sh 步骤 ① 是隔离校验。
    // 把 npm 搬进 build.rs 等于造出第二条构建路径,而那条路径绕过 ①(docs/18 §2.3)。
    // 同一条理由也写在 tauri.conf.json 的 beforeBuildCommand 旁边。
    tauri_build::build()
}
