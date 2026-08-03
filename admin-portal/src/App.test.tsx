import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, waitFor } from '@testing-library/react';
import App from './App';

// App mounts the whole provider stack; nothing here should reach the network. These resolve
// rather than returning undefined -- both callers chain .then() directly on the result.
vi.mock('./api/endpoints', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), refresh: vi.fn() },
  meApi: { access: vi.fn(async () => ({ permissions: [] })) },
  setupApi: { status: vi.fn(async () => ({ setupRequired: false })) },
}));

/**
 * Bug fix regression test: <Routes> had no catch-all, so any unmatched path matched no <Route> and
 * rendered null -- a completely blank white page with no message and no way back. Verified in a
 * browser against the user app, which had the identical defect and the identical fix.
 *
 * With no token in storage, "/" is the Dashboard behind ProtectedRoute, which sends an
 * unauthenticated visitor on to /login -- so the assertion is "we ended up somewhere real and
 * rendered something", not a specific final path.
 */
describe('App routing — unmatched paths', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.history.pushState({}, '', '/');
  });

  /**
   * Landing on Login starts setupApi.status(), whose .then() sets state. Without draining that
   * here, it resolves after Vitest has torn the environment down and surfaces as a suite-level
   * "error was caught after test environment was torn down" -- a real, reported error even though
   * every test passes.
   */
  async function settlePendingEffects() {
    await act(async () => {
      await Promise.resolve();
    });
  }

  it.each([
    '/this-route-does-not-exist',
    '/users/extra/segments',
    '/analytics/nope',
  ])('does not render a blank screen for %s', async (path) => {
    window.history.pushState({}, '', path);

    const { container } = render(<App />);

    // Off the unmatched path...
    await waitFor(() => expect(window.location.pathname).not.toBe(path));
    // ...and onto something that actually renders. An empty render is exactly what the bug was.
    await waitFor(() => expect(container.textContent?.trim()).not.toBe(''));
    await settlePendingEffects();
  });

  it('leaves a route that does exist alone', async () => {
    window.history.pushState({}, '', '/login');

    render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe('/login'));
    await settlePendingEffects();
  });
});
