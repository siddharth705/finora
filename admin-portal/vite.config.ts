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
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
