import { describe, it, expect } from 'vitest';
import { isSafeHttpUrl } from './safeUrl';

/**
 * Bug fix / security hardening: Bank.websiteUrl has no scheme validation on the backend -- any
 * BANK_MANAGE admin could set it to a `javascript:` URL, which Banks.tsx used to render verbatim
 * as a real, clickable <a href> to every OTHER admin who opens that bank's Summary tab. Clicking
 * it would execute the value as a script in the admin portal's own origin -- a stored XSS one
 * BANK_MANAGE admin could use against any other admin session. isSafeHttpUrl is the guard that
 * closes this; these tests pin exactly what it accepts and rejects.
 */
describe('isSafeHttpUrl', () => {
  it('accepts a normal https URL', () => {
    expect(isSafeHttpUrl('https://hdfcbank.com')).toBe(true);
  });

  it('accepts a normal http URL', () => {
    expect(isSafeHttpUrl('http://example.com')).toBe(true);
  });

  it('rejects a javascript: URL', () => {
    expect(isSafeHttpUrl('javascript:alert(document.cookie)')).toBe(false);
  });

  it('rejects a javascript: URL disguised with a tab character (a classic filter bypass)', () => {
    // Browsers strip C0 control characters (tabs, newlines) from a URL during parsing, so
    // "java\tscript:..." still resolves to the javascript: scheme -- exactly the kind of bypass a
    // naive `url.startsWith('http')` or regex check would miss, and the URL constructor doesn't.
    expect(isSafeHttpUrl('java\tscript:alert(1)')).toBe(false);
  });

  it('rejects a data: URL', () => {
    expect(isSafeHttpUrl('data:text/html,<script>alert(1)</script>')).toBe(false);
  });

  it('rejects a vbscript: URL', () => {
    expect(isSafeHttpUrl('vbscript:msgbox(1)')).toBe(false);
  });

  it('rejects null, undefined, and the empty string', () => {
    expect(isSafeHttpUrl(null)).toBe(false);
    expect(isSafeHttpUrl(undefined)).toBe(false);
    expect(isSafeHttpUrl('')).toBe(false);
  });
});
