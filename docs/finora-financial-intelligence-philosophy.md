# Finora Financial Intelligence Philosophy

**Status:** Product Architecture — foundational vision document. This is the philosophy every other engine spec in `docs/` should be read against: `financial-intelligence-engine-spec.md` (Merchant Resolution, Confidence, Learning — ✅ implemented), `rule-engine-relationship-engine-eds.md` (Rule Engine, Decision Source, Relationship Engine — ✅ implemented), and `statement-intelligence-engine-spec.md` (bank-specific/PDF parsing, compliance-gated, not yet built). Where this document's target pipeline differs from what those specs describe as currently implemented, that's called out explicitly in §9 rather than presented as settled.

---

## Problem Statement

Traditional personal finance applications assume that every transaction contains enough information to determine its purpose.

In reality, especially in India, this assumption is incorrect.

Example:

```
UPI
Rahul Kumar
₹342
```

Was this: Uber? A friend? Groceries? Milk? An auto ride? A haircut? Rent?

The bank statement alone cannot answer this.

Therefore, Finora should never rely solely on transaction descriptions to understand a user's finances. Instead, Finora should build a Financial Knowledge System that continuously learns from multiple sources of financial evidence.

---

## Core Principle

**The bank statement is the source of truth for money movement. Everything else provides context.**

The bank statement tells us: money moved, the amount, the date, the description, the account.

It does not tell us *why* the money was spent. That intelligence must come from additional evidence and user learning.

---

## Financial Evidence Sources

Instead of relying on one source, Finora should gradually combine multiple sources:

```
Bank Statement
        │
Credit Card Statement
        │
Gmail Receipts
        │
PDF Invoices
        │
Manual Entries
        │
Future Mobile App (SMS / Notifications)
        │
Future Merchant APIs
        │
User Learning
```

Each source contributes information. None of them individually is considered perfect.

---

## Financial Event vs Financial Transaction

One of the most important architectural decisions.

**Financial Event** — represents something that happened. Example: an Uber Ride, ₹342, 8:15 PM. This is not proof that money moved. It is only evidence. Other examples: Amazon Order, Swiggy Order, Hotel Booking, Flight Ticket, Electricity Bill, Insurance Premium.

**Financial Transaction** — represents actual movement of money. Example: UPI to Rahul Kumar, ₹342, 8:17 PM. This is the official financial record.

---

## Reconciliation

The Reconciliation Engine links evidence to transactions:

```
Uber Receipt
        │
        ▼
UPI Transaction
        │
        ▼
Transportation Expense
```

The bank statement remains the ledger. The receipt enriches it.

---

## Financial Intelligence Pipeline

```
Statement Import
        │
        ▼
Merchant Resolution
        │
        ▼
Rule Engine
        │
        ▼
Learning Engine
        │
        ▼
Context / Evidence Matching
        │
        ▼
Duplicate Detection
        │
        ▼
Transfer Detection
        │
        ▼
Confidence Engine
        │
        ▼
User Confirmation (only if needed)
        │
        ▼
Persist Transaction
```

This is the *target* pipeline. See §9 for where it currently diverges from what's implemented.

### Rule Engine

Purpose: apply explicit business rules.

```
IF Description contains "SWIGGY"
  → Category = Food
```

Rules may be Global or User-defined. Rules are deterministic. Rules never guess.

*Implemented as `RuleEngineService` — see `rule-engine-relationship-engine-eds.md`.*

### Learning Engine

Purpose: learn from user corrections.

Month 1: `ACH DR SBI MF` → user changes to Investment.
Month 2: `ACH DR SBI MF` → Investment, automatically.

Learning remembers user behavior.

*Implemented as `MerchantLearningService` + `ConfidenceEngine` — merchant-keyed today. See §9 for the behavioral (non-merchant) extension this philosophy calls for.*

### Context Engine

Purpose: combine external evidence — Gmail receipts, invoices, future SMS, future notifications, future integrations.

```
Uber Receipt, ₹342
        │
        ▼
UPI Payment, ₹342
        │
        ▼
Matched
        │
        ▼
Transportation
```

*Not yet built. See §9 for compliance and precedence questions this needs answered before implementation.*

### Merchant Recognition

Merchant recognition identifies known merchants: Amazon, Swiggy, Netflix, a fuel pump, Reliance Fresh.

Unknown merchants remain unknown. The system should never invent merchant names.

*Implemented as `MerchantNormalizationEngine` — this principle is already load-bearing in that code today (falls back to "Unknown Merchant," never guesses a name).*

---

## The Personal QR Code Problem

The biggest challenge in Indian digital payments.

```
UPI
Rahul Kumar
₹180
```

Could be: groceries, tea, vegetables, an auto ride, a friend, family, medicine, a donation.

No software can determine this with certainty using the bank statement alone. Finora should avoid making unsupported assumptions.

---

## Philosophy for Unknown Transactions

Finora follows a confidence-based approach.

**High Confidence** — known merchant, known receipt, known learning. Automatically categorize.

**Medium Confidence** — behavior looks similar to previous spending. Suggest category, explain why, user confirms.

**Low Confidence** — no reliable evidence. Ask the user once. Learn forever.

*Today's `ConfidenceEngine` is binary (a single auto-apply threshold, default 90%), not three-tiered — see §9.*

---

## Learning Beyond Merchants

Learning should not only remember merchants. It should also remember spending behavior:

- An unknown individual, ₹150–₹600, usually categorized as Transportation.
- An unknown individual, ₹100–₹900, usually categorized as Grocery.
- An unknown merchant that always occurs on Saturday mornings, usually Grocery.

The goal is to learn user behavior rather than memorize merchant names.

*Not yet built — a genuinely new engine, distinct from the existing merchant-keyed `MerchantCategoryLearning`. See §9.*

---

## Financial Knowledge Graph

Finora should internally build a connected financial knowledge model.

```
Uber Receipt → UPI Transaction → Transportation Category → Monthly Travel Report
```

```
Amazon Order → Credit Card Transaction → Warranty Invoice → Electronics Expense
```

This graph enables richer analytics and explanations than isolated transactions.

---

## Product Philosophy

Finora should never aim to eliminate all user interaction. Instead, its objective is to reduce manual work every month.

```
350 Transactions Imported
220 Categorized Automatically
 90 Learned from Previous History
 25 Suggested with High Confidence
 15 Require User Confirmation
```

The objective is not zero questions. The objective is fewer questions every month while maintaining accuracy and user trust.

---

## Design Principles

1. Bank statements are the authoritative record of money movement.
2. External sources provide context, not financial truth.
3. Never invent facts when evidence is insufficient.
4. Explain every automatic decision through Decision Source and Confidence.
5. Ask the user only when confidence is genuinely low.
6. Every user correction becomes long-term knowledge.
7. Learn spending behavior, not just merchant names.
8. Build a Financial Knowledge Graph that links transactions, receipts, merchants, relationships, and user behavior into a single intelligent system.

---

## §9. Open Questions Before This Guides Implementation

This philosophy is largely already validated by what's built (Rule Engine, Learning Engine, Decision Source, "never invent merchant names"). Four things are genuinely undecided, not settled by the prose above, and should be resolved before the modules they touch get built — flagging them here rather than letting them be silently assumed:

1. **Duplicate/Transfer Detection ordering is a restated known gap, not a new one.** This document's pipeline (and `financial-intelligence-engine-spec.md`'s target pipeline, §1.1) both put duplicate/transfer detection before persistence. Today `ReconciliationService.reconcileForUser()` runs as a full-table pass *after* persistence. Moving to a true pre-persistence gate is real, non-trivial rework of tested code — its own milestone, not a side effect of adopting this philosophy.

2. **Precedence between Rule Engine, Learning Engine, and Context/Evidence Matching is unspecified.** If a receipt matches a transaction, does that evidence override a Global/User rule match, or only apply when rules and learned patterns are silent? This decides exactly where Evidence Matching slots into `CategorizationService.suggest()`'s existing chain (user rule → global rule → learned distribution → keyword fallback) — needs an explicit answer before that stage is built, not an implicit one.

3. **Gmail Receipts need at least the same compliance gate as PDF parsing, arguably a stricter one.** `statement-intelligence-engine-spec.md` gates bank-specific PDF parsing behind a DPDP Act 2023 review before its AI-fallback path proceeds. Reading a user's inbox (OAuth scopes, retention, consent) is a bigger trust surface than an uploaded PDF, not an equivalent line item in an evidence-source list.

4. **High/Medium/Low confidence needs real thresholds.** Today's `ConfidenceEngine` is binary (`DEFAULT_AUTO_APPLY_THRESHOLD = 90`). A three-tier model needs a second, concrete boundary — where "Medium" starts and ends — before it's buildable rather than aspirational.

5. **Behavioral (non-merchant) learning is new scope, not an extension.** Amount-range and day-of-week pattern learning for unresolved individual payees (the Personal QR Code problem) needs its own entity and service — `MerchantCategoryLearning` is keyed by merchant identity and doesn't naturally extend to "payee + amount range."
