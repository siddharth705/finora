import { describe, it, expect } from 'vitest';
import type { Breadcrumb, ErrorEvent } from '@sentry/react';
import { redactPath, scrubBreadcrumb, scrubEvent, scrubUrl } from './monitoring';

/*
 * Ported from mobile/src/lib/monitoring.test.ts and frontend/src/lib/monitoring.test.ts, plus the
 * navigation cases that have no mobile counterpart (a native app has no URL to leak).
 *
 * These assert what must NEVER leave the browser. Scrubbing that quietly stops working is
 * indistinguishable from scrubbing that works, so each case below names the specific leak it
 * prevents rather than just pinning current behaviour.
 */

describe('redactPath', () => {
  it('replaces account and transaction UUIDs', () => {
    const out = redactPath('/api/v1/transactions/3f2504e0-4f89-11d3-9a0c-0305e82c3301');
    expect(out).toBe('/api/v1/transactions/{id}');
    expect(out).not.toMatch(/3f2504e0/);
  });

  it('replaces long digit runs, which is what an account number looks like in a path', () => {
    expect(redactPath('/accounts/12345678901234')).toBe('/accounts/{n}'); // synthetic-ok: invented, and the assertion is that it gets redacted
  });

  it('leaves the route shape intact so errors still group', () => {
    expect(redactPath('/api/v1/statement-imports/{id}/file')).toBe('/api/v1/statement-imports/{id}/file');
    expect(redactPath('/api/v1/dashboard/summary')).toBe('/api/v1/dashboard/summary');
  });

  // /api/v1 must survive -- redacting it would collapse every route into the same string.
  it('does not redact short numbers that carry no identity', () => {
    expect(redactPath('/api/v1/goals')).toBe('/api/v1/goals');
  });
});

describe('scrubUrl', () => {
  it('drops the query string entirely', () => {
    const out = scrubUrl('https://api.example.com/api/v1/transactions?keyword=Dr%20Sharma%20clinic&page=0');
    expect(out).toBe('https://api.example.com/api/v1/transactions');
    expect(out).not.toMatch(/keyword|Sharma/i);
  });

  it('redacts identifiers in the remaining path', () => {
    expect(scrubUrl('https://api.example.com/api/v1/accounts/3f2504e0-4f89-11d3-9a0c-0305e82c3301'))
      .toBe('https://api.example.com/api/v1/accounts/{id}');
  });

  it('returns undefined for a non-string rather than coercing', () => {
    expect(scrubUrl(undefined)).toBeUndefined();
    expect(scrubUrl(null)).toBeUndefined();
    expect(scrubUrl(42)).toBeUndefined();
  });
});

describe('scrubBreadcrumb', () => {
  it('drops console breadcrumbs wholesale', () => {
    const crumb: Breadcrumb = { category: 'console', message: 'balance for 3f2504e0 is 84210.55' };
    expect(scrubBreadcrumb(crumb)).toBeNull();
  });

  it('keeps only method, status and a scrubbed URL from a network breadcrumb', () => {
    const out = scrubBreadcrumb({
      category: 'xhr',
      data: {
        method: 'POST',
        status_code: 400,
        url: 'https://api.example.com/api/v1/transactions?keyword=rent',
        request_body: '{"amount":84210.55}',
        headers: { Authorization: 'Bearer secret-token' },
      },
    });

    expect(out?.data).toEqual({
      method: 'POST',
      status_code: 400,
      url: 'https://api.example.com/api/v1/transactions',
    });
    expect(JSON.stringify(out)).not.toMatch(/Bearer|secret-token|84210|rent/);
  });

  /*
   * WEB-ONLY, and the reason this file is not a straight copy of the mobile one.
   *
   * Ledger.tsx keeps the transaction search term in `?q=` and ResetPassword.tsx reads a live
   * password-reset token from `?token=`. Sentry's navigation breadcrumbs carry full `from`/`to`
   * URLs, and mobile's scrubBreadcrumb passes navigation crumbs through untouched because on
   * native they are just screen names. Porting it verbatim would have shipped both to a third
   * party on every crash.
   */
  it('redacts the customer UUIDs an admin navigation trail is built from', () => {
    const out = scrubBreadcrumb({
      category: 'navigation',
      data: {
        from: '/users',
        to: '/users/3f2504e0-4f89-11d3-9a0c-0305e82c3301',
      },
    });

    expect(out?.data).toEqual({ from: '/users', to: '/users/{id}' });
    // Otherwise the breadcrumb trail is a record of which specific customers were looked at.
    expect(JSON.stringify(out)).not.toMatch(/3f2504e0/i);
  });

  it('drops a password-reset token out of a navigation breadcrumb', () => {
    const out = scrubBreadcrumb({
      category: 'navigation',
      data: { from: '/login', to: '/reset-password?token=live-reset-token-value' }, // synthetic-ok: invented, asserted to be removed
    });

    expect(out?.data?.to).toBe('/reset-password');
    expect(JSON.stringify(out)).not.toMatch(/live-reset-token-value/);
  });

  it('passes through breadcrumbs that carry no request data', () => {
    const crumb: Breadcrumb = { category: 'ui.click', message: 'button[aria-label="Close"]' };
    expect(scrubBreadcrumb(crumb)).toEqual(crumb);
  });
});

describe('scrubEvent', () => {
  it('strips the request body from a failed registration', () => {
    const event = {
      request: {
        method: 'POST',
        url: 'https://app.example.com/register',
        data: { email: 'someone@example.com', password: 'hunter2', phoneNumber: '+910000000000' }, // synthetic-ok: invented payload proving it never leaves
        headers: { Authorization: 'Bearer secret-token' },
      },
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    expect(out.request).toEqual({ method: 'POST', url: 'https://app.example.com/register' });
    expect(JSON.stringify(out)).not.toMatch(/hunter2|someone@example|Bearer/);
  });

  /** On web, request.url is the PAGE url -- so a crash while viewing a customer would otherwise
   *  ship that customer's id as part of the event itself, not just as a breadcrumb. */
  it('redacts the page URL an event was raised on', () => {
    const event = {
      request: { method: 'GET', url: 'https://admin.example.com/users/3f2504e0-4f89-11d3-9a0c-0305e82c3301' },
    } as unknown as ErrorEvent;

    expect(scrubEvent(event).request?.url).toBe('https://admin.example.com/users/{id}');
  });

  it('removes user identity even if something attached it', () => {
    const event = {
      user: { email: 'someone@example.com', id: 'user-1', ip_address: '203.0.113.7' },
    } as unknown as ErrorEvent;

    expect(scrubEvent(event).user).toBeUndefined();
  });

  it('scrubs breadcrumbs carried on the event, not just live ones', () => {
    const event = {
      breadcrumbs: [
        { category: 'console', message: 'account 12345678901 balance' }, // synthetic-ok: invented, asserted to be dropped
        { category: 'navigation', data: { from: '/x', to: '/users?email=someone%40example.com' } },
      ],
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    // The console crumb is dropped, not merely emptied.
    expect(out.breadcrumbs).toHaveLength(1);
    expect(JSON.stringify(out)).not.toMatch(/someone%40example|12345678901/);
  });

  it('leaves an event with nothing sensitive untouched', () => {
    const event = { message: 'Something failed' } as unknown as ErrorEvent;
    expect(scrubEvent(event)).toEqual({ message: 'Something failed' });
  });
});
