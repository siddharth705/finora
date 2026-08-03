/**
 * Formatting for the two genuinely different kinds of date this API returns.
 *
 * A date-only value (Java `LocalDate` -- a maturity date, a statement period boundary, a due date)
 * is a calendar date with no timezone. `new Date('2027-03-12')` parses it as UTC midnight, so
 * `toLocaleDateString` in any timezone BEHIND UTC renders the previous day: a deposit maturing on
 * 12 March shows as 11 March for a user in the Americas. Every existing call site had this bug --
 * it just never surfaced, because Finora's users are in IST (UTC+5:30), where UTC midnight is still
 * the same calendar day.
 *
 * A timestamp (Java `Instant` -- when an import ran) is a real moment and SHOULD be converted into
 * the viewer's timezone, so it must not get the same treatment.
 *
 * `formatDate` distinguishes them by shape rather than making every caller remember which it has,
 * because the two are indistinguishable at the type level (both arrive as `string`) and getting it
 * wrong is silent.
 */

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

const DEFAULT_OPTIONS: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
};

/**
 * Renders an API date for display. Date-only values are parsed in the LOCAL calendar so the day
 * shown is the day the statement says; timestamps keep their instant semantics.
 *
 * Returns an empty string for null/undefined so callers can interpolate without guarding, which is
 * what every call site was already doing by hand.
 */
export function formatDate(value: string | null | undefined,
                           options: Intl.DateTimeFormatOptions = DEFAULT_OPTIONS): string {
  if (!value) return '';

  const match = DATE_ONLY.exec(value);
  if (match) {
    // Explicit y/m/d construction is local-time by definition -- no UTC round trip to shift it.
    const [year, month, day] = value.split('-').map(Number);
    return new Date(year, month - 1, day).toLocaleDateString('en-IN', options);
  }

  const parsed = new Date(value);
  // An unparseable string would otherwise render as the literal "Invalid Date".
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toLocaleDateString('en-IN', options);
}
