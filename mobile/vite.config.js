import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";
export default defineConfig({
  plugins: [(typeof uni === "function" ? uni : uni.default)()],
  server: {
    host: "127.0.0.1",
    port: 5175,
    proxy: {
      "/api": {
        target: process.env.LIANZHI_DEV_BACKEND || "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },
});
