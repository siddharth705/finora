import { File, Paths } from 'expo-file-system';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { shareCsv, sharePdf, toCsv, toPrintableHtml } from './reportExport';
import type { ReportData } from '../api/endpoints';

jest.mock('expo-print', () => ({ printToFileAsync: jest.fn() }));
jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: jest.fn().mockResolvedValue(undefined),
}));

const report: ReportData = {
  month: '2026-07',
  income: 82000,
  expense: 51500,
  categories: [
    { category: 'Groceries', amount: 12000 },
    { category: 'Dining "out"', amount: 8400 },
    { category: 'Rent & Bills <shared>', amount: 31100 },
  ],
};

describe('toCsv', () => {
  it('includes the totals and every category row', () => {
    const lines = toCsv(report).split('\n');
    expect(lines[0]).toBe('"Month","2026-07"');
    expect(lines).toContain('"Income","82000"');
    expect(lines).toContain('"Net","30500"');
    expect(lines).toContain('"Groceries","12000"');
    expect(lines).toHaveLength(9); // 4 totals + blank + header + 3 categories
  });

  // Without quote doubling this row ends the quoted field early and every column after it shifts,
  // which no error surfaces -- the file just opens wrong in a spreadsheet.
  it('doubles quotes inside a value (RFC 4180)', () => {
    expect(toCsv(report)).toContain('"Dining ""out""","8400"');
  });

  it('keeps commas inside a value from splitting the row', () => {
    const csv = toCsv({ ...report, categories: [{ category: 'Food, drink', amount: 100 }] });
    const row = csv.split('\n').find((l) => l.startsWith('"Food'));
    expect(row).toBe('"Food, drink","100"');
  });

  it('handles a month with no expenses', () => {
    const csv = toCsv({ month: '2026-07', income: 0, expense: 0, categories: [] });
    expect(csv).toContain('"Category","Amount"');
    expect(csv).not.toContain('undefined');
  });

  // Category names are user-controlled and flow into a file a spreadsheet may later open. Without
  // this guard, a category named e.g. `=HYPERLINK(...)` would execute as a formula on open instead
  // of displaying as text.
  it('neutralises a category name that starts with a formula-triggering character', () => {
    const csv = toCsv({
      ...report,
      categories: [{ category: '=HYPERLINK("http://evil.example","click")', amount: 100 }],
    });
    const row = csv.split('\n').find((l) => l.includes('HYPERLINK'));
    expect(row).toBe('"\'=HYPERLINK(""http://evil.example"",""click"")","100"');
  });

  // A leading space before the formula-triggering character must not slip past the guard --
  // spreadsheet apps can still evaluate a leading-space-then-formula value as a formula on import.
  it('neutralises a category name with leading whitespace before a formula-triggering character', () => {
    const csv = toCsv({
      ...report,
      categories: [{ category: " =cmd|'/c calc'!A0", amount: 100 }],
    });
    const row = csv.split('\n').find((l) => l.includes('cmd'));
    expect(row).toBe(`"' =cmd|'/c calc'!A0","100"`);
  });

  // A lone leading tab or carriage return is itself a formula trigger (not just whitespace to
  // skip past) -- stripping it before the regex check, e.g. via a blanket trimStart(), would
  // silently defeat the guard for this input shape.
  it('neutralises a category name starting with a tab', () => {
    const csv = toCsv({ ...report, categories: [{ category: '\tDining', amount: 100 }] });
    const row = csv.split('\n').find((l) => l.includes('Dining') && l.includes('100'));
    expect(row).toBe(`"'\tDining","100"`);
  });

  it('does not mangle a genuine negative amount', () => {
    const csv = toCsv({ ...report, categories: [{ category: 'Refund', amount: -500 }] });
    expect(csv).toContain('"Refund","-500"');
  });
});

describe('toPrintableHtml', () => {
  it('renders the totals and category rows', () => {
    const html = toPrintableHtml(report);
    expect(html).toContain('2026-07');
    expect(html).toContain('Groceries');
    expect(html).toContain('Category Breakdown');
  });

  // A category containing < or & would otherwise produce malformed markup that renders as a
  // truncated or mangled row rather than failing loudly.
  it('escapes HTML-significant characters in category names', () => {
    const html = toPrintableHtml(report);
    expect(html).toContain('Rent &amp; Bills &lt;shared&gt;');
    expect(html).not.toContain('<shared>');
    expect(html).toContain('Dining &quot;out&quot;');
  });

  it('says so plainly when there are no expenses, rather than printing an empty table', () => {
    const html = toPrintableHtml({ month: '2026-07', income: 5000, expense: 0, categories: [] });
    expect(html).toContain('No expenses recorded this month.');
    expect(html).not.toContain('<table>');
  });

  // useMarkupFormatter aside, expo-print's own docs warn that HTML without a doctype can produce a
  // blank trailing page.
  it('declares a doctype', () => {
    expect(toPrintableHtml(report).startsWith('<!DOCTYPE html>')).toBe(true);
  });
});

/**
 * D2 (Track D security cleanup). The exported file exists on disk only to hand the OS share
 * sheet a real URI -- once shareAsync settles, it has to go, whichever of the two builders wrote
 * it and whether the share itself succeeded or threw.
 */
describe('shareCsv / sharePdf clean up the cache file after sharing', () => {
  const shareAsync = Sharing.shareAsync as jest.Mock;
  const printToFileAsync = Print.printToFileAsync as jest.Mock;

  beforeEach(() => {
    shareAsync.mockReset().mockResolvedValue(undefined);
  });

  it('shareCsv deletes its own cache file after a successful share', async () => {
    await shareCsv(report);

    const file = new File(Paths.cache, `fynora-report-${report.month}.csv`);
    expect(file.exists).toBe(false);
  });

  it('shareCsv still deletes its cache file when the share itself throws', async () => {
    shareAsync.mockRejectedValue(new Error('share sheet dismissed with an error'));

    await expect(shareCsv(report)).rejects.toThrow();

    const file = new File(Paths.cache, `fynora-report-${report.month}.csv`);
    expect(file.exists).toBe(false);
  });

  it('sharePdf deletes the file expo-print wrote after a successful share', async () => {
    const printed = new File(Paths.cache, 'printed-report.pdf');
    printed.create();
    printed.write('%PDF-fake');
    printToFileAsync.mockResolvedValue({ uri: printed.uri });

    await sharePdf(report);

    expect(printed.exists).toBe(false);
  });

  it('sharePdf still deletes the printed file when the share itself throws', async () => {
    const printed = new File(Paths.cache, 'printed-report-2.pdf');
    printed.create();
    printed.write('%PDF-fake');
    printToFileAsync.mockResolvedValue({ uri: printed.uri });
    shareAsync.mockRejectedValue(new Error('share sheet dismissed with an error'));

    await expect(sharePdf(report)).rejects.toThrow();

    expect(printed.exists).toBe(false);
  });
});
