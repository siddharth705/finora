import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Deliberately a separate config from vite.config.ts rather than merging it -- the dev-server
// proxy/port settings over there are meaningless for tests, and keeping this self-contained
// avoids any risk of the two configs interfering with each other.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
});
