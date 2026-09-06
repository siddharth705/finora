import type { NavigatorScreenParams } from '@react-navigation/native';
import type { ReimportResult } from '../types';

/**
 * Route params, kept in their own module so screens can type their props without importing the
 * navigators (which import the screens -- a cycle).
 */
export type AuthStackParamList = {
  // Phase 3B: the unified identifier-first entry screen (mirrors web's AuthEntry.tsx). Fronts
  // Login/Register -- both stay directly reachable on their own for the "No account? Register" /
  // "Sign in" footer links, which intentionally skip AuthEntry rather than round-trip through it.
  AuthEntry: undefined;
  // identifier: set only when arriving via AuthEntry's POST /auth/identify result -- prefills
  // the field. message: the pre-existing one-time confirmation banner (e.g. after a password
  // reset), unrelated to AuthEntry.
  //
  // Phase 7 (resolved 2026-08-23): this used to also carry `method` (PASSWORD/GOOGLE/APPLE) so
  // LoginScreen could hide the password form for a known OAuth account -- removed along with
  // nextAction no longer revealing which method an account uses (see AuthEntryScreen's own doc
  // comment).
  Login: { message?: string; identifier?: string } | undefined;
  // email/phoneNumber: set only when AuthEntry's identify() call returns nextAction CONTINUE --
  // prefills whichever of Register's two fields the identifier looked like.
  Register: { email?: string; phoneNumber?: string } | undefined;
  ForgotPassword: undefined;
};

/**
 * The "More" tab is a stack, not a single screen: it's the catch-all menu that everything outside
 * the four tabs gets pushed onto.
 */
export type MoreStackParamList = {
  MoreHome: undefined;
  Accounts: undefined;
  // The categorization review queue. Reached from the More menu, from Settings' Categorization
  // section (which promises it), and from the Dashboard nudge when the backlog is non-empty.
  CategoryReview: undefined;
  Statements: undefined;
  Budgets: undefined;
  Goals: undefined;
  Reports: undefined;
  Insights: undefined;
  Investments: undefined;
  Profile: undefined;
  Settings: undefined;
  // Subscription billing V4. Picks between the Paywall and My Subscription content internally --
  // see SubscriptionScreen's own doc comment for why this is one route, not two.
  Subscription: undefined;
  // Phase 4: reached via the deep link EmailChangeService emails to the new address
  // (finora://email-change-verify?sessionId=...&token=...), registered in RootNavigator's
  // `linking` config -- see VerifyEmailChangeScreen's own doc comment.
  VerifyEmailChange: { sessionId?: string; token?: string } | undefined;
  // Support, Help & Feedback v1, Phase 8 (mobile). Reached from Settings' "Help & Support"
  // section. SupportTicketDetail is this app's first push-to-a-detail-screen-with-params route --
  // every other "list" screen (StatementHistoryScreen) shows its detail as an in-screen Modal
  // instead, but a ticket's description can run to several paragraphs, which fits a full pushed
  // screen (with its own native back) better than a sheet.
  SupportTickets: undefined;
  SupportTicketDetail: { ticketId: string };
  // Refer & Earn MVP. Reached from this menu (MoreScreen), same pattern as Budgets/Goals/etc.
  Referrals: undefined;
};

/**
 * A re-import that has already been staged server-side, handed to the Import tab so the review and
 * confirm steps are the ones the user already knows rather than a second copy of them.
 *
 * Plain JSON on purpose: React Navigation warns about non-serializable params, and this crosses a
 * tab boundary where state could be restored from disk. It carries the staged ROWS, not the file --
 * that part of the original design held.
 *
 * The password is a different story. It used to be dropped here on the theory that staging was the
 * password's whole job -- true for reading the document, not true for confirming it: confirm
 * re-parses the same stored bytes server-side to check the reviewed rows against, and for a
 * protected PDF that re-parse needs the password again. Dropping it here made every reimport-confirm
 * of a protected statement fail unconditionally, with no way for this screen to recover (see
 * StatementImportService.confirmReimport's own doc comment for the incident). Carried through now
 * instead, in memory only, for exactly as long as this one review-and-confirm round trip lasts.
 */
interface ReimportParams {
  statementImportId: string;
  accountId: string;
  accountName: string;
  staging: ReimportResult['staging'];
  /** Present only when the statement needed one to stage. Re-sent verbatim at confirm time. */
  password?: string;
  /**
   * Distinguishes one arrival from the next. A tab's params outlive a visit, so the Import screen
   * needs to know whether it has already loaded THIS re-import -- and the statement id alone can't
   * say, since re-importing the same statement twice is a legitimate thing to do.
   */
  nonce: number;
}

/**
 * Track C/C4. A drill-through into the Ledger from wherever a category or a month is already on
 * screen -- a donut legend row, a budget card, an insight/mover row, a report's category
 * breakdown. `categoryId` wins when a caller already has one (BudgetsScreen's Budget carries its
 * own); `categoryName` is for the three callers that only ever see a NAME (spendByCategory,
 * category movers, a report's per-category breakdown are all keyed by name, not id) -- LedgerScreen
 * resolves it against the category list it already fetches for its own picker, rather than adding
 * a categories query to three more screens just to look up an id nothing else on those screens
 * needs. `label` is what the active-filter chip actually shows, so a caller can word it exactly
 * ("Dining · August 2026") rather than LedgerScreen reconstructing it from parts.
 *
 * `nonce` mirrors ImportScreen's own re-import param: the Transactions tab stays mounted like
 * every other tab, so its params outlive a visit, and without a per-arrival key a second
 * drill-through with an unchanged categoryName/date range (tapping the same donut slice twice)
 * would not be recognised as a new arrival by a render-time state update keyed on the params
 * themselves.
 */
export interface LedgerDrillThroughFilters {
  /** Track C/C6: ImportScreen's "View in Ledger" carries the just-confirmed statement's own
   *  account -- the one piece of context none of C4's four original callers had a reason to
   *  filter by. */
  accountId?: string;
  categoryId?: string;
  categoryName?: string;
  dateFrom?: string;
  dateTo?: string;
  label: string;
  nonce: number;
}

/**
 * Four tabs, deliberately, even though Phase 4 landed the Reports/Insights/Investments screens the
 * roadmap sketched as a fifth "Insights" tab. Those three are report surfaces people open
 * occasionally, not destinations they switch between mid-task, and a fifth tab would shrink every
 * label toward the width where both platforms start truncating them. They live in the More stack
 * with Budgets and Goals instead.
 */
export type AppTabParamList = {
  Home: undefined;
  // Params only ever set when arriving via a drill-through (Track C/C4); a normal tap on the
  // Transactions tab carries none and the screen shows everything, as always.
  Transactions: { filters: LedgerDrillThroughFilters } | undefined;
  // Params only ever set when arriving from "Re-import" on the Statement History screen; a normal
  // tap on the Import tab carries none and the screen starts at its upload step as always.
  Import: { reimport: ReimportParams } | undefined;
  // NavigatorScreenParams (not plain `undefined`, though nothing pushes a param onto it directly
  // today) is what tells React Navigation's linking types that this tab hosts a nested navigator
  // with MoreStackParamList's own routes -- RootNavigator's `linking` config needs this to type
  // its VerifyEmailChange deep-link path against the nested stack, not just this tab itself.
  More: NavigatorScreenParams<MoreStackParamList> | undefined;
};
