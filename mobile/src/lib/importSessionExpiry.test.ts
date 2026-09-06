import { expiresInLabel, hasExpired } from './importSessionExpiry';

const NOW = Date.UTC(2026, 8, 3, 12, 0, 0);
const at = (msFromNow: number) => new Date(NOW + msFromNow).toISOString();

describe('expiresInLabel', () => {
  it('counts down in minutes under an hour', () => {
    expect(expiresInLabel(at(45 * 60_000), NOW)).toBe('Expires in 45 minutes');
  });

  it('does not say "0 minutes" in the final seconds', () => {
    // The window is still open -- the server would accept a resume -- so it must not read as
    // expired, but "in 0 minutes" is nonsense.
    expect(expiresInLabel(at(30_000), NOW)).toBe('Expires in under a minute');
  });

  it('switches to hours, then days, with correct singulars', () => {
    expect(expiresInLabel(at(60 * 60_000), NOW)).toBe('Expires in 1 hour');
    expect(expiresInLabel(at(6 * 60 * 60_000), NOW)).toBe('Expires in 6 hours');
    expect(expiresInLabel(at(25 * 60 * 60_000), NOW)).toBe('Expires in 1 day');
    expect(expiresInLabel(at(72 * 60 * 60_000), NOW)).toBe('Expires in 3 days');
  });

  it('says so once the server would already refuse it', () => {
    // Reachable just by leaving the screen open: the list is fetched once, not live.
    expect(expiresInLabel(at(-1), NOW)).toBe('Expired');
    expect(expiresInLabel(at(0), NOW)).toBe('Expired');
  });

  it('treats an unparseable timestamp as expired rather than rendering NaN', () => {
    // Fail closed: offering to resume something we cannot reason about is worse than hiding it.
    expect(expiresInLabel('not-a-date', NOW)).toBe('Expired');
  });
});

describe('hasExpired', () => {
  it('is true at and after the expiry instant, false before', () => {
    expect(hasExpired(at(1), NOW)).toBe(false);
    expect(hasExpired(at(0), NOW)).toBe(true);
    expect(hasExpired(at(-60_000), NOW)).toBe(true);
  });

  it('is true for an unparseable timestamp', () => {
    expect(hasExpired('nonsense', NOW)).toBe(true);
  });
});
