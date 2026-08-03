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
};

/**
 * Only the tabs whose screens actually exist. The roadmap's target IA also has an Insights tab
 * grouping Reports/Insights/Investments (Phase 4) -- added when there's something behind it,
 * rather than mounted now as an empty placeholder.
 */
export type AppTabParamList = {
  Home: undefined;
  Transactions: undefined;
  Import: undefined;
  More: undefined;
};
