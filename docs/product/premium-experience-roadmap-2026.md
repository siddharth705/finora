# Premium experience roadmap — 2026

**What this is.** An execution plan for turning Finora's existing capabilities into a premium
product. Built directly on
[capability-audit-2026-08-14](../project-management/capability-audit-2026-08-14.md) — start there
for the evidence; this document is what to do about it.

**Not scheduled. Nothing here precedes v1 launch.**

---

## 1. Executive summary

Finora already contains the foundational engines of a financial-intelligence product:
categorization with a five-tier decision cascade, merchant normalization with a learning loop,
duplicate detection, an evidence engine (ADR-005/006), working recurring-payment detection, goals,
budgets, insights, and a financial health score.

**The premium gap is not missing algorithms. It is that this intelligence does not reach the
user.** The audit found four recurring patterns: capability computed and never rendered; capability
deliberately retired to admin-only; capabilities siloed so facts never meet; and nothing delivered
outside the app. The most consequential single finding:

> The self-service Merchants / Rules / Learning Engine / Analytics / Activity pages **existed and
> were retired into the admin portal** (`frontend/src/types/index.ts:352-354`,
> `AnalyticsController.java:13-18`). 27 of 47 controllers are `Admin*`.

Strategically this is a strong position: most of the premium experience is **activation of existing
capability**, measured in days over tested backends — not quarters of new engineering. The plan
below sequences activation first, new engineering second, and treats billing as the gate on
monetizing any of it.

---

## 2. The premium promise

Positioning first, because it decides which features matter and which are noise. Not "more
features" — a sentence the product has to earn:

> **Finora Plus: your money, automatically organized, explained, and optimized.**

Three words, three obligations:

| Word | Obligation | Status |
|---|---|---|
| **Organized** | Data arrives and is categorized without manual work | Largely done — import + categorization |
| **Explained** | Every number can say why it is what it is | **The gap.** Attribution + lineage (§5B, §5F) |
| **Optimized** | Finora recommends actions and shows their effect | **Missing entirely.** Recommendation engine (§6) |

Anything that doesn't serve one of those three is not premium — it's a feature.

## 3. The financial intelligence graph

The audit's sharpest line names the structural problem:

> "The product knows your goal deadline, your budget limits, and your predicted future charges —
> and never puts any two of those facts in the same sentence."

Finora's entities exist as parallel silos over one transaction table. Premium value comes almost
entirely from the *edges* between them, and the edges are what's missing:

```
                        Transaction
                       /     |      \
              Merchant    Category    Recurring
                  |          |            |
                  |       Budget          |
                  \         |            /
                   \        |           /
                    ─── Attribution ───          <- MISSING: the join that
                            |                       makes "why" answerable
                       Health Score
                            |
                    Recommendation               <- MISSING: the layer that
                            |                       makes "so what" actionable
                          Goal
```

Two edges carry most of the value and neither exists:

- **Attribution** — joins category movement to the merchants that caused it (§5F)
- **Recommendation** — joins a finding to an action and its effect on a goal (§6)

Everything else in the diagram already exists. This is why the roadmap is activation-weighted:
the nodes are built, the edges are not.

## 4. Current capability map

Action verbs: **Expose** (backend exists, no user surface) · **Restore** (had a surface, retired) ·
**Explain** (works, but opaque) · **Upgrade** (surface exists, too shallow) · **Connect** (two
capabilities that never meet) · **Build** (genuinely new).

| Capability | Backend | User experience | Action |
|---|---|---|---|
| Import pipeline (CSV/PDF) | Strong | Good | Keep — best thing in the product |
| Recurring detection | Strong (interval clustering + amount tolerance) | One card on Insights; **no page, no route** | **Expose** |
| Merchant intelligence | Strong (normalization, aliases, learning) | Fallback display string only | **Restore** |
| Categorization | Strong (5-tier cascade) | One badge: "Auto" | **Explain** |
| Rule engine | Exists | Admin-only | **Restore** |
| Audit trail | Wired into ~20 services | Admin-only; user sees nothing | **Restore** |
| Health score | Exists (`computeHealthScore`) | A bare number | **Explain** |
| Dashboard notifications | Computed | **Never rendered** | **Expose** |
| Goals | Arithmetic only | Basic; `prompt()` dialogs | **Upgrade** |
| Budgets | Sum vs static limit | Alerts at 100% only | **Upgrade** |
| Insights | 5 templates, fixed thresholds | Descriptive | **Connect** (attribution) |
| Reports | 1 month, 1 dimension | `window.print()` for PDF | **Upgrade** |
| Ledger | Filters/bulk ops exist in API | Bulk ops are dead code | **Expose** |
| Security / sessions | Real per-device revoke | Solid | **Upgrade** (login history) |
| Accounts / balances | Well-engineered convention | Fine | Keep |
| Investments | Manual entry, no model | CRUD form | **Build** |
| Net worth | Sum; liabilities = cards only | Structurally incomplete | **Build** |
| Forecasting | — | — | **Build** |
| Proactive delivery | — | — | **Build** |

---

## 5. Phase 1 — Activate existing intelligence

Everything in this phase runs over backends that already exist and are tested. This is the phase
that changes how the product *feels* for the least engineering.

### A. Financial Command Center

The dashboard currently answers "what happened." It should answer "where do I stand, and what needs
me."

```
  Your financial status

  Health          82 / 100    ↑ 5 this month
  Cash flow       Healthy — ₹28,400 net last month
  Upcoming        ₹32,000 obligations in the next 14 days
  Savings goal    On track — Goa trip 68%

  ⚠ Food spending up 24% vs your 3-month average
  ⚠ HDFC card payment due in 3 days
```

Two of these lines need no new computation at all: `DashboardSummaryDto.notifications` already
computes credit-card due dates and low-balance warnings and **is never rendered**, and the health
score already exists. The "upcoming obligations" line is a Connect — `RecurringService.nextEstimate`
already predicts dates and currently feeds one card.

### B. Explainable intelligence

The highest-trust-per-line-of-code change in the product.

```
  Amazon                                       ₹4,999
  Category    Electronics

  Why this category?
    Merchant recognized as Amazon.in
    Matched your rule "Amazon → Electronics"
    Confidence 96% · learned from 12 previous Amazon purchases
```

**Blocked by exactly two fields.** `Transaction` persists `decisionSource` and `decisionRuleId`;
`TransactionDto` omits both, so five distinct decision sources
(`user_rule`/`global_rule`/`learned`/`rule`/`default`) collapse into a badge reading "Auto." The
explanation cannot reach the UI even if the UI wanted it. Adding the fields converts "it guessed"
into "it explained" across every transaction in the product.

Pairs with **Restore**: user-facing rule management (`RuleEngineService` exists; only
`admin-portal/GlobalRules.tsx` uses it). Explaining a decision is worth much more when the user can
then change the rule behind it.

### C. Subscription intelligence

**The single biggest surfacing miss.** `RecurringService` implements real detection — gap-regularity
clustering, amount consistency within 20%, minimum three occurrences, cadence labelling, next-date
estimation — and has no page, no route, and no sidebar entry.

```
  Subscriptions                    ₹8,450/month · ₹1,01,400/year

  Netflix        ₹649/mo    next 18 Aug
  Spotify        ₹119/mo    next 22 Aug
  Adobe        ₹1,675/mo    next 25 Aug   ⚠ rose from ₹1,499 in June
  Google One     ₹130/mo    next 28 Aug   ⚠ no activity since March

  Potential savings                ₹2,400/month
```

Price-increase detection is a cheap Connect — the amount-consistency check already computes each
series' average, so a step change is detectable with data in hand.

### D. Merchant intelligence

Turn plumbing into a consumer feature, over data `AdminUserMerchantController` already serves to
staff:

```
  Amazon                                        Shopping

  ₹48,500 across 22 purchases (12 months)
  Average order ₹4,041 · Largest ₹29,999 · 3 returns
  Most purchased  Electronics
  Categorized by  your rule "Amazon → Electronics"    [ Edit rule ]
```

### E. Goal intelligence

Goals store a `targetDate` used in **zero calculations**, and write a contribution history that is
**never read** (`findByGoalIdOrderByContributedAtDesc` has no production call site). The data for
pace math is already being persisted and discarded.

```
  Goa trip                                       68%
  ₹1,36,000 of ₹2,00,000

  At your current pace (₹8,000/mo)  → March 2027
  Your target date                   → January 2027
  To hit the target                  ₹10,700/mo  (+₹2,700)
```

Also table stakes at a paid tier: goals have no edit endpoint, budgets have no delete, and
contributions are captured via a browser `prompt()` (`Goals.tsx:60`).

### F. Attribution — the keystone

`InsightsService` computes category totals and merchant totals in **two independent aggregations
that never join**. The code knows Food is up 28% and separately knows the top merchant, and cannot
connect them. Attribution is therefore structurally impossible, not merely unimplemented.

```
  Food & Dining up 28% (₹12,500 vs ₹9,750 avg)
    Swiggy      +₹2,300   (11 orders vs usual 6)
    Restaurants +₹1,800
```

Small change; unblocks disproportionately much — explanations, health-score deltas, and every
later assistant answer depend on it. **Treat as the first item of Phase 1.**

### G. Trust surfaces

- **Account activity** — a user-visible view over `audit_logs`, which already records their actions
  and is admin-only today.
- **Data lineage** — source, evidence reference, and confidence on each transaction (the same
  provenance the Gmail proposal specs for its rows).
- **Ledger completeness** — wire the bulk operations that exist in the API client with zero call
  sites, and expose the account/category filters the backend already accepts.

---

## 6. Phase 2 — The recommendation engine

The missing middle layer, and the one that converts intelligence into money. Explanations tell a
user what happened; recommendations tell them what to do and what it's worth. Everything above is
input to this; the AI assistant (§7) is a *conversational interface onto it*, not a replacement for
it — which is why the engine must exist first.

```
   Attribution + subscriptions + budgets + goals
                        ↓
              Recommendation engine
                        ↓
        A specific action, with its quantified effect
```

The shape that matters — a finding is not a recommendation until it names an action and its
consequence:

```
  Weak    "You spent ₹8,450 on subscriptions this month."
  Better  "3 subscriptions show no activity since March."
  Premium "Cancelling Adobe and Google One saves ₹1,805/month —
           that reaches your Goa goal 2 months earlier."
```

That last line requires four existing capabilities to join: recurring detection (the subscription),
merchant/usage signals (the inactivity), goal pace math (§5E), and attribution (§5F). All four are
either built or Phase 1. **The recommendation engine is mostly a join, not a new algorithm** — which
is why it sits in Phase 2 rather than with forecasting.

### The premium value loop

A SaaS product needs the loop to close, or intelligence is just decoration:

```
   Data → Insight → Recommendation → User action → Better outcome
     ↑                                                    |
     └──────── more connected sources ← higher trust ─────┘
```

The closing step is the one products usually skip — **showing the user the result of the action they
took**:

```
  You cancelled Adobe.  ₹1,675/month saved.
  Financial health 79 → 82.  Goa goal now March 2027 (was May).
```

This is what makes a user connect a second data source, which improves every recommendation, which
earns the next action. It also produces the only honest premium-retention argument: the product
demonstrably made the user money.

## 7. Phase 3 — New engineering

Only after Phase 1. Each item here is genuinely new and needs its own proposal.

**Forecasting.** Verified absent repo-wide — no cash-flow projection, no safe-to-spend, no
month-end estimate.

```
  Based on your last 6 months
  Expected month-end balance    ₹42,000
  Safe to spend this week       ₹6,200  (after ₹32,000 upcoming obligations)
```

**Net worth intelligence.** Today net worth is a sum of account balances, and **liabilities are
credit cards only** — loans and EMIs are recognized at import then dropped
(`NOT_MODELLED_YET`). Net worth is structurally wrong for any user with a home, car, or education
loan. Needs liability modeling with amortization before "net worth growth +12% YoY" means anything.
Investments separately need a real holding model (units, cost basis, price/NAV feed) — no returns
math exists anywhere (no XIRR/CAGR repo-wide).

**Timeline intelligence.** Correlating a Gmail order → bank debit → refund into one financial event.
Note this is **the same engineering problem as cross-source duplicate detection** (Gmail proposal
§11) — solve once, ship twice.

**Proactive delivery.** Email is auth-only today; budget alerts fire at 100%, after the overspend.
Mid-month pace warnings, renewal reminders, and a weekly digest need a scheduled user-facing job —
none exists.

**AI money assistant.** A financial reasoning layer, not a chatbot:

> **"Can I buy a car?"** — Yes, but wait ~3 months. An ₹18,000 EMI drops your savings rate from 31%
> to 14%, and your emergency fund is at 1.8 months against your 3-month target.

**Hard prerequisites, in order:** attribution (§3F), forecasting, and liability modeling. Built
before those, it can only restate totals the user can already see. Consistent with the Gmail
proposal's stance on AI: **it proposes, the user confirms** — reading and explaining is a very
different risk profile from writing to the ledger.

---

## 8. Premium subscription strategy

**Nothing here is monetizable today.** No `Plan`, `Subscription`, or `Entitlement` entity exists;
billing is itself only a proposal. The one gate that exists, `FeatureFlag`, is a global boolean with
no per-user dimension that **fails open** — the opposite of a paywall.

Two things follow:

1. **Billing is a prerequisite for the premium tier, not a parallel track.**
2. **Build the entitlement seam now, cheaply.** A single fail-closed `canUse(userId, feature)` check
   means switching to real plans later is one implementation change rather than an audit of every
   call site.

A proposed tier split — **for decision, not decided.** Pricing in particular is Sid's call:

| Free | Finora Plus | Finora Pro |
|---|---|---|
| Manual entry | Everything in Free | Everything in Plus |
| CSV/PDF import | Subscription intelligence | Family / household |
| Core categorization + ledger | Health score + explanations | Wealth planning |
| Basic budgets and goals | Advanced reports, forecasting | AI CFO assistant |
| Current-state dashboard | Connected sources (Gmail, SMS) | Investment integrations |

Worth deciding early, because it determines which Phase 1 surfaces need gating at all — and several
of them (explainability, activity log) are arguably trust features that should stay free.

### Measuring whether any of this works

No product-analytics infrastructure exists today (confirmed in the audit) — so this is a Phase 1
dependency, not an afterthought. Without it the roadmap is untestable and re-prioritization after
launch is guesswork.

**Activation — first 7 days.** The question: does a new user reach the moment the product becomes
useful?

| Signal | Why it matters |
|---|---|
| Connected a source (or completed an import) | The precondition for everything else |
| Viewed first insight | Did intelligence reach them at all? |
| Confirmed first transaction | Did they trust it enough to act? |
| Created first goal or budget | Did they invest in the product? |

**Engagement — weekly.** Dashboard opens, insights viewed, **recommendations accepted** (the loop
closing), goals updated, subscriptions reviewed.

**Conversion signals.** Which behaviors predict willingness to pay — likely candidates to validate,
not assume: multiple connected sources, a high count of detected recurring transactions,
investment tracking in use, assistant usage once it exists.

**The one metric that matters most:** *recommendations accepted, and outcome delivered.* It is the
only measure that the value loop (§6) is closing rather than producing advice nobody takes.

---

## 9. Execution order

```
PHASE 0 — Launch                                    ← current priority
 └── v1 blockers only. No premium work.

PHASE 1 — Make existing intelligence visible        ← highest ROI
 ├── 1. Attribution join            (keystone — unblocks everything below)
 ├── 2. Transaction explanation     (two DTO fields)
 ├── 3. Subscription Center         (page over a working algorithm)
 ├── 4. Merchant intelligence page  (restore)
 ├── 5. User rules                  (restore)
 ├── 6. Account activity            (restore)
 ├── 7. Health score explanation
 ├── 8. Render dashboard notifications + ledger completeness
 └── + product analytics            (dependency, not afterthought)

PHASE 2 — Connect intelligence
 ├── Recommendation engine + value loop
 ├── Financial timeline
 ├── Goal intelligence (pace, projection)
 └── Budget intelligence (burn rate, mid-month alerts)

PHASE 3 — Premium CFO
 ├── Forecasting / safe-to-spend
 ├── Liability modeling → real net worth
 ├── Holdings model (units, cost basis, returns)
 └── Proactive delivery (scheduled, outside the app)

PHASE 4 — AI CFO
 └── Conversational assistant, planning, personalized advice

PARALLEL — Billing / entitlements   (gates monetization of any of the above)
CONNECTORS — Gmail · SMS · UPI · account aggregator · broker APIs
```

**One correction to the phase list as originally sketched:** attribution belongs in **Phase 1, item
#1**, not Phase 2. It is small, it is a join rather than an engine, and Phase 2's recommendation
engine plus Phase 4's assistant are both unbuildable without it. Sequencing it late would stall
both.

**On Gmail's position.** The [Gmail proposal](../proposals/gmail-transaction-sync-proposal.md) is
mature and correctly scoped, and it solves data *acquisition*. Placing it in Phase 3 is defensible —
but note the tension recorded earlier: Gmail is also described as an acquisition/activation feature,
and activation matters most at launch. Whichever way that resolves, **Google's CASA/OAuth
verification is multi-week calendar time Finora does not control** and should start early
regardless of when the build happens.

---

## 10. Open questions

- **Why were the self-service surfaces retired?** This plan treats it as reversible, but the
  retirement was deliberate and the reasoning has not been recovered. There may have been a real
  cause (support load, data quality, performance) that changes the design rather than the decision.
  **Worth answering before Phase 1's Restore items.**
- Free/Plus/Pro split and pricing (§6) — product decision, blocks entitlement design.
- Whether explainability and activity log should be free trust features rather than paid.
- Household/multi-user — raised repeatedly, not designed; interacts with the ownership-confidence
  model in the Gmail proposal.
- This roadmap reads code, not usage. It says what is missing, not which absences users feel most.
  Post-launch telemetry should re-order Phase 1 before it is executed.
