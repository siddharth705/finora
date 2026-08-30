import {
  buildNewAccountPayload, buildRowPayload, initialAccountForm, initialCategories,
  type NewAccountForm,
} from './importPayload';
import { beginReview, type RowReview } from './importReview';
import type { DetectedAccountInfo, DuplicateMatch, StagedRow } from '../types';

const row = (over: Partial<StagedRow> = {}): StagedRow => ({
  date: '2026-08-01',
  description: 'ACME STORES',
  amount: 1200,
  type: 'EXPENSE',
  suggestedCategory: 'Shopping',
  categorySource: 'rule',
  ruleId: null,
  likelyDuplicate: false,
  referenceNumber: null,
  balanceAfter: null,
  duplicateMatch: null,
  rowPosition: null,
  ...over,
});

/** A row the engine questioned: `likelyDuplicate` AND the evidence behind it, because the review
 *  keys on the evidence and the two must not be able to disagree. */
const flagged = (over: Partial<StagedRow> = {}): StagedRow =>
  row({ likelyDuplicate: true, duplicateMatch: match(), ...over });

const match = (over: Partial<DuplicateMatch> = {}): DuplicateMatch => ({
  existingTransactionId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  existingAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  existingDate: '2026-08-01',
  existingDescription: 'ACME STORES',
  existingAmount: 1200,
  existingType: 'EXPENSE',
  existingImportedAt: '2026-08-02T09:00:00Z',
  matchCount: 1,
  confidence: 'EXACT',
  reason: 'Same date, amount and description as a transaction already in your ledger.',
  ...over,
});

/** The include flags a caller would hold after answering nothing -- used where a test only cares
 *  about a field being carried through, not about the duplicate gate. */
const included = (flags: boolean[]): RowReview => ({
  included: flags,
  decisions: flags.map(() => 'import'),
});

const detected = (over: Partial<DetectedAccountInfo> = {}): DetectedAccountInfo =>
  ({
    suggestedName: 'HDFC Savings',
    suggestedAccountType: 'SAVINGS',
    openingBalance: 1000,
    closingBalance: 2000,
    statementPeriodStart: '2026-07-01',
    statementPeriodEnd: '2026-07-31',
    accountNumberMasked: '••••4802',
    creditLimit: null,
    paymentDueDate: null,
    accountHolderName: 'A Holder',
    branchName: 'Some Branch',
    ifscCode: 'HDFC0XXXXXX',
    bank: { id: 'hdfc' },
    detectedProduct: 'SAVINGS_ACCOUNT',
    productConfidence: 0.9,
    productNeedsReview: false,
    productEvidence: [],
    productIdentityHash: 'hash-abc',
    principalAmount: null,
    interestRate: null,
    maturityDate: null,
    maturityAmount: null,
    installmentAmount: null,
    installmentsPaid: null,
    installmentsTotal: null,
    ...over,
  }) as unknown as DetectedAccountInfo;

const form: NewAccountForm = {
  name: 'My Account',
  accountType: 'SAVINGS',
  openingBalance: '1000',
  creditLimit: '',
  dueDate: '',
};

describe('buildRowPayload', () => {
  // These two were absent from the mobile types until they were re-synced against the backend
  // record. Nothing failed -- the ledger just lost them.
  it('carries referenceNumber and balanceAfter through from staging', () => {
    const rows = [row({ referenceNumber: 'CHQ-77', balanceAfter: 8400 })];
    const [out] = buildRowPayload(rows, included([true]), ['Shopping']);
    expect(out.referenceNumber).toBe('CHQ-77');
    expect(out.balanceAfter).toBe(8400);
  });

  // Admin Import Row Trace (Founder Operations Dashboard) reads this back off the resulting
  // Transaction -- silently dropping it here would leave every mobile-confirmed import untraceable.
  it('carries rowPosition through from staging', () => {
    const rows = [row({ rowPosition: 4 })];
    const [out] = buildRowPayload(rows, included([true]), ['Shopping']);
    expect(out.rowPosition).toBe(4);
  });

  // The backend uses these to decide whether a category was a real decision worth teaching the
  // merchant map, or an unresolved guess the user left alone.
  it('preserves categorySource and ruleId unchanged through review', () => {
    const rows = [row({ categorySource: 'user_rule', ruleId: 'rule-1' })];
    const [out] = buildRowPayload(rows, included([true]), ['Groceries']);
    expect(out.categorySource).toBe('user_rule');
    expect(out.ruleId).toBe('rule-1');
  });

  it('uses the reviewed category, not the suggested one', () => {
    const [out] = buildRowPayload([row({ suggestedCategory: 'Other' })], included([true]), ['Dining']);
    expect(out.category).toBe('Dining');
  });

  it('reflects the include toggle per row', () => {
    const out = buildRowPayload([row(), row(), row()], included([true, false, true]), ['A', 'B', 'C']);
    expect(out.map((r) => r.include)).toEqual([true, false, true]);
  });

  it('reports likelyDuplicate honestly rather than hiding excluded rows', () => {
    const [out] = buildRowPayload([flagged()], { included: [false], decisions: ['skip'] }, ['Shopping']);
    expect(out.likelyDuplicate).toBe(true);
    expect(out.include).toBe(false);
  });

  /**
   * The field this app never sent. Without it the backend defaults it to false, so a row the user
   * explicitly imported is re-flagged by the next reconciliation pass and stripped from every spend
   * total -- the decision visible in the ledger and absent from the numbers. V65 measured that on
   * the web path before it was fixed there.
   */
  describe('confirmedNotDuplicate — the user answer, not the engine guess', () => {
    it('is true only when the user chose to import a row the engine questioned', () => {
      const rows = [flagged()];
      const [out] = buildRowPayload(rows, { included: [true], decisions: ['import'] }, ['Shopping']);
      expect(out.confirmedNotDuplicate).toBe(true);
    });

    it('is false when the user skipped the row', () => {
      const [out] = buildRowPayload([flagged()], { included: [false], decisions: ['skip'] }, ['Shopping']);
      expect(out.confirmedNotDuplicate).toBe(false);
    });

    // A client must not be able to claim a decision the user was never asked to make. An ordinary
    // row is 'import' from the start -- that is a default, not an answer, and asserting it as one
    // would tell reconciliation a human had cleared a row nobody ever looked at.
    it('is false for a row the engine never questioned, even though it imports', () => {
      const rows = [row()];
      const [out] = buildRowPayload(rows, beginReview(rows), ['Shopping']);
      expect(out.include).toBe(true);
      expect(out.confirmedNotDuplicate).toBe(false);
    });
  });
});

describe('buildNewAccountPayload', () => {
  // Dropping these is what turns a fixed deposit into an empty savings account.
  it('echoes the deposit block back unchanged', () => {
    const out = buildNewAccountPayload(form, detected({
      principalAmount: 100000,
      interestRate: 7.1,
      maturityDate: '2027-08-01',
      maturityAmount: 107100,
      installmentAmount: null,
      installmentsPaid: null,
      installmentsTotal: null,
    }));

    expect(out.principalAmount).toBe(100000);
    expect(out.interestRate).toBe(7.1);
    expect(out.maturityDate).toBe('2027-08-01');
    expect(out.maturityAmount).toBe(107100);
  });

  // Without this a re-import cannot tell an already-held deposit from a new one, and double-counts.
  it('echoes productIdentityHash', () => {
    expect(buildNewAccountPayload(form, detected()).productIdentityHash).toBe('hash-abc');
  });

  it('sends detectedProduct when the engine was confident', () => {
    expect(buildNewAccountPayload(form, detected()).detectedProduct).toBe('SAVINGS_ACCOUNT');
  });

  // The user's own choice must win. Re-asserting a low-confidence guess would silently override
  // the correction they just made on the review screen.
  it('drops detectedProduct when the engine flagged it for review', () => {
    const out = buildNewAccountPayload(form, detected({ productNeedsReview: true }));
    expect(out.detectedProduct).toBeNull();
    expect(out.productIdentityHash).toBe('hash-abc'); // identity is still echoed
  });

  it('keeps bank, holder, branch and IFSC from detection', () => {
    const out = buildNewAccountPayload(form, detected());
    expect(out.bankId).toBe('hdfc');
    expect(out.accountHolderName).toBe('A Holder');
    expect(out.branchName).toBe('Some Branch');
    expect(out.ifscCode).toBe('HDFC0XXXXXX');
  });

  it('only sends credit-card fields for a credit card', () => {
    const savings = buildNewAccountPayload(
      { ...form, accountType: 'SAVINGS', creditLimit: '50000', dueDate: '2026-09-01' },
      detected()
    );
    expect(savings.creditLimit).toBeNull();
    expect(savings.dueDate).toBeNull();

    const card = buildNewAccountPayload(
      { ...form, accountType: 'CREDIT_CARD', creditLimit: '50000', dueDate: '2026-09-01' },
      detected()
    );
    expect(card.creditLimit).toBe(50000);
    expect(card.dueDate).toBe('2026-09-01');
  });

  it('treats blank and unparseable amounts as null rather than NaN', () => {
    const out = buildNewAccountPayload({ ...form, openingBalance: '' }, detected());
    expect(out.openingBalance).toBeNull();

    const bad = buildNewAccountPayload({ ...form, openingBalance: 'abc' }, detected());
    expect(bad.openingBalance).toBeNull();
  });

  it('falls back to a placeholder name rather than sending an empty one', () => {
    expect(buildNewAccountPayload({ ...form, name: '   ' }, detected()).name).toBe('Imported Account');
  });

  it('survives having no detected account at all', () => {
    const out = buildNewAccountPayload(form, null);
    expect(out.bankId).toBeNull();
    expect(out.detectedProduct).toBeNull();
    expect(out.principalAmount).toBeNull();
  });
});

describe('initial review state', () => {
  // "excludes flagged duplicates by default" used to sit here, asserting initialInclusion's
  // `!likelyDuplicate`. It passed for as long as it existed and was testing the defect: excluding
  // the row was only ever half the behaviour, and the half it did not assert -- that a question had
  // been asked and left unanswered -- did not exist. The replacement is
  // importReview.test.ts's "never unticks a row without also recording that nobody has answered
  // for it", which asserts both halves together because beginReview cannot produce one without the
  // other.

  it('starts each row at its suggested category', () => {
    expect(initialCategories([row({ suggestedCategory: 'Dining' }), row({ suggestedCategory: 'Fuel' })]))
      .toEqual(['Dining', 'Fuel']);
  });

  it('prefills the account form from detection', () => {
    const out = initialAccountForm(detected({ openingBalance: 2500 }));
    expect(out.name).toBe('HDFC Savings');
    expect(out.accountType).toBe('SAVINGS');
    expect(out.openingBalance).toBe('2500');
  });

  it('has sane defaults when nothing was detected', () => {
    expect(initialAccountForm(null)).toEqual({
      name: '',
      accountType: 'SAVINGS',
      openingBalance: '',
      creditLimit: '',
      dueDate: '',
    });
  });
});
