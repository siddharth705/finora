/**
 * Formatting helpers shared across screens. Ported from the copies that live inline in
 * frontend/src/pages/Dashboard.tsx, Ledger.tsx, and Setup.tsx -- the web app repeats fmt() in
 * several files; there's no reason to repeat it here too.
 */

/**
 * Negative amounts must render as "-₹500", not "₹-500" -- concatenating the symbol before the
 * sign produced the latter. This is a real bug the web app already fixed; preserved here.
 */
export function fmtCurrency(n: number): string {
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Renders either kind of date this API returns, distinguishing them by shape.
 *
 * A date-only value (Java `LocalDate` -- a deposit's maturity date, a goal's target date, a
 * statement period boundary) is a calendar date with no timezone, and `new Date('2027-03-12')`
 * parses it as UTC MIDNIGHT. Formatted in any timezone behind UTC that renders the previous day: a
 * deposit maturing on 12 March shows as 11 March. A timestamp (Java `Instant` -- when an import
 * ran) is a real moment and must keep being converted into the viewer's zone.
 *
 * The two are indistinguishable at the type level -- both arrive as `string` -- and getting it
 * wrong is silent, so this decides rather than asking each caller to remember. Mirrors
 * frontend/src/utils/date.ts, which fixed the same bug across the web app.
 */
export function fmtDate(d: string | null | undefined): string | null {
  if (!d) return null;
  const options: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'short', day: 'numeric' };

  if (DATE_ONLY.test(d)) {
    // Explicit y/m/d construction is local by definition -- no UTC round trip to shift it.
    return fromLocalDateString(d).toLocaleDateString('en-IN', options);
  }

  const parsed = new Date(d);
  // An unparseable string would otherwise render as the literal "Invalid Date".
  return Number.isNaN(parsed.getTime()) ? null : parsed.toLocaleDateString('en-IN', options);
}

/**
 * A Date to the "YYYY-MM-DD" a backend LocalDate field expects, read in the DEVICE's timezone.
 *
 * Not `toISOString().slice(0, 10)`, which converts to UTC first: for anyone east of UTC, a date
 * picked as the 1st is submitted as the previous month's last day. This is the same off-by-one the
 * web app hit rendering LocalDate values and fixed in its own date utility -- the same bug, in the
 * other direction, on the way in rather than out.
 */
export function toLocalDateString(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

/**
 * A backend LocalDate ("YYYY-MM-DD") back into a Date at LOCAL midnight -- the inverse of the
 * above, and necessary for the same reason: `new Date('2027-03-01')` parses as UTC midnight, which
 * is the previous day for anyone behind UTC.
 */
export function fromLocalDateString(s: string): Date {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** "2026-08" -> "Aug 26". Used for the cash-flow chart's x-axis labels. */
export function monthLabel(monthStr: string): string {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
}

/** Up to two letters for the avatar badge. "?" rather than an empty circle when there's no name. */
export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
}

/** "August 2026" -- for "Member since", where a precise day is noise. */
export function fmtMonthYear(iso: string | null | undefined): string {
  if (!iso) return '—';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
}

/**
 * "today" / "3 days ago" / "2 months ago" -- for facts where the gap is the point ("last changed",
 * "last active") and the exact timestamp is not.
 *
 * Returns null rather than a string for missing or unparseable input, so callers can distinguish
 * "never happened" from "happened at some point" and word it themselves.
 *
 * Months and years are approximated at 30 and 360 days. That is deliberate for a phrase already
 * hedged by the word "ago": calendar-exact arithmetic would change "11 months ago" to "1 year ago"
 * on a date nobody is checking against a calendar.
 */
export function fmtRelativeTime(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const days = Math.floor((Date.now() - then) / (1000 * 60 * 60 * 24));
  if (days < 1) return 'today';
  if (days === 1) return 'yesterday';
  if (days < 30) return `${days} days ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? '' : 's'} ago`;
  const years = Math.floor(months / 12);
  return `${years} year${years === 1 ? '' : 's'} ago`;
}

/**
 * Reads the current hour in the user's chosen timezone (see Settings) rather than the device
 * clock. The two only differ when someone's device is set to a different zone than the one they
 * actually keep finance-app hours in -- but when they do differ, using the device clock is just
 * wrong. Falls back to the device hour if the timezone is unset or Intl rejects it.
 *
 * Hermes ships full ICU as of recent React Native versions, so the timeZone option resolves
 * correctly; the try/catch covers older engines and malformed stored values either way.
 */
export function greeting(timezone: string | undefined): string {
  let hourStr: string;
  try {
    hourStr = new Intl.DateTimeFormat('en-US', {
      hour: 'numeric',
      hour12: false,
      timeZone: timezone || undefined,
    }).format(new Date());
  } catch {
    hourStr = String(new Date().getHours());
  }
  const h = parseInt(hourStr, 10) % 24;
  if (h < 5) return 'Good night';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  if (h < 21) return 'Good evening';
  return 'Good night';
}
