import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './client';

// No HTTP-mocking library (msw, axios-mock-adapter) is set up in this project, so this test
// invokes axios's registered response-interceptor rejection handler directly rather than mocking
// a full request/response round trip -- sufficient to lock in the actual bug fixed here, without
// adding a new test dependency for one regression test.
function rejectedHandler() {
  const handlers = (api.interceptors.response as any).handlers;
  return handlers[handlers.length - 1].rejected;
}

describe('api response interceptor', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('does not clear the session for a 401 from /auth/login (wrong credentials, not an expired session)', async () => {
    // Regression test: this 401 used to be treated the same as an expired-session 401 -- with no
    // refresh token in storage (a fresh browser, exactly like someone's first login attempt),
    // that fell straight to clearAdminSession() + a hard `window.location.href = '/login'`
    // navigation, wiping out Login.tsx's inline "Invalid credentials" message before it could
    // ever render. Checking that an unrelated stored value survives is a more robust way to
    // confirm clearAdminSession() didn't run than asserting on jsdom's navigation simulation.
    localStorage.setItem('finora_admin_email', 'someone-elses-stale-session@example.com');
    const error = {
      response: { status: 401, data: { message: 'Invalid credentials', errorCode: null } },
      config: { url: '/auth/login', _retried: false },
    };

    await expect(rejectedHandler()(error)).rejects.toBeTruthy();

    expect(localStorage.getItem('finora_admin_email')).toBe('someone-elses-stale-session@example.com');
  });
});
