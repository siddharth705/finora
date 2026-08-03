import type { Breadcrumb, ErrorEvent } from '@sentry/react-native';
import { redactPath, scrubBreadcrumb, scrubEvent, scrubUrl } from './monitoring';

/*
 * These assert what must NEVER leave the device. Scrubbing that quietly stops working is
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
  // The ledger search sends whatever the user typed. That can be a merchant, a landlord, or their
  // own name -- the single most sensitive free-text field in the app.
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
  // Anything console.log'd while debugging a finance bug tends to be an amount or an account.
  it('drops console breadcrumbs wholesale', () => {
    const crumb: Breadcrumb = {
      category: 'console',
      message: 'balance for 3f2504e0 is 84210.55',
    };
    expect(scrubBreadcrumb(crumb)).toBeNull();
  });

  it('keeps only method, status and a scrubbed URL from a network breadcrumb', () => {
    const crumb: Breadcrumb = {
      category: 'xhr',
      data: {
        method: 'POST',
        status_code: 400,
        url: 'https://api.example.com/api/v1/transactions?keyword=rent',
        // Fields Sentry or a future version might attach:
        request_body: '{"amount":84210.55}',
        headers: { Authorization: 'Bearer secret-token' },
      },
    };

    const out = scrubBreadcrumb(crumb);
    expect(out?.data).toEqual({
      method: 'POST',
      status_code: 400,
      url: 'https://api.example.com/api/v1/transactions',
    });
    // Rebuilt rather than deleted key by key, so unknown fields can't survive.
    expect(JSON.stringify(out)).not.toMatch(/Bearer|secret-token|84210|rent/);
  });

  it('applies the same treatment to fetch breadcrumbs', () => {
    const out = scrubBreadcrumb({
      category: 'fetch',
      data: { method: 'GET', url: 'https://api.example.com/x?q=private' },
    });
    expect(out?.data?.url).toBe('https://api.example.com/x');
  });

  it('passes through breadcrumbs that carry no request data', () => {
    const crumb: Breadcrumb = { category: 'navigation', message: 'Login -> Dashboard' };
    expect(scrubBreadcrumb(crumb)).toEqual(crumb);
  });
});

describe('scrubEvent', () => {
  // The worst case: registration sends email, phone, and a plaintext password, and a crash there
  // is both likely and maximally sensitive.
  it('strips the request body from a failed registration', () => {
    const event = {
      request: {
        method: 'POST',
        url: 'https://api.example.com/api/v1/auth/register',
        data: { email: 'someone@example.com', password: 'hunter2', phoneNumber: '+910000000000' }, // synthetic-ok: invented payload proving it never leaves
        headers: { Authorization: 'Bearer secret-token' },
      },
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    expect(out.request).toEqual({
      method: 'POST',
      url: 'https://api.example.com/api/v1/auth/register',
    });
    expect(JSON.stringify(out)).not.toMatch(/hunter2|someone@example|Bearer/);
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
        { category: 'xhr', data: { method: 'GET', url: 'https://api.example.com/t?keyword=rent' } },
      ],
    } as unknown as ErrorEvent;

    const out = scrubEvent(event);

    // The console crumb is dropped, not merely emptied.
    expect(out.breadcrumbs).toHaveLength(1);
    expect(JSON.stringify(out)).not.toMatch(/keyword|rent|12345678901/);
  });

  it('leaves an event with nothing sensitive untouched', () => {
    const event = { message: 'Something failed' } as unknown as ErrorEvent;
    expect(scrubEvent(event)).toEqual({ message: 'Something failed' });
  });
});
