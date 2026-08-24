import { API_BASE } from './config';
import type { Row } from './statements';
import { csv } from './statements';

/**
 * The product's own API, for arranging state a test is not itself about.
 *
 * A duplicate-review test needs a transaction already on the ledger before it can review anything.
 * Driving that first import through the UI would double the runtime and, worse, make the test fail
 * for reasons that have nothing to do with duplicate review. So the ARRANGE step goes through the
 * API and the ACT step goes through the browser — and it is always the API, never SQL, so the
 * arranged state is state the product actually produces.
 */

interface StagedRow {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  suggestedCategory: string;
  categorySource: string;
  ruleId: string | null;
  likelyDuplicate: boolean;
  referenceNumber: string | null;
  balanceAfter: number | null;
  duplicateMatch: DuplicateMatch | null;
}

interface DuplicateMatch {
  existingTransactionId: string;
  existingAccountId: string | null;
  existingDate: string;
  existingDescription: string;
  existingAmount: number;
  existingType: string | null;
  existingImportedAt: string;
  matchCount: number;
  confidence: string;
  reason: string;
}

export interface Envelope<T> {
  success: boolean;
  message: string;
  errorCode: string | null;
  data: T | null;
}

export class Api {
  constructor(private readonly token: string) {}

  private headers(extra: Record<string, string> = {}) {
    return { Authorization: `Bearer ${this.token}`, ...extra };
  }

  async get<T>(path: string): Promise<Envelope<T>> {
    const response = await fetch(`${API_BASE}${path}`, { headers: this.headers() });
    return (await response.json()) as Envelope<T>;
  }

  async post<T>(path: string, body: unknown): Promise<Envelope<T>> {
    const response = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      headers: this.headers({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    });
    return (await response.json()) as Envelope<T>;
  }

  /** Raw status too, for the negative phases where the CODE is the assertion. */
  async postRaw(path: string, body: unknown): Promise<{ status: number; body: Envelope<unknown> }> {
    const response = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      headers: this.headers({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    });
    return { status: response.status, body: (await response.json().catch(() => null)) as Envelope<unknown> };
  }

  async getRaw(path: string): Promise<{ status: number; body: Envelope<unknown> }> {
    const response = await fetch(`${API_BASE}${path}`, { headers: this.headers() });
    return { status: response.status, body: (await response.json().catch(() => null)) as Envelope<unknown> };
  }

  async deleteRaw(path: string): Promise<{ status: number; body: Envelope<unknown> }> {
    const response = await fetch(`${API_BASE}${path}`, { method: 'DELETE', headers: this.headers() });
    return { status: response.status, body: (await response.json().catch(() => null)) as Envelope<unknown> };
  }

  async stage(rows: Row[], fileName = 'statement.csv') {
    const form = new FormData();
    form.append('file', new Blob([csv(rows)], { type: 'text/csv' }), fileName);
    const response = await fetch(`${API_BASE}/import/csv/stage`, {
      method: 'POST',
      headers: this.headers(),
      body: form,
    });
    return (await response.json()) as Envelope<{
      sessionId: string;
      multiAccount: boolean;
      staging: {
        rows: StagedRow[];
        totalParsed: number;
        flaggedDuplicates: number;
        detectedAccount: Record<string, unknown>;
        unparseableRows: unknown[];
        verification: { findings: { rule: string; outcome: string; details: Record<string, unknown> }[] } | null;
      };
    }>;
  }

  /**
   * Imports a statement end to end.
   *
   * @param confirmedNotDuplicate applied to every flagged row — the API-level equivalent of
   *        clicking "Import anyway" on each one. Defaults to false, which is what a client that
   *        never showed a review screen sends.
   */
  async importStatement(
    rows: Row[],
    opts: {
      accountId?: string;
      accountName?: string;
      fileName?: string;
      confirmedNotDuplicate?: boolean;
      includeFlagged?: boolean;
    } = {}
  ) {
    const staged = await this.stage(rows, opts.fileName);
    if (!staged.success || !staged.data) {
      throw new Error(`Staging failed: ${staged.errorCode} ${staged.message}`);
    }

    const includeFlagged = opts.includeFlagged ?? true;
    const payload = {
      sessionId: staged.data.sessionId,
      rows: staged.data.staging.rows.map((r) => ({
        date: r.date,
        description: r.description,
        amount: r.amount,
        type: r.type,
        category: r.suggestedCategory,
        include: r.likelyDuplicate ? includeFlagged : true,
        categorySource: r.categorySource,
        ruleId: r.ruleId,
        likelyDuplicate: r.likelyDuplicate,
        confirmedNotDuplicate: r.likelyDuplicate ? (opts.confirmedNotDuplicate ?? false) : false,
        referenceNumber: r.referenceNumber,
        balanceAfter: r.balanceAfter,
      })),
      existingAccountId: opts.accountId ?? null,
      newAccount: opts.accountId
        ? null
        : { name: opts.accountName ?? 'E2E Account', accountType: 'SAVINGS', openingBalance: 10000 },
      statementOpeningBalance: null,
      statementClosingBalance: null,
    };

    const confirmed = await this.post<{
      imported: number;
      skipped: number;
      duplicatesDetected: number;
      newMerchantsLearned: number;
      accountsCreated: string[];
      account: { id: string; name: string } | null;
    }>('/import/csv/confirm', payload);

    if (!confirmed.success) {
      throw new Error(`Confirm failed: ${confirmed.errorCode} ${confirmed.message}`);
    }
    return { staged: staged.data, summary: confirmed.data! };
  }

  async accounts() {
    return this.get<{ id: string; name: string }[]>('/accounts');
  }

  async dashboard() {
    return this.get<{
      monthlyExpense: number;
      monthlyIncome: number;
      netWorth: number;
      currentBalance: number;
      spendByCategory: Record<string, number>;
    }>('/dashboard/summary');
  }
}

/**
 * Waits until the merchant learning queue has nothing left in flight for this user.
 *
 * Learning is asynchronous by design (WI1) — that is the whole point, and it is why an assertion
 * about learning cannot simply follow the import statement in the test body. Polls the database
 * rather than sleeping a fixed interval, so the test is as fast as the queue is and does not go
 * green because a hard-coded wait happened to be long enough on this machine.
 */
export async function waitForLearningToSettle(
  userId: string,
  learningEventsFor: (id: string) => Promise<{ status: string }[]>,
  timeoutMs = 20_000
): Promise<{ status: string }[]> {
  const deadline = Date.now() + timeoutMs;
  let events: { status: string }[] = [];
  while (Date.now() < deadline) {
    events = await learningEventsFor(userId);
    const inFlight = events.some((e) => e.status === 'PENDING' || e.status === 'PROCESSING');
    if (events.length > 0 && !inFlight) return events;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(
    `Learning queue did not settle within ${timeoutMs}ms. Statuses: ${
      events.map((e) => e.status).join(', ') || '(no events)'
    }`
  );
}
