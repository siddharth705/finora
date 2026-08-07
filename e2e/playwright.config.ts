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
 * against real dev servers and a real backend, and are correspondingly slower. Component behaviour
 * belongs in Vitest; only things that genuinely need a browser, a database, or both apps at once
 * belong here.
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

  // An import of several hundred rows is a legitimately slow operation and one phase measures
  // exactly that, so the default 30s is too tight for this suite. Individual expectations keep
  // their own shorter timeouts, so a hung page still fails fast.
  timeout: 90_000,

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
   *
   * `workflow/` is the third directory and the reason this suite exists at all: specs that span
   * both apps and the database, where the question is not "did the page render" but "did the
   * user's decision survive the whole system".
   */
  projects: [
    {
      name: 'user-portal',
      testDir: './tests/user-portal',
      use: { ...devices['Desktop Chrome'], baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173' },
    },
    {
      name: 'admin-portal',
      testDir: './tests/admin-portal',
      use: { ...devices['Desktop Chrome'], baseURL: process.env.FINORA_E2E_ADMIN_APP ?? 'http://localhost:5174' },
    },
    {
      name: 'workflow',
      testDir: './tests/workflow',
      use: { ...devices['Desktop Chrome'], baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173' },
    },

    /**
     * Cross-browser and responsive coverage, opt-in.
     *
     * Defined only when `FINORA_E2E_BROWSERS=1` is set, because `npx playwright test` with no
     * arguments runs every project that exists -- a comment saying "opt-in via --project" does not
     * make it so, and the first full run of this suite duly executed all five projects and took
     * five times as long for it. These re-execute the same specs against a different engine or
     * viewport, catching a class of defect (engine-specific rendering, touch-target layout) that
     * changes far less often than the business logic does.
     *
     *     npm run test:browsers      # Firefox + Edge
     *     npm run test:responsive    # tablet + mobile viewports
     *
     * WebKit is deliberately absent: the product's browser support matrix does not include Safari,
     * and a permanently red project teaches people to ignore red projects.
     */
    ...(process.env.FINORA_E2E_BROWSERS ? [
    {
      name: 'user-portal-firefox',
      testDir: './tests/user-portal',
      use: { ...devices['Desktop Firefox'], baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173' },
    },
    {
      name: 'user-portal-edge',
      testDir: './tests/user-portal',
      use: {
        ...devices['Desktop Edge'],
        channel: 'msedge',
        baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173',
      },
    },
    {
      name: 'user-portal-tablet',
      testDir: './tests/user-portal',
      use: { ...devices['iPad (gen 7) landscape'], baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173' },
    },
    {
      name: 'user-portal-mobile',
      testDir: './tests/user-portal',
      use: { ...devices['Pixel 5'], baseURL: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173' },
    },
    {
      name: 'admin-portal-firefox',
      testDir: './tests/admin-portal',
      use: { ...devices['Desktop Firefox'], baseURL: process.env.FINORA_E2E_ADMIN_APP ?? 'http://localhost:5174' },
    },
    ] : []),
  ],

  /**
   * Playwright boots both Vite dev servers itself. The ports are not arbitrary -- they are the
   * ports each app's own vite.config.ts pins (5173 for the user app, 5174 for the admin portal,
   * chosen so both can run side by side against one backend), so these tests hit the same origins
   * a developer does locally, and the backend's CORS allow-list already covers them.
   *
   * `FINORA_API_PROXY_TARGET` is passed through to each dev server so the suite can drive a
   * throwaway backend on a free port -- the "fresh backend, fresh database" the milestone brief
   * asks for -- without editing either vite.config.ts. Defaults to 8081, which is where
   * `npm run stack:up` puts it; a developer's own backend on 8080 is left alone.
   *
   * The backend itself is NOT started here. It needs a database, a migration run and a built jar,
   * and burying that in a test config makes a failed boot look like a failed test. `globalSetup`
   * checks it is reachable and says exactly what to run if it is not.
   *
   * `reuseExistingServer` locally means a dev server you already have running is used as-is rather
   * than Playwright failing on a port clash; in CI there is never one to reuse, and silently
   * reusing a stale server would be a bad way to find that out.
   */
  globalSetup: './fixtures/global-setup.ts',

  webServer: [
    {
      command: 'npm run dev',
      cwd: '../frontend',
      url: process.env.FINORA_E2E_USER_APP ?? 'http://localhost:5173',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: { FINORA_API_PROXY_TARGET: process.env.FINORA_E2E_API_ORIGIN ?? 'http://localhost:8081' },
    },
    {
      command: 'npm run dev',
      cwd: '../admin-portal',
      url: process.env.FINORA_E2E_ADMIN_APP ?? 'http://localhost:5174',
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'pipe',
      stderr: 'pipe',
      env: { FINORA_API_PROXY_TARGET: process.env.FINORA_E2E_API_ORIGIN ?? 'http://localhost:8081' },
    },
  ],
});
