import { toCsv, toPrintableHtml } from './reportExport';
import type { ReportData } from '../api/endpoints';

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
