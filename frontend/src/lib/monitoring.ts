import * as Sentry from '@sentry/react';
import type { Breadcrumb, ErrorEvent } from '@sentry/react';

/**
 * Crash reporting.
 *
 * Ported from mobile/src/lib/monitoring.ts, which established the rules this app has to follow
 * too. The scrubbing decisions there are not repeated in full here -- read that file for the
 * reasoning. What IS spelled out below is where the web version has to go *further*, because the
 * browser leaks things a native app has no equivalent of.
 *
 * Disabled unless VITE_SENTRY_DSN is set, mirroring how every other external integration in this
 * codebase behaves (RESEND_API_KEY, GOOGLE_APPLICATION_CREDENTIALS, EXPO_PUBLIC_SENTRY_DSN):
 * absent config degrades to a no-op rather than crashing or half-working. That also keeps local
 * development and the test suite free of network calls with no extra setup.
 *
 * ---
 *
 * Why this exists at all: until now the two web apps had no crash reporting of any kind, while the
 * mobile app -- the newest and least-used surface -- had it. A render error in this app unmounts
 * the tree and leaves a blank white page, and nothing anywhere would tell you it happened. That is
 * not hypothetical: a routing defect with exactly that symptom shipped and was found by navigating
 * to a bad URL on purpose, because no mechanism existed that could have reported it.
 *
 * ---
 *
 * THE WEB-SPECIFIC PART.
 *
 * On mobile there is no URL. Here there is, and this app puts sensitive things in it:
 *
 *   - Ledger.tsx keeps the transaction search term in `?q=` (see its useSearchParams call), so the
 *     single most sensitive free-text field in the product -- a merchant, a landlord, a clinic, the
 *     user's own name -- is sitting in the address bar.
 *   - ResetPassword.tsx reads a live password-reset token from `?token=`.
 *
 * Both end up in two places Sentry collects by default: `event.request.url` (handled by scrubEvent,
 * inherited from mobile) and **navigation breadcrumbs**, whose `from`/`to` are full URLs. Mobile's
 * scrubBreadcrumb passes navigation breadcrumbs through untouched because on a native app they are
 * just screen names. Copying it verbatim would have shipped the search term and the reset token to
 * a third party on every crash. Hence the navigation branch below, which has no mobile counterpart.
 *
 * `ui.click` breadcrumbs are deliberately KEPT. Sentry records a DOM path for those, which can
 * include `aria-label`/`title`; every such attribute in this app was checked and they are all
 * static UI strings ("Close", "Next page", "Hide password"), never user or financial data. They are
 * genuinely useful for reproducing a crash, so they are not dropped on a risk that was looked for
 * and not found.
 */

/** Replaces UUIDs and long digit runs in a path, so error grouping still works but the specific
 *  account, transaction, or statement isn't identifiable. */
export function redactPath(path: string): string {
  return path
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '{id}')
    .replace(/\b\d{4,}\b/g, '{n}');
}

/** Drops the query string entirely -- it carries the ledger's free-text search term and the
 *  password-reset token -- and redacts identifiers from what's left. */
export function scrubUrl(raw: unknown): string | undefined {
  if (typeof raw !== 'string') return undefined;
  const [withoutQuery] = raw.split('?');
  return redactPath(withoutQuery);
}

/**
 * Returning null drops the breadcrumb entirely.
 *
 * Console output is the least controlled surface in the app: any `console.log` left in while
 * debugging becomes a breadcrumb, and what people log while debugging a finance bug is amounts and
 * account numbers. Dropped wholesale rather than filtered, because a filter has to be right every
 * time and dropping only has to be right once.
 */
export function scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb | null {
  if (breadcrumb.category === 'console') return null;

  if (breadcrumb.category === 'xhr' || breadcrumb.category === 'fetch') {
    const data = breadcrumb.data ?? {};
    // Method and status are what make a network breadcrumb useful; the body and headers are what
    // make it dangerous. Rebuilt from scratch rather than deleted key by key, so a field Sentry
    // adds in a future version isn't included by default.
    return {
      ...breadcrumb,
      data: {
        method: data.method,
        status_code: data.status_code,
        url: scrubUrl(data.url),
      },
    };
  }

  // Web-only, no mobile counterpart -- see this module's doc comment. `from`/`to` are full URLs,
  // and this app's URLs carry the ledger search term and the password-reset token. Rebuilt rather
  // than patched so no other field Sentry attaches here survives by default.
  if (breadcrumb.category === 'navigation') {
    const data = breadcrumb.data ?? {};
    return {
      ...breadcrumb,
      data: {
        from: scrubUrl(data.from),
        to: scrubUrl(data.to),
      },
    };
  }

  return breadcrumb;
}

/** Strips request bodies and identity from an outgoing event. */
export function scrubEvent(event: ErrorEvent): ErrorEvent {
  if (event.request) {
    // A registration failure would otherwise ship email, phone, and password in request.data. On
    // web, request.url is the PAGE url, so this is also what keeps `?q=` and `?token=` out.
    event.request = {
      method: event.request.method,
      url: scrubUrl(event.request.url),
    };
  }

  delete event.user;

  if (event.breadcrumbs) {
    event.breadcrumbs = event.breadcrumbs
      .map(scrubBreadcrumb)
      .filter((b): b is Breadcrumb => b !== null);
  }

  return event;
}

export function initMonitoring(): void {
  const dsn = import.meta.env.VITE_SENTRY_DSN;
  if (!dsn) return;

  Sentry.init({
    dsn,

    // Never attach IP address, cookies, or user identity. Sentry's own default is already false,
    // but this is the setting whose accidental flip would be most damaging here, so it's stated
    // rather than inherited.
    sendDefaultPii: false,

    environment: import.meta.env.DEV ? 'development' : 'production',

    // Crash reporting only. Performance spans are keyed by URL, which would reintroduce the
    // identifiers scrubbed above, and there's no performance question being asked yet that would
    // justify that trade. Stated explicitly even though no tracing integration is registered --
    // adding one later shouldn't silently start sampling.
    tracesSampleRate: 0,

    // Session replay records the screen. On an app showing balances and transaction history, that
    // is not something to enable by default, or quietly. No replay integration is registered
    // either; these are belt and braces.
    replaysSessionSampleRate: 0,
    replaysOnErrorSampleRate: 0,

    beforeBreadcrumb: scrubBreadcrumb,
    beforeSend: scrubEvent,
  });
}

/**
 * Reports a handled error the user already saw a message for. Use sparingly — an error the app
 * recovered from cleanly usually isn't worth an event.
 *
 * Never pass a caught value's message into `extra`: backend error messages are written for users
 * and can quote the data that failed validation.
 */
export function reportHandledError(error: unknown, context: string): void {
  if (!import.meta.env.VITE_SENTRY_DSN) return;
  Sentry.captureException(error, { tags: { context } });
}
