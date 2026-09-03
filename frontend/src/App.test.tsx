import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import App from './App';

// App mounts the whole provider stack; nothing here should reach the network.
vi.mock('./api/endpoints', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), register: vi.fn(), refresh: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

// Preventive, not currently load-bearing: no test here reaches a chart today, because Dashboard
// and Investments (the only two modules importing react-chartjs-2) are lazy() and sit behind
// ProtectedRoute, and these tests have no session. But this file renders the whole routed app, so
// it is the one place where adding a test that mocks auth and lands on an authenticated route
// would silently mount a live Chart.js instance -- and in jsdom that instance is built with
// canvas === null, so its first update() throws uncaught and unmounts the entire React root
// mid-test (see the long note in Dashboard.test.tsx). Cheaper to hold the line here than to
// rediscover that as an intermittent failure in an unrelated test.
vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="line-chart" />,
  Doughnut: () => <div data-testid="doughnut-chart" />,
}));

/**
 * Bug fix regression test: <Routes> had no catch-all, so any unmatched path matched no <Route> and
 * rendered null -- a completely blank white page, verified in a browser as #root with empty
 * innerHTML. This is not a dev-only curiosity: wrangler.json sets
 * assets.not_found_handling = "single-page-application", so Cloudflare serves index.html for every
 * unknown path in production too. A typo'd URL, a stale bookmark, or a link to a route that has
 * since moved all produced a blank screen with no message and no way back.
 */
describe('App routing — unmatched paths', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  it.each([
    '/app/this-route-does-not-exist',
    '/definitely-not-a-page',
    '/app/transactions/extra/segments',
  ])('redirects %s to the landing page instead of rendering a blank screen', async (path) => {
    window.history.pushState({}, '', path);

    const { container } = render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe('/'));
    // Something real is on screen. Asserted as "the render is not empty" rather than by hunting
    // for a specific string, because an empty render is precisely and entirely what the bug was.
    await waitFor(() => expect(container.textContent?.trim()).not.toBe(''));
    // And it is specifically the landing page, asserted structurally. This used to match the CTA
    // text ("Get Started Free"), which contradicted the comment directly above it and duly broke
    // the moment that button was reworded -- a copy edit failing a routing test tells you nothing
    // about routing. An entry-flow link is what the landing page is FOR, so it survives rewording.
    // (Points at /auth, not /register directly, since the landing page's CTAs now route through
    // the unified identifier-first entry page -- see AuthEntry.tsx.)
    await waitFor(() => expect(container.querySelector('a[href="/auth"]')).not.toBeNull());
  });

  it('leaves a route that does exist alone', async () => {
    window.history.pushState({}, '', '/terms');

    render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe('/terms'));
  });

  it('replaces rather than pushes, so Back does not bounce into the bad URL again', async () => {
    window.history.pushState({}, '', '/terms');
    window.history.pushState({}, '', '/definitely-not-a-page');

    render(<App />);

    await waitFor(() => expect(window.location.pathname).toBe('/'));

    window.history.back();
    await waitFor(() => expect(window.location.pathname).toBe('/terms'));
  });
});

describe('App routing — /login and /register redirect to /auth', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  it.each(['/login', '/register'])('redirects %s to /auth', async (path) => {
    window.history.pushState({}, '', path);
    render(<App />);
    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
  });
});
