# Finora Personal CFO — the experience

**What this is.** What Finora *feels like* when it works. No architecture, no schemas, no phases —
those live in [premium-experience-roadmap-2026](premium-experience-roadmap-2026.md). This document
exists so that every engineering decision has a user moment to answer to.

**How to use it.** When a roadmap item is in question, ask: *which moment below does this create?*
If none, it may not belong in the premium tier.

**Not scheduled.** Aspirational by design — several moments here need capabilities that don't exist
yet. Each is annotated with what it depends on, so nothing reads as available when it isn't.

---

## The North Star moment

Monday morning. The user opens Finora and, in about eight seconds, knows exactly where they stand.

```
  Good morning, Siddharth

  Financial health          82 / 100     ↑ 4 this month
  Safe to spend             ₹18,400      after upcoming obligations
  Next 14 days              ₹32,000      HDFC EMI tomorrow

  Your money moved
    ✓  Salary received                          +₹1,10,000
    ✓  Netflix renewed                              −₹649
    ⚠  Shopping 35% above your usual              −₹12,400

  Worth doing
    Reduce shopping by ₹2,000 this month to keep
    your Goa trip on schedule for January.
                                      [ Show me why ]
```

Every line answers a different question a person actually has: *Am I okay? What can I spend? What's
coming? What changed? What should I do?*

**What this needs:** health score (exists), safe-to-spend (forecasting — Phase 3), upcoming
obligations (recurring detection exists, needs surfacing), the "35% above usual" comparison (exists
in insights), and the recommendation (Phase 2 engine).

---

## The five feelings

Each moment below is written to produce one specific feeling. That is the actual product spec.

### 1. "It understands my money" — Explanation

The user taps a transaction they don't recognize.

```
  Amazon                                        ₹4,999
  Electronics · 14 Aug

  Why Electronics?
    Recognized as Amazon.in
    Your rule: "Amazon → Electronics"
    Learned from 12 previous Amazon purchases

  Where this came from
    Gmail receipt · Amazon Order #12345
    Confirmed by you on 14 Aug
                                       [ Change rule ]
```

The feeling is *nothing here is a black box*. Finora never says "trust me" — it shows its work, and
the user can overrule it in one tap. **Needs:** two DTO fields (Phase 1). This is the cheapest
premium moment in the entire product.

### 2. "It found money I was losing" — Discovery

```
  Subscriptions                     ₹8,450/month · ₹1,01,400/year

  Adobe             ₹1,675/mo    ⚠ no activity since March
  Google One          ₹130/mo    ⚠ no activity since March
  Netflix             ₹649/mo    renews 18 Aug
  Spotify             ₹119/mo    renews 22 Aug

  You could save ₹1,805/month
```

The feeling is *this paid for itself*. A user who discovers ₹21,660/year of waste has already
justified a subscription many times over — and it is the single most concrete argument for the paid
tier. **Needs:** a page over `RecurringService`, which already works (Phase 1).

### 3. "It told me before it mattered" — Anticipation

Thursday, unprompted, outside the app:

```
  Finora

  Heads up — ₹32,000 HDFC EMI clears tomorrow.
  Your balance covers it with ₹18,400 left.
  You're still on track for Goa.
```

The feeling is *someone is watching this for me*. This is the difference between software the user
must remember to open and software that earns its place. Today Finora is entirely passive: email is
auth-only, and budget alerts fire at 100% — after the overspend. **Needs:** proactive delivery
(Phase 3). Arguably the single largest perceived-value jump on the roadmap.

### 4. "It told me what to do, not just what happened" — Guidance

```
  Your shopping is ₹4,100 above your usual.

  Driven by
    Amazon        +₹2,900   (3 orders vs your usual 1)
    Myntra        +₹1,200

  If you hold shopping at ₹8,000 for the rest of the month,
  your Goa goal stays on track for January.
  Otherwise it moves to March.
                          [ Set a ₹8,000 shopping cap ]
```

The feeling is *it's on my side*. Note the structure: finding → attribution → consequence → one-tap
action. A number alone is a report; this is advice. **Needs:** attribution (Phase 1) and the
recommendation engine (Phase 2).

### 5. "It showed me I'm doing better" — Reward

Two weeks after cancelling Adobe:

```
  Nice — that cancellation saved you ₹1,675 this month.

  Financial health   79 → 82
  Goa trip           March 2027 → January 2027
```

The feeling is *this is working*. This is the step products skip, and it's the one that closes the
loop: a user who sees their action produce a result connects another data source, which makes every
future recommendation better. **Needs:** the value loop (Phase 2).

---

## The first five minutes

Retention is decided at signup, not at month three.

```
  Let's build your financial picture.

  ① Add your accounts            ✓ 3 accounts
  ② Import a statement           ✓ 247 transactions
  ③ Review what we found         ✓ 18 subscriptions detected

  Here's what we can already tell you

    ₹8,450/month goes to subscriptions
    3 of them show no recent activity
    Your savings rate is 24%

  Your dashboard is ready.
```

The principle: **show value before asking for commitment.** The user should reach a genuine insight
in the first session, not an empty dashboard asking them to enter data for weeks first. Subscription
detection is the ideal first-session payoff because it works on a single imported statement and
produces a number a person immediately cares about.

---

## What Finora should never feel like

Explicit anti-goals — several describe the product as it exists today, which is the point:

- **A data-entry chore.** If the user is doing bookkeeping, the product failed. (Today: goal
  contributions are captured via a browser `prompt()`; goals can't be edited; budgets can't be
  deleted.)
- **A wall of numbers with no meaning.** Reports nobody reads because they answer nothing.
- **A black box.** "Auto" as an explanation. Recommendations without reasoning.
- **A nag.** Alerts that fire after the damage, or so often they're ignored. (Today: budget alerts
  fire only at 100%.)
- **A chatbot bolted onto a tracker.** An assistant that can only restate totals already on screen
  is worse than none — it advertises the absence of real intelligence.
- **Confidently wrong.** Every automated conclusion should carry its confidence and its evidence.
  In a finance product, a wrong number costs more trust than a missing one earns.

---

## The one-sentence test

> **Finora Plus: your money, automatically organized, explained, and optimized.**

For any proposed feature, ask which word it serves:

- **Organized** — does it reduce manual work?
- **Explained** — does it make something understandable that wasn't?
- **Optimized** — does it lead to a better outcome, and show that it did?

If a feature serves none of the three, it isn't premium — regardless of how much engineering it
takes.
