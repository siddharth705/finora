import { Client } from 'pg';

/**
 * Direct database access for the e2e suite.
 *
 * Two jobs, and it is worth being precise about why each one needs SQL rather than the API.
 *
 * **Seeding.** On a genuinely fresh database there is no way to reach an authenticated state
 * through the product alone. `BootstrapService` creates a pre-verified bootstrap admin, setup
 * promotes a real admin and suspends the bootstrap account in the same transaction, and the new
 * admin does not inherit `phoneVerified`. Locally `FirebaseConfig` returns null when credentials
 * are absent, so `POST /phone/verify` answers "Phone verification is not configured on this
 * server." The only account that can reach admin endpoints cannot verify; the only pre-verified
 * account is suspended. That is Issue 01 in E2E_TEST_REPORT.md, and until it is fixed, a test
 * fixture has to do in SQL exactly what Firebase would have done — nothing more.
 *
 * **Assertion.** The brief's Phase 15 asks the suite to cross-check the database against the UI,
 * and Phase 7 asks whether an imported transaction is *counted*, not merely *displayed*. Neither
 * question can be answered from the DOM. A workflow that looks correct while writing wrong
 * financial data is still a failed test, and the only way to catch that is to look at the rows.
 *
 * Everything else goes through the real API or the real UI. The rule this file holds to: SQL may
 * set up what the product cannot, and may observe anything, but it never performs a step the test
 * is meant to be exercising.
 */

const CONNECTION = process.env.FINORA_E2E_DB_URL
  ?? 'postgresql://finora:finora@localhost:5433/finora';

/** One-shot query. A pool would be faster; a fresh client per call is simpler and cannot leak
 *  a half-open connection across a worker that Playwright tore down mid-test. */
export async function query<T = Record<string, unknown>>(
  sql: string,
  params: unknown[] = []
): Promise<T[]> {
  const client = new Client({ connectionString: CONNECTION });
  await client.connect();
  try {
    const result = await client.query(sql, params);
    return result.rows as T[];
  } finally {
    await client.end();
  }
}

export async function one<T = Record<string, unknown>>(
  sql: string,
  params: unknown[] = []
): Promise<T | null> {
  const rows = await query<T>(sql, params);
  return rows[0] ?? null;
}

/** A single scalar, already unwrapped -- most integrity checks are counts and sums. */
async function scalar<T = string>(sql: string, params: unknown[] = []): Promise<T | null> {
  const row = await one<Record<string, T>>(sql, params);
  if (!row) return null;
  const values = Object.values(row);
  return values.length ? values[0] : null;
}

export async function count(sql: string, params: unknown[] = []): Promise<number> {
  return Number(await scalar<string>(sql, params) ?? 0);
}

/** Money, as a number, with null read as zero -- `sum()` over no rows is NULL in SQL, and a test
 *  asserting "nothing was counted" wants 0 rather than a null that compares false against it. */
async function money(sql: string, params: unknown[] = []): Promise<number> {
  return Number(await scalar<string>(sql, params) ?? 0);
}

export async function databaseReachable(): Promise<boolean> {
  try {
    await query('select 1');
    return true;
  } catch {
    return false;
  }
}

/**
 * What every spend calculation actually sees.
 *
 * `is_duplicate_of IS NULL` is the filter shared by BudgetService, AnalyticsService,
 * DashboardService, InsightsService, RecurringService, ReportService and two TransactionRepository
 * aggregates. Asserting against this rather than against one endpoint's response is deliberate:
 * it is the single condition all seven agree on, so a row that passes here is counted everywhere,
 * and one that fails here is missing from everywhere.
 */
export async function countedExpense(userId: string): Promise<number> {
  return money(
    `select coalesce(sum(amount), 0) from transactions
      where user_id = $1 and txn_type = 'EXPENSE' and is_duplicate_of is null`,
    [userId]
  );
}

/** Everything on the ledger, counted or not. The gap between this and countedExpense is exactly
 *  the money the user can see but the product refuses to add up. */
export async function ledgerExpense(userId: string): Promise<number> {
  return money(
    `select coalesce(sum(amount), 0) from transactions
      where user_id = $1 and txn_type = 'EXPENSE'`,
    [userId]
  );
}

export interface TransactionRow {
  id: string;
  txn_date: string;
  description: string;
  amount: string;
  merchant: string | null;
  is_duplicate_of: string | null;
  not_duplicate_confirmed_at: string | null;
  reconciliation_status: string;
}

export async function transactionsFor(userId: string, description?: string) {
  return query<TransactionRow>(
    `select id, txn_date, description, amount, merchant, is_duplicate_of,
            not_duplicate_confirmed_at, reconciliation_status
       from transactions
      where user_id = $1 ${description ? 'and description = $2' : ''}
      order by created_at`,
    description ? [userId, description] : [userId]
  );
}

export interface LearningEventRow {
  id: string;
  status: string;
  attempt_count: number;
  merchant_id: string;
  category_id: string;
  source_statement_import_id: string | null;
  source_import_session_id: string | null;
  last_error: string | null;
}

export async function learningEventsFor(userId: string) {
  return query<LearningEventRow>(
    `select id, status, attempt_count, merchant_id, category_id,
            source_statement_import_id, source_import_session_id, last_error
       from merchant_learning_events where user_id = $1 order by created_at`,
    [userId]
  );
}

export async function merchantsFor(userId: string) {
  return query<{ id: string; canonical_name: string; lifecycle_status: string }>(
    `select id, canonical_name, lifecycle_status from merchants
      where user_id = $1 order by created_at`,
    [userId]
  );
}

export async function learningRowsFor(userId: string) {
  return query<{ merchant_id: string; category_id: string; confidence: number; confirmation_count: number }>(
    `select merchant_id, category_id, confidence, confirmation_count
       from merchant_category_learning where user_id = $1`,
    [userId]
  );
}
