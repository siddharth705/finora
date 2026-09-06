export interface TourStep {
  targetSelector: string;
  title: string;
  body: string;
}

export const TOUR_STEPS: TourStep[] = [
  { targetSelector: '[data-tour="dashboard"]', title: 'Your Financial Command Center',
    body: 'This dashboard gives you a complete view of your finances, including spending, budgets, goals, and account balances.' },
  { targetSelector: '[data-tour="accounts"]', title: 'Accounts',
    body: 'See every linked or manually added account in one place.' },
  { targetSelector: '[data-tour="import"]', title: 'Import Bank Statements',
    body: 'Upload your bank statements and Fynora automatically organizes your transactions. No manual entry required.' },
  { targetSelector: '[data-tour="transactions"]', title: 'Every Transaction Explained',
    body: 'Search, filter, categorize, and understand every transaction in one place. See exactly where your money is going.' },
  { targetSelector: '[data-tour="budgets"]', title: 'Stay Within Budget',
    body: 'Create monthly budgets and track your progress in real time. Get notified before you overspend.' },
  { targetSelector: '[data-tour="goals"]', title: 'Achieve Your Financial Goals',
    body: "Whether it's an emergency fund, vacation, or new car, Fynora helps you stay on track." },
  { targetSelector: '[data-tour="insights"]', title: 'Discover Spending Patterns',
    body: 'Fynora automatically identifies trends and spending habits so you can make smarter financial decisions.' },
];
