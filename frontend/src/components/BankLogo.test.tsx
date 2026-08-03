import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { extractDomain, brandfetchUrl, BankLogo } from './BankLogo';
import type { BankInfo } from '../types';

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

/**
 * The circuit breaker, proved to actually trip.
 *
 * Production logged a wall of `403 (Forbidden)` from the Brandfetch CDN -- one per bank logo on the
 * page, because each logo discovered the same failure independently, each burning its own timeout
 * first. A breaker that cannot be shown to trip is not a breaker, so this asserts the second logo
 * never requests Brandfetch at all after the first one is rejected.
 */
describe('BankLogo Brandfetch circuit breaker', () => {
  const hdfc: BankInfo = {
    id: 'HDFC',
    officialName: 'HDFC Bank',
    shortName: 'HDFC',
    colorHex: '#004C8F',
    initials: 'HDFC',
    logoPath: '/assets/banks/hdfc.svg',
    category: 'PRIVATE',
    websiteUrl: 'https://www.hdfcbank.com',
    ifscPrefix: 'HDFC',
    supportedAccountTypes: ['SAVINGS', 'CREDIT_CARD'],
  };

  it('stops requesting Brandfetch for every other logo once one request is rejected', async () => {
    // The client ID is read once at module load, and no .env exists for tests -- so without
    // stubbing it and re-importing, the Brandfetch stage is skipped entirely and this test would
    // pass while exercising nothing. That is the failure mode this whole test exists to avoid, so
    // it must not be reintroduced by the test's own setup.
    vi.stubEnv('VITE_BRANDFETCH_CLIENT_ID', 'test-client-id');
    vi.resetModules();
    const { BankLogo: FreshBankLogo } = await import('./BankLogo');

    const first = render(<FreshBankLogo bank={hdfc} />);
    const img = first.container.querySelector('img');
    expect(img?.getAttribute('src')).toContain('cdn.brandfetch.io');

    // The CDN rejects it -- exactly what a 403 does to an <img>.
    fireEvent.error(img!);
    first.unmount();

    // A logo mounted afterwards must go straight to the fallback rather than repeating the same
    // doomed request.
    const second = render(<FreshBankLogo bank={hdfc} />);
    const secondImg = second.container.querySelector('img');
    expect(secondImg?.getAttribute('src') ?? '').not.toContain('cdn.brandfetch.io');

    vi.unstubAllEnvs();
  });

  it('always renders something identifiable, whichever stage it lands on', () => {
    // The user-facing guarantee behind the whole fallback chain: a failed CDN never leaves an
    // empty hole on the page.
    const { container } = render(<BankLogo bank={hdfc} />);
    expect(container.firstChild).not.toBeNull();
    expect(screen.getByTitle('HDFC Bank')).toBeTruthy();
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
