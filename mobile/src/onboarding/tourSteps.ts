export interface TourStep {
  key: string;
  tab: 'Home' | 'Transactions' | 'Import' | 'More';
  title: string;
  body: string;
}

// Web's Sidebar shows every target as a persistent link, so its tour never navigates. Mobile's
// bottom tab bar is narrower (Home/Transactions/Import/More only -- AppTabs.tsx); Accounts/
// Budgets/Goals/Insights live as rows inside the More tab's own list screen (MoreScreen.tsx), not
// as separate top-level tabs. This tour therefore navigates the tab bar as it advances -- see the
// design spec's §7 addendum.
export const TOUR_STEPS: TourStep[] = [
  { key: 'home', tab: 'Home', title: 'Your Financial Command Center',
    body: 'This dashboard gives you a complete view of your finances, including spending, budgets, goals, and account balances.' },
  { key: 'accounts', tab: 'More', title: 'Accounts',
    body: 'See every linked or manually added account in one place.' },
  { key: 'import', tab: 'Import', title: 'Import Bank Statements',
    body: 'Upload your bank statements and Fynora automatically organizes your transactions. No manual entry required.' },
  { key: 'transactions', tab: 'Transactions', title: 'Every Transaction Explained',
    body: 'Search, filter, categorize, and understand every transaction in one place. See exactly where your money is going.' },
  { key: 'budgets', tab: 'More', title: 'Stay Within Budget',
    body: 'Create monthly budgets and track your progress in real time. Get notified before you overspend.' },
  { key: 'goals', tab: 'More', title: 'Achieve Your Financial Goals',
    body: "Whether it's an emergency fund, vacation, or new car, Fynora helps you stay on track." },
  { key: 'insights', tab: 'More', title: 'Discover Spending Patterns',
    body: 'Fynora automatically identifies trends and spending habits so you can make smarter financial decisions.' },
];
