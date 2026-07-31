import { describe, it, expect } from 'vitest';
import { extractDomain, brandfetchUrl } from './BankLogo';

/**
 * Covers the Brandfetch URL construction bug fix -- verified against Brandfetch's own current
 * docs (docs.brandfetch.com/logo-api/overview): every documented sizing example uses
 * `/h/{height}/w/{width}/`, height before width (the code had it reversed), and their
 * recommended format uses an explicit `domain/` type prefix "to avoid potential naming
 * collisions between identifier types" (the code was relying on the older, still-functional but
 * non-recommended bare-domain auto-detection fallback instead).
 */
describe('brandfetchUrl', () => {
  it('builds the URL with the explicit domain/ prefix and h before w, matching current Brandfetch docs', () => {
    expect(brandfetchUrl('hdfcbank.com', 80, 'test-client-id')).toBe(
      'https://cdn.brandfetch.io/domain/hdfcbank.com/h/80/w/80/logo?c=test-client-id'
    );
  });

  it('returns null when no client ID is configured', () => {
    expect(brandfetchUrl('hdfcbank.com', 80, undefined)).toBeNull();
  });

  it('returns null when there is no domain to look up', () => {
    expect(brandfetchUrl(null, 80, 'test-client-id')).toBeNull();
  });
});

describe('extractDomain', () => {
  it('extracts the bare hostname from a website URL', () => {
    expect(extractDomain('https://sbi.co.in')).toBe('sbi.co.in');
  });

  it('strips a leading www.', () => {
    expect(extractDomain('https://www.hdfcbank.com')).toBe('hdfcbank.com');
  });

  it('returns null for a null websiteUrl', () => {
    expect(extractDomain(null)).toBeNull();
  });

  it('returns null rather than throwing for a malformed URL', () => {
    expect(extractDomain('not a url')).toBeNull();
  });
});
