import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// A distinct dev port (5174, not the user app's 5173) so both frontends can run side by side
// locally against the same backend -- see app.cors.allowed-origins in application.yml, which
// needs to allow both origins for this to work in the browser.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
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
