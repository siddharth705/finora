import { test, expect } from '../../fixtures/test';
import { waitForLearningToSettle } from '../../fixtures/api';
import {
  learningEventsFor, learningRowsFor, merchantsFor, transactionsFor, query, count,
} from '../../fixtures/db';
import type { Row } from '../../fixtures/statements';

/**
 * Phase 9 — the temporary merchant lifecycle, and the learning that hangs off it.
 *
 * The milestone's claim is that an import never blocks on learning and learning never costs an
 * import. Before WI1, one lost race against `UNIQUE(user_id, merchant_id, category_id)` rolled back
 * every transaction in a statement the user had already reviewed and approved. Learning is now
 * queued and applied after commit — which means an assertion about learning cannot simply follow
 * the import in the test body, and `waitForLearningToSettle` polls rather than sleeps so a green
 * result means the queue drained, not that a hard-coded wait happened to be long enough.
 *
 * Unknown merchants land as TEMPORARY (WI4) so staging can stay read-only (WI3) — there has to be
 * somewhere for a guess to go that is visibly a guess.
 */

/**
 * Merchants the engine has never seen but CAN categorise -- these match category rules, so the
 * import resolves them confidently and queues learning.
 *
 * The distinction matters and is easy to get wrong. A row the engine could not categorise falls to
 * "Other" with categorySource "default", and `ImportRuleLearningService.recordDecision` marks it an
 * unresolved guess and queues NO learning. That is correct: learning a guess would poison every
 * future statement, so the product asks the user first (the Dashboard review card) instead. A test
 * fixture of genuinely unrecognisable descriptions therefore produces zero learning events, and
 * reads as a broken queue when it is a working one. See the explicit test at the end of this block.
 */
// Not Swiggy/Uber: MerchantSeedService now seeds every new user with 34 curated brand merchants
// (APPROVED, not TEMPORARY) at registration, including both of those, specifically so that a real
// transaction naming them resolves to the seeded row instead of minting a guess -- which is exactly
// the behaviour this file's "unknown" fixture needs to NOT trigger. Starbucks/Airtel are real
// CategoryRules keywords (Dining, Utilities) that stay off the curated list, so the import still
// resolves a confident category and queues learning, but the merchant identity is genuinely new.
const UNKNOWN: Row[] = [
  { date: '2026-06-03', description: 'STARBUCKS COFFEE 2291', amount: 640.0, type: 'DEBIT' },
  { date: '2026-06-04', description: 'AIRTEL RECHARGE 88817', amount: 300.0, type: 'DEBIT' },
];

/** Descriptions nothing can categorise -- used only where "the engine had to guess" is the point. */
const UNCATEGORISABLE: Row[] = [
  { date: '2026-06-05', description: 'KIRANA STORE MG ROAD', amount: 640.0, type: 'DEBIT' },
];

test.describe('Phase 9 — temporary merchant lifecycle', () => {
  test('an unknown merchant arrives as a guess, clearly marked as one', async ({ api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary' });

    // Scoped to TEMPORARY, not merchantsFor(user.id) as a whole -- every fresh user already has 34
    // curated merchants from registration (MerchantSeedService), APPROVED by design, not guesses.
    // The claim under test is about what THIS import produced, not about every row the user owns.
    const guessed = (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'TEMPORARY');
    expect(
      guessed.length,
      'a merchant the engine invented must not present itself as confirmed'
    ).toBeGreaterThanOrEqual(2);
  });

  test('learning is applied after the import commits, not during it', async ({ api, user }) => {
    const { summary } = await api.importStatement(UNKNOWN, { accountName: 'Primary' });

    // The import is already complete and its transactions are already durable at this point.
    expect(summary.imported).toBe(2);
    expect(await transactionsFor(user.id)).toHaveLength(2);

    const events = await waitForLearningToSettle(user.id, learningEventsFor);
    expect(events).toHaveLength(2);
    expect(events.every((e) => e.status === 'COMPLETED')).toBe(true);

    const learned = await learningRowsFor(user.id);
    expect(learned.length).toBe(2);
  });

  /** Every event carries the statement and session that earned it, so an operator can answer
   *  "which import produced this" without a join through the merchant (WI2's requirement). */
  test('every learning event is attributable to the import that produced it', async ({ api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary', fileName: 'june.csv' });
    await waitForLearningToSettle(user.id, learningEventsFor);

    const events = await learningEventsFor(user.id);
    expect(events.length).toBeGreaterThan(0);
    for (const event of events) {
      expect(event.source_statement_import_id, 'no statement id — the queue row is a dead end')
        .not.toBeNull();
      expect(event.source_import_session_id, 'no session id — the correlation chain is broken')
        .not.toBeNull();
    }
  });

  /** Re-importing a merchant the user already has strengthens the existing learning rather than
   *  duplicating it — the confirmation count is the whole point of "ask once, learn forever". */
  test('a repeat of a known merchant strengthens learning instead of duplicating it',
    async ({ api, user }) => {
      const { summary } = await api.importStatement(UNKNOWN, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);
      const before = await learningRowsFor(user.id);

      await api.importStatement(
        [{ ...UNKNOWN[0], date: '2026-07-03' }],
        { accountId: summary.account?.id }
      );
      await waitForLearningToSettle(user.id, learningEventsFor);

      const after = await learningRowsFor(user.id);
      expect(after.length, 'no new learning row for a merchant already known').toBe(before.length);

      // The merchant that was re-imported specifically, identified by name rather than by taking
      // whichever row happens to come back first -- both merchants appear in `before`, so a
      // positional lookup would as often as not assert about the one that did not change.
      const merchants = await merchantsFor(user.id);
      const repeated = merchants.find((m) => /starbucks/i.test(m.canonical_name))!;
      const row = after.find((r) => r.merchant_id === repeated.id)!;
      expect(row.confirmation_count, 'a second sighting must strengthen what is already known')
        .toBeGreaterThan(1);
    });

  /**
   * Staging must write nothing (WI3). Asserted by counting rows before and after, not by verifying
   * that a mock was not called — the mock version of this test passed for as long as it existed
   * while the real code wrote merchants during preview.
   */
  test('previewing a statement full of unknown merchants writes nothing', async ({ api, user }) => {
    // Scoped to this user, not platform-wide. Staging can only ever write rows belonging to the
    // caller, so a per-user count answers the same question -- and a global one answers a different
    // one badly, because a parallel test writing its own rows between the two reads looks exactly
    // like the leak this is hunting for.
    const snapshot = async () => ({
      merchants: await count('select count(*) from merchants where user_id = $1', [user.id]),
      aliases: await count(
        `select count(*) from merchant_aliases a
          join merchants m on m.id = a.merchant_id where m.user_id = $1`, [user.id]),
      learning: await count('select count(*) from merchant_category_learning where user_id = $1', [user.id]),
      transactions: await count('select count(*) from transactions where user_id = $1', [user.id]),
      events: await count('select count(*) from merchant_learning_events where user_id = $1', [user.id]),
    });
    const before = await snapshot();

    const staged = await api.stage([
      { date: '2026-06-20', description: 'NYKAA FASHION LTD 55231', amount: 1899.0, type: 'DEBIT' },
      { date: '2026-06-21', description: 'CULTFIT GYM MEMBERSHIP', amount: 2400.0, type: 'DEBIT' },
    ]);
    expect(staged.success).toBe(true);

    expect(await snapshot(), 'staging wrote to the ledger').toEqual(before);
  });

  /**
   * A merchant with transactions attributed to it cannot be discarded, because discarding would
   * strand them. The product must refuse rather than cascade — an operator tidying a queue should
   * not be able to detach a user's spending history by clicking the wrong button.
   */
  test('a merchant with transactions cannot be discarded', async ({ adminApi, api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary' });
    // Not merchants[0] -- created_at ordering puts the 34 registration-seeded curated merchants
    // first, and they have no transactions until something imports one of their own brand names.
    const merchants = await merchantsFor(user.id);
    const withTransactions = merchants.find((m) => m.lifecycle_status === 'TEMPORARY')!;

    const response = await adminApi.deleteRaw(
      `/admin/merchant-review/users/${user.id}/merchants/${withTransactions.id}`
    );

    expect(response.status, 'a refusal, not a cascade').toBeGreaterThanOrEqual(400);
    expect(await merchantsFor(user.id)).toHaveLength(merchants.length);
    expect(await transactionsFor(user.id), 'nothing detached').toHaveLength(2);
  });

  test('approving a merchant confirms it and leaves its learning intact',
    async ({ adminApi, api, user }) => {
      await api.importStatement(UNKNOWN, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);
      const before = await learningRowsFor(user.id);
      // Not [0] -- see the "cannot be discarded" test above for why created_at ordering picks a
      // registration-seeded curated merchant instead of this import's guess.
      const merchant = (await merchantsFor(user.id)).find((m) => m.lifecycle_status === 'TEMPORARY')!;

      const response = await adminApi.post(
        `/admin/merchant-review/users/${user.id}/merchants/${merchant.id}/approve`, {}
      );
      expect(response.success).toBe(true);

      const after = (await merchantsFor(user.id)).find((m) => m.id === merchant.id)!;
      expect(after.lifecycle_status).toBe('APPROVED');
      expect(await learningRowsFor(user.id), 'approving is a confirmation, not a reset')
        .toHaveLength(before.length);

      // The decision is auditable. An operator action on someone else's data that leaves no trace
      // is not a governed action.
      const audits = await count(
        `select count(*) from audit_logs where action = 'MERCHANT_APPROVED'`
      );
      expect(audits).toBeGreaterThan(0);
    });

  /**
   * Merging repoints transactions, aliases and learning rather than deleting them. The brief calls
   * this out specifically, and it is the operation with the most to lose: a merge that dropped
   * history would silently rewrite a user's past.
   */
  test('merging preserves transactions, aliases and learning', async ({ adminApi, api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary' });
    await waitForLearningToSettle(user.id, learningEventsFor);

    // Filtered to TEMPORARY, not the raw list -- see the "cannot be discarded" test above for why
    // an unfiltered merchantsFor() no longer lines up with "the merchants this import created".
    const merchants = (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'TEMPORARY');
    const [absorbed, surviving] = merchants;

    // A merge target has to be approved first — you cannot fold one guess into another.
    await adminApi.post(`/admin/merchant-review/users/${user.id}/merchants/${surviving.id}/approve`, {});

    const transactionsBefore = await transactionsFor(user.id);
    const aliasesBefore = await count('select count(*) from merchant_aliases where merchant_id = $1', [absorbed.id]);

    const response = await adminApi.post(
      `/admin/merchant-review/users/${user.id}/merchants/${absorbed.id}/merge`,
      { survivingMerchantId: surviving.id }
    );
    expect(response.success, `merge failed: ${response.errorCode} ${response.message}`).toBe(true);

    expect(await transactionsFor(user.id), 'no transaction may be lost to a merge')
      .toHaveLength(transactionsBefore.length);

    const orphaned = await count(
      `select count(*) from transactions t
        where t.user_id = $1 and t.merchant_id is not null
          and not exists (select 1 from merchants m where m.id = t.merchant_id)`,
      [user.id]
    );
    expect(orphaned, 'a transaction pointing at a merchant that no longer exists').toBe(0);

    const aliasesAfter = await count('select count(*) from merchant_aliases where merchant_id = $1', [surviving.id]);
    expect(aliasesAfter, 'the absorbed merchant\'s aliases moved rather than vanished')
      .toBeGreaterThanOrEqual(aliasesBefore);

    const learningOrphans = await count(
      `select count(*) from merchant_category_learning l
        where l.user_id = $1
          and not exists (select 1 from merchants m where m.id = l.merchant_id)`,
      [user.id]
    );
    expect(learningOrphans).toBe(0);
  });

  /**
   * The "ask once, learn forever" contract, stated from its other side: the engine must not learn
   * something it never actually resolved. A row that fell through to "Other" is a question for the
   * user, not a fact to remember -- learning it would make one bad guess permanent and silent.
   */
  test('a category the engine had to guess is not learned', async ({ api, user }) => {
    await api.importStatement(UNCATEGORISABLE, { accountName: 'Primary' });

    // The merchant still exists (it has transactions attached) and is still marked as a guess.
    // Scoped to TEMPORARY, not merchantsFor(user.id) as a whole -- see the first test in this
    // file for why: every fresh user already owns 34 curated, APPROVED merchants from registration.
    const guessed = (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'TEMPORARY');
    expect(guessed.length).toBeGreaterThan(0);

    // But nothing was learned from it, and nothing was queued to be.
    expect(await learningEventsFor(user.id), 'a guess must not enter the learning queue').toEqual([]);
    expect(await learningRowsFor(user.id)).toEqual([]);

    // And the row is marked for the user to resolve rather than quietly filed away.
    const flagged = await count(
      'select count(*) from transactions where user_id = $1 and needs_category_review = true',
      [user.id]
    );
    expect(flagged, 'an unresolved guess the user is never asked about').toBeGreaterThan(0);
  });

  test('renaming corrects the guess without disturbing what it is attached to',
    async ({ adminApi, api, user }) => {
      await api.importStatement(UNKNOWN, { accountName: 'Primary' });
      // Not [0] -- see the "cannot be discarded" test above for why created_at ordering picks a
      // registration-seeded curated merchant instead of this import's guess.
      const merchant = (await merchantsFor(user.id)).find((m) => m.lifecycle_status === 'TEMPORARY')!;
      const attached = await count(
        'select count(*) from transactions where merchant_id = $1', [merchant.id]
      );

      const response = await adminApi.post(
        `/admin/merchant-review/users/${user.id}/merchants/${merchant.id}/rename`,
        { canonicalName: 'Kirana Store (MG Road)' }
      );
      expect(response.success).toBe(true);

      const renamed = (await merchantsFor(user.id)).find((m) => m.id === merchant.id)!;
      expect(renamed.canonical_name).toBe('Kirana Store (MG Road)');
      expect(
        await count('select count(*) from transactions where merchant_id = $1', [merchant.id]),
        'renaming is not re-attributing'
      ).toBe(attached);
    });
});

test.describe('Phase 15 — data integrity', () => {
  /**
   * Cross-checks the shapes that a partial failure would leave behind. Each of these is a state the
   * system should be incapable of reaching, which is exactly why they are worth asserting: nobody
   * writes a test for the state they meant to create.
   */
  test('a full import leaves no orphans and no dangling references', async ({ api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary', fileName: 'integrity.csv' });
    await waitForLearningToSettle(user.id, learningEventsFor);

    const checks: [string, string, unknown[]][] = [
      ['transaction pointing at a missing merchant',
       `select count(*) from transactions t where t.user_id = $1 and t.merchant_id is not null
          and not exists (select 1 from merchants m where m.id = t.merchant_id)`, [user.id]],
      ['transaction pointing at a missing account',
       `select count(*) from transactions t where t.user_id = $1
          and not exists (select 1 from accounts a where a.id = t.account_id)`, [user.id]],
      ['transaction pointing at a missing statement import',
       `select count(*) from transactions t where t.user_id = $1 and t.statement_import_id is not null
          and not exists (select 1 from statement_imports s where s.id = t.statement_import_id)`, [user.id]],
      ['is_duplicate_of pointing at a transaction that no longer exists',
       `select count(*) from transactions t where t.user_id = $1 and t.is_duplicate_of is not null
          and not exists (select 1 from transactions o where o.id = t.is_duplicate_of)`, [user.id]],
      ['learning event pointing at a missing merchant',
       `select count(*) from merchant_learning_events e where e.user_id = $1
          and not exists (select 1 from merchants m where m.id = e.merchant_id)`, [user.id]],
      ['learning row for a merchant that does not exist',
       `select count(*) from merchant_category_learning l where l.user_id = $1
          and not exists (select 1 from merchants m where m.id = l.merchant_id)`, [user.id]],
      ['alias for a merchant that does not exist',
       `select count(*) from merchant_aliases a
          where not exists (select 1 from merchants m where m.id = a.merchant_id)`, []],
    ];

    for (const [label, sql, params] of checks) {
      expect(await count(sql, params), label).toBe(0);
    }
  });

  /** A merchant marked TEMPORARY that has no transactions and no aliases is a leak: something
   *  created it and nothing is using it. Scoped to this user so other tests cannot make it noisy.
   *
   *  Scoped to lifecycle_status = 'TEMPORARY' in the query itself, not just in this comment --
   *  MerchantSeedService now gives every new user 34 curated, APPROVED merchants at registration,
   *  unattached until a real transaction first matches one of their names. That is the intended
   *  shape of the catalog, not a leak: nothing "created" those as a guess, so their being unattached
   *  proves nothing about whether a guess ever goes unclaimed. The TEMPORARY filter is what makes
   *  this assertion mean what its own name says. */
  test('no merchant is created without something attaching to it', async ({ api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary' });

    const stranded = await query<{ canonical_name: string }>(
      `select m.canonical_name from merchants m
        where m.user_id = $1
          and m.lifecycle_status = 'TEMPORARY'
          and not exists (select 1 from transactions t where t.merchant_id = m.id)
          and not exists (select 1 from merchant_aliases a where a.merchant_id = m.id)`,
      [user.id]
    );
    expect(stranded.map((m) => m.canonical_name)).toEqual([]);
  });

  /** Every confirmed import must have left a statement_imports row — it is what backs Statement
   *  History and re-import, and an import that produced transactions but no record of itself is a
   *  ledger nobody can trace. */
  test('every import records itself', async ({ api, user }) => {
    await api.importStatement(UNKNOWN, { accountName: 'Primary', fileName: 'traceable.csv' });

    const statements = await count(
      'select count(*) from statement_imports where user_id = $1', [user.id]
    );
    expect(statements).toBe(1);

    const untraceable = await count(
      `select count(*) from transactions where user_id = $1 and statement_import_id is null
         and source = 'CSV_IMPORT'`,
      [user.id]
    );
    expect(untraceable, 'an imported transaction that cannot say where it came from').toBe(0);
  });
});
