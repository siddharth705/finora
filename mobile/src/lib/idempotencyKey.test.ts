import { newIdempotencyKey } from './idempotencyKey';

describe('newIdempotencyKey', () => {
  it('does not repeat, including within the same millisecond', () => {
    // The timestamp prefix alone is not enough: a user can only realistically start one re-import
    // per millisecond, but the property this key needs is uniqueness, and relying on the clock for
    // it would make the guard silently weaker the faster the device is. The random suffix is what
    // actually carries it.
    const keys = new Set(Array.from({ length: 5000 }, () => newIdempotencyKey()));
    expect(keys.size).toBe(5000);
  });

  it('is a plain string the server can store as-is', () => {
    // VARCHAR(255) server-side, and it travels in a JSON body -- no separators or characters that
    // would need escaping anywhere along that path.
    const key = newIdempotencyKey();
    expect(key).toMatch(/^[a-z0-9]+-[a-z0-9]+$/);
    expect(key.length).toBeLessThan(64);
  });
});
