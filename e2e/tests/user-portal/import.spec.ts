import { test, expect, uploadStatement } from '../../fixtures/test';
import {
  csv, emptyStatement, corruptedPdf, notAPdf, unsupportedFile, manyMerchants, wrappedHeaderPdf,
  FIVE_ROW_STATEMENT, CONSISTENT_BALANCE_STATEMENT, BROKEN_BALANCE_STATEMENT,
} from '../../fixtures/statements';
import { count, transactionsFor } from '../../fixtures/db';

/**
 * Phases 1, 2, 4 and 13 — import, verification, completion, and how it behaves at size.
 *
 * The through-line is the repo's own principle: **never lose information, and never claim more than
 * you know**. A statement that half-parses must say which rows it could not read rather than
 * quietly importing the rest; a document that fails must say what to do about it; and a
 * classification the engine is unsure of must present itself as a question rather than a fact.
 */

test.describe('Phase 1 — statement upload', () => {
  test('a CSV uploads on selection and reaches the review step', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));

    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });
    await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeVisible();
  });

  /**
   * A PDF opens a password panel BEFORE anything is sent. Most Indian bank e-statements arrive
   * protected, so asking first is cheaper than a guaranteed wasted upload of the whole file.
   */
  test('a PDF asks about a password before spending the upload', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await userPage.getByTestId('statement-file-input').setInputFiles({
      name: 'statement.pdf', mimeType: 'application/pdf', buffer: corruptedPdf(),
    });

    await expect(userPage.getByLabel(/statement password/i)).toBeVisible();
    await expect(userPage.getByRole('button', { name: /upload statement/i })).toBeVisible();
  });

  /** A CSV has nothing to unlock, so adding the step there would be pure cost. */
  test('a CSV is not asked for a password', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await userPage.getByTestId('statement-file-input').setInputFiles({
      name: 'statement.csv', mimeType: 'text/csv', buffer: csv(FIVE_ROW_STATEMENT),
    });

    await expect(userPage.getByLabel(/statement password/i)).toBeHidden();
  });

  test('an unsupported file is refused without being uploaded', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await userPage.getByTestId('statement-file-input').setInputFiles({
      name: 'holiday-photos.zip', mimeType: 'application/zip', buffer: unsupportedFile(),
    });

    await expect(userPage.getByText(/csv|pdf|unsupported|only/i).first()).toBeVisible();
    expect(await transactionsFor(user.id), 'a refused file must not reach the ledger').toHaveLength(0);
  });

  /**
   * A document that parses into a table with no usable rows is IMPORT_007, and it is deliberately
   * separate from "no table found" (IMPORT_001) because the follow-up differs. Folding them
   * together is what once let a real statement import as a silent, confirmable no-op.
   */
  test('a statement with no usable rows says so instead of importing nothing',
    async ({ userPage, user, allowConsoleErrors }) => {
      allowConsoleErrors('the rejection is the point -- a 422 logging to the console is correct');
      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'empty.csv', 'text/csv', emptyStatement());

      await expect(userPage.getByText(/no transaction|could not|rejected|empty/i).first())
        .toBeVisible({ timeout: 20_000 });
      expect(await transactionsFor(user.id)).toHaveLength(0);
    });

  /**
   * A damaged file is the user's problem to fix, not a server fault -- the same reasoning that makes
   * IMPORT_008/009 into 422s rather than 500s.
   *
   * This test found the opposite: a corrupted PDF returned INTERNAL_ERROR carrying
   * "Unexpected error: Missing root object specification in trailer." A 500 for a problem the server
   * did not have, and a PDFBox internal handed to a person who cannot act on it. The assertion is
   * therefore on both halves -- an actionable message AND the absence of library internals.
   */
  test('a corrupted PDF is treated as a damaged file, not a server fault',
    async ({ userPage, allowConsoleErrors }) => {
      allowConsoleErrors('a rejected upload logs its 422 to the console, which is correct');
      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'statement.pdf', 'application/pdf', corruptedPdf());

      await expect(userPage.getByText(/damaged or incomplete/i)).toBeVisible({ timeout: 30_000 });
      await expect(userPage.getByText(/downloading it again/i)).toBeVisible();
      // No library internals across the boundary.
      await expect(userPage.getByText(/root object|trailer|Unexpected error/i)).toHaveCount(0);
      await expect(userPage.locator('body')).not.toBeEmpty();
    });

  /** Already handled well: the guard reads the magic bytes rather than trusting the extension, and
   *  the message tells the user what to do instead. Asserted so it stays that way. */
  test('a file that is not a PDF despite its name says so, and says what to do',
    async ({ userPage, allowConsoleErrors }) => {
      allowConsoleErrors('a rejected upload logs its 415 to the console, which is correct');
      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'renamed.pdf', 'application/pdf', notAPdf());

      await expect(userPage.getByText(/this file is not a PDF/i)).toBeVisible({ timeout: 30_000 });
      await expect(userPage.getByText(/upload it as a CSV instead/i)).toBeVisible();
    });

  /**
   * A PDF whose column heading wraps across two visual lines reaches review with its rows, rather
   * than succeeding with nothing in it.
   *
   * This is the end-to-end half of `WRAPPED_HEADER`. The unit tests assert the locator's geometry;
   * this asserts the only thing a user experiences — that the statement arrives at the review step
   * with its transactions, through the real upload, the real parse and the real screen.
   *
   * It is worth an E2E test specifically because of the shape of the failure it guards. A heading
   * the engine cannot read does not raise an error: no table is found, nothing is staged, and the
   * import reports SUCCESS. A real HDFC statement shipped exactly that, and every layer in
   * isolation looked healthy while the user got an empty import. The assertion below is therefore
   * on the rows being present AND on the absence of a "nothing to import" message — because the
   * regression this protects against is silence, and silence passes any test that only checks for
   * an error that never comes.
   *
   * See `wrappedHeaderPdf` for why neither line of that heading is a header on its own.
   */
  test('a PDF whose heading wraps onto a second line reaches review with its rows',
    async ({ userPage }) => {
      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'wrapped-header.pdf', 'application/pdf', wrappedHeaderPdf());

      await expect(userPage.getByText(/4 row\(s\) parsed/i)).toBeVisible({ timeout: 30_000 });
      await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeVisible();

      // The silent-success shape this capability exists to prevent.
      await expect(userPage.getByText(/no transaction table|nothing to import|0 row/i)).toHaveCount(0);

      // Anchored on the merged heading: the dates and amounts land in the right columns, which
      // they only can if the columns came from both lines. Read one line at a time, this table is
      // not found at all and none of these rows exist.
      // Review table renders dates as DD-MMM-YYYY (formatDateDDMMMYYYY), not the raw ISO value.
      const review = userPage.getByRole('table');
      await expect(review.getByText('12-Jan-2026')).toBeVisible();
      await expect(review.getByText('16-Jan-2026')).toBeVisible();
      await expect(review.getByText('₹45000')).toBeVisible();

      /**
       * ACKNOWLEDGED GAP, asserted rather than hidden.
       *
       * Every description arrives EMPTY. The merged heading names this column "Transaction
       * Remarks", and `TransactionNormalizer` resolves the description with
       * `CsvParser.firstNonBlank`, which compares each hint against the WHOLE normalized column
       * name — "transaction remarks" is not "remarks", so it matches nothing and the description
       * silently becomes "". It is the same whole-cell-versus-per-word mismatch already fixed
       * three times in the engine (`isDateColumn`, `isAmountColumn`, `hasDateValue`), reaching a
       * fourth place now that wrapped headings produce compound column names.
       *
       * Not fixed here, and not reachable on any real document yet: across the 18-statement
       * corpus, wrapped headings appear only on deposit schedules, which stage no transactions.
       * It becomes live the first time a bank wraps a heading over a TRANSACTION table, and the
       * failure will be silent — rows import, descriptions are blank, nothing errors. Asserting
       * the emptiness is what makes this test fail loudly on the day someone fixes it, so the fix
       * is noticed rather than absorbed.
       */
      const firstDescription = review.getByRole('row').nth(1).getByRole('cell').nth(2);
      await expect(firstDescription).toHaveText(/^\s*(low confidence)?\s*$/i);
    });
});

test.describe('Phase 2 — verification engine', () => {
  /** A statement whose balances agree should not be dressed up as a problem. Verification that
   *  cries wolf on every import is verification nobody reads. */
  test('a statement that reconciles does not manufacture a warning', async ({ api }) => {
    const staged = await api.stage(CONSISTENT_BALANCE_STATEMENT);
    const findings = staged.data!.staging.verification?.findings ?? [];

    expect(findings.length, 'the engine ran').toBeGreaterThan(0);
    const chain = findings.find((f) => f.rule === 'BALANCE_CHAIN');
    expect(chain?.outcome, 'a consistent chain must verify').toBe('VERIFIED');
  });

  /** When it does break, it must say WHERE — a verdict with no location is not something a user
   *  can check against their own statement. */
  test('a broken balance chain names the row it breaks at', async ({ api }) => {
    const staged = await api.stage(BROKEN_BALANCE_STATEMENT);
    const findings = staged.data!.staging.verification!.findings;

    const chain = findings.find((f) => f.rule === 'BALANCE_CHAIN')!;
    expect(['WARNING', 'FAILED']).toContain(chain.outcome);
    const discrepancies = chain.details.discrepancies as { rowIndex: number }[];
    expect(discrepancies.length).toBeGreaterThan(0);
    expect(discrepancies[0]).toHaveProperty('expectedBalance');
    expect(discrepancies[0]).toHaveProperty('actualBalance');
  });

  /** A rule that cannot apply reports NOT_APPLICABLE rather than passing, because "we did not
   *  check" and "we checked and it was fine" are different things and only one is reassuring. */
  test('a rule with nothing to check reports that, rather than a pass', async ({ api }) => {
    const staged = await api.stage(FIVE_ROW_STATEMENT); // no balance column at all
    const findings = staged.data!.staging.verification!.findings;

    const outcomes = new Set(findings.map((f) => f.outcome));
    expect(outcomes.has('NOT_APPLICABLE'), 'nothing reported itself as inapplicable').toBe(true);
    for (const finding of findings) {
      expect(['VERIFIED', 'WARNING', 'FAILED', 'NOT_APPLICABLE']).toContain(finding.outcome);
    }
  });

  test('the review screen surfaces findings without breaking on an unfamiliar one',
    async ({ userPage }) => {
      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'broken.csv', 'text/csv', csv(BROKEN_BALANCE_STATEMENT));

      await expect(userPage.getByText(/3 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });
      // The panel is present and the page still works -- the Phase 17 console guard on this fixture
      // is what catches a rule the UI has no renderer for.
      await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeVisible();
    });
});

test.describe('Phase 4 — import completion', () => {
  test('the summary reports what actually happened, row by row', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    await expect(userPage.getByText('Transactions imported')).toBeVisible();
    await expect(userPage.getByText(/statement period/i)).toBeVisible();
    expect(await transactionsFor(user.id), 'the summary and the ledger must agree').toHaveLength(5);
  });

  /** Unticking a row must be reported back, not silently absorbed. "5 parsed, 5 imported" when one
   *  was excluded is the shape of a summary nobody can trust. */
  test('rows the user excluded are reported as excluded', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    await userPage.locator('table input[type=checkbox]').first().uncheck();
    await userPage.getByRole('button', { name: /confirm import/i }).click();

    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });
    await expect(userPage.getByText(/left unchecked during review/i)).toBeVisible();
    expect(await transactionsFor(user.id)).toHaveLength(4);
  });

  test('offers a way onward rather than leaving the user on a terminal screen', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });
    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    await expect(userPage.getByRole('button', { name: /import another/i })).toBeVisible();
    await expect(userPage.getByRole('button', { name: /dashboard/i })).toBeVisible();
  });
});

test.describe('Phase 13 — behaviour at size', () => {
  /**
   * A year of statements is roughly this shape, and the largest file this engine has parsed in
   * anger was 569 rows. The threshold is deliberately generous — this is a smoke test for
   * pathological slowness and UI freezing, not a benchmark, and a tight number on a developer
   * laptop would fail for reasons that have nothing to do with the product.
   */
  test('a 300-row statement stages, renders and imports without freezing', async ({ userPage, user }) => {
    const rows = manyMerchants(300);

    await userPage.goto('/app/import');
    const startedAt = Date.now();
    await uploadStatement(userPage, 'large.csv', 'text/csv', csv(rows));
    await expect(userPage.getByText(/300 row\(s\) parsed/i)).toBeVisible({ timeout: 60_000 });
    const stagedIn = Date.now() - startedAt;

    // The page is still interactive, which is the thing a user actually notices.
    await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeEnabled();

    const confirmStartedAt = Date.now();
    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 90_000 });
    const confirmedIn = Date.now() - confirmStartedAt;

    expect(await transactionsFor(user.id)).toHaveLength(300);
    console.log(`[phase 13] 300 rows — staged in ${stagedIn}ms, confirmed in ${confirmedIn}ms`);
    expect(stagedIn, 'staging 300 rows took pathologically long').toBeLessThan(60_000);
    expect(confirmedIn, 'confirming 300 rows took pathologically long').toBeLessThan(90_000);
  });

  /** The duplicate review screen is the one that renders a card per flagged row, so it is where a
   *  large duplicate set turns into an unusable page. This is also why "apply to similar" exists. */
  test('a large duplicate set renders and stays usable', async ({ userPage, api, user }) => {
    const rows = manyMerchants(60);
    await api.importStatement(rows, { accountName: 'Primary' });

    await userPage.goto('/app/import');
    const startedAt = Date.now();
    await uploadStatement(userPage, 'again.csv', 'text/csv', csv(rows));

    const panel = userPage.getByTestId('duplicate-review');
    await expect(panel).toBeVisible({ timeout: 60_000 });
    await expect(panel).toContainText('60 possible duplicates');
    const renderedIn = Date.now() - startedAt;

    // Still responsive: a decision registers immediately rather than after a layout thrash.
    await userPage.getByTestId('duplicate-0').getByRole('button', { name: 'Import anyway' }).click();
    await expect(panel).toContainText(/59 still need a decision/);

    console.log(`[phase 13] 60 duplicates rendered in ${renderedIn}ms`);
    expect(renderedIn).toBeLessThan(60_000);
    expect(await count('select count(*) from transactions where user_id = $1', [user.id]))
      .toBe(60);
  });
});
