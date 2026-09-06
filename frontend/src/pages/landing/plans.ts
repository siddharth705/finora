/**
 * Plan configuration — the single source of truth for what Fynora offers and what can actually be
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
 * screenshotted it.
 *
 * Plus and Premium are real, purchasable plans now (subscription billing V1/V2, PRs #1008/#1016)
 * -- these four numbers (₹399/₹3,500/₹799/₹8,000) are the design spec's own §2 pricing decision
 * table and match the already-seeded `billing_prices` rows exactly, not invented for this page.
 * Checkout itself happens inside the app's Billing Portal (frontend/src/pages/Billing.tsx), never
 * on this public page -- see Pricing.tsx's own doc comment for why.
 *
 * landing-claims.test.tsx asserts the price/availability rule rather than trusting this comment.
 */

export type Availability = 'available' | 'coming-soon' | 'planned' | 'exploring';

export interface Plan {
  id: string;
  name: string;
  /** Only ever set when availability is 'available'. See the rule above. */
  price: string | null;
  cadence?: string;
  /** A second cadence worth mentioning below the primary price (e.g. the yearly price, when the
   *  primary price shown is monthly) -- purely informational, never a second buyable price on its
   *  own; checking out at a specific cycle happens inside the app's Billing Portal, not here. */
  secondaryPriceNote?: string;
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
  'coming-soon': { background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' },
  planned: { background: '#F1F5F9', color: '#64748B' },
  exploring: { background: '#F1F5F9', color: '#94A3B8' },
};

// Free/Plus/Premium — Product's Billing Plan Taxonomy Decision, 2026-08-12 (see
// docs/proposals/billing-subscription-entitlements-proposal.md §3.1/§3.2). Family and Future
// were dropped, not renamed; Plus and Premium's feature lists below follow that same decision's
// entitlement mapping (§3.2), not invented copy — Plus gets deeper analysis of a user's own data,
// Premium adds new capabilities (Fino, investment insights) on top of it.
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
    id: 'plus',
    name: 'Plus',
    price: '₹399',
    cadence: '/month',
    secondaryPriceNote: 'or ₹3,500/year',
    availability: 'available',
    blurb: 'For people who want deeper financial intelligence.',
    promise: 'For people who simply want to go deeper.',
    stage: { when: 'Tomorrow', outcome: 'Understand your spending patterns.' },
    features: [
      'Unlimited accounts',
      'Advanced reports and analytics',
      'Extended financial history',
      'Long-term trends',
    ],
    ladder: ['Unlimited accounts', 'Advanced reports', 'Extended history', 'Long-term trends'],
  },
  {
    id: 'premium',
    name: 'Premium',
    price: '₹799',
    cadence: '/month',
    secondaryPriceNote: 'or ₹8,000/year',
    availability: 'available',
    blurb: 'For people who want Fynora to work for them, not just show them the numbers.',
    promise: 'For people who want an assistant, not just a dashboard.',
    stage: { when: 'Later', outcome: 'Let Fynora work for you.' },
    features: [
      'Everything in Plus',
      'Investment insights',
      'Fino, your financial assistant',
      'Priority support',
    ],
    ladder: ['Investment insights', 'Fino, your financial assistant', 'Priority support'],
  },
];

/**
 * Free vs Plus vs Premium, for the comparison table. Rows and tier columns follow the same
 * entitlement mapping PLANS' Plus/Premium feature lists do (billing proposal §3.2) — only
 * capabilities already committed to appear here, no invented rows padding a column to make the
 * table look worth reading.
 */
export const COMPARISON: { label: string; free: boolean; plus: boolean; premium: boolean }[] = [
  { label: 'Statement import', free: true, plus: true, premium: true },
  { label: 'Financial dashboard', free: true, plus: true, premium: true },
  { label: 'Transaction categorization', free: true, plus: true, premium: true },
  { label: 'Budget tracking', free: true, plus: true, premium: true },
  { label: 'Learning engine', free: true, plus: true, premium: true },
  { label: 'Advanced analytics', free: false, plus: true, premium: true },
  { label: 'Extended financial history', free: false, plus: true, premium: true },
  { label: 'Long-term trends', free: false, plus: true, premium: true },
  { label: 'Investment insights', free: false, plus: false, premium: true },
  { label: 'Fino, your financial assistant', free: false, plus: false, premium: true },
  { label: 'Priority support', free: false, plus: false, premium: true },
];

/** The plans shown as cards. Every current tier is real and committed, so this is just an alias
 *  for PLANS today -- kept as its own export (rather than importing PLANS directly in Pricing.tsx)
 *  in case a future tier is added that belongs in the ladder but not the buyable card grid, the
 *  same distinction `future` used to draw. */
export const PRICING_CARDS = PLANS;
