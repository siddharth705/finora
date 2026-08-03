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
    // Vitest's 5s default is too tight for the userEvent-driven tests here once several files
    // run in parallel. Register.test.tsx's "+91 prepended" case takes ~1.4s alone but exceeded
    // 5s under concurrent load on a developer machine -- and GitHub's runners are slower and
    // have fewer cores, so CI would have been intermittently red from day one. This buys
    // headroom for a slow machine without hiding a genuinely hung test: a real deadlock still
    // fails, just 15s later.
    testTimeout: 15_000,
  },
});
