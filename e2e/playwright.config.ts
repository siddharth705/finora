import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests for both web apps.
 *
 * This lives at the repo root rather than inside `frontend/` or `admin-portal/` for one practical
 * reason: Playwright downloads a browser binary per install, and the two apps are deployed
 * independently but tested against the same backend. One workspace means one browser download and
 * one place where a cross-app flow (an admin acting on a user's data, then the user seeing the
 * result) can be written at all -- which is impossible from inside either app's own suite.
 *
 * The existing Vitest suites in `frontend/` and `admin-portal/` are NOT superseded by this. They
 * test components in jsdom, fast, with the network mocked. These tests drive a real browser
 * against real dev servers, and are correspondingly slower. Component behaviour belongs in Vitest;
 * only things that genuinely need a browser and a running app belong here.
 */
export default defineConfig({
  testDir: './tests',

  // A failing E2E test is far more often a flake than a unit test is, so retry in CI where nobody
  // is watching -- but never locally, where a retry just hides a real race from the person who can
  // actually debug it.
  retries: process.env.CI ? 2 : 0,
  forbidOnly: !!process.env.CI,

  // Both dev servers are single instances shared by every worker, so parallel workers do not
  // multiply server load the way they would with per-worker servers. Left at Playwright's default
  // locally; pinned to 1 in CI where the runner is small and flakiness is expensive.
  workers: process.env.CI ? 1 : undefined,

  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],

  use: {
    // Keep the artefacts that make a failure diagnosable and drop the ones that only cost disk:
    // a trace and a screenshot of the failing run, nothing from the passing ones.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  /**
   * Each project owns a directory, not a filename pattern.
   *
   * The first draft of this config matched on filename (`/user-portal\..*\.ts/`), which meant a
   * spec added as `login-flow.spec.ts` would match no project and be silently skipped -- it does
   * not fail, it does not warn, it simply never runs, which is the worst possible behaviour for a
   * test harness and the same silent-no-op shape this repo's audit kept turning up. Scoping by
   * directory makes the rule "put the spec in the folder for the app it drives", and a misplaced
   * file is visible in the tree rather than invisible in a regex.
   */
  projects: [
    {
      name: 'user-portal',
      testDir: './tests/user-portal',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:5173' },
    },
    {
      name: 'admin-portal',
      testDir: './tests/admin-portal',
      use: { ...devices['Desktop Chrome'], baseURL: 'http://localhost:5174' },
    },
  ],

  /**
   * Playwright boots both Vite dev servers itself. The ports are not arbitrary -- they are the
   * ports each app's own vite.config.ts pins (5173 for the user app, 5174 for the admin portal,
   * chosen so both can run side by side against one backend), so these tests hit the same origins
   * a developer does locally, and the backend's CORS allow-list already covers them.
   *
   * `reuseExistingServer` locally means a dev server you already have running is used as-is rather
   * than Playwright failing on a port clash; in CI there is never one to reuse, and silently
   * reusing a stale server would be a bad way to find that out.
   */
  webServer: [
    {
      command: 'npm run dev',
      cwd: '../frontend',
      url: 'http://localhost:5173',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: 'npm run dev',
      cwd: '../admin-portal',
      url: 'http://localhost:5174',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
});
