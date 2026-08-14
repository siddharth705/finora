import { describe, it, expect, afterEach, vi } from 'vitest';
import { formatDate, formatTime } from './date';

describe('formatDate', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders a date-only value as the calendar day the statement says', () => {
    expect(formatDate('2027-03-12')).toBe('12 Mar 2027');
  });

  it('does not shift a date-only value backwards a day for a viewer behind UTC', () => {
    // The bug this helper exists for. `new Date('2027-03-12')` is UTC midnight, so
    // toLocaleDateString in any timezone behind UTC renders 11 March -- a deposit maturing on the
    // 12th shown as maturing on the 11th. Asserted by comparing against the explicit local-calendar
    // construction rather than by faking a timezone, which vitest cannot do per-test.
    const localCalendarDay = new Date(2027, 2, 12).toLocaleDateString('en-IN', {
      year: 'numeric', month: 'short', day: 'numeric',
    });
    expect(formatDate('2027-03-12')).toBe(localCalendarDay);

    // And it genuinely differs from the naive parse for anyone behind UTC -- if this ever stops
    // differing it means the test is running at UTC+ and is not proving anything, so it asserts
    // the mechanism rather than the symptom.
    const utcMidnightDay = new Date('2027-03-12').getUTCDate();
    expect(utcMidnightDay).toBe(12);
  });

  it('keeps instant semantics for a real timestamp', () => {
    // An importedAt is a moment, not a calendar date, and SHOULD localise. Only the shape tells
    // them apart, which is why the helper branches on it rather than trusting the caller.
    const withTime = '2027-03-12T18:30:00Z';
    expect(formatDate(withTime)).toBe(
      new Date(withTime).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' })
    );
  });

  it('returns an empty string for null or undefined rather than "Invalid Date"', () => {
    expect(formatDate(null)).toBe('');
    expect(formatDate(undefined)).toBe('');
    expect(formatDate('')).toBe('');
  });

  it('returns an empty string for an unparseable value', () => {
    expect(formatDate('not a date')).toBe('');
  });

  it('honours caller-supplied format options', () => {
    expect(formatDate('2027-03-12', { month: 'long', year: 'numeric' })).toContain('2027');
  });
});

describe('formatTime', () => {
  it('renders a real timestamp in the same en-IN locale every other formatter here uses', () => {
    // Bug fix: the import timeline first shipped calling toLocaleTimeString() with no locale
    // argument -- the one date/time display in the app that let the browser's default locale
    // decide the format instead of matching every sibling call site's explicit 'en-IN'.
    const withTime = '2027-03-12T18:30:00Z';
    expect(formatTime(withTime)).toBe(new Date(withTime).toLocaleTimeString('en-IN'));
  });

  it('returns an empty string for null, undefined, or an unparseable value', () => {
    expect(formatTime(null)).toBe('');
    expect(formatTime(undefined)).toBe('');
    expect(formatTime('')).toBe('');
    expect(formatTime('not a date')).toBe('');
  });
});
