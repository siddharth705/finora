/**
 * Every word on the landing page, in one place.
 *
 * The point is that changing marketing copy should never mean opening component logic. A section
 * file decides how something looks; this file decides what it says. That split is what makes
 * localization, A/B variants and a non-engineer editing a headline all tractable -- and it means a
 * claim can be reviewed by reading one file instead of seventeen.
 *
 * Plans live separately in ./plans, because they carry a real invariant (only an available plan
 * may have a price) that the claims test enforces. Copy has no invariant; plans do.
 *
 * THE ONE RULE FOR EDITING THIS FILE: every sentence here is a public claim about a financial
 * product. Before changing one, run the four questions in
 * docs/engineering/marketing-claims-checklist.md. `landing-claims.test.tsx` catches the mistakes
 * that can be caught mechanically; it cannot catch a sentence that is merely untrue.
 */

export const hero = {
  headline: 'Money tells a story.',
  headlineAccent: 'Fynora helps you read it.',
  blurb: 'Understand every rupee, not just your balance. Upload a statement — Fynora does the rest.',
  primaryCta: 'Import your first statement',
  secondaryCta: 'See how it works',
  assurances: [
    'Secure by design',
    'Learns from your corrections',
    'Explains its decisions',
    'No upsells, ever',
  ],
};

/**
 * Copy for the cinematic hero's score ring, intelligence-scan checklist, and floating data
 * badges. Same rule as the rest of this file: these are illustrative figures, kept internally
 * consistent with the numbers DashboardMock already shows elsewhere on the page (see
 * DashboardMock.tsx's own note on why that matters).
 */
export const heroScore = {
  label: 'Financial Health',
  value: 84,
  delta: '+6 this month',
};

export const heroIntelligence = {
  heading: 'Analyzing your finances…',
  steps: [
    'Spending patterns detected',
    'Subscription detected',
    'Saving opportunity found',
    'Financial health calculated',
  ],
};

export const heroBadges = [
  { label: '+₹1,24,500 Salary' },
  { label: 'Investment +12%' },
  { label: 'Goal 72%' },
  { label: 'AI Insight ✨' },
  { label: 'Savings improved' },
];

export const problem = {
  eyebrow: 'Why this is hard',
  title: "Managing money shouldn't feel like work.",
  chores: [
    'Downloading statements. Every month.',
    'Scrolling hundreds of rows for one charge.',
    'Guessing where the money actually went.',
    'A spreadsheet that is stale by Tuesday.',
  ],
  closer: 'The data already exists.',
  closerMuted: 'Understanding it is the hard part.',
};

export const importSection = {
  eyebrow: 'Import anything',
  title: 'Upload once.',
  titleLine2: 'Everything else is automatic.',
  blurb: "Fynora reads the statement, finds the accounts, sorts the transactions and has it ready before you've put the kettle on.",
  supported: ['PDF', 'CSV', 'Password-protected', 'Multiple accounts', 'Composite statements'],
};

export const learning = {
  eyebrow: 'It learns from you',
  title: 'Correct it once.',
  titleLine2: 'Never correct it again.',
  blurb: 'Your corrections are the training. Every month it needs you less.',
  footnote: "Nothing is filed quietly. Anything it isn't sure about waits for you.",
};

export const beforeAfter = {
  eyebrow: 'The difference',
  title: 'Same statement. Different month.',
  blurb: 'Nothing about your bank changes. What changes is how much of your Sunday it costs.',
  before: [
    'Download the statement',
    'Scroll hundreds of rows',
    'Categorize by hand',
    'Paste into a spreadsheet',
    'Still not certain',
  ],
  after: [
    'Upload the statement',
    'Organized automatically',
    'Categorized, and it learns',
    'A dashboard, already current',
    'You actually know',
  ],
  beforeVerdict: 'Confusion.',
  afterVerdict: 'Confidence.',
};

/**
 * Written as what changes for the reader, not what the software contains. "You stop wondering
 * where your salary went" is the product; "spending analysis" is the implementation detail that
 * happens to deliver it.
 */
export const journey = {
  eyebrow: 'Over time',
  title: 'It gets better',
  titleLine2: 'the longer you use it.',
  blurb: 'One statement is useful. A year of them is a different thing entirely.',
  milestones: [
    { month: 'January', headline: 'You stop wondering where it went.', body: 'One statement in, and the month is no longer a guess.' },
    { month: 'March', headline: 'You start seeing yourself.', body: 'Three months is enough history for the repeats to show up.' },
    { month: 'June', headline: 'You catch it before it hurts.', body: 'Habits are visible early enough to change, not just to regret.' },
    { month: 'December', headline: 'You know where you stand.', body: 'A full year, readable at a glance. No spreadsheet involved.' },
  ],
  outcomes: ['Insights', 'Habits', 'Spending patterns', 'Trends'],
};

export const trust = {
  eyebrow: 'Complete transparency',
  title: 'What Fynora Will Never Do.',
  never: [
    'Sell your financial data',
    'Push loans at you',
    'Push credit cards at you',
    'Recommend a product because someone paid us',
    'Hide how a decision was made',
  ],
  always: [
    'Your data stays yours',
    'Every automatic decision can be reviewed',
    'You make the final call',
    'Your corrections improve future imports',
    'Built on trust, not commissions',
  ],
  whyTitle: 'Why?',
  whyLead: "Because we don't make money selling financial products.",
  whyBody:
    'No commissions, no referral fees, no sponsored placements. Our success depends entirely on building software people trust — which only works if the advice was never for sale.',
};

/**
 * Deliberately plain-language. Every step was verified against the code before it was written --
 * see Security.tsx's own note for what is and is not claimed. "Private storage" is the honest
 * label: files are content-addressed and integrity-checked, which is a real property, whereas
 * "encrypted storage" would be taking credit for a platform default the application does not
 * implement.
 */
export const security = {
  eyebrow: 'Security & privacy',
  title: 'You never hand us your bank login.',
  blurb:
    'Fynora reads statements you upload. There is no standing connection to your bank, so there is nothing for anyone to misuse.',
  chain: [
    { title: 'You', body: 'Your device, your statement.' },
    { title: 'HTTPS', body: 'Encrypted the whole way across.' },
    { title: 'Fynora', body: 'Checks it is really you, every request.' },
    { title: 'Private storage', body: 'Your files, fingerprinted and verified.' },
    { title: 'Your data only', body: 'Every query bound to your account.' },
  ],
  footnote: 'Passwords are hashed and never stored in readable form — not even we can see them.',
  ownership: 'Your financial data belongs to you.',
  ownershipAccent: 'Fynora exists to help you understand it — not to profit from it.',
};

export const showcase = {
  eyebrow: 'See it in action',
  title: 'Everything.',
  titleLine2: 'In one place.',
  blurb: 'Accounts, transactions, budgets, goals, reports and insights — one picture instead of six tabs.',
};

export const everywhere = {
  eyebrow: 'Anywhere',
  title: 'One picture. Every device.',
  blurb: 'The same account, the same numbers, wherever you happen to be looking.',
  moments: [
    { when: 'Morning', what: "Import last month's statement over coffee. Two minutes, done." },
    { when: 'Afternoon', what: "Check what's left in the food budget before ordering lunch." },
    { when: 'Evening', what: 'Nudge a goal after the salary lands. Same numbers, same account.' },
  ],
  // The web app is responsive and runs anywhere today. The native apps are built and on no store.
  // Update this note and the section wording together, never just the note.
  nativeStatus: 'In development',
  nativeNote:
    'Fynora runs in any browser today. Native iOS and Android apps are being built and are not on the app stores yet.',
};

export const useCases = {
  eyebrow: 'Made for everyone',
  title: 'For every stage of your life.',
  blurb: 'One platform. Endless clarity.',
  audiences: [
    { title: 'Working professionals', body: 'Salary in, spending out, savings visible — without keeping a spreadsheet alive.' },
    { title: 'Families', body: 'Household money in one place, so it can be discussed instead of guessed at.' },
    { title: 'Freelancers', body: 'Irregular income made legible, and the cash flow that follows it.' },
    { title: 'Students', body: 'Build the habit early, while the numbers are still small enough to learn on.' },
  ],
};

export const faq = {
  eyebrow: 'Questions',
  title: 'Anything else?',
  items: [
    [
      'Is my financial data secure?',
      'Passwords are hashed with bcrypt and never stored in readable form, sessions use short-lived access tokens with rotating refresh tokens, and every request to a protected endpoint is verified server-side. Traffic is encrypted in transit over HTTPS, and uploaded statements are fingerprinted so a corrupted or swapped file is detected rather than served. Your data is never sold or shared.',
    ],
    [
      'Does Fynora connect to my bank account?',
      'No, and that is deliberate. Fynora never asks for your net-banking credentials and holds no connection to your bank — it reads only the statements you upload yourself. There is no standing access for anyone to misuse.',
    ],
    [
      'Can I import several bank accounts?',
      'Yes. Savings accounts, credit cards, wallets and deposits, as many as you have. Each statement is matched to the right account automatically, and a single statement covering several accounts is split into them rather than flattened into one.',
    ],
    [
      'Can I upload password-protected PDFs?',
      'Yes. Most Indian banks e-mail statements locked with a password — enter it during upload and Fynora opens the file to read it. The password travels in the request body, never in a URL, and is not stored afterwards, so a later re-import will ask again.',
    ],
    [
      'How does categorization get better?',
      'Correct a transaction once and Fynora remembers that merchant, applying your preference on future imports. It records how confident each suggestion was and which signals matched, and anything below your confidence threshold waits for you rather than being filed quietly. It is always a suggestion, never a decision you cannot see or change.',
    ],
    [
      'Can I export or delete my data?',
      "Partly, and it is worth being precise. You can download any statement you uploaded and export a month's category breakdown as CSV. A full export of your raw transaction list, and self-service account deletion, are genuinely not built yet — deletion currently goes through support. These are missing features, not a lock-in strategy.",
    ],
  ] as [string, string][],
};

export const finalCta = {
  title: 'Your next bank statement',
  titleLine2: "doesn't have to be another PDF.",
  blurb: 'Let Fynora turn it into clarity.',
  primary: 'Start free',
  footnote: 'Free forever for the core product. No credit card required.',
};

export const footer = {
  mission: 'Helping people understand their finances with clarity, transparency and confidence.',
  principles: ['Built with transparency.', 'Designed for trust.', 'Made in India.'],
  tagline: 'Understand every rupee. Not just your balance.',
  instagram: 'https://www.instagram.com/finoratech.info/',
  instagramHandle: '@finoratech.info',
};
