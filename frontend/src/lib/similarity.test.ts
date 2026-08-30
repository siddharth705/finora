import { describe, it, expect } from 'vitest';
import { similarityRatio } from './similarity';

describe('similarityRatio', () => {
  it('returns 1 for identical strings', () => {
    expect(similarityRatio('SIP', 'SIP')).toBe(1);
  });

  it('is case-insensitive', () => {
    expect(similarityRatio('SIP', 'sip')).toBe(1);
  });

  it('returns a high ratio for a near-miss', () => {
    expect(similarityRatio('SIP', 'S.I.P.')).toBeGreaterThan(0.65);
  });

  it('returns a low ratio for unrelated strings', () => {
    expect(similarityRatio('SIP', 'Groceries')).toBeLessThan(0.4);
  });

  it('returns 0 for an empty string against a non-empty one', () => {
    expect(similarityRatio('', 'SIP')).toBe(0);
  });
});
