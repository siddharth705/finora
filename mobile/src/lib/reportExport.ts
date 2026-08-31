import { File, Paths } from 'expo-file-system';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import type { ReportData } from '../api/endpoints';
import { fmtCurrency } from './format';

/**
 * Turning a month's report into something the user can keep.
 *
 * The web page does this with a Blob + a synthetic `<a download>` click for CSV and
 * `window.print()` for PDF. Neither exists on native, and a file written into the app's sandbox is
 * invisible to the user anyway -- so both routes end at the system share sheet, which is where
 * "save to Files", "mail it to myself" and AirPrint/Android print actually live. Same reasoning,
 * and the same cache-directory choice, as statementImportsApi.downloadFile.
 *
 * The two builders are pure and separately tested: escaping is the part that breaks silently. A
 * category named `Dining "out"` produces a corrupt CSV column without proper quote doubling, and
 * one named `Rent & Bills <shared>` produces malformed HTML without escaping -- neither throws,
 * both just quietly render wrong.
 *
 * csvCell also has to defend against formula interpretation, not just CSV parsing: category names
 * are user-controlled (CategorizationService.resolveOrCreateCategory takes any string from manual
 * entry, bulk recategorize, or import review), and Excel/Sheets/LibreOffice evaluate a cell value
 * starting with `=`, `+`, `-`, `@`, or a leading tab/carriage return as a formula on open, quoted
 * or not. Mirrors frontend/src/lib/download.ts's csvCell.
 */

function csvCell(value: string): string {
  const isPlainNumber = value.trim() !== '' && Number.isFinite(Number(value));
  const needsFormulaGuard = !isPlainNumber && /^[=+\-@\t\r]/.test(value);
  const guarded = needsFormulaGuard ? `'${value}` : value;
  // RFC 4180: wrap in quotes, and double any quote inside.
  return `"${guarded.replace(/"/g, '""')}"`;
}

export function toCsv(report: ReportData): string {
  const rows: string[][] = [
    ['Month', report.month],
    ['Income', String(report.income)],
    ['Expense', String(report.expense)],
    ['Net', String(report.income - report.expense)],
    [],
    ['Category', 'Amount'],
    ...report.categories.map((c) => [c.category, String(c.amount)]),
  ];
  return rows.map((r) => r.map(csvCell).join(',')).join('\n');
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

export function toPrintableHtml(report: ReportData): string {
  const net = report.income - report.expense;
  const categoryRows = report.categories
    .map(
      (c) =>
        `<tr><td>${escapeHtml(c.category)}</td><td class="num">${escapeHtml(fmtCurrency(c.amount))}</td></tr>`
    )
    .join('');

  // Self-contained: expo-print renders this in a WebView with no network and, on iOS, no access to
  // local asset URLs, so there is nothing to link out to -- styles inline, no images.
  return `<!DOCTYPE html>
<html>
<head><meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" />
<style>
  body { font-family: -apple-system, Roboto, sans-serif; color: #1b1e2b; padding: 24px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #6b7280; font-size: 12px; margin: 0 0 24px; }
  .totals { display: flex; gap: 12px; margin-bottom: 24px; }
  .total { flex: 1; border: 1px solid #e7e8f2; border-radius: 8px; padding: 12px; }
  .total .label { font-size: 10px; text-transform: uppercase; color: #6b7280; }
  .total .value { font-size: 18px; font-weight: 700; margin-top: 4px; }
  .income { color: #16a34a; } .expense { color: #dc2626; }
  h2 { font-size: 12px; text-transform: uppercase; color: #6b7280; margin: 0 0 8px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  td { padding: 8px 0; border-bottom: 1px solid #e7e8f2; }
  .num { text-align: right; }
  .empty { color: #6b7280; font-style: italic; font-size: 13px; }
</style>
</head>
<body>
  <h1>Fynora — ${escapeHtml(report.month)}</h1>
  <p class="sub">Monthly report</p>
  <div class="totals">
    <div class="total"><div class="label">Income</div><div class="value income">${escapeHtml(fmtCurrency(report.income))}</div></div>
    <div class="total"><div class="label">Expense</div><div class="value expense">${escapeHtml(fmtCurrency(report.expense))}</div></div>
    <div class="total"><div class="label">Net</div><div class="value">${escapeHtml(fmtCurrency(net))}</div></div>
  </div>
  <h2>Category Breakdown</h2>
  ${categoryRows ? `<table>${categoryRows}</table>` : '<p class="empty">No expenses recorded this month.</p>'}
</body>
</html>`;
}

async function share(uri: string, mimeType: string, utiType: string, dialogTitle: string) {
  if (!(await Sharing.isAvailableAsync())) {
    throw new Error('Sharing is not available on this device.');
  }
  await Sharing.shareAsync(uri, { mimeType, UTI: utiType, dialogTitle });
}

export async function shareCsv(report: ReportData): Promise<void> {
  const name = `fynora-report-${report.month}.csv`;
  const file = new File(Paths.cache, name);
  // A previous export of the same month leaves the file behind, and create() won't overwrite.
  if (file.exists) file.delete();
  file.create();
  file.write(toCsv(report));
  await share(file.uri, 'text/csv', 'public.comma-separated-values-text', name);
}

export async function sharePdf(report: ReportData): Promise<void> {
  const { uri } = await Print.printToFileAsync({ html: toPrintableHtml(report) });
  await share(uri, 'application/pdf', 'com.adobe.pdf', `fynora-report-${report.month}.pdf`);
}
