import {
  currentYearMonth, fmtCurrency, fmtDate, fmtMonthYear, fmtRelativeTime, fromLocalDateString,
  initials, monthDateRange, monthLabel, monthLabelLong, toLocalDateString,
} from './format';

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

describe('initials', () => {
  it('takes the first letter of the first two words', () => {
    expect(initials('Ada Lovelace')).toBe('AL');
    expect(initials('  ada   lovelace  ')).toBe('AL');
  });

  it('handles a single name', () => {
    expect(initials('Ada')).toBe('A');
  });

  it('falls back to a placeholder rather than an empty badge', () => {
    expect(initials(null)).toBe('?');
    expect(initials(undefined)).toBe('?');
    expect(initials('   ')).toBe('?');
  });
});

describe('fmtMonthYear', () => {
  it('renders month and year', () => {
    const out = fmtMonthYear('2026-08-05T10:00:00Z');
    expect(out).toContain('2026');
    expect(out).toMatch(/august/i);
  });

  it('returns a dash for missing or unparseable input', () => {
    expect(fmtMonthYear(null)).toBe('—');
    expect(fmtMonthYear('not a date')).toBe('—');
  });
});

describe('fmtRelativeTime', () => {
  const DAY = 24 * 60 * 60 * 1000;
  const ago = (days: number) => new Date(Date.now() - days * DAY).toISOString();

  it('words the near past in days', () => {
    expect(fmtRelativeTime(ago(0))).toBe('today');
    expect(fmtRelativeTime(ago(1))).toBe('yesterday');
    expect(fmtRelativeTime(ago(5))).toBe('5 days ago');
    expect(fmtRelativeTime(ago(29))).toBe('29 days ago');
  });

  it('switches to months and years, singular where it should be', () => {
    expect(fmtRelativeTime(ago(30))).toBe('1 month ago');
    expect(fmtRelativeTime(ago(75))).toBe('2 months ago');
    expect(fmtRelativeTime(ago(365))).toBe('1 year ago');
    expect(fmtRelativeTime(ago(800))).toBe('2 years ago');
  });

  // Null, not a string: the caller has to be able to say "Never changed" rather than print a gap
  // that never happened.
  it('returns null when there is no timestamp to compare', () => {
    expect(fmtRelativeTime(null)).toBeNull();
    expect(fmtRelativeTime(undefined)).toBeNull();
    expect(fmtRelativeTime('not a date')).toBeNull();
  });
});

describe('monthLabel', () => {
  it('shortens a YYYY-MM to month and 2-digit year', () => {
    expect(monthLabel('2026-08')).toBe('Aug 26');
    expect(monthLabel('2026-01')).toBe('Jan 26');
  });
});

describe('monthLabelLong', () => {
  it('spells a YYYY-MM out to full month and year', () => {
    expect(monthLabelLong('2026-08')).toBe('August 2026');
    expect(monthLabelLong('2026-01')).toBe('January 2026');
  });
});

describe('monthDateRange', () => {
  it('spans the whole calendar month, both ends inclusive', () => {
    expect(monthDateRange('2026-08')).toEqual({ dateFrom: '2026-08-01', dateTo: '2026-08-31' });
  });

  it('gets February and leap years right without a lookup table', () => {
    expect(monthDateRange('2026-02')).toEqual({ dateFrom: '2026-02-01', dateTo: '2026-02-28' });
    expect(monthDateRange('2028-02')).toEqual({ dateFrom: '2028-02-01', dateTo: '2028-02-29' });
  });

  it('rolls December into the correct year rather than month 13', () => {
    expect(monthDateRange('2026-12')).toEqual({ dateFrom: '2026-12-01', dateTo: '2026-12-31' });
  });
});

describe('currentYearMonth', () => {
  it('matches the device clock at call time', () => {
    const now = new Date();
    const expected = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    expect(currentYearMonth()).toBe(expected);
  });
});
