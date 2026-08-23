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
  Statements: undefined;
  Budgets: undefined;
  Goals: undefined;
  Reports: undefined;
  Insights: undefined;
  Investments: undefined;
  Profile: undefined;
  Settings: undefined;
  // Phase 4: reached via the deep link EmailChangeService emails to the new address
  // (finora://email-change-verify?sessionId=...&token=...), registered in RootNavigator's
  // `linking` config -- see VerifyEmailChangeScreen's own doc comment.
  VerifyEmailChange: { sessionId?: string; token?: string } | undefined;
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
export interface ReimportParams {
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
 * Four tabs, deliberately, even though Phase 4 landed the Reports/Insights/Investments screens the
 * roadmap sketched as a fifth "Insights" tab. Those three are report surfaces people open
 * occasionally, not destinations they switch between mid-task, and a fifth tab would shrink every
 * label toward the width where both platforms start truncating them. They live in the More stack
 * with Budgets and Goals instead.
 */
export type AppTabParamList = {
  Home: undefined;
  Transactions: undefined;
  // Params only ever set when arriving from "Re-import" on the Statement History screen; a normal
  // tap on the Import tab carries none and the screen starts at its upload step as always.
  Import: { reimport: ReimportParams } | undefined;
  // NavigatorScreenParams (not plain `undefined`, though nothing pushes a param onto it directly
  // today) is what tells React Navigation's linking types that this tab hosts a nested navigator
  // with MoreStackParamList's own routes -- RootNavigator's `linking` config needs this to type
  // its VerifyEmailChange deep-link path against the nested stack, not just this tab itself.
  More: NavigatorScreenParams<MoreStackParamList> | undefined;
};
