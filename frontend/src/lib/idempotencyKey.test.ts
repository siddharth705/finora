import { describe, expect, it } from 'vitest';
import { newIdempotencyKey } from './idempotencyKey';

describe('newIdempotencyKey', () => {
  it('does not repeat, including within the same millisecond', () => {
    // The timestamp prefix alone is not enough. The property this key needs is uniqueness, and
    // leaning on the clock for it would make the guard silently weaker on a faster machine -- the
    // random suffix is what actually carries it.
    const keys = new Set(Array.from({ length: 5000 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(5000);
  });

  it('is a plain string the server can store as-is', () => {
    // VARCHAR(255) server-side, travelling in a JSON body -- nothing here needs escaping anywhere
    // along that path.
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[a-z0-9]+-[a-z0-9]+$/);
    expect(key.length).toBeLessThan(64);
  });

  it('matches the mobile implementation shape', () => {
    // The two clients cannot share a module, so the contract is pinned on both sides instead. A
    // divergence would mean one client protected and the other not -- the exact state this fixes.
    expect(newIdempotencyKey().split('-')).toHaveLength(2);
  });
});
