import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发服务器只监听本机；/api 代理到 Spring Boot（也只监听 127.0.0.1）
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": "http://127.0.0.1:8080",
    },
  },
  preview: {
    host: "127.0.0.1",
  },
});
