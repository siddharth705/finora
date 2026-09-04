import type { CounterpartyType } from '../types';

/**
 * Mirrored verbatim in `mobile/src/lib/counterpartyLabel.ts` -- see that copy's header. Keep the
 * wording identical; there is no shared package to hold it in one place.
 *
 * Turns a counterparty type plus a direction into something a person can read.
 *
 * Two separate facts are joined HERE, at render time, and nowhere earlier. The server stores who
 * was on the other side and never which way the money moved, because those are independent and
 * conflating them has already gone wrong once in this product: V123 shipped a category literally
 * named "Paid a Person", and 99 of the 434 transactions it labelled were money *received*. A stored
 * label cannot be right for both directions, so it isn't stored — it's composed.
 *
 * Returns null when there is nothing worth saying, and callers render nothing at all in that case.
 * UNKNOWN is roughly a fifth of real rows, and a badge reading "unknown" on one row in five is
 * noise that tells the reader strictly less than blank space does.
 */
export function counterpartyLabel(
  type: CounterpartyType,
  direction: 'INCOME' | 'EXPENSE',
): { short: string; full: string } | null {
  const inbound = direction === 'INCOME';

  switch (type) {
    case 'PERSON':
      return { short: 'Person', full: inbound ? 'Received from a person' : 'Sent to a person' };
    case 'BUSINESS':
      return { short: 'Business', full: inbound ? 'Received from a business' : 'Paid a business' };
    case 'FINANCIAL_INSTITUTION':
      // Not direction-composed, unlike the two above, and that is a judgement rather than an
      // oversight: these rows are interest, charges, mandates, ATM withdrawals and cashback, where
      // the bank is the counterparty in both directions and "paid a bank" reads as a transfer the
      // user made deliberately. The neutral noun is the honest one; the sign of the amount already
      // shows which way it went.
      return { short: 'Bank', full: 'Bank or financial institution' };
    case 'GOVERNMENT':
      return { short: 'Government', full: 'Government or tax body' };
    case 'UNKNOWN':
      return null;
    default:
      // A type this build does not know about, sent by a newer server. The backend stores this
      // column as a plain string precisely so an unrecognised value degrades instead of breaking
      // (see V142), and the client honours the same contract: say nothing rather than crash or
      // print a raw enum name at someone.
      return null;
  }
}
