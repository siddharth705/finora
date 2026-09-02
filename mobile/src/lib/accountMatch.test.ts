import { matchExistingAccount } from './accountMatch';
import type { Account, DetectedAccountInfo } from '../types';

// Ported from frontend/src/lib/accountMatch.test.ts alongside the implementation itself (A3,
// two-pass mobile audit) -- vitest imports dropped for jest's globals, fixtures widened to
// mobile's own Account/DetectedAccountInfo shapes. The cases are deliberately identical: the two
// clients answer the same question and must keep answering it the same way.

const KOTAK = {
  id: 'bank-kotak', officialName: 'Kotak Mahindra Bank', shortName: 'Kotak', colorHex: '#003874',
  initials: 'K', logoPath: '', category: 'PRIVATE' as const, websiteUrl: null, ifscPrefix: 'KKBK',
  supportedAccountTypes: ['SAVINGS'],
};
const HDFC = { ...KOTAK, id: 'bank-hdfc', shortName: 'HDFC', ifscPrefix: 'HDFC' };

function account(over: Partial<Account>): Account {
  return {
    id: 'acc-1', name: 'Kotak Savings', accountType: 'SAVINGS', balance: 0, bank: KOTAK,
    accountNumberMasked: null, lastImportedAt: null, lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null, statementsCount: 0, transactionsCount: 0, status: 'ACTIVE',
    ...over,
  } as Account;
}

function detected(over: Partial<DetectedAccountInfo>): DetectedAccountInfo {
  return {
    suggestedName: 'Kotak Savings', suggestedAccountType: 'SAVINGS', openingBalance: null,
    closingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
    accountNumberMasked: null, creditLimit: null, totalAmountDue: null, paymentDueDate: null,
    accountHolderName: null, branchName: null, ifscCode: null, bank: KOTAK,
    detectedProduct: 'SAVINGS', productConfidence: 0.9, productNeedsReview: false,
    productEvidence: [], productIdentityHash: null, principalAmount: null, interestRate: null,
    maturityDate: null, maturityAmount: null, installmentAmount: null, installmentsPaid: null,
    installmentsTotal: null,
    ...over,
  } as DetectedAccountInfo;
}

describe('matchExistingAccount', () => {
  it('returns null when there are no accounts yet', () => {
    expect(matchExistingAccount(detected({}), [])).toBeNull();
  });

  it('does not match an account at a different bank', () => {
    // The reported bug: import Kotak, then import any other bank's statement, and the screen
    // preselected "use an existing account: Kotak".
    const kotak = account({ id: 'acc-kotak', bank: KOTAK });
    const fromHdfc = detected({ bank: HDFC, suggestedName: 'HDFC Savings' });

    expect(matchExistingAccount(fromHdfc, [kotak])).toBeNull();
  });

  it('matches on bank and account number', () => {
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XXXXXX4587' });
    const same = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(same, [kotak])?.id).toBe('acc-kotak');
  });

  it('matches the same account across statements that mask it differently', () => {
    // The same account is not masked consistently between statements, so only the digits can be
    // compared -- "XX4587" and "XXXXXX4587" are the same account.
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XX4587' });
    const same = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(same, [kotak])?.id).toBe('acc-kotak');
  });

  it('does not match a different account at the same bank', () => {
    // Two Kotak accounts. The number is what tells them apart, and getting this wrong merges one
    // account's transactions into the other's.
    const first = account({ id: 'acc-1', accountNumberMasked: 'XXXXXX4587' });
    const second = detected({ accountNumberMasked: 'XXXXXX9921' });

    expect(matchExistingAccount(second, [first])).toBeNull();
  });

  it('treats a known number as a veto, not just a missed match', () => {
    // Sole account at the bank, same type -- but this statement names a DIFFERENT number, so it is
    // known to be a different account however few candidates there are.
    const first = account({ id: 'acc-1', accountNumberMasked: 'XXXXXX4587', accountType: 'SAVINGS' });
    const second = detected({ accountNumberMasked: 'XXXXXX9921', suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(second, [first])).toBeNull();
  });

  it('still matches the sole same-type account when only the statement carries a number', () => {
    // The veto above only fires when some existing account at this bank actually HAS a number to
    // contradict. An account created manually, or by an import whose extraction failed (which
    // ImportService logs as a known recurring case), has none -- so a returning user with one
    // account must still get it preselected rather than being pushed into creating a duplicate.
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: null, accountType: 'SAVINGS' });
    const withNumber = detected({ accountNumberMasked: 'XXXXXX4587', suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(withNumber, [kotak])?.id).toBe('acc-kotak');
  });

  it('matches a sole same-type account at the bank when neither side has a number', () => {
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: null, accountType: 'SAVINGS' });
    const noNumber = detected({ accountNumberMasked: null, suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(noNumber, [kotak])?.id).toBe('acc-kotak');
  });

  it('does not guess between two same-type accounts at the same bank', () => {
    const a = account({ id: 'acc-1', accountType: 'SAVINGS' });
    const b = account({ id: 'acc-2', accountType: 'SAVINGS' });

    expect(matchExistingAccount(detected({}), [a, b])).toBeNull();
  });

  it('does not match a credit card statement to a savings account at the same bank', () => {
    const savings = account({ id: 'acc-savings', accountType: 'SAVINGS' });
    const card = detected({ suggestedAccountType: 'CREDIT_CARD' });

    expect(matchExistingAccount(card, [savings])).toBeNull();
  });

  it('ignores a masked number too short to identify anything', () => {
    // "XX87" is two digits of signal. Matching on that would pair unrelated accounts; it is
    // treated as absent, which falls through to the single-same-type-account rule.
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XX87', accountType: 'SAVINGS' });
    const short = detected({ accountNumberMasked: 'XX87', suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(short, [kotak])?.id).toBe('acc-kotak');
  });

  it('picks the right account out of several at the same bank', () => {
    const savings = account({ id: 'acc-savings', accountType: 'SAVINGS', accountNumberMasked: 'XXXXXX4587' });
    const card = account({ id: 'acc-card', accountType: 'CREDIT_CARD', accountNumberMasked: 'XXXXXX9921' });
    const cardStatement = detected({ suggestedAccountType: 'CREDIT_CARD', accountNumberMasked: 'XXXXXX9921' });

    expect(matchExistingAccount(cardStatement, [savings, card])?.id).toBe('acc-card');
  });

  it('does not match two accounts at DIFFERENT banks that share the same last four digits', () => {
    // CsvParser.maskAccountNumber emits "••••" + last4 for every bank, so a cross-bank collision
    // on the last four digits is ordinary rather than exotic. The bank filter is what stops it
    // becoming a silent misfiling -- the exact false positive this whole function exists to
    // prevent, and the one a bank-blind digit comparison would walk straight into.
    const kotak = account({ id: 'acc-kotak', bank: KOTAK, accountNumberMasked: '••••1234' });
    const fromHdfc = detected({ bank: HDFC, accountNumberMasked: '••••1234' });

    expect(matchExistingAccount(fromHdfc, [kotak])).toBeNull();
  });
});
