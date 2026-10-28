// The module isn't connected to A's root router yet. Compile its lazy Vue component separately
// so a successful root application build cannot silently omit C's new page.
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
