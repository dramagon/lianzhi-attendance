// Compile C's exported module and lazy views separately as well as through the main application.
import { build } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

await build({
  configFile: false,
  root: fileURLToPath(new URL('../', import.meta.url)),
  plugins: [vue()],
  build: {
    outDir: 'artifacts/intelligence-build',
    lib: {
      entry: fileURLToPath(new URL('../src/modules/intelligence/routes.js', import.meta.url)),
      formats: ['es'],
      fileName: 'routes',
    },
    rollupOptions: { external: ['vue', 'axios'] },
  },
})
