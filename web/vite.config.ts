import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.VITE_API_PROXY ?? 'http://localhost:28080',
      '/ws': process.env.VITE_WS_PROXY ?? 'ws://localhost:28108'
    }
  }
})
