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
