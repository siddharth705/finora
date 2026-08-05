/**
 * Shared input parsing for the web app's money fields.
 *
 * This mirrors `mobile/src/lib/validation.ts` deliberately. That file's own comment records why
 * the helper exists at all: every money field takes the same rule, and re-deriving it inline at
 * each call site is what let Investments.tsx ship without the check. The web app then repeated
 * the same mistake one field further along -- Settings.tsx's low-balance threshold parsed with a
 * bare `parseFloat` and never checked the result, so clearing the field sent `NaN`, which
 * `JSON.stringify` renders as `null`, which the backend reads as "leave this field unchanged."
 *
 * One function, one test, one place to change -- so the set of call sites is closed rather than
 * rediscovered each time a new form is added.
 */

/**
 * Parses a money input that must be a real number greater than zero.
 *
 * Returns null when the input isn't usable, so callers branch on the value rather than repeating
 * the predicate. Never returns NaN -- that is the whole point; NaN survives `parseFloat`, passes
 * a truthiness check, and only reveals itself as "₹NaN" on screen or as a dropped field on the
 * wire.
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
 * The zero-permitting variant, for fields where zero is a legitimate value (an opening balance, a
 * goal's starting contribution) but "not a number" still is not. Kept separate from
 * `parsePositiveAmount` rather than adding a boolean flag, so a call site reads as what it means.
 */
export function parseNonNegativeAmount(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value < 0) return null;
  return value;
}
