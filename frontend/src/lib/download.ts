/**
 * Client-side file downloads, in one place.
 *
 * Two copies of the same download shape existed (endpoints.ts's statementImportsApi.downloadFile
 * and Reports.tsx's downloadCsv), and the second was written by copying the first — its comment
 * says so: "endpoints.ts's statementImportsApi.downloadFile already follows this revoke pattern;
 * this brings Reports.tsx's newer download path in line with it." Both carried the same two
 * defects, so the second inherited them. Sharing the implementation is what stops a third.
 */

/**
 * Hands a blob to the browser as a file download.
 *
 * Two things here are not incidental:
 *
 * 1. The object URL is revoked on a later tick, not in the same synchronous block as click().
 *    click() only INITIATES the download; the browser reads from the blob URL asynchronously
 *    afterwards. Revoking immediately can invalidate the URL before that read finishes, which
 *    fails or truncates the download silently, with nothing surfaced to the user. Small files
 *    usually win the race, which is exactly why this passes casual testing and then bites on a
 *    large statement PDF.
 *
 * 2. The anchor is attached to the document before clicking and removed after. A detached anchor
 *    is not reliably actionable via programmatic click() across browsers.
 */
export function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.style.display = 'none';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // Long enough for the browser to have started reading the blob, short enough that a long-lived
  // SPA session doesn't accumulate object URLs — the leak the immediate revoke was added to fix.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/**
 * Escapes one value for a CSV cell, against BOTH the parser and the spreadsheet.
 *
 * Quoting and doubling embedded quotes handles CSV *parsing*. It does nothing about spreadsheet
 * *formula interpretation*: Excel, LibreOffice and Google Sheets all evaluate a cell whose value
 * begins with `=`, `+`, `-`, `@`, or a leading tab/carriage return as a formula when the file is
 * opened — quoted or not. That matters here because exported values are user-controlled:
 * CategorizationService.resolveOrCreateCategory creates a category from any string supplied
 * through manual entry, bulk recategorize, or import review, and no DTO constrains the name. The
 * same category names then flow into admin-facing platform analytics, so the payload can reach
 * someone other than whoever created it.
 *
 * Prefixing with a single quote is the standard neutralisation: spreadsheets read it as "treat
 * the rest as literal text" and do not display it, while a plain CSV parser sees one extra
 * character.
 *
 * Genuine numbers are deliberately exempt. The dangerous-prefix list includes `-`, and a plain
 * negative amount ("-500") starts with it — guarding those would turn every negative figure in an
 * exported report into text a spreadsheet refuses to sum, which breaks the export for the reason
 * people asked for it. A value that parses as a finite number cannot be a formula, so testing for
 * that first neutralises `-1+1` while leaving `-500` alone.
 */
export function csvCell(value: string | number): string {
  const raw = String(value ?? '');
  const isPlainNumber = raw.trim() !== '' && Number.isFinite(Number(raw));
  const needsFormulaGuard = !isPlainNumber && /^[=+\-@\t\r]/.test(raw);
  const guarded = needsFormulaGuard ? `'${raw}` : raw;
  return `"${guarded.replace(/"/g, '""')}"`;
}

/**
 * Joins rows into CSV text with every cell escaped by {@link csvCell}, prefixed with a UTF-8 byte
 * order mark.
 *
 * Bug 45. Without it, Excel (particularly on Windows) guesses the file's encoding from its bytes
 * alone and defaults to the system codepage rather than UTF-8 -- every non-ASCII character
 * (₹, and any accented or non-Latin merchant/category name, both of which are genuinely
 * user-controlled here, same as csvCell's own formula-injection concern above) renders as mojibake
 * the moment the file is opened. The BOM is invisible everywhere else that reads this CSV: Node,
 * Python, and every spreadsheet application either skip it automatically or treat it as UTF-8's own
 * explicit self-identification.
 */
const UTF8_BOM = '\uFEFF';

export function toCsv(rows: (string | number)[][]): string {
  return UTF8_BOM + rows.map((row) => row.map(csvCell).join(',')).join('\n');
}
