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

export function fmtDate(d: string | null | undefined): string | null {
  if (!d) return null;
  return new Date(d).toLocaleDateString('en-IN', { year: 'numeric', month: 'short', day: 'numeric' });
}

/** "2026-08" -> "Aug 26". Used for the cash-flow chart's x-axis labels. */
export function monthLabel(monthStr: string): string {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'short', year: '2-digit' });
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
