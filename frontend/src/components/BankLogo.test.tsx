import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { extractDomain, logoDevUrl, BankLogo } from './BankLogo';
import type { BankInfo } from '../types';

/**
 * Logo.dev's img.logo.dev endpoint, per the pasted setup doc this integration was built from --
 * a bare domain (no `domain/` prefix, unlike `name/`/`ticker/`/`crypto/`/`isin/`), `format=png`
 * for transparency, and `fallback=404` so a miss falls through to this app's own local-SVG/
 * initials chain instead of Logo.dev's generic monogram.
 */
describe('logoDevUrl', () => {
  it('builds the URL with size, png format, and fallback=404', () => {
    expect(logoDevUrl('hdfcbank.com', 80, 'test-token')).toBe(
      'https://img.logo.dev/hdfcbank.com?token=test-token&size=80&format=png&fallback=404'
    );
  });

  it('returns null when no token is configured', () => {
    expect(logoDevUrl('hdfcbank.com', 80, undefined)).toBeNull();
  });

  it('returns null when there is no domain to look up', () => {
    expect(logoDevUrl(null, 80, 'test-token')).toBeNull();
  });
});

/**
 * The circuit breaker, proved to actually trip.
 *
 * Production logged a wall of `403 (Forbidden)` from the logo CDN (Brandfetch, under the
 * integration this replaced) -- one per bank logo on the page, because each logo discovered the
 * same failure independently, each burning its own timeout first. A breaker that cannot be shown
 * to trip is not a breaker, so this asserts the second logo never requests Logo.dev at all after
 * the first one is rejected.
 */
describe('BankLogo Logo.dev circuit breaker', () => {
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

  it('stops requesting Logo.dev for every other logo once one request is rejected', async () => {
    // The token is read once at module load, and no .env exists for tests -- so without stubbing
    // it and re-importing, the Logo.dev stage is skipped entirely and this test would pass while
    // exercising nothing. That is the failure mode this whole test exists to avoid, so it must
    // not be reintroduced by the test's own setup.
    vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
    vi.resetModules();
    const { BankLogo: FreshBankLogo } = await import('./BankLogo');

    const first = render(<FreshBankLogo bank={hdfc} />);
    const img = first.container.querySelector('img');
    expect(img?.getAttribute('src')).toContain('img.logo.dev');

    // The CDN rejects it -- exactly what a 403 does to an <img>.
    fireEvent.error(img!);
    first.unmount();

    // A logo mounted afterwards must go straight to the fallback rather than repeating the same
    // doomed request.
    const second = render(<FreshBankLogo bank={hdfc} />);
    const secondImg = second.container.querySelector('img');
    expect(secondImg?.getAttribute('src') ?? '').not.toContain('img.logo.dev');

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
