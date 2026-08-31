import { describe, it, expect } from 'vitest';
import { isLikelyMatch } from './holderNameMatcher';

// Mirrors backend/src/test/java/com/finora/imports/ownership/HolderNameMatcherTest.java --
// same algorithm, same cases, kept in sync so a translation bug in the port shows up here.
describe('isLikelyMatch', () => {
  it('matches an exact name', () => {
    expect(isLikelyMatch('Rahul Sharma', 'Rahul Sharma')).toBe(true);
  });

  it('ignores case and whitespace', () => {
    expect(isLikelyMatch('  rahul   SHARMA  ', 'RAHUL sharma')).toBe(true);
  });

  it('matches an initial on the statement against the full profile name', () => {
    expect(isLikelyMatch('R Sharma', 'Rahul Sharma')).toBe(true);
  });

  it('matches an honorific and middle initial', () => {
    expect(isLikelyMatch('MR R K SHARMA', 'Rahul Sharma')).toBe(true);
  });

  it('does not care about token order', () => {
    expect(isLikelyMatch('SHARMA RAHUL K', 'Rahul Sharma')).toBe(true);
  });

  it('rejects a different person', () => {
    expect(isLikelyMatch('Sunil Verma', 'Rahul Sharma')).toBe(false);
  });

  it('rejects a spouse\'s separate, non-joint account', () => {
    expect(isLikelyMatch('Priya Sharma', 'Rahul Sharma')).toBe(false);
  });

  it('matches either holder of a joint account joined by "AND"', () => {
    expect(isLikelyMatch('RAHUL AND PRIYA SHARMA', 'Rahul Sharma')).toBe(true);
    expect(isLikelyMatch('RAHUL AND PRIYA SHARMA', 'Priya Sharma')).toBe(true);
  });

  it('matches a joint account joined by "&"', () => {
    expect(isLikelyMatch('RAHUL SHARMA & PRIYA SHARMA', 'Rahul Sharma')).toBe(true);
  });

  it('matches a joint account joined by "OR"', () => {
    expect(isLikelyMatch('RAHUL SHARMA OR PRIYA SHARMA', 'Priya Sharma')).toBe(true);
  });

  it('still rejects a truly unrelated profile name on a joint account', () => {
    expect(isLikelyMatch('RAHUL AND PRIYA SHARMA', 'Sunil Verma')).toBe(false);
  });

  it('never matches a blank or missing extracted name', () => {
    expect(isLikelyMatch('', 'Rahul Sharma')).toBe(false);
    expect(isLikelyMatch(null, 'Rahul Sharma')).toBe(false);
    expect(isLikelyMatch(undefined, 'Rahul Sharma')).toBe(false);
  });

  it('never matches a blank or missing profile name', () => {
    expect(isLikelyMatch('Rahul Sharma', '')).toBe(false);
    expect(isLikelyMatch('Rahul Sharma', null)).toBe(false);
    expect(isLikelyMatch('Rahul Sharma', undefined)).toBe(false);
  });
});
