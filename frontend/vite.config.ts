import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.OPNL_VITE_PROXY_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
      "/share": {
        target: process.env.OPNL_VITE_PROXY_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
      "/ws": {
        target: process.env.OPNL_VITE_PROXY_TARGET ?? "http://localhost:8080",
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ["react", "react-dom", "react-router-dom"],
          mui: ["@mui/material", "@mui/icons-material"],
          "mui-x-grid": ["@mui/x-data-grid"],
          "mui-x-charts": ["@mui/x-charts"],
          query: ["@tanstack/react-query"],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["src/test/setup.ts"],
    css: false,
    testTimeout: 30000,
    coverage: {
      provider: "v8",
      reporter: ["text", "html", "json-summary"],
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/test/**",
        "src/main.tsx",
        "src/**/*.d.ts",
      ],
      thresholds: {
        statements: 90,
        branches: 80,
        lines: 90,
      },
    },
  },
});
