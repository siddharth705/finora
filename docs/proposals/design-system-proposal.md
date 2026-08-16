# Finora Design System — Scoping Proposal (PR2)

**Status:** Scoping only. **Nothing here is implemented.** D-22 (`docs/project-management/plans/project-plan-v1.0.md`)
set the sequence PR1 (Dashboard redesign, merged) → **PR2 (this document)** → PR3 (Real Dashboard
Intelligence) → PR4 (Premium Layer). This is the scoping work product for PR2, produced before any
component code is written, per this plan's own "audit before building" rule (§8a).

**The ask, as the owner framed it:** `frontend/src/design-system/` with `FinoraCard`, `MetricCard`,
`EmptyState`, `SectionHeader`, `QuickActionCard`, `ChartContainer`, `PremiumBadge` — "because right
now every page will slowly become inconsistent." No `design-system/` directory exists yet; this is
genuinely greenfield, not a rename of something already there.

**Method:** rather than accept "every page will slowly become inconsistent" as a prediction, checked
it against the current codebase — grepped every app-shell page (`Dashboard`, `Ledger`, `Reports`,
`Investments`, `Budgets`, `Goals`, `StatementHistory`, `Insights`, `Settings`, `Setup`) for the seven
proposed components' current equivalents. Six of seven have real, countable drift already; one is
pure prep-work with nothing to migrate yet. Findings and numbers below.

---

## 1. `FinoraCard` — the base card shell

**Evidence:** `bg-card rounded-xl2 ... shadow-card border border-border` (or a near-variant) appears
**43 times** across `pages/*.tsx` and `components/*.tsx`, each one hand-written with slightly
different padding (`p-5`, `p-6`, `p-8`, `p-10`), and inconsistent whether `overflow-hidden` or a
fixed width is added. No two are byte-identical, but all 43 are visually the same primitive: a
rounded, shadowed, bordered surface theming already defines via `card`/`border`/`shadow-card`/`xl2`
tokens in `tailwind.config.js`.

**Scope:** `FinoraCard({ children, padding?, className? })` wrapping the class string once.
Straightforward extraction — the tokens already exist, this just stops re-typing them.

## 2. `MetricCard` — the KPI number

**Evidence:** two incompatible number-display languages already coexist. Dashboard's 5 KPI cards use
`text-2xl font-bold text-ink` for the value and `text-sm text-muted` for the label (`Dashboard.tsx`).
`Reports.tsx` renders its Income/Expense/Net trio with `text-xl font-semibold` (a full size step down)
and `text-[10px] uppercase text-gray-500` for the label — different size, different weight, and a raw
Tailwind gray instead of the `muted` token. `Investments.tsx` and `Budgets.tsx` have no shared metric
styling with either. A user moving from Dashboard to Reports today sees financial figures rendered at
two different visual weights with no reason for the difference.

**Scope:** `MetricCard({ label, value, icon?, iconBg?, iconColor?, delta? })`. Delta rendering
(the ▲/▼/— line) already has a working implementation in `Dashboard.tsx` worth lifting as-is rather
than redesigning.

## 3. `EmptyState` — this session's own new pattern, not yet anywhere else

**Evidence:** Dashboard's per-section empty state (icon circle + title + description + CTA button,
shipped in PR1) is the only rich empty state in the app. Every other page still uses a bare, one-line
italic string:

| File | Line | Current markup |
|---|---|---|
| `Budgets.tsx` | 76 | `text-sm italic text-gray-500` — "No budgets set yet." |
| `Goals.tsx` | 97 | `text-sm italic text-gray-300` — "No goals yet." |
| `Investments.tsx` | 143, 210 | `text-sm italic text-gray-500` — two separate empty states |
| `StatementHistory.tsx` | 204 | `text-sm text-muted` — "No statements imported yet." |
| `Insights.tsx` | 49, 68 | `text-sm italic text-gray-500` — two separate empty states |
| `Reports.tsx` | 58 | `text-muted`, no icon, no CTA |

Six files, eight sites, **three different color tokens** for the same "nothing here" meaning
(`text-gray-500`, `text-gray-300`, `text-muted`) — `Goals.tsx`'s `text-gray-300` is barely visible
against a light background and may be an outright bug, not just inconsistency. This is the same
"empty state is doing most of the work" gap the owner named for PR3's cash-flow timeline, just on
every page Dashboard *isn't*.

**Scope:** extract Dashboard's existing `SectionEmptyState` (currently private to `Dashboard.tsx`,
`pages/Dashboard.tsx:103`) into `design-system/EmptyState.tsx` unchanged, then it already has one
real, working call site — this is a move, not a redesign.

## 4. `SectionHeader` — heading + "View All" link

**Evidence:** the `<h2 className="font-semibold text-ink">...</h2>` + `<Link ... className="text-xs
text-primary font-medium">View All</Link>` pair appears 5 times inside `Dashboard.tsx` alone (Spending
Breakdown, Accounts Overview, Recent Transactions, Budget Progress, Goals), each one a separate
hand-written pair. No drift *between* pages yet — because no other page has sub-sectioned headers to
drift from — but real duplication within the one page that does.

**Scope:** `SectionHeader({ title, viewAllTo? })`. Lowest-risk extraction of the seven — one file,
one already-consistent pattern, just currently copy-pasted five times instead of parameterized once.

## 5. `QuickActionCard` — new primitive, no current drift to fix

**Evidence:** the 7-item Quick Actions grid (`Dashboard.tsx:652`) is the only action-tile grid in the
app. Nothing else to migrate — this is pure prep for the *next* page that wants one, not a fix.

**Scope:** extract the one existing tile shape (icon + label, `Link` or `button` depending on
whether `to` is present) as `QuickActionCard`. Small, but justified only as "don't hand-roll a second
one when Reports or Investments eventually wants a shortcut row" — lower priority than 1-4.

## 6. `ChartContainer` — two independent Chart.js wrappers

**Evidence:** `Dashboard.tsx` (Cash Flow line chart, Spending Breakdown doughnut) and
`Investments.tsx` (allocation doughnut, performance line) each construct their own Chart.js `options`
object inline — colors, font, tooltip styling, aspect ratio all hand-set twice, independently.
`Reports.tsx`'s category breakdown does *not* use Chart.js at all (custom bar divs), so it's a third,
unrelated pattern for what is conceptually the same "show me a distribution" need.

**Scope:** narrower than the name suggests. `ChartContainer` should own the *wrapper* (fixed-height
div, loading state, empty state via #3 above, consistent card padding) and a shared `chartOptions`
base object (font family, tooltip theme, grid color) that Dashboard's and Investments' existing
`<Line>`/`<Doughnut>` calls both spread into. Not a chart-library abstraction — Chart.js stays
directly imported at each call site, this just stops the options object from being retyped.

## 7. `PremiumBadge` — pure prep, deliberately nothing to migrate

**Evidence:** grepped for any existing beta/premium/coming-soon badge anywhere in `pages/` or
`components/` — exactly one hit, Dashboard's own `AI Insights` "Beta" pill (`Dashboard.tsx:591`,
`text-[10px] uppercase font-semibold bg-primary/15 text-primary px-1.5 py-0.5 rounded`). No tier
badges exist anywhere because no tier system exists anywhere (D-7 still open, `FeatureFlag` still
fails open per D-19's audit).

**Scope, deliberately limited:** extract the one existing "Beta" pill as a generic `Badge({ tone,
label })` (tones: `beta`, and whatever PR4 needs later) so PR4 doesn't invent its own badge component
independently. **Not building `PremiumFeatureGate`/`SubscriptionPlan`/entitlement-checking logic
here** — that's PR4's job, gated on D-7 pricing same as before. This component is a visual primitive
only; wiring it to real entitlements is explicitly out of scope for PR2.

---

## What this document deliberately does not scope

- **Migrating all 99 raw `text-gray-*` usages** found across `pages/`/`components/` to the `muted`/
  `ink` semantic tokens. Real number, real drift, but a full sweep is its own mechanical PR, not
  something to bundle into the component-building PR — flagged here so it doesn't get lost, not
  silently absorbed into "PR2 fixes everything."
- **Marketing/public pages** (`Landing`, `About`, `Careers`, `Contact`, `Help`, `Privacy`, `Terms`,
  `RefundPolicy`, `ShippingPolicy`) and **auth pages** (`Login`, `Register`, `ForgotPassword`,
  `ResetPassword`, `VerifyPhone`). Different design language on purpose (marketing site vs. product
  shell) — the owner's own comparison (Stripe/Linear/Notion) was about the *dashboard*, and pulling
  the public site into this system would be scope creep no one asked for.
- **`PremiumFeatureGate`/`SubscriptionPlan`/`FeatureFlag` architecture** — PR4, blocked on D-7.
- **Fixing `FeatureFlag`'s fail-open default** — flagged in my last message as a real gap for
  whoever eventually scopes PR4, not part of this component-library PR.

## Open question for the owner — migration scope inside PR2

The audit above is complete; what's not yet decided is how far PR2 goes once the seven components
exist:

- **(a) Components only.** Build all seven, migrate Dashboard's own existing usages onto them
  (proves each component works against its real call site), touch no other page. Smallest, fastest,
  lowest risk of merge conflicts with any other in-flight work on Ledger/Reports/etc.
- **(b) Components + first-wave migration.** Build all seven, migrate Dashboard, and additionally
  migrate the `EmptyState` gap (§3) into the 6 files/8 sites above — that's the one drift that's
  actively a small visible bug (`Goals.tsx`'s near-invisible `text-gray-300`), not just inconsistency.
- **(c) Components + full page migration.** (a) or (b) plus bringing `Reports.tsx`'s `MetricCard`
  styling and `Investments.tsx`'s `ChartContainer` usage into line too. Most thorough, largest diff,
  most likely to need its own review pass separate from the component work itself.

No recommendation forced here — (a) is the safest "prove the system works" slice consistent with
every prior D-row's "narrowest slice first" discipline (D-17, D-19, D-21), but (b) closes a real,
small, currently-live bug (the barely-visible Goals empty state) for one extra file's worth of diff.
