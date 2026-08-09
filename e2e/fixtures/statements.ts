/**
 * Statement files, built rather than committed.
 *
 * No real bank statement is ever checked into this repo — see the import engine's own rules. These
 * are synthetic files whose content each test states inline, which also means a test's fixture is
 * readable next to its assertions instead of in a binary someone has to open to understand.
 */

export interface Row {
  date: string;
  description: string;
  amount: number;
  type: 'DEBIT' | 'CREDIT';
  balance?: number;
}

export function csv(rows: Row[], opts: { withBalance?: boolean } = {}): Buffer {
  const withBalance = opts.withBalance ?? rows.some((r) => r.balance !== undefined);
  const header = withBalance ? 'Date,Description,Amount,Type,Balance' : 'Date,Description,Amount,Type';
  const body = rows.map((r) =>
    withBalance
      ? `${r.date},${r.description},${r.amount.toFixed(2)},${r.type},${(r.balance ?? 0).toFixed(2)}`
      : `${r.date},${r.description},${r.amount.toFixed(2)},${r.type}`
  );
  return Buffer.from([header, ...body].join('\n') + '\n', 'utf-8');
}

/** Header only. Parsing succeeds and finds a table; every row in it is rejected — the case
 *  IMPORT_007 exists to keep separate from "no table found" (IMPORT_001). */
export function emptyStatement(): Buffer {
  return Buffer.from('Date,Description,Amount,Type\n', 'utf-8');
}

/** Bytes that begin like a PDF and then are not one. Exercises the extractor's failure path
 *  rather than the upload guard, which is a different test. */
export function corruptedPdf(): Buffer {
  return Buffer.concat([
    Buffer.from('%PDF-1.4\n', 'ascii'),
    Buffer.from([0x00, 0xff, 0x13, 0x37, 0xde, 0xad, 0xbe, 0xef]),
    Buffer.from('\n%%EOF\n', 'ascii'),
  ]);
}

/** Not a PDF at all, named as one. The extension says one thing and the bytes another, which is
 *  what a user does when they rename a file to get past a picker. */
export function notAPdf(): Buffer {
  return Buffer.from('this is plain text pretending to be a statement', 'utf-8');
}

/** An unsupported type the dropzone should refuse before anything is uploaded. */
export function unsupportedFile(): Buffer {
  return Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0x00]); // a zip header
}

export const FIVE_ROW_STATEMENT: Row[] = [
  { date: '2026-06-03', description: 'SWIGGY ORDER 4471', amount: 486.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'UBER TRIP 8891', amount: 240.0, type: 'DEBIT' },
  { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' },
  { date: '2026-06-08', description: 'BLINKIT GROCERIES 9982', amount: 1240.5, type: 'DEBIT' },
  { date: '2026-06-10', description: 'ZOMATO ORDER 1123', amount: 712.5, type: 'DEBIT' },
];

/** A balance chain that adds up. Opening 5000, each row's balance follows from the last. */
export const CONSISTENT_BALANCE_STATEMENT: Row[] = [
  { date: '2026-06-02', description: 'AMAZON PAY 7781', amount: 1200.0, type: 'DEBIT', balance: 3800.0 },
  { date: '2026-06-03', description: 'SALARY CREDIT', amount: 50000.0, type: 'CREDIT', balance: 53800.0 },
  { date: '2026-06-04', description: 'RENT PAYMENT', amount: 18000.0, type: 'DEBIT', balance: 35800.0 },
];

/** The same statement with one balance wrong, so the chain breaks at a known row. The engine
 *  should say WHERE, not merely that something is off. */
export const BROKEN_BALANCE_STATEMENT: Row[] = [
  { date: '2026-06-02', description: 'AMAZON PAY 7781', amount: 1200.0, type: 'DEBIT', balance: 3800.0 },
  { date: '2026-06-03', description: 'SALARY CREDIT', amount: 50000.0, type: 'CREDIT', balance: 53800.0 },
  { date: '2026-06-04', description: 'RENT PAYMENT', amount: 18000.0, type: 'DEBIT', balance: 30000.0 },
];

/** N distinct merchants, for the large-statement and large-queue phases. Distinct descriptions on
 *  purpose -- a hundred copies of one merchant measures something quite different from a hundred
 *  merchants, and it is the latter that stresses merchant resolution and the learning queue. */
export function manyMerchants(n: number, startDate = '2026-05-01'): Row[] {
  const start = new Date(startDate);
  return Array.from({ length: n }, (_, i) => {
    const date = new Date(start);
    date.setDate(start.getDate() + (i % 28));
    return {
      date: date.toISOString().slice(0, 10),
      description: `MERCHANT ${String(i).padStart(4, '0')} PURCHASE`,
      amount: Math.round((50 + (i % 40) * 13.5) * 100) / 100,
      type: 'DEBIT' as const,
    };
  });
}

/**
 * A real, parseable PDF whose column heading is printed across TWO visual lines.
 *
 * Built here rather than committed, for the same reason every other fixture in this file is: a
 * binary nobody can read is a fixture nobody can check. It is assembled by hand because the e2e
 * workspace has no PDF library and this needs no new dependency to justify — the whole file is a
 * catalog, one page, one font and one uncompressed content stream of positioned `Tj` calls, which
 * is exactly what a bank's own generator emits and exactly what the extractor reads back.
 *
 * The layout is the point. Neither line is a header on its own:
 *
 *   - the UPPER line carries the column names and no date word at all;
 *   - the LOWER line carries "Date" but only two recognised names across seven cells, which is
 *     under the density the engine requires to tell a heading from a sentence.
 *
 * Read one line at a time, this table is invisible and the import succeeds with nothing in it —
 * the failure a real HDFC statement shipped. Read as one heading, it yields "Txn Date",
 * "Transaction Remarks", "Withdrawal Amt" and "Deposit Amt", and its rows stage as transactions.
 *
 * PDF user space puts the origin at the BOTTOM-left with y increasing upward, so rows are laid out
 * by DECREASING y — the 9pt gap between the two heading lines is what makes them one heading, and
 * the 20pt gap to the first transaction is what keeps that transaction out of it.
 */
export function wrappedHeaderPdf(): Buffer {
  const COLUMNS = [56, 120, 180, 300, 370, 440, 510];
  const upper = ['Txn', 'Cheque', 'Transaction', 'Withdrawal', 'Deposit', 'Closing', 'Value'];
  const lower = ['Date', 'No.', 'Remarks', 'Amt', 'Amt', 'Bal', 'Ref'];
  const rows = [
    ['12/01/2026', '000123', 'UPI PAYMENT GROCER', '1,250.00', '', '8,750.00', 'R001'],
    ['14/01/2026', '000124', 'CARD PAYMENT FUEL', '2,000.00', '', '6,750.00', 'R002'],
    ['16/01/2026', '000125', 'SALARY CREDIT', '', '45,000.00', '51,750.00', 'R003'],
    ['18/01/2026', '000126', 'ATM WITHDRAWAL', '500.00', '', '51,250.00', 'R004'],
  ];

  const cells: string[] = [];
  const line = (values: string[], y: number) =>
    values.forEach((v, i) => {
      if (v) cells.push(`BT /F1 9 Tf ${COLUMNS[i]} ${y} Td (${pdfEscape(v)}) Tj ET`);
    });

  line(upper, 770);
  line(lower, 761); // 9pt below: a wrapped heading's second line, not the next row
  rows.forEach((r, i) => line(r, 741 - i * 20)); // 20pt apart: unmistakably separate rows

  return assemblePdf(cells.join('\n'));
}

/** `(`, `)` and `\` end or escape a PDF string literal, so a description containing one would
 *  truncate the content stream and produce a file that is corrupt in a way that looks like a
 *  parser bug. */
function pdfEscape(text: string): string {
  return text.replace(/([\\()])/g, '\\$1');
}

/**
 * The smallest valid PDF that holds one page of positioned text.
 *
 * The cross-reference table is computed from real byte offsets rather than faked. Readers vary in
 * how much they will repair, and a fixture that only works because the extractor happens to be
 * forgiving is a fixture that will fail for a reason that has nothing to do with the test.
 */
function assemblePdf(contentStream: string): Buffer {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] '
      + '/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(contentStream, 'latin1')} >>\nstream\n${contentStream}\nendstream`,
  ];

  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [];
  objects.forEach((body, i) => {
    offsets.push(Buffer.byteLength(pdf, 'latin1'));
    pdf += `${i + 1} 0 obj\n${body}\nendobj\n`;
  });

  const startxref = Buffer.byteLength(pdf, 'latin1');
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((o) => { pdf += `${String(o).padStart(10, '0')} 00000 n \n`; });
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${startxref}\n%%EOF\n`;

  return Buffer.from(pdf, 'latin1');
}
