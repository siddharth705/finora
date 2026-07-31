import { describe, it, expect } from 'vitest';
import { normalizeApiBase } from './client';

/**
 * Regression coverage for a real production bug: VITE_API_BASE_URL was set to the bare Railway
 * origin (e.g. https://confident-wonder-dev.up.railway.app) without the /api/v1 path segment
 * every backend route actually lives under -- every request silently dropped that segment,
 * hitting "<origin>/auth/register" instead of "<origin>/api/v1/auth/register" (a route that
 * doesn't exist), which the browser reported as a CORS failure rather than a 404 (the OPTIONS
 * preflight itself never got a matching route to succeed against). normalizeApiBase makes this
 * correct regardless of which form the env var is set to.
 */
describe('normalizeApiBase', () => {
  it('appends /api/v1 when the raw base is just the bare origin', () => {
    expect(normalizeApiBase('https://confident-wonder-dev.up.railway.app'))
      .toBe('https://confident-wonder-dev.up.railway.app/api/v1');
  });

  it('does not double up /api/v1 when the raw base already includes it', () => {
    expect(normalizeApiBase('https://confident-wonder-dev.up.railway.app/api/v1'))
      .toBe('https://confident-wonder-dev.up.railway.app/api/v1');
  });

  it('strips a trailing slash before checking/appending, either form', () => {
    expect(normalizeApiBase('https://example.com/'))
      .toBe('https://example.com/api/v1');
    expect(normalizeApiBase('https://example.com/api/v1/'))
      .toBe('https://example.com/api/v1');
  });

  it('handles multiple trailing slashes', () => {
    expect(normalizeApiBase('https://example.com///'))
      .toBe('https://example.com/api/v1');
  });
});
