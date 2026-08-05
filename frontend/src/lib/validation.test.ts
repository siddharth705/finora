import { describe, expect, it } from 'vitest';
import { parseNonNegativeAmount, parsePositiveAmount } from './validation';

describe('parsePositiveAmount', () => {
  it('accepts real positive numbers', () => {
    expect(parsePositiveAmount('5000')).toBe(5000);
    expect(parsePositiveAmount('1234.56')).toBe(1234.56);
    expect(parsePositiveAmount('  250  ')).toBe(250);
  });

  it('rejects zero and negatives', () => {
    expect(parsePositiveAmount('0')).toBeNull();
    expect(parsePositiveAmount('-500')).toBeNull();
  });

  it('rejects empty and whitespace-only input', () => {
    // The BUG-1 case: a cleared `type="number"` field yields ''. parseFloat('') is NaN, which
    // JSON.stringify writes as null, which the backend reads as "leave unchanged" -- a save that
    // silently does nothing while reporting success.
    expect(parsePositiveAmount('')).toBeNull();
    expect(parsePositiveAmount('   ')).toBeNull();
  });

  it('rejects partially-numeric and non-finite input that parseFloat would accept', () => {
    expect(parsePositiveAmount('12abc')).toBeNull();
    expect(parsePositiveAmount('abc')).toBeNull();
    expect(parsePositiveAmount('1,000')).toBeNull();
    expect(parsePositiveAmount('Infinity')).toBeNull();
  });

  it('never returns NaN', () => {
    for (const input of ['', ' ', 'abc', '12abc', 'NaN', 'Infinity', '-0']) {
      const result = parsePositiveAmount(input);
      expect(result === null || Number.isFinite(result)).toBe(true);
    }
  });
});

describe('parseNonNegativeAmount', () => {
  it('accepts zero, unlike parsePositiveAmount', () => {
    expect(parseNonNegativeAmount('0')).toBe(0);
    expect(parsePositiveAmount('0')).toBeNull();
  });

  it('still rejects negatives and non-numbers', () => {
    expect(parseNonNegativeAmount('-1')).toBeNull();
    expect(parseNonNegativeAmount('abc')).toBeNull();
    expect(parseNonNegativeAmount('')).toBeNull();
  });
});
