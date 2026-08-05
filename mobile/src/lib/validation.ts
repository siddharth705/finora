/**
 * Registration/profile validation rules, kept in one module rather than inline in a screen so they
 * can be tested directly and reused by the account forms that Phases 4-5 add.
 *
 * These must agree with frontend/src/pages/Register.tsx. The backend enforces its own rules
 * regardless, but two clients disagreeing about what's acceptable is a support problem.
 */

/** Real Indian mobile numbers always start 6-9. */
export const PHONE_PATTERN = /^[6-9][0-9]{9}$/;

/**
 * Letters (including accented and non-Latin), spaces, hyphens, apostrophes, periods -- covers
 * "Jean-Luc", "O'Brien", "Md. Rahman", "José", "李明" while rejecting digits and email-like input.
 * Must start and end with a letter so stray punctuation can't slip through.
 */
export const FULL_NAME_PATTERN = /^[\p{L}][\p{L}\s.'-]{0,98}[\p{L}]$/u;

export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Digits only, capped at 10 -- the country code is a fixed "+91" shown beside the field, never
 * typed into it.
 *
 * Also strips a pasted country code, but only when the result would otherwise exceed 10 digits.
 * That guard matters: a genuine 10-digit number starting "91" (any number in 910-919) must keep
 * those digits. The web app splits this across separate typing and paste handlers; React Native
 * delivers both through onChangeText, so one function covers both — and the field must NOT set
 * maxLength, or RN truncates the paste before this ever runs. See RegisterScreen.
 */
export function sanitizePhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

/** Digits only, capped at 6. Same no-maxLength rule as the phone field: pasting a whole SMS
 *  ("Your code is 123456") must reach this function intact to have the digits extracted. */
export function sanitizeOtp(raw: string): string {
  return raw.replace(/\D/g, '').slice(0, 6);
}

/**
 * Every money field in the app takes the same rule -- a real number greater than zero -- and the
 * web app re-derives it inline in Budgets.tsx, Goals.tsx and Investments.tsx, which is how
 * Investments.tsx came to be missing it entirely for a while: `parseFloat` returned NaN, `NaN > 0`
 * was false but nothing checked it, and "₹NaN" rendered across the totals. One function, one test.
 *
 * Returns null when the input isn't usable, so callers branch on the value rather than repeating
 * the predicate.
 */
export function parsePositiveAmount(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  // parseFloat("12abc") is 12 -- permissive enough to accept input a user never meant. Number()
  // rejects the whole string, which is the behavior wanted for a field that must be only a number.
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value <= 0) return null;
  return value;
}

/**
 * Four independent signals, no library. Purely a nudge shown under the field -- never a
 * submission gate; the backend's 8-character minimum is the real requirement.
 */
export function passwordStrength(pw: string): { score: number; label: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'];
  return { score, label: labels[score] };
}
