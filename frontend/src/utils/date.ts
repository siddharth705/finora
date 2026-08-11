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

const MONTH_ABBREVIATIONS = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/**
 * The fixed `DD-MMM-YYYY` display format the transaction import review table standardizes on
 * (e.g. `01-Jul-2026`), regardless of how the source statement printed its dates or what locale
 * the browser is in.
 *
 * Deliberately NOT built on `toLocaleDateString`, unlike {@link formatDate} above: locale
 * formatting is exactly right when the goal is "render this the way the viewer's own locale
 * expects," which is `formatDate`'s job elsewhere in the app, but wrong here, where the whole
 * point is one fixed, unambiguous shape regardless of locale or browser. Reuses `formatDate`'s
 * same local-calendar y/m/d construction for a date-only value, so it inherits the identical fix
 * for the UTC-midnight-shifts-a-day-behind bug documented above, without inheriting locale
 * variance.
 */
export function formatDateDDMMMYYYY(value: string | null | undefined): string {
  if (!value) return '';

  let year: number, month: number, day: number;
  const match = DATE_ONLY.exec(value);
  if (match) {
    [year, month, day] = value.split('-').map(Number);
  } else {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) return '';
    year = parsed.getFullYear();
    month = parsed.getMonth() + 1;
    day = parsed.getDate();
  }

  return `${String(day).padStart(2, '0')}-${MONTH_ABBREVIATIONS[month - 1]}-${year}`;
}
