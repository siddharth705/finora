import { fmtCurrency, fmtDate, fromLocalDateString, monthLabel, toLocalDateString } from './format';

describe('fmtCurrency', () => {
  // The bug this preserves: string-concatenating the symbol produced "₹-500" for a negative.
  it('puts the sign before the currency symbol', () => {
    expect(fmtCurrency(-500)).toBe('-₹500');
    expect(fmtCurrency(500)).toBe('₹500');
  });

  it('rounds to whole rupees', () => {
    expect(fmtCurrency(1234.4)).toBe('₹1,234');
    expect(fmtCurrency(0)).toBe('₹0');
  });
});

/*
 * The date helpers exist to keep a calendar date from shifting by a day. These tests would pass
 * trivially in IST -- where UTC midnight is still the same calendar day -- which is exactly why the
 * bug survived so long in the web app. Each one below is written to fail if the implementation
 * round-trips through UTC, regardless of the zone the suite runs in.
 */
describe('local calendar dates', () => {
  it('formats a Date without a UTC round trip', () => {
    // Local midnight on the 1st. toISOString() on this yields the PREVIOUS day anywhere east of
    // UTC, which is what the naive implementation returned.
    expect(toLocalDateString(new Date(2027, 2, 1))).toBe('2027-03-01');
    expect(toLocalDateString(new Date(2026, 11, 31))).toBe('2026-12-31');
  });

  it('pads single-digit months and days', () => {
    expect(toLocalDateString(new Date(2026, 0, 5))).toBe('2026-01-05');
  });

  it('parses a LocalDate string back to the same calendar day', () => {
    const d = fromLocalDateString('2027-03-01');
    expect(d.getFullYear()).toBe(2027);
    expect(d.getMonth()).toBe(2);
    expect(d.getDate()).toBe(1);
  });

  it('round-trips without drifting', () => {
    for (const s of ['2026-01-01', '2026-06-15', '2026-12-31', '2028-02-29']) {
      expect(toLocalDateString(fromLocalDateString(s))).toBe(s);
    }
  });
});

describe('fmtDate', () => {
  it('renders a date-only value as the day it names', () => {
    // Not "11 Mar" -- see fmtDate's own comment. Asserted on the day number alone so the test
    // doesn't depend on the platform's locale formatting.
    expect(fmtDate('2027-03-12')).toContain('12');
    expect(fmtDate('2027-03-12')).toContain('2027');
  });

  it('still treats a timestamp as an instant', () => {
    expect(fmtDate('2026-08-05T10:30:00Z')).not.toBeNull();
  });

  it('returns null for missing or unparseable input', () => {
    expect(fmtDate(null)).toBeNull();
    expect(fmtDate(undefined)).toBeNull();
    expect(fmtDate('')).toBeNull();
    expect(fmtDate('not a date')).toBeNull();
  });
});

describe('monthLabel', () => {
  it('shortens a YYYY-MM to month and 2-digit year', () => {
    expect(monthLabel('2026-08')).toBe('Aug 26');
    expect(monthLabel('2026-01')).toBe('Jan 26');
  });
});
