# Insights — architectural vision, not a roadmap

**Status:** directional thinking, not a commitment. No estimates, no priority against other
workstreams, no sequencing promise. See "Why this document exists" below for why that
distinction is deliberate.

**Last updated:** 2026-08-30

## Why this document exists

A proposal in `docs/proposals/` implies a team is likely to execute within the next few
cycles. That isn't true here yet — mobile launch, production hardening, parser quality, and
categorization accuracy are the larger platform priorities competing for attention right now,
and insight features have a specific failure mode: they expand rapidly the moment they become
a formal roadmap item, because every one of the capabilities below sounds good in isolation
and it's easy to greenlight all of them at once.

This document exists to preserve the thinking — what "AI Insights" actually is today, what's
wrong with it, what a meaningfully better version looks like, and what to deliberately not
build — without that pressure. Nothing here is scoped, estimated, or sequenced against other
work. When some of this does become a proposal, it should be because a specific capability
was chosen deliberately against the platform's other priorities at that time, not because this
document existed and asked to be executed.

## 1. Current state

"AI Insights" (`InsightsService.build()`, `/api/v1/insights`, the Dashboard preview card and
the full `/app/insights` page, mirrored on mobile) is real, rule-based statistics computed
from a user's actual transactions — not an LLM call. The product is honest about this: the
Insights page and mobile screen both say so directly to the user, and the README lists "Real
AI / OpenAI integration" under Known Gaps, naming `InsightsService.build()` and
`CategorizationService.suggest()` as the two seams where a real model call would plug in.

What it computes today, every month:
- Total spend and category count
- The single biggest spending category
- Month-over-month category deltas ("movers"), against a rolling prior-months average
- The single top merchant by spend
- One grounded recommendation: "consider a budget for X" when a category is trending up with
  none set

All of it correctly nets refunds, excludes duplicates/transfers/CC-payment settlements (via
the shared `RefundNetting` utility), and scopes to live accounts only. As of PR #589, it also
surfaces a genuinely new spending category (previously silently dropped), never lets a blank
"Unknown" merchant bucket win top-merchant, and has full unit coverage for the first time.

Two adjacent pieces of the app are relevant context, because a future capability below might
duplicate or extend them rather than start from nothing:

- **A Financial Health Score already exists** (`DashboardService.computeHealthScore`) —
  savings rate, debt score, emergency fund, spend consistency, cash flow stability, each
  broken out on the Dashboard. Any future "health score" idea should extend this, not
  reinvent it.
- **A per-number trace already exists, for admins only** — `InsightsExplorerService` reruns
  the exact same `InsightsService.pipeline()` seam in a mode that returns the contributing
  transactions behind each number, for the Insight Explorer (admin portal, founder ops). The
  seam that would make user-facing insights explainable (see §4) is already built; it just
  isn't exposed to the user yet.

## 2. Problems with the current approach

Not bugs — PR #589 covered the actual defects. This is about what's structurally thin even
once the numbers are all correct.

- **It only ever answers "what happened."** Every sentence is retrospective. Nothing projects
  forward against something the user is actually trying to do — a budget, a goal, their own
  history.
- **It's 100% expense-only.** No income trend, no savings-rate movement, no net-worth
  trajectory, no goal-pacing commentary — despite the app already computing all of these
  elsewhere. "Insights" only ever talks about spending.
- **Nothing combines signals.** Every sentence is one isolated statistic. There's no "X is up
  while Y is barely funded" — the kind of synthesis that reads as actually paying attention
  rather than running a report.
- **Exactly one recommendation type exists.** "Set a budget for X." Nothing else is
  actionable.
- **Everything is compared to a generic threshold (15%), not to the user.** A flat percentage
  cutoff feels like a rule, not a read on this specific person's habits.

The throughline: today's Insights is an **observation engine**. It looks once and is
forgotten, because it never answers the question a user actually opens the page with, which
isn't "what happened" — it's "what should I pay attention to, and what should I do."

## 3. Observation → decision engine

Two examples of the same fact, reported two ways:

> Observation: "Dining is up 18%."
> Decision-relevant: "At your current pace you'll exceed your dining budget by ₹3,200."

> Observation: "You've saved ₹50,000."
> Decision-relevant: "You're on track to reach your Europe trip goal in March 2027."

The first version of each is a fact nobody acts on. The second is a fact that changes
behavior, because it's stated against something the user already told the app they care
about — a budget, a goal — rather than against the calendar.

This is not a call to add prediction models. Most of the shift is reframing: take a number
Insights (or Budgets, or Goals) already computes, and state it against the user's own target
instead of against last month. The hard part is judgment about which comparison is actually
useful, not the arithmetic.

## 4. Candidate future capabilities

Listed in the priority order that currently seems right — cheapest and most explainable
first, hardest and least certain last. This ordering is itself a candidate, not a
commitment; nothing below has been scoped.

1. **Personal-baseline insights.** Everything needed already exists: historical
   transactions, category totals, merchant totals. "Dining is 62% above your normal monthly
   average" is a straightforward extension of the mover math already in `InsightsService` —
   compare to a longer personal history instead of (or alongside) the current 4-month window,
   and state the comparison as a deviation from the user's own norm rather than a flat 15%
   threshold. No ML, no LLM, no prediction — and it reads as personalized because it is.

2. **Anomaly detection.** Likely the highest wow-per-engineering-hour item on this list, for
   the same reason baselines are cheap: it's deviation-based logic over data already
   computed. "This month's healthcare spending is significantly higher than your normal
   pattern." "A new recurring payment of ₹799/month was detected" (this one has a real head
   start — `RecurringService.detectForUser` already detects recurring merchants; anomaly
   framing is a presentation layer on top of data that already exists). These answer the
   actual question someone opens an Insights page with: "what should I pay attention to?"

3. **Insight ranking / scoring.** Once there are more candidate observations than fit on a
   page, something has to decide which ones are worth showing. A crude `impact × confidence
   × relevance` score, surfacing only the top few, is what separates "the app found 40 things
   to say" from "the app told me the two things that matter."

4. **Insight explanations / traceability** (not on the original list, added here because the
   seam already exists). Every insight should be able to answer "based on what?" — tapping
   "Dining spend increased ₹4,200" should show the transactions behind it, same shape as the
   admin Insight Explorer's trace already does. This is a strong candidate to build *early*
   relative to its position in a pure value-per-effort ranking, precisely because
   `InsightsService.pipeline()` was already factored out for exactly this purpose (see §1) —
   the cost of exposing it to users is much lower than building it from nothing, and
   transparency compounds in a financial product: it builds trust and makes every other
   capability on this list easier to debug when it says something wrong.

5. **Forecasting / budget-risk projection.** "You're projected to exceed your dining budget
   by ₹2,700" — the clearest example of the observation → decision shift in §3. Meaningfully
   harder than 1–4: it requires a pace model (even a simple linear one) and has a real way to
   be visibly wrong (a big one-off purchase early in the month skews a naive projection).
   Wrong here is more damaging than wrong in an observation, because a projection asks the
   user to trust it prospectively.

6. **Cross-feature reasoning.** "Shopping increased ₹8,000 this month and is the primary
   reason your savings rate fell from 24% to 17%." Valuable, but it's the item most likely to
   quietly turn into a much bigger project than it looks: it means correctly attributing
   causality across category spend, savings rate, and whatever else gets pulled in, and a
   wrong causal claim ("X is the reason Y") is a stronger, more falsifiable statement than a
   single-signal observation.

7. **Goal-aware recommendations.** "Reducing Zomato spending by 15% would fully fund your
   emergency-fund target two months earlier." The natural conclusion of the decision-engine
   framing, and probably sequenced last among the "safe" items because it depends on
   forecasting (5) already being trustworthy — a recommendation built on an untrustworthy
   projection is worse than no recommendation.

8. **LLM narration layer.** See §6. Explicitly last, and explicitly downstream of everything
   above existing first, not a replacement for it.

**Deliberately not on this list, for now:** life-event detection (new job, house move, new
loan). See §5.

## 5. Non-goals

Things this document explicitly argues against building soon, and why — not because they're
bad ideas, but because the cost of being wrong is asymmetric with the other candidates above.

- **Life-event inference.** "Looks like you changed jobs" or "looks like you moved homes,"
  correct, is memorable in the good way. Wrong, it's memorable in the way that erodes trust
  in every other insight the app has ever shown — "the app doesn't understand my life" reads
  very differently from "eh, it missed one." The precision bar for this category of claim is
  categorically higher than "spending increased 18%," and there isn't yet the confidence data
  to clear it. Keep behind an experimental flag, revisit once real usage data exists on the
  cheaper capabilities above.
- **An LLM call before structured insights exist.** See §6 — this is a sequencing non-goal,
  not a permanent one.
- **A second, competing health/insight score.** A Financial Health Score already exists
  (§1). Any future scoring idea extends it or doesn't ship, rather than fragmenting "how
  healthy is my financial life" into two disagreeing numbers.
- **Insight volume as a goal.** More sentences is not more value — see the ranking item in
  §4. A page that says three things a user acts on beats a page that says fifteen things a
  user skims past.

## 6. AI integration principles

The architecture that matters, stated as a shape rather than a diagram:

```
Transactions → Analytics → Structured Insights → Ranking → LLM Narration
```

Not:

```
Transactions → LLM
```

The distinction is the whole point. In the first shape, the LLM's only job is turning an
already-correct, already-ranked, already-explainable structured fact into better prose — it
is never the thing computing whether a number is right. In the second, the LLM is doing
financial arithmetic on a user's real money, with no structured ground truth to check it
against. The first is cheaper, testable (the structured layer has a right answer; the prose
layer doesn't need one), and safe to get wrong in the way that matters least — bad phrasing
of a correct fact, never a fabricated fact stated confidently.

This isn't a new principle introduced by this document — it's already how the current,
non-LLM implementation is written. `InsightsService`'s own class comment states it for the
one recommendation that exists today: not "a fabricated general 'you should spend less'
suggestion, which would be advice this service has no real basis to give." Every future
capability in §4, LLM-narrated or not, inherits that same rule: **an insight is only ever a
restatement of something the structured layer actually computed.** The LLM milestone changes
how a true statement is phrased. It never becomes the source of whether the statement is
true.

Practically, that means:
- Every future capability in §4 should be buildable and fully correct with *no* LLM in the
  loop first — narration is a presentation upgrade on top of a working structured layer, not
  a prerequisite for one.
- Ranking (§4.3) happens before narration, not after — an LLM should never be asked to decide
  what's worth saying, only how to say what ranking already selected.
- Traceability (§4.4) is what keeps this honest in practice: if every insight can show its
  own receipts, a narrated sentence that oversells or misstates what's behind it is
  falsifiable by the same mechanism that makes the plain statistical version trustworthy
  today.
