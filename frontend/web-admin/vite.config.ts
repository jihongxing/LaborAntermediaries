import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), Components({ resolvers: [ElementPlusResolver()] })],
    server: {
      host: '127.0.0.1',
      port: 3000,
      strictPort: true,
      proxy: {
        '/api': {
          target: env.VITE_DEV_API_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
