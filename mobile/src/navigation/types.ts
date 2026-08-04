import type { ReimportResult } from '../types';

/**
 * Route params, kept in their own module so screens can type their props without importing the
 * navigators (which import the screens -- a cycle).
 */
export type AuthStackParamList = {
  Login: { message?: string } | undefined;
  Register: undefined;
  ForgotPassword: undefined;
};

/**
 * The "More" tab is a stack, not a single screen: it's the catch-all menu that Budgets, Goals,
 * Statement History, and Settings all get pushed onto as later phases land.
 */
export type MoreStackParamList = {
  MoreHome: undefined;
  Accounts: undefined;
  Statements: undefined;
};

/**
 * A re-import that has already been staged server-side, handed to the Import tab so the review and
 * confirm steps are the ones the user already knows rather than a second copy of them.
 *
 * Plain JSON on purpose: React Navigation warns about non-serializable params, and this crosses a
 * tab boundary where state could be restored from disk. It carries the staged ROWS, not the file
 * or a password -- a protected statement is unlocked during staging on the Statement History
 * screen, and by the time this exists the password has already done its job and been dropped.
 */
export interface ReimportParams {
  statementImportId: string;
  accountId: string;
  accountName: string;
  staging: ReimportResult['staging'];
  /**
   * Distinguishes one arrival from the next. A tab's params outlive a visit, so the Import screen
   * needs to know whether it has already loaded THIS re-import -- and the statement id alone can't
   * say, since re-importing the same statement twice is a legitimate thing to do.
   */
  nonce: number;
}

/**
 * Only the tabs whose screens actually exist. The roadmap's target IA also has an Insights tab
 * grouping Reports/Insights/Investments (Phase 4) -- added when there's something behind it,
 * rather than mounted now as an empty placeholder.
 */
export type AppTabParamList = {
  Home: undefined;
  Transactions: undefined;
  // Params only ever set when arriving from "Re-import" on the Statement History screen; a normal
  // tap on the Import tab carries none and the screen starts at its upload step as always.
  Import: { reimport: ReimportParams } | undefined;
  More: undefined;
};
