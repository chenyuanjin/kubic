import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // 后端是同机的另一个进程,不是另一个服务(docs/10 §2.2)。
      // 生产由 Caddy 把 /api/* 反代到 :8080,dev 这里做同一件事 —— 让前端代码里
      // 永远只写相对路径 /api/*,不出现 host,也就不存在跨环境改地址这回事。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 后端没起来时不要让 vite 打一屏堆栈:前端本来就会回退到离线示例数据。
        configure: (proxy) => {
          proxy.on('error', () => {})
        },
      },
    },
  },
})
