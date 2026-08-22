import { test, expect } from '../../fixtures/test';
import { createUser } from '../../fixtures/accounts';
import { Api, waitForLearningToSettle } from '../../fixtures/api';
import { learningEventsFor, learningRowsFor, merchantsFor, transactionsFor, count } from '../../fixtures/db';
import type { Row } from '../../fixtures/statements';

/**
 * Phase 10 — multi-user isolation.
 *
 * This milestone made a deliberate product decision (design doc §1.2): there is **no global
 * merchant registry**. Merchants, aliases and learning are per user, and the Merchant Review Center
 * operates within one account. Cross-user intelligence is a separate milestone.
 *
 * That decision is only worth anything if it holds. These tests are the difference between "we
 * scoped it per user" and "it is scoped per user" — and the failure mode is the worst kind, because
 * a leak here means one customer's spending shapes another customer's categorisation, silently and
 * permanently.
 *
 * Two users are created explicitly rather than using the `user` fixture twice, so the test body
 * says which one it is asserting about.
 */

/**
 * Descriptions that both carry an owner's name AND categorise.
 *
 * The name is what makes a leak visible -- "alice" appearing anywhere in Bob's account is
 * unambiguous. The categorisable keyword is what makes learning happen at all: a row the engine
 * cannot categorise falls to "Other" and is deliberately NOT learned (see
 * ImportRuleLearningService.recordDecision), so a fixture of purely invented names would produce an
 * empty learning queue and prove nothing about isolation.
 */
const ALICE_SPEND: Row[] = [
  { date: '2026-06-03', description: 'ALICE SWIGGY ORDER 4471', amount: 4500.0, type: 'DEBIT' },
  { date: '2026-06-04', description: 'ALICE UBER TRIP 8891', amount: 3000.0, type: 'DEBIT' },
];

const BOB_SPEND: Row[] = [
  { date: '2026-06-03', description: 'BOB ZOMATO ORDER 1123', amount: 1200.0, type: 'DEBIT' },
];

test.describe('Phase 10 — one user cannot see or shape another', () => {
  test('transactions, merchants and learning stay in their own account', async () => {
    const alice = await createUser('alice');
    const bob = await createUser('bob');

    await new Api(alice.token).importStatement(ALICE_SPEND, { accountName: "Alice's" });
    await new Api(bob.token).importStatement(BOB_SPEND, { accountName: "Bob's" });

    await waitForLearningToSettle(alice.id, learningEventsFor);
    await waitForLearningToSettle(bob.id, learningEventsFor);

    expect(await transactionsFor(alice.id)).toHaveLength(2);
    expect(await transactionsFor(bob.id)).toHaveLength(1);

    const bobsMerchants = (await merchantsFor(bob.id)).map((m) => m.canonical_name).join(' ');
    expect(bobsMerchants, "Alice's merchants must not exist in Bob's account")
      .not.toMatch(/alice/i);

    const aliceLearning = await learningRowsFor(alice.id);
    const bobLearning = await learningRowsFor(bob.id);
    const shared = aliceLearning.filter((a) => bobLearning.some((b) => b.merchant_id === a.merchant_id));
    expect(shared, 'a learning row reachable from both accounts').toEqual([]);
  });

  /**
   * The API boundary, not just the data model. A per-user schema is no protection if an endpoint
   * will hand over someone else's row to anyone who knows its id.
   */
  test("one user's API token cannot read another user's transactions", async () => {
    const alice = await createUser('alice');
    const bob = await createUser('bob');
    await new Api(alice.token).importStatement(ALICE_SPEND, { accountName: "Alice's" });

    const aliceTransactions = await transactionsFor(alice.id);
    const bobApi = new Api(bob.token);

    const response = await bobApi.getRaw(`/transactions/${aliceTransactions[0].id}`);
    expect(
      response.status,
      "Bob asked for Alice's transaction by id and got something other than a refusal"
    ).toBeGreaterThanOrEqual(400);
  });

  /** Duplicate detection is per user. Two customers buying the same thing on the same day for the
   *  same amount is a coincidence, not a duplicate — and treating it as one would leak the fact
   *  that someone else made that purchase. */
  test('an identical purchase by another user is not a duplicate', async () => {
    const alice = await createUser('alice');
    const bob = await createUser('bob');
    const shared: Row = { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' };

    await new Api(alice.token).importStatement([shared], { accountName: "Alice's" });
    const bobStaging = await new Api(bob.token).stage([shared]);

    expect(bobStaging.data!.staging.flaggedDuplicates).toBe(0);
    expect(bobStaging.data!.staging.rows[0].duplicateMatch).toBeNull();
  });

  /** Learning is per user too: Alice categorising a merchant must not pre-categorise it for Bob.
   *  This is decision §1.2 stated as an assertion. */
  test("one user's categorisation does not reach another user's staging", async () => {
    const alice = await createUser('alice');
    const bob = await createUser('bob');
    // Categorisable (so Alice genuinely learns it) but otherwise unremarkable, so if Bob's staging
    // reports "learned" it can only have come from Alice's account.
    const merchant: Row = {
      date: '2026-06-09', description: 'SWIGGY OBSCURE VENDOR XY7', amount: 555.0, type: 'DEBIT',
    };

    await new Api(alice.token).importStatement([merchant], { accountName: "Alice's" });
    await waitForLearningToSettle(alice.id, learningEventsFor);

    const bobStaging = await new Api(bob.token).stage([merchant]);
    expect(
      bobStaging.data!.staging.rows[0].categorySource,
      "Bob's row was categorised from something learned in Alice's account"
    ).not.toBe('learned');

    expect(await learningRowsFor(bob.id), 'Bob learned nothing merely by staging').toEqual([]);
  });

  /**
   * The admin surfaces are platform-wide by design — that is what makes them useful to an operator
   * — so the assertion is not that Bob is invisible, but that every row says whose it is. An
   * operator acting on the wrong account is the failure this prevents.
   */
  test('admin queues attribute every row to a specific account', async ({ adminApi }) => {
    const alice = await createUser('alice');
    await new Api(alice.token).importStatement(ALICE_SPEND, { accountName: "Alice's" });
    await waitForLearningToSettle(alice.id, learningEventsFor);

    const queue = await adminApi.get<{ content: { userId: string; userEmail: string }[] }>(
      '/admin/learning-queue?page=0&size=50'
    );
    expect(queue.success).toBe(true);
    for (const row of queue.data!.content) {
      expect(row.userId, 'a queue row with no owner is one an operator can act on blindly')
        .toBeTruthy();
      expect(row.userEmail).toBeTruthy();
    }

    const review = await adminApi.get<{ content: { userId: string; userEmail: string }[] }>(
      '/admin/merchant-review?page=0&size=50'
    );
    for (const row of review.data!.content) {
      expect(row.userId).toBeTruthy();
      expect(row.userEmail).toBeTruthy();
    }
  });

  /** An ordinary customer must not reach an operator surface. Asserted rather than assumed — the
   *  permission grants for these endpoints (V63, V64) are new in this milestone. */
  test('a customer cannot reach the operator surfaces', async ({ api }) => {
    for (const path of [
      '/admin/learning-queue?page=0&size=5',
      '/admin/learning-queue/summary',
      '/admin/merchant-review?page=0&size=5',
      '/admin/merchant-review/count',
    ]) {
      const response = await api.getRaw(path);
      expect(response.status, `${path} answered a customer's token`).toBeGreaterThanOrEqual(400);
    }
  });

  /** No request at all. A 401 rather than a 200 with an empty list, which would be a far more
   *  dangerous shape — it reads as "nothing to see" instead of "you are not allowed". */
  test('the operator surfaces refuse an unauthenticated caller', async ({ request }) => {
    const base = process.env.FINORA_E2E_API_ORIGIN ?? 'http://localhost:8081';
    const response = await request.get(`${base}/api/v1/admin/learning-queue?page=0&size=5`);
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });

  /**
   * What a deletion would take with it, and what would block it.
   *
   * There is no account-deletion feature today — accounts are suspended, never removed — so this
   * asserts a property of the schema rather than of a user journey. It is here because that feature
   * is inevitable (an erasure request is not optional), and the schema is currently inconsistent
   * about it in a way nobody would discover until the day they tried.
   *
   * The milestone's own tables cascade correctly. Four others do not, and are listed by name below
   * rather than merely counted, so whoever builds deletion inherits the list instead of finding it
   * one constraint violation at a time.
   */
  test("this milestone's data is removed with the account it belongs to", async () => {
    const doomed = await createUser('ephemeral');
    await new Api(doomed.token).importStatement(ALICE_SPEND, { accountName: 'Temporary' });
    await waitForLearningToSettle(doomed.id, learningEventsFor);

    expect(await transactionsFor(doomed.id)).toHaveLength(2);

    const { query } = await import('../../fixtures/db');
    // Cleared first because these four FKs are NO ACTION rather than CASCADE — see the test below.
    // Doing it here rather than pretending the delete works unaided keeps this test about the
    // milestone's own tables, which is what it can honestly speak to.
    for (const table of ['password_history', 'password_change_sessions', 'category_rules', 'relationships']) {
      await query(`delete from ${table} where user_id = $1`, [doomed.id]);
    }
    await query('delete from users where id = $1', [doomed.id]);

    for (const [label, table] of [
      ['transactions', 'transactions'],
      ['merchants', 'merchants'],
      ['learning rows', 'merchant_category_learning'],
      ['learning events', 'merchant_learning_events'],
      ['statement imports', 'statement_imports'],
      ['import sessions', 'import_sessions'],
      ['accounts', 'accounts'],
    ] as const) {
      expect(
        await count(`select count(*) from ${table} where user_id = $1`, [doomed.id]),
        `${label} outlived the account they belonged to`
      ).toBe(0);
    }
  });

  /**
   * A diagnostic, not a pass/fail on today's behaviour.
   *
   * Every `user_id` foreign key is either CASCADE (the row belongs to the user and should go with
   * them) or SET NULL (an audit entry, which must survive precisely so the deletion itself remains
   * accountable). NO ACTION is neither, and means a plain `DELETE FROM users` fails.
   *
   * This asserts the CURRENT set exactly, so it fails in both directions: if someone adds another
   * non-cascading user_id FK it fails, and when someone fixes one it fails too and has to be
   * updated deliberately. A test that only counted them would let a new one slip in under a fix.
   */
  test('records which user_id foreign keys still block account deletion', async () => {
    const { query } = await import('../../fixtures/db');
    const blocking = await query<{ table_name: string }>(
      `select distinct tc.table_name
         from information_schema.table_constraints tc
         join information_schema.referential_constraints rc on rc.constraint_name = tc.constraint_name
         join information_schema.key_column_usage kcu on kcu.constraint_name = tc.constraint_name
        where tc.constraint_type = 'FOREIGN KEY'
          and kcu.column_name = 'user_id'
          and rc.delete_rule = 'NO ACTION'
        order by 1`
    );

    expect(
      blocking.map((r) => r.table_name),
      'The set of tables that would block deleting a user has changed. If you added one, give it ' +
      'ON DELETE CASCADE unless it is an audit trail (those use SET NULL). If you fixed one, ' +
      'remove it from this list. Account deletion (UserAccountLifecycleService.requestDeletion / ' +
      'AccountPurgeSweepService.purgeOne) never issues a raw DELETE FROM users -- it soft-deletes ' +
      'and anonymizes instead -- so this stays a diagnostic against that scenario, not a ' +
      'regression it has hit yet.'
    ).toEqual([
      'category_rules',
      'password_change_sessions',
      'password_history',
      'phone_change_sessions',
      'relationships',
    ]);
    // payments/referral_codes/subscriptions/wallet_ledger (D-28's billing/wallet/referral
    // tables) were fixed to ON DELETE CASCADE by V106 -- see that migration's own comment for
    // why all four belong to the user rather than needing to survive as an audit trail.
  });
});
