import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { sentryVitePlugin } from '@sentry/vite-plugin';

// Cloudflare Pages sets this in the build environment automatically, for every build (Production
// and Preview alike) -- no dashboard configuration needed, unlike the SENTRY_* vars below. Reused
// for two things below: the Sentry release name this build's events report under (see
// __APP_RELEASE__ in vite-env.d.ts / src/lib/monitoring.ts) and the release name source maps
// upload to, so the two agree without being wired together by hand.
const commitSha = process.env.CF_PAGES_COMMIT_SHA;

// A distinct dev port (5174, not the user app's 5173) so both frontends can run side by side
// locally against the same backend -- see app.cors.allowed-origins in application.yml, which
// needs to allow both origins for this to work in the browser.
export default defineConfig({
  plugins: [
    react(),
    // Uploads source maps to Sentry so a crash resolves to real file/line numbers instead of
    // minified bundle offsets -- see docs/operations/deployment/deployment-guide.md's Sentry
    // section for where SENTRY_AUTH_TOKEN/SENTRY_ORG/SENTRY_PROJECT get set. Applied only when
    // all three are present, the same "absent config degrades to no-op" posture as every other
    // integration in this app (VITE_SENTRY_DSN, ...) -- a checkout or PR preview without them
    // still builds and deploys normally, it just ships crash reports with unresolved stack
    // traces, same as today.
    ...(process.env.SENTRY_AUTH_TOKEN && process.env.SENTRY_ORG && process.env.SENTRY_PROJECT
      ? [
          sentryVitePlugin({
            org: process.env.SENTRY_ORG,
            project: process.env.SENTRY_PROJECT,
            authToken: process.env.SENTRY_AUTH_TOKEN,
            release: { name: commitSha },
            // The maps only need to reach Sentry, not a browser -- serving them at their public
            // dist/ URL would hand anyone the unminified structure of an admin tool for a finance
            // app for free. Sentry has its own copy by the time this deletes them.
            sourcemaps: { filesToDeleteAfterUpload: ['**/*.js.map'] },
          }),
        ]
      : []),
  ],
  build: {
    // 'hidden': the .map file is still emitted (the plugin above needs it to upload) but the
    // built JS carries no `//# sourceMappingURL` comment pointing at it, so a browser's devtools
    // won't fetch it. Plain `true` would leave that comment in -- filesToDeleteAfterUpload above
    // only removes the .map files themselves, not a reference left behind to a file that's gone.
    sourcemap: 'hidden',
  },
  define: {
    __APP_RELEASE__: JSON.stringify(commitSha ?? ''),
  },
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
