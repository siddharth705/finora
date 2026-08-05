/**
 * Whether `url` is safe to render as a real, clickable <a href> -- i.e. it navigates rather than
 * executes.
 *
 * Bug fix / security hardening: Bank.websiteUrl (backend: BankManagementService.create/update)
 * had no scheme validation when this guard was written -- any admin holding BANK_MANAGE could set
 * it to a `javascript:` URL, and it was rendered verbatim as a real <a href> on that bank's
 * Summary tab, reached by EVERY OTHER admin who opens that bank's drawer. Clicking an anchor with
 * a `javascript:` href executes the string as a script in the current page's origin -- a stored
 * XSS one BANK_MANAGE admin could use against any other admin who has that same permission (or
 * broader), including session-token theft via `document.cookie`/localStorage or silently calling
 * admin endpoints as the victim.
 *
 * That sentence is now out of date in one direction and it matters which: the backend DOES
 * validate today. `AccountDto.CreateRequest.websiteUrl` and `UpdateRequest.websiteUrl` both carry
 * `@SafeHttpUrl` (see that annotation's own doc comment). Stated as-is, the comment invited the
 * obvious conclusion -- "the server checks it now, so this is redundant" -- and deleting this
 * would be wrong.
 *
 * This guard stays, for a reason the original wording happened to capture: it must reject the
 * value whether it was stored maliciously or corrupted some other way, not just the one
 * BANK_MANAGE form this app submits through. Server-side validation constrains what NEW writes
 * can store; it says nothing about rows written before `@SafeHttpUrl` existed, and nothing about
 * anything that reaches the column by another route. Rendering is the last point where the value
 * is actually dangerous, so it is the right place for the final check. Two independent layers,
 * deliberately.
 *
 * Restricted to http:/https: -- the only schemes an anchor should ever navigate the browser to
 * for a "bank's website" field. Uses the URL constructor rather than a prefix/regex check:
 * browsers strip tab/newline/other C0 control characters during parsing as part of the URL
 * standard (e.g. "java\tscript:alert(1)" parses to protocol "javascript:"), which is exactly the
 * kind of bypass a naive `url.startsWith('http')` check would miss.
 */
export function isSafeHttpUrl(url: string | null | undefined): boolean {
  if (!url) return false;
  try {
    const parsed = new URL(url, window.location.origin);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}
