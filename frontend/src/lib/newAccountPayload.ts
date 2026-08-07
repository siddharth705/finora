import type { Account, DetectedAccountInfo } from '../types';
import type { NewAccountPayload } from '../api/endpoints';

/**
 * The one way to build the `newAccount` half of a confirm request.
 *
 * **Why this is a module rather than an object literal on the page.** Both confirm paths built this
 * payload by hand, and they disagreed: the single-account path sent all nineteen fields, the
 * multi-account path sent ten. The nine it dropped were `detectedProduct`, `productIdentityHash` and
 * the seven deposit attributes — precisely the fields that decide *what kind of thing* is being
 * created. So a composite statement's fixed-deposit section arrived at the server with no product
 * and no principal, and was created as an empty savings account: the deposit's own numbers, which
 * the review screen had just displayed back to the user, were dropped between the screen and the
 * request.
 *
 * Nothing caught it. The fields were absent from `NewAccountPayload`, so neither path was
 * typechecked against the real request shape, and both were "correct" as far as the compiler was
 * concerned. Adding the fields to the type is half the fix; this function is the other half, because
 * a type only rejects a *wrong* payload and cannot make two call sites build the *same* one.
 *
 * That is the same argument `lib/importReview.ts` makes for `beginReview`, and it is the same defect
 * one layer over — the multi-account path hand-rolling a payload the single-account path had already
 * got right. Fixing it per call site is what produced this bug the first time.
 *
 * **The mobile client already did this.** `mobile/src/lib/importPayload.ts` has
 * `buildNewAccountPayload` with this signature, this `productNeedsReview` guard and all nineteen
 * fields — so the web app was the outlier, not the pioneer, and the drift was between two web call
 * sites rather than between platforms. Kept as `to*` here to match `toConfirmedRows` next door;
 * mobile's `build*` matches `buildRowPayload` next door to it. Any field added to one belongs in
 * both, and the two are worth diffing when this shape changes.
 */

/**
 * The five fields the user can actually edit on the review screen.
 *
 * Named to match both call sites' existing state — `SectionState` satisfies this structurally, so
 * the multi-account path passes its section straight in.
 */
export interface NewAccountForm {
  newName: string;
  newType: Account['accountType'];
  newOpeningBalance: string;
  newCreditLimit: string;
  newDueDate: string;
}

export function toNewAccountPayload(
  form: NewAccountForm,
  detected: DetectedAccountInfo | null,
): NewAccountPayload {
  const isCard = form.newType === 'CREDIT_CARD';
  return {
    name: form.newName.trim() || 'Imported Account',
    accountType: form.newType,
    openingBalance: form.newOpeningBalance ? parseFloat(form.newOpeningBalance) : null,
    // Both card-only: a savings account with a stale credit-limit box left over from a previous
    // section should not persist one.
    creditLimit: isCard && form.newCreditLimit ? parseFloat(form.newCreditLimit) : null,
    dueDate: isCard && form.newDueDate ? form.newDueDate : null,

    // Detected and shown (disabled) on the review step. The bank id lets the new account's
    // logo/colour render correctly even if the user renames the account away from the bank's
    // official name.
    accountHolderName: detected?.accountHolderName ?? null,
    accountNumberMasked: detected?.accountNumberMasked ?? null,
    bankId: detected?.bank.id ?? null,
    branchName: detected?.branchName ?? null,
    ifscCode: detected?.ifscCode ?? null,

    // Deliberately dropped when the engine wasn't sure. `productNeedsReview` means the user was
    // asked to name the product themselves, and the type they picked in `newType` is then the
    // answer — sending the guess as well would override the correction they were just asked for.
    detectedProduct:
      detected && !detected.productNeedsReview ? detected.detectedProduct : null,
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
