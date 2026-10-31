import { defineConfig } from '@playwright/test'

// 独立端口与合成凭据，仅由地图仿真测试启动，不覆盖本机配置。
export default defineConfig({
  testDir: './map-e2e',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:5174', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    env: { VITE_AMAP_KEY: 'synthetic-map-key', VITE_AMAP_SECURITY_CODE: 'synthetic-security-code' },
  },
  reporter: 'list',
})
