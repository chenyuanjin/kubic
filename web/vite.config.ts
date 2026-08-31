import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // 后端是同机的另一个进程,不是另一个服务(docs/技术架构 §2.2)。
      // 生产由 Caddy 把 /api/* 反代到 :8080,dev 这里做同一件事 —— 让前端代码里
      // 永远只写相对路径 /api/*,不出现 host,也就不存在跨环境改地址这回事。
      '/api': {
        // 默认 :8080(生产 Caddy 也是转到那儿)。留一个环境变量出口是因为本机常有
        // 另一个进程占着 8080 —— 那时想连自己刚打的包,不该去改这个文件再记得改回来。
        //   KAODIAN_API_TARGET=http://localhost:8081 npm run dev
        target: process.env.KAODIAN_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
        // 后端没起来时不要让 vite 打一屏堆栈:前端本来就会回退到离线示例数据。
        configure: (proxy) => {
          proxy.on('error', () => {})
        },
      },
    },
  },
})
