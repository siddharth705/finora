/**
 * Whether the import review screen's primary action ("Import N transactions") may fire.
 *
 * <b>Why this is a function and not an inline expression.</b> It gates the single most
 * consequential action in the app, and it has three independent reasons to say no -- nothing
 * selected, an unanswered duplicate question, and no destination account. The third was added in
 * A3 (two-pass mobile audit, 2026-09-01) and immediately introduced a regression that no test
 * could have caught, because the expression lived inline in JSX and the screen has no test at
 * all: a re-import renders no account picker, so gating it on `selectedAccountId` disabled the
 * button with no control on screen able to satisfy it.
 *
 * Extracted so that invariant is stated once, in one place, and pinned by tests -- the same
 * treatment `importReview.ts` and `refreshingIndicator.ts` already get for the same reason.
 */
export type ImportGateState = {
  /** How many staged rows the user has ticked. Zero means there is nothing to import. */
  includedCount: number;
  /** Duplicate questions the engine asked that nobody has answered yet. */
  outstanding: number;
  /**
   * Whether this is a re-import. A re-import is pinned to the account the statement already
   * belongs to and renders no account picker, so the account condition below must not apply to it.
   */
  isReimport: boolean;
  accountChoice: 'existing' | 'new';
  selectedAccountId: string;
};

export function canConfirmImport(state: ImportGateState): boolean {
  if (state.includedCount === 0) return false;
  // The engine asked a question about a possible duplicate and nobody answered it. Importing
  // anyway is how a duplicate gets in without anyone having looked at it.
  if (state.outstanding > 0) return false;
  // "An existing account" chosen with nothing actually highlighted -- reachable by tapping the
  // chip without then tapping a row. Without this the confirm posts an empty existingAccountId
  // and the backend answers with a 400 the user cannot act on. Never applies to a re-import,
  // which has no picker and posts its own pinned account id instead.
  if (!state.isReimport && state.accountChoice === 'existing' && !state.selectedAccountId) return false;
  return true;
}
