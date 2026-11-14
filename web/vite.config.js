import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ArcoResolver } from 'unplugin-vue-components/resolvers'
import { readFileSync } from 'node:fs'
const tlsKey = process.env.WORKDSH_WEB_TLS_KEY
const tlsCert = process.env.WORKDSH_WEB_TLS_CERT
if (!!tlsKey !== !!tlsCert) throw new Error('Portal HTTPS requires both key and certificate')
const https = tlsKey ? { key:readFileSync(tlsKey),cert:readFileSync(tlsCert),minVersion:'TLSv1.2' } : undefined

export default defineConfig({
  // Arco components and their styles are imported per use instead of registering the whole library.
  plugins: [vue(), Components({ resolvers: [ArcoResolver({ sideEffect: true })], dts: false })],
  build: { rollupOptions: { output: { manualChunks: id => (id.includes('node_modules') ? 'vendor' : undefined) } } },
  server: { https, port: Number(process.env.WORKDSH_WEB_PORT || 18891),
    proxy: { '/api': process.env.WORKDSH_ADMIN_URL || 'http://127.0.0.1:18890' } },
  test: { environment: 'jsdom', setupFiles: ['./src/__tests__/setup.js'] },
})
