/**
 * Plan configuration — the single source of truth for what Finora offers and what can actually be
 * bought today.
 *
 * Separated from the components so enabling Premium later is a data change, not a UI rewrite:
 * flip `availability` to 'available', give it a `price`, and both the pricing cards and the
 * "Growing with you" ladder update together. They read from this same array precisely so they
 * cannot drift into describing different products, which is what happened when each had its own
 * hardcoded list.
 *
 * THE RULE THIS FILE ENFORCES: `price` may only be set on a plan whose availability is
 * 'available'. Anything else shows its status where the price would go. A number beside a small
 * "coming soon" tag still reads as a price, and an invented one is remembered by whoever
 * screenshotted it. There is no billing in the backend at all — no plan field on User, no payment
 * integration — so today exactly one plan can be 'available', and that is Free.
 *
 * landing-claims.test.tsx asserts that rule rather than trusting this comment.
 */

export type Availability = 'available' | 'coming-soon' | 'planned' | 'exploring';

export interface Plan {
  id: string;
  name: string;
  /** Only ever set when availability is 'available'. See the rule above. */
  price: string | null;
  cadence?: string;
  availability: Availability;
  blurb: string;
  features: string[];
  /**
   * What this plan is FOR, in one line. The card leads with this rather than a feature list --
   * people don't buy "unlimited accounts", they buy going deeper into their own finances.
   */
  promise: string;
  /** The outcome this stage unlocks, for the "Growing with you" ladder. Progress, not features. */
  stage: { when: string; outcome: string };
  /** Kept for the comparison table, which is where feature-by-feature belongs. */
  ladder: string[];
}

export const AVAILABILITY_LABEL: Record<Availability, string> = {
  available: 'Available today',
  'coming-soon': 'Coming soon',
  planned: 'Planned',
  exploring: 'Exploring',
};

export const AVAILABILITY_STYLE: Record<Availability, { background: string; color: string }> = {
  available: { background: '#DCFCE7', color: '#166534' },
  'coming-soon': { background: '#DBEAFE', color: '#1D4ED8' },
  planned: { background: '#F1F5F9', color: '#64748B' },
  exploring: { background: '#F1F5F9', color: '#94A3B8' },
};

export const PLANS: Plan[] = [
  {
    id: 'free',
    name: 'Free',
    price: '₹0',
    cadence: '/month',
    availability: 'available',
    blurb: 'Everything you need to organize your money.',
    promise: 'Get your money in order.',
    stage: { when: 'Today', outcome: 'Organize your money.' },
    features: [
      'Import statements (PDF & CSV)',
      'Password-protected and multi-account files',
      'Automatic categorization that learns',
      'Budgets, goals and reports',
      'Financial dashboard and insights',
    ],
    ladder: ['Import statements', 'Automatic categorization', 'Budgets and goals', 'Spending analysis'],
  },
  {
    id: 'premium',
    name: 'Premium',
    price: null,
    availability: 'coming-soon',
    blurb: 'For people who want deeper financial intelligence.',
    promise: 'For people who simply want to go deeper.',
    stage: { when: 'Tomorrow', outcome: 'Understand your spending patterns.' },
    features: [
      'Unlimited accounts',
      'Advanced analytics',
      'Extended financial history',
      'Long-term trends',
      'Priority support',
    ],
    ladder: ['Unlimited accounts', 'Advanced analytics', 'Long-term trends', 'Priority support'],
  },
  {
    id: 'family',
    name: 'Family',
    price: null,
    availability: 'planned',
    blurb: 'Manage household finances together.',
    promise: 'For a household, not just a person.',
    stage: { when: 'Later', outcome: "Manage your family's finances together." },
    features: ['Shared dashboards', 'Household budgets', 'Shared financial goals'],
    ladder: ['Shared dashboards', 'Household budgets', 'Shared goals'],
  },
  {
    id: 'future',
    name: 'Future',
    price: null,
    availability: 'exploring',
    blurb: 'For financial professionals and businesses.',
    promise: 'Where Finora is headed next.',
    stage: { when: 'Eventually', outcome: 'Plan your financial future.' },
    features: ['Tools for professionals', 'Deeper financial intelligence'],
    ladder: ['Tools for professionals', 'Deeper financial intelligence'],
  },
];

/**
 * Free vs Premium, for the comparison table. Only capabilities already committed to appear here --
 * no invented rows padding the Premium column to make the table look worth reading.
 */
export const COMPARISON: { label: string; free: boolean; premium: boolean }[] = [
  { label: 'Statement import', free: true, premium: true },
  { label: 'Financial dashboard', free: true, premium: true },
  { label: 'Transaction categorization', free: true, premium: true },
  { label: 'Budget tracking', free: true, premium: true },
  { label: 'Learning engine', free: true, premium: true },
  { label: 'Advanced analytics', free: false, premium: true },
  { label: 'Long-term trends', free: false, premium: true },
  { label: 'Priority support', free: false, premium: true },
];

/** The plans shown as cards. `future` appears only in the ladder, not as a card to buy. */
export const PRICING_CARDS = PLANS.filter((p) => p.id !== 'future');
