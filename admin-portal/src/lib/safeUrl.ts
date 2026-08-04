/**
 * Whether `url` is safe to render as a real, clickable <a href> -- i.e. it navigates rather than
 * executes.
 *
 * Bug fix / security hardening: Bank.websiteUrl (backend: BankManagementService.create/update)
 * has no scheme validation at all -- any admin holding BANK_MANAGE can set it to a `javascript:`
 * URL, and it was rendered verbatim as a real <a href> on that bank's Summary tab, reached by
 * EVERY OTHER admin who opens that bank's drawer. Clicking an anchor with a `javascript:` href
 * executes the string as a script in the current page's origin -- a stored XSS one BANK_MANAGE
 * admin could use against any other admin who has that same permission (or broader), including
 * session-token theft via `document.cookie`/localStorage or silently calling admin endpoints as
 * the victim. This is a client-side guard since admin-portal has no backend of its own to add
 * server-side validation to; it must reject the same value whether stored maliciously or
 * corrupted some other way, not just the one BANK_MANAGE form this app happens to submit through.
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
