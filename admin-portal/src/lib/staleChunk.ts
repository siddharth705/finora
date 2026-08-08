/**
 * Recovery for the one render failure a retry button can never fix: a route chunk that no longer
 * exists on the server.
 *
 * <h2>The failure, exactly</h2>
 *
 * Vite content-hashes every chunk, so a deploy replaces `Login-00kC5-u3.js` with a differently
 * named file and deletes the old one. A browser that loaded `index.html` BEFORE that deploy holds
 * the old names in its in-memory module graph. Navigating client-side to a lazy route then requests
 * a file that is gone.
 *
 * This is not an HTML caching problem, and fixing cache headers does not address it. Production
 * already serves `index.html` as `max-age=0, must-revalidate`. The stale reference survives because
 * client-side navigation never re-fetches the HTML at all -- the tab simply outlived the deploy.
 * The window is every deploy, and the population is every user with the app open.
 *
 * <h2>Why it presents as a MIME error rather than a 404</h2>
 *
 * Cloudflare Pages answers unmatched paths with the SPA fallback, which is right for routes and
 * unhelpful for assets: a missing `/assets/*.js` returns `index.html` with `200 text/html`, so the
 * browser reports "Expected a JavaScript-or-Wasm module script but the server responded with a MIME
 * type of text/html" instead of a clean 404. Both spellings are matched below, because which one a
 * user sees depends on the host, not on the fault.
 *
 * <h2>Why the retry button cannot help</h2>
 *
 * {@code ErrorBoundary.reset} clears `hasError` and re-renders. React re-attempts the same lazy
 * import, which requests the same missing URL, and fails identically -- so "Try again" is an
 * infinite loop of one failure. The only recovery is a full document reload, which fetches fresh
 * HTML naming chunks that exist.
 *
 * <h2>The guard is the whole design</h2>
 *
 * An unguarded reload-on-chunk-error is a reload loop the moment the failure is NOT staleness -- a
 * genuinely broken deploy, an asset upload that half-completed, an offline device. The reload then
 * fetches fresh HTML that fails the same way, forever, and the user cannot even read the error.
 * That is strictly worse than the bug being fixed.
 *
 * So a reload is attempted at most once per {@link RELOAD_COOLDOWN_MS}. If the failure recurs
 * inside that window the fresh HTML failed too, which means it is not staleness, and the recovery
 * panel is shown instead. A cooldown rather than once-per-session because sessions outlive
 * deploys: a user with the app open across two deploys should be recovered both times.
 */

/** sessionStorage, not localStorage: the guard should not outlive the tab. A user returning
 *  tomorrow to a genuinely stale tab deserves a fresh attempt. */
const RELOAD_MARKER = 'finora:stale-chunk-reload';

/** Long enough that a reload-loop cannot form (a reload plus a failed import is well under this),
 *  short enough that a second deploy later in the same session is still recovered. */
export const RELOAD_COOLDOWN_MS = 30_000;

/**
 * Every spelling browsers use for "the module you asked for did not load".
 *
 * Deliberately a list of substrings rather than one clever regex: each entry is a specific
 * browser's specific wording, and when a future engine changes its message the fix is to add a
 * line, not to re-derive a pattern. Matched case-insensitively against the message only -- never
 * the stack, which contains URLs that could coincidentally match.
 */
const STALE_CHUNK_SIGNATURES = [
  // Chrome / Edge, the common case.
  'failed to fetch dynamically imported module',
  // Firefox.
  'error loading dynamically imported module',
  // Safari.
  'importing a module script failed',
  // Chrome, when the SPA fallback answers with index.html instead of 404 -- the spelling seen in
  // the production incident this module was written for.
  'expected a javascript-or-wasm module script',
  // Firefox's wording for that same MIME mismatch.
  'is not a valid javascript mime type',
  // Vite's own preload helper, for a stylesheet rather than a script.
  'unable to preload css',
];

/** True when the error is a module that failed to load, rather than a fault in our own code. */
export function isStaleChunkError(error: unknown): boolean {
  const message =
    error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  if (!message) return false;

  const normalized = message.toLowerCase();
  return STALE_CHUNK_SIGNATURES.some((signature) => normalized.includes(signature));
}

/**
 * Reloads the document once to pick up fresh HTML, unless a reload was already attempted recently.
 *
 * @returns true when a reload was started -- the caller should assume the page is going away and
 *          do nothing further. False means the guard declined, and the caller must fall back to
 *          showing the user an error.
 *
 * Storage access is wrapped because it throws outright in some privacy modes rather than returning
 * null. Failing closed there (returning false, no reload) is deliberate: with no way to record that
 * an attempt happened, there is no way to stop a loop, and an un-loopable error panel beats an
 * un-escapable reload cycle.
 */
export function recoverFromStaleChunk(): boolean {
  let store: Storage;
  try {
    store = window.sessionStorage;
    if (!store) return false;
  } catch {
    return false;
  }

  const now = Date.now();

  try {
    const previous = Number(store.getItem(RELOAD_MARKER));
    // Number('') and Number(null) are 0, and Number('nonsense') is NaN -- both mean "no usable
    // previous attempt", and both must fall through to attempting one.
    if (Number.isFinite(previous) && previous > 0 && now - previous < RELOAD_COOLDOWN_MS) {
      return false;
    }
    store.setItem(RELOAD_MARKER, String(now));
  } catch {
    return false;
  }

  window.location.reload();
  return true;
}

// Deliberately NOT exposing a "clear the guard once the app renders successfully" helper, which
// was the first draft of this module and is a loop with extra steps. On a genuinely broken deploy
// the shell renders perfectly well -- it is the LAZY routes that are missing -- so a
// clear-on-render would wipe the marker every time, and each subsequent navigation would reload
// again. The elapsed-time cooldown is the only release, precisely because it cannot be reset by
// the partial success that a broken deploy still produces.
