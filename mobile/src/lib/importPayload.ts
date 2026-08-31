import type { ConfirmedRowPayload, NewAccountPayload } from '../api/endpoints';
import type { Account, DetectedAccountInfo, StagedRow } from '../types';
import { isUnderReview, type RowReview } from './importReview';

/**
 * Builds the confirm payloads for an import.
 *
 * Pure and tested, because this is where the expensive mistakes live. Every field below is carried
 * through from staging, and dropping one is silent: nothing fails, the import succeeds, and the
 * ledger is quietly missing a reference number, or a fixed deposit has been created as an empty
 * savings account. That exact gap already existed in this app's types until they were re-synced
 * against the backend records.
 */

export interface NewAccountForm {
  name: string;
  accountType: Account['accountType'];
  openingBalance: string;
  creditLimit: string;
  dueDate: string;
}

/**
 * One row, exactly as the backend's ImportDto.ConfirmedRow expects it.
 *
 * `category` is the user's choice from review; everything else is echoed from staging unchanged.
 * `categorySource` and `ruleId` in particular must survive review untouched -- they are how the
 * backend decides whether this was a real categorisation decision worth teaching the merchant map,
 * or an unresolved guess the user simply left alone.
 */
export function buildRowPayload(
  rows: StagedRow[],
  review: RowReview,
  chosenCategory: string[]
): ConfirmedRowPayload[] {
  return rows.map((r, i) => ({
    date: r.date,
    description: r.description,
    amount: r.amount,
    type: r.type,
    category: chosenCategory[i],
    include: review.included[i],
    categorySource: r.categorySource,
    ruleId: r.ruleId,
    likelyDuplicate: r.likelyDuplicate,
    referenceNumber: r.referenceNumber,
    balanceAfter: r.balanceAfter,
    rowPosition: r.rowPosition,
    // The user's answer, not the engine's guess. Without it, reconciliation re-flags the row the
    // moment it lands and strips it from every spend total -- the decision would show in the ledger
    // and vanish from the numbers. Only ever true for a row the engine actually questioned: a
    // client cannot claim a decision the user was never asked to make.
    confirmedNotDuplicate: isUnderReview(r) && review.decisions[i] === 'import',
  }));
}

function toNumberOrNull(raw: string): number | null {
  if (!raw.trim()) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/**
 * The new-account request, mixing what the user typed with what staging detected.
 *
 * `detectedProduct` is deliberately dropped when the engine wasn't sure: in that case the account
 * type the user just picked is the answer, and re-asserting a low-confidence guess here would
 * silently override their correction. Everything else from the detected block is echoed unchanged
 * -- the review screen shows those read-only, so there is nothing for the client to have gotten
 * wrong, and omitting them is what turns a deposit into an empty savings account.
 */
export function buildNewAccountPayload(
  form: NewAccountForm,
  detected: DetectedAccountInfo | null
): NewAccountPayload {
  const isCreditCard = form.accountType === 'CREDIT_CARD';

  return {
    name: form.name.trim() || 'Imported Account',
    accountType: form.accountType,
    openingBalance: toNumberOrNull(form.openingBalance),
    // Only meaningful on a credit card; sending it for a savings account would persist a limit
    // that has no meaning there.
    creditLimit: isCreditCard ? toNumberOrNull(form.creditLimit) : null,
    dueDate: isCreditCard && form.dueDate ? form.dueDate : null,

    accountHolderName: detected?.accountHolderName ?? null,
    accountNumberMasked: detected?.accountNumberMasked ?? null,
    // Keeps the new account's logo and colour correct even if the user renames it away from the
    // bank's official name.
    bankId: detected?.bank.id ?? null,
    branchName: detected?.branchName ?? null,
    ifscCode: detected?.ifscCode ?? null,

    detectedProduct: detected && !detected.productNeedsReview ? detected.detectedProduct : null,
    // Opaque, and already a hash before it reached this device. Lets a re-import recognise a
    // product already held instead of creating a second one and double-counting it in net worth.
    productIdentityHash: detected?.productIdentityHash ?? null,

    principalAmount: detected?.principalAmount ?? null,
    interestRate: detected?.interestRate ?? null,
    maturityDate: detected?.maturityDate ?? null,
    maturityAmount: detected?.maturityAmount ?? null,
    installmentAmount: detected?.installmentAmount ?? null,
    installmentsPaid: detected?.installmentsPaid ?? null,
    installmentsTotal: detected?.installmentsTotal ?? null,
  };
}

// initialInclusion() used to live here: `rows.map(r => !r.likelyDuplicate)`, with the comment
// "the user can still tick them back on". That was the whole problem -- the row was unticked
// whether or not anyone read it, nothing recorded that a question had gone unanswered, and the
// import could be confirmed without the user ever seeing one. Replaced by beginReview() in
// ./importReview, which produces the include flags and the decisions together so an untick cannot
// exist without the unresolved answer that blocks the import.

export function initialCategories(rows: StagedRow[]): string[] {
  return rows.map((r) => r.suggestedCategory);
}

/** Prefills the new-account form from what the statement itself said. Every field stays editable --
 *  detection is best-effort by design. */
export function initialAccountForm(detected: DetectedAccountInfo | null): NewAccountForm {
  return {
    name: detected?.suggestedName ?? '',
    accountType: detected?.suggestedAccountType ?? 'SAVINGS',
    openingBalance: detected?.openingBalance != null ? String(detected.openingBalance) : '',
    creditLimit: detected?.creditLimit != null ? String(detected.creditLimit) : '',
    dueDate: detected?.paymentDueDate ?? '',
  };
}
