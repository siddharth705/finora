import {
  EMAIL_PATTERN, FULL_NAME_PATTERN, PHONE_PATTERN, passwordStrength, sanitizeOtp, sanitizePhoneNumber,
} from './validation';

/*
 * Test inputs are grouped here rather than inlined so each group carries one `synthetic-ok`
 * marker (the hygiene check matches per line, so scattered literals would need one marker each).
 *
 * Every value is invented to sit on a boundary of the pattern under test -- leading digit 0-5,
 * exactly 10 vs 11 digits, a genuine number that happens to begin "91" -- using ascending or
 * repeating digit runs and RFC 2606 example addresses. None of it is copied from anywhere.
 */

const VALID_PHONES = ['9876543210', '6000000000', '7123456789', '8999999999']; // synthetic-ok: invented boundary values
const INVALID_PHONES = ['5876543210', '0987654321', '1234567890', '987654321', '98765432101']; // synthetic-ok: invented boundary values
const PASTED_WITH_COUNTRY_CODE: [string, string][] = [
  ['+919876543210', '9876543210'], // synthetic-ok: invented
  ['919876543210', '9876543210'], // synthetic-ok: invented
  ['+91 98765 43210', '9876543210'], // synthetic-ok: invented
  ['+91-98765-43210', '9876543210'], // synthetic-ok: invented
];
const STARTS_WITH_91 = ['9198765432', '9112345678']; // synthetic-ok: real 10-digit numbers begin 91 too
const VALID_EMAILS = ['a@b.co', 'you@example.com']; // synthetic-ok: RFC 2606 / minimal shape
const INVALID_EMAILS = ['bad', 'a@b', 'a b@c.com', '@x.com', 'x@.com']; // synthetic-ok: malformed by design

describe('sanitizePhoneNumber', () => {
  it('keeps a plain 10-digit number', () => {
    expect(sanitizePhoneNumber(VALID_PHONES[0])).toBe(VALID_PHONES[0]);
  });

  it('strips punctuation and spaces as typed', () => {
    expect(sanitizePhoneNumber('98765 43210')).toBe('9876543210'); // synthetic-ok: invented
    expect(sanitizePhoneNumber('98-765-43210')).toBe('9876543210'); // synthetic-ok: invented
  });

  // Regression: the field must NOT set maxLength, or React Native truncates the paste before this
  // runs and a pasted full number arrives cut short -- silently the wrong number. See
  // RegisterScreen's comment on the phone field.
  it.each(PASTED_WITH_COUNTRY_CODE)('strips a pasted country code: %s', (pasted, expected) => {
    expect(sanitizePhoneNumber(pasted)).toBe(expected);
  });

  // The country-code strip is guarded on length for exactly this reason: numbers in 910-919 are
  // real 10-digit mobiles and must keep their leading "91".
  it.each(STARTS_WITH_91)('does not eat the leading 91 of a genuine 10-digit number: %s', (n) => {
    expect(sanitizePhoneNumber(n)).toBe(n);
  });

  it('caps at 10 digits', () => {
    expect(sanitizePhoneNumber('98765432109999')).toHaveLength(10); // synthetic-ok: invented overflow input
  });

  it('handles empty and non-numeric input', () => {
    expect(sanitizePhoneNumber('')).toBe('');
    expect(sanitizePhoneNumber('abc')).toBe('');
  });
});

describe('sanitizeOtp', () => {
  it('keeps six digits', () => {
    expect(sanitizeOtp('123456')).toBe('123456');
  });

  // Regression for the second paste bug: with maxLength={6} the OS truncates "Your code is
  // 123456" to "Your c" before this runs, which then strips to nothing at all.
  it('extracts the code from a pasted SMS', () => {
    expect(sanitizeOtp('Your code is 123456')).toBe('123456');
    expect(sanitizeOtp('123456 is your Finora code')).toBe('123456');
  });

  it('caps at six digits', () => {
    expect(sanitizeOtp('1234567890')).toBe('123456'); // synthetic-ok: invented overflow input
  });
});

describe('PHONE_PATTERN', () => {
  it.each(VALID_PHONES)('accepts %s', (n) => {
    expect(PHONE_PATTERN.test(n)).toBe(true);
  });

  // Indian mobile numbers never start 0-5, and must be exactly 10 digits.
  it.each(INVALID_PHONES)('rejects %s', (n) => {
    expect(PHONE_PATTERN.test(n)).toBe(false);
  });
});

describe('FULL_NAME_PATTERN', () => {
  it.each(['Jean-Luc', "O'Brien", 'Md. Rahman', 'José', '李明'])('accepts %s', (n) => {
    expect(FULL_NAME_PATTERN.test(n)).toBe(true);
  });

  it.each(['John123', 'a@b.com', 'A', '', ' Jane'])('rejects %s', (n) => { // synthetic-ok: malformed by design
    expect(FULL_NAME_PATTERN.test(n)).toBe(false);
  });
});

describe('EMAIL_PATTERN', () => {
  it.each(VALID_EMAILS)('accepts %s', (e) => {
    expect(EMAIL_PATTERN.test(e)).toBe(true);
  });

  it.each(INVALID_EMAILS)('rejects %s', (e) => {
    expect(EMAIL_PATTERN.test(e)).toBe(false);
  });
});

describe('passwordStrength', () => {
  it('scores each independent signal', () => {
    expect(passwordStrength('')).toEqual({ score: 0, label: 'Too short' });
    expect(passwordStrength('longenough')).toEqual({ score: 1, label: 'Weak' });
    expect(passwordStrength('LongEnough')).toEqual({ score: 2, label: 'Fair' });
    expect(passwordStrength('LongEnough1')).toEqual({ score: 3, label: 'Good' });
    expect(passwordStrength('LongEnough1!')).toEqual({ score: 4, label: 'Strong' });
  });

  it('does not credit length to a short password', () => {
    expect(passwordStrength('Ab1!').score).toBe(3);
  });
});
