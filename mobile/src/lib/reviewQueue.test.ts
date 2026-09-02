import { reviewNudgeLabel, reviewQueueCount, reinsertAt } from './reviewQueue';
import type { MerchantGroup, Transaction } from '../types';

function txn(id: string): Transaction {
  return {
    id,
    accountId: 'a1',
    categoryId: 'c1',
    categoryName: 'Other',
    date: '2026-09-01',
    description: `txn ${id}`,
    merchant: 'M',
    paymentMethod: 'UPI',
    amount: -100,
    type: 'EXPENSE',
    tags: [],
    notes: null,
    reconciliationStatus: 'OK',
    recurring: false,
    needsCategoryReview: true,
    categoryManuallySet: false,
  };
}

function group(merchantId: string, ...ids: string[]): MerchantGroup {
  return { merchantId, merchantName: `Merchant ${merchantId}`, transactionIds: ids };
}

describe('reviewQueueCount', () => {
  it('is zero for an empty queue', () => {
    expect(reviewQueueCount({ singles: [], groups: [] })).toBe(0);
  });

  it('sums singles and every transaction inside every group', () => {
    // The two server queries are disjoint by construction -- TransactionService.needsReview
    // filters out anything TransactionGroupingService already returned as a group -- so the
    // honest total is the sum, not either one alone.
    const queue = {
      singles: [txn('s1'), txn('s2')],
      groups: [group('m1', 'g1', 'g2', 'g3'), group('m2', 'g4', 'g5')],
    };
    expect(reviewQueueCount(queue)).toBe(7);
  });

  it('counts a transaction once even if the two queries stop being disjoint', () => {
    // Defensive, and deliberately not a passthrough sum: this number is shown to the user as
    // "N transactions need a quick look". If a backend regression ever let a grouped transaction
    // back into the singles list, a naive sum would inflate the nudge -- overstating the backlog
    // in a track whose entire point is that displayed numbers are trustworthy. Under-reporting a
    // regression is recoverable; lying about the count is not.
    const queue = { singles: [txn('g1'), txn('s1')], groups: [group('m1', 'g1', 'g2')] };
    expect(reviewQueueCount(queue)).toBe(3);
  });

  it('ignores a group the server sent with no transactions', () => {
    expect(reviewQueueCount({ singles: [txn('s1')], groups: [group('m1')] })).toBe(1);
  });
});

describe('reviewNudgeLabel', () => {
  it('uses the singular verb for one transaction', () => {
    expect(reviewNudgeLabel(1)).toBe('1 transaction needs a quick look');
  });

  it('uses the plural verb for more than one', () => {
    expect(reviewNudgeLabel(12)).toBe('12 transactions need a quick look');
  });

  it('groups thousands the way the rest of the app formats counts', () => {
    expect(reviewNudgeLabel(1234)).toBe('1,234 transactions need a quick look');
  });
});

describe('reinsertAt', () => {
  it('puts a rolled-back row back where it was, not at the end', () => {
    // Rollback has to be positional: the queue is ordered by date, and dropping a failed save at
    // the bottom would silently reorder the user's list under them.
    expect(reinsertAt(['a', 'c', 'd'], 1, 'b')).toEqual(['a', 'b', 'c', 'd']);
  });

  it('appends when the list has since shrunk past the original index', () => {
    expect(reinsertAt(['a'], 5, 'b')).toEqual(['a', 'b']);
  });

  it('does not mutate the input list', () => {
    const original = ['a', 'b'];
    reinsertAt(original, 0, 'z');
    expect(original).toEqual(['a', 'b']);
  });
});
