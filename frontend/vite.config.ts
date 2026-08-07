import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

import { cloudflare } from "@cloudflare/vite-plugin";

export default defineConfig({
  plugins: [react(), cloudflare()],
  server: {
    port: 5173,
    proxy: {
      // Matches by prefix, so /api/v1/... (the versioned API) is forwarded unchanged —
      // Vite doesn't rewrite the path, it just proxies whatever starts with /api.
      '/api': {
        // Overridable so a test run can point at a throwaway backend on a free port without
        // editing this file. The e2e suite needs it: "fresh backend, fresh database" means a
        // second instance alongside whatever the developer already has on 8080.
        target: process.env.FINORA_API_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});