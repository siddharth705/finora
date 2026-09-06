/**
 * Hands a blob to the browser as a file download.
 *
 * Ported from frontend/src/lib/download.ts's identical helper -- two things here are not
 * incidental, and this app's first blob download (the held-statement document) would otherwise be
 * one keystroke away from reintroducing both defects that file's own doc comment already records:
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
  // SPA session doesn't accumulate object URLs -- the leak the immediate revoke was added to fix.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
