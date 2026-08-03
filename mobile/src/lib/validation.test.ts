import {
  EMAIL_PATTERN, FULL_NAME_PATTERN, PHONE_PATTERN, passwordStrength, sanitizeOtp, sanitizePhoneNumber,
} from './validation';

describe('sanitizePhoneNumber', () => {
  it('keeps a plain 10-digit number', () => {
    expect(sanitizePhoneNumber('9876543210')).toBe('9876543210');
  });

  it('strips punctuation and spaces as typed', () => {
    expect(sanitizePhoneNumber('98765 43210')).toBe('9876543210');
    expect(sanitizePhoneNumber('98-765-43210')).toBe('9876543210');
  });

  // Regression: the field must NOT set maxLength, or React Native truncates the paste before this
  // runs and "+919876543210" arrives as "+91987654" -- silently the wrong number. See
  // RegisterScreen's comment on the phone field.
  it.each([
    ['+919876543210', '9876543210'],
    ['919876543210', '9876543210'],
    ['+91 98765 43210', '9876543210'],
    ['+91-98765-43210', '9876543210'],
  ])('strips a pasted country code: %s', (pasted, expected) => {
    expect(sanitizePhoneNumber(pasted)).toBe(expected);
  });

  // The country-code strip is guarded on length for exactly this reason: numbers in 910-919 are
  // real 10-digit mobiles and must keep their leading "91".
  it('does not eat the leading 91 of a genuine 10-digit number', () => {
    expect(sanitizePhoneNumber('9198765432')).toBe('9198765432');
    expect(sanitizePhoneNumber('9112345678')).toBe('9112345678');
  });

  it('caps at 10 digits', () => {
    expect(sanitizePhoneNumber('98765432109999')).toBe('9876543210');
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
    expect(sanitizeOtp('1234567890')).toBe('123456');
  });
});

describe('PHONE_PATTERN', () => {
  it.each(['9876543210', '6000000000', '7123456789', '8999999999'])('accepts %s', (n) => {
    expect(PHONE_PATTERN.test(n)).toBe(true);
  });

  // Indian mobile numbers never start 0-5.
  it.each(['5876543210', '0987654321', '1234567890', '987654321', '98765432101'])(
    'rejects %s',
    (n) => {
      expect(PHONE_PATTERN.test(n)).toBe(false);
    }
  );
});

describe('FULL_NAME_PATTERN', () => {
  it.each(["Jean-Luc", "O'Brien", 'Md. Rahman', 'José', '李明', 'Ann-Marie O\'Neil'])(
    'accepts %s',
    (n) => {
      expect(FULL_NAME_PATTERN.test(n)).toBe(true);
    }
  );

  it.each(['John123', 'a@b.com', 'A', '', ' Jane'])('rejects %s', (n) => {
    expect(FULL_NAME_PATTERN.test(n)).toBe(false);
  });
});

describe('EMAIL_PATTERN', () => {
  it.each(['a@b.co', 'you@example.com'])('accepts %s', (e) => {
    expect(EMAIL_PATTERN.test(e)).toBe(true);
  });

  it.each(['bad', 'a@b', 'a b@c.com', '@x.com', 'x@.com'])('rejects %s', (e) => {
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
