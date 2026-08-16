# Finora Billing, Subscription & Entitlements — Design Proposal

**Status:** Proposal only. Nothing implemented. Design after GA blockers, production-safety work, and
the current bug hunt are closed. Same sequencing as every other document in this directory — and
more so here, since this is the first proposal in the set that moves real money.

**Correction #1 — this is genuinely greenfield, unlike the last five proposals.** Investigation found
zero `Plan`/`Subscription`/`Entitlement`/`Payment`/`Referral`/`Wallet` entities, no payment-gateway
dependency in any `pom.xml`/`package.json`, and no tier/premium field on `User` anywhere. This is the
first document in this set that isn't primarily correcting an over-broad rebuild — the architecture
in the originating draft is reasonable to design mostly as proposed.

**Correction #2, superseded by a formal Product decision — record kept for context.** This section
originally flagged that "Plus" didn't exist in `frontend/src/pages/landing/plans.ts` (which listed
Free/Premium/Family/Future) and recommended anchoring to that taxonomy instead of inventing a tier.
**Product has since made the taxonomy decision explicitly** (Billing Plan Taxonomy Decision Note,
2026-08-12, Decision Owner: Product): Finora will support exactly three tiers — **Free, Plus,
Premium** — and Family/Future are not carried forward. This is now the taxonomy this proposal uses
(§3.1, §4a). The underlying principle from the original correction still holds and is now a
**prerequisite, not a caveat**: `plans.ts` and its enforcing `landing-claims.test.tsx` currently
describe Free/Premium/Family/Future and **must be updated to match this decision before or alongside
implementation** — the whole reason `plans.ts` exists is so the frontend and backend can't drift
into describing different products, and that guarantee only holds if both sides are actually updated
together. Pricing itself remains undefined per Product's own note — not invented here, same as
before.

**Correction #3 — `FeatureFlag`'s fail-open default must not be inherited by entitlements.**
`FeatureFlagRepository.isEnabled()` returns `true` for an unknown key — a reasonable default for a
platform-wide toggle like recurring detection (missing flag → feature works). It would be a revenue
leak if entitlement checks copied the same default: a missing/mistyped entitlement key would grant
paid access to everyone. Entitlement lookups must fail **closed** (unknown → no access) — the
opposite convention, deliberately, from the existing flag system. Recorded here so whoever
implements this doesn't copy the pattern by habit.

---

## 1. Objective

Introduce a subscription/entitlement/payment/referral platform as a layer above Finora's core product
— per the draft's own architecture, kept separate from Fino and connected only through entitlement
checks. Scoped to Product's approved Free/Plus/Premium taxonomy, with pricing left open.

## 2. What exists today (baseline)

- **Nothing backend-side** — confirmed greenfield (see Correction #1).
- **The frontend's public plan taxonomy (`plans.ts`) currently describes a different set of tiers
  (Free/Premium/Family/Future) than Product has now approved (Free/Plus/Premium).** This is a real,
  outstanding gap between the approved decision and the shipped product, not something this proposal
  can resolve on its own — `plans.ts` and `landing-claims.test.tsx` need a corresponding update,
  tracked as a prerequisite (§2a), not assumed already done.
- **`FeatureFlag`** — reusable as a *template* (audit-logged admin toggle pattern), not as the
  entitlement mechanism itself: it's a single global boolean per key, no `user_id`/`plan_id` column,
  and fails open (Correction #3).
- **`RefreshToken`**'s existing device/IP capture (`user-security-center-proposal.md`) is directly
  reusable for referral abuse detection (§8) — no new device-fingerprinting mechanism needed.
- **`AuditService`**'s existing convention (new-state-only, `record(actorId, action, entityType,
  entityId, metadata)`) is what subscription/payment state changes should log through — same
  reasoning as every other proposal in this set, not repeated in full here.

### 2a. Prerequisite: reconcile `plans.ts` with the approved taxonomy

Not backend work, but a real blocker for this proposal's "seed `plans` from `plans.ts`" approach
(§3.1): the frontend source of truth needs to be updated to Free/Plus/Premium (dropping
Family/Future) before that seeding makes sense, and `landing-claims.test.tsx` will need its
assertions updated to match. Sequencing this proposal's implementation ahead of that update would
recreate the exact drift `plans.ts` was built to prevent — two systems each claiming to be the
source of truth for what Finora sells. Flagged as a prerequisite step, not assumed pre-done.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Plan and Subscription (Product-approved taxonomy)

```
plans
├── id
├── code            — FREE, PLUS, PREMIUM (per Product's Billing Plan Taxonomy Decision, 2026-08-12)
├── name
├── price            — nullable; null means "not yet purchasable," matching plans.ts's own rule
├── billing_cycle
├── active
```

```
subscriptions
├── id
├── user_id
├── plan_id
├── status           — ACTIVE, CANCELLED, EXPIRED, TRIAL, PAYMENT_FAILED
├── start_date / end_date / renewal_date
├── trial_start / trial_end       — nullable; populated only for TRIAL-status subscriptions
├── payment_provider
```

Seed data for `plans` should be generated from `plans.ts` (once §2a lands), not authored
independently — two divergent sources of "what plans exist" is exactly the drift `plans.ts`'s own
comment says it was built to prevent ("They read from this same array precisely so they cannot drift
into describing different products").

### 3.1a Subscription lifecycle events (append-only history, separate from current state)

`subscriptions` holds current state; it doesn't answer "why did premium subscribers drop yesterday."
Same append-only-log pattern already used elsewhere in this proposal set (`AuditLog`, the
Notification proposal's `notification_logs`):

```
subscription_events
├── id
├── subscription_id
├── event_type      — SUBSCRIPTION_CREATED, PAYMENT_SUCCESS, PAYMENT_FAILED, PLAN_CHANGED,
│                      SUBSCRIPTION_CANCELLED, SUBSCRIPTION_RENEWED
├── metadata
├── created_at
```

This is what makes "47 subscriptions failed renewal yesterday" an answerable query rather than
something only reconstructable by diffing `subscriptions` snapshots — the same reasoning that
justifies `AuditLog` existing separately from the live tables it describes.

### 3.1b Plan change (upgrade/downgrade) handling

Changing plans isn't a single-field update — it has real timing and entitlement questions the
`subscriptions` table alone doesn't answer:

```
plan_changes
├── id
├── subscription_id
├── from_plan_id / to_plan_id
├── effective_at     — when the change actually takes effect
├── reason            — USER_INITIATED, PAYMENT_FAILURE_DOWNGRADE, ADMIN_OVERRIDE, etc.
├── created_at
```

**Whether an upgrade/downgrade is immediate or takes effect at the next billing date is a product
decision, not assumed here** — the schema supports either (`effective_at` can equal "now" or the
next `renewal_date`). What this proposal does fix: a `PLAN_CHANGED` row is always created (feeding
§3.1a), and `feature_entitlements` lookups (§3.2) always resolve against the subscription's *current*
plan as of the lookup time, not a cached value — so a downgrade takes effect on the entitlement check
immediately after `effective_at` passes, with no separate "revoke features" step to remember.
Refund handling on downgrade is explicitly not designed here — a business decision, not an
engineering default.

### 3.2 Feature entitlements (fail-closed — see Correction #3)

```
feature_entitlements
├── id
├── plan_id
├── feature_key       — e.g. ADVANCED_ANALYTICS, EXTENDED_HISTORY, PRIORITY_SUPPORT, FINO_AI
├── enabled
```

Lookup: `hasEntitlement(userId, featureKey)` → resolve user's active subscription → resolve plan →
look up `(plan_id, feature_key)` → **default false if no row matches**, not true. This is the one
place in Finora's flag/config/entitlement family of systems where fail-closed is the correct default,
and it should be implemented as a clearly different code path from `FeatureFlagRepository.isEnabled`,
not a parameterized variant of it — the two have opposite failure-mode requirements and conflating
them risks one accidentally inheriting the other's default.

**Product-approved entitlement mapping** (Billing Plan Taxonomy Decision, 2026-08-12) — the seed data
for `feature_entitlements`, not something engineering invents independently:

| Feature | Free | Plus | Premium |
|---|:---:|:---:|:---:|
| Basic Dashboard | ✓ | ✓ | ✓ |
| Advanced Reports | ✗ | ✓ | ✓ |
| Extended History | ✗ | ✓ | ✓ |
| Investment Insights | ✗ | ✗ | ✓ |
| Fino AI | ✗ | ✗ | ✓ |
| Priority Support | ✗ | ✗ | ✓ |

**Caching — not built now, noted so the lookup path isn't designed into a corner.** A live query per
entitlement check (matching `PlatformSettingsService`'s own "no cache" reasoning elsewhere in this
codebase) is the right default at Finora's current scale — an admin or a payment webhook changing a
subscription should take effect on the next request, not wait out a cache TTL. If entitlement checks
become a real hot path later (e.g. checked on every Fino request), the future shape is a short-TTL
cache invalidated on `subscription`/`plan_changes` writes, not a rebuild of the lookup itself. Revisit
only with evidence of actual load, same evidence-gating principle applied throughout this proposal
set.

### 3.3 Payments (architecture only — no gateway selected here)

```
payments
├── id
├── user_id / subscription_id
├── amount / currency
├── provider
├── provider_transaction_id
├── status
```

**Security requirements, given this handles real money:**
- Use the gateway's own hosted checkout / tokenization (Razorpay Checkout, Cashfree's hosted page) —
  **Finora's backend should never receive or store raw card/UPI credentials**, same principle
  already applied to passwords/JWTs elsewhere in this codebase.
- Webhook signature verification is mandatory before trusting any payment-status update — payment
  gateways sign their webhooks specifically so a forged "payment succeeded" callback can't grant
  access.
- **Webhook idempotency is mandatory, separate from signature verification.** A verified-but-repeated
  webhook (gateways retry on any ambiguous response, e.g. a slow 200) must not activate a
  subscription twice or record a duplicate payment:

  ```
  payment_events
  ├── id
  ├── provider_event_id   — UNIQUE constraint, direct from the gateway's own event id
  ├── payment_id
  ├── processed_at
  ```

  Insert-or-ignore on `provider_event_id` before acting on a webhook — already-processed events are
  dropped at the database constraint, not by application-level "have I seen this before" logic that
  could itself race. Same idempotency-key discipline as the Notification proposal's
  `notification_key` and this document's own referral-crediting requirement (§8) — three places in
  this proposal set independently arriving at the same pattern, which is itself a signal it's the
  right one.
- Treat gateway API keys/webhook secrets with the same boot-time-validation rigor
  `ProductionConfigValidator` already applies to `RESEND_API_KEY`/`JWT_SECRET` — a misconfigured
  payment secret should fail loudly at boot, not silently accept unverified webhooks.
- Provider selection (Razorpay vs. Cashfree vs. Stripe) is a business/compliance decision, not made
  here — flagged as an open question (§10).

### 3.4 Billing history

As proposed in the original draft — a read view over `payments`/`subscriptions` for the user's own
records. No new backend concept beyond §3.1/§3.3.

## 4. Referral program (separate sub-scope, smaller than billing)

```
referral_codes                          referrals
├── id                                  ├── id
├── user_id                             ├── referrer_user_id / referred_user_id
├── code                                ├── status — INVITED, REGISTERED, SUBSCRIBED, REWARDED
├── created_at                          ├── reward
                                         ├── created_at
```

**Abuse prevention reuses existing infrastructure rather than building new detection:**
`RefreshToken`'s device/IP capture (already shipped, per the Security Center proposal) is the same
signal needed to flag self-referral/device-abuse patterns — check device/IP overlap between
`referrer_user_id` and `referred_user_id`'s sessions before crediting a reward, rather than building a
parallel fingerprinting system.

**Reward mechanics need an immutable ledger, not a mutable balance column.** If rewards include wallet
credit, storing a single `balance` field on `User` and updating it in place (`balance = balance +
reward`) is the wrong model for a financial app that already treats correctness this carefully
elsewhere (e.g. import recording's `REQUIRES_NEW` transaction handling to avoid exactly this class of
race). Instead:

```
wallet_ledger
├── id
├── user_id
├── amount           — signed (credit positive, debit negative)
├── reason            — REFERRAL_REWARD, etc.
├── reference_id       — the referral/subscription event that caused this entry
├── created_at
```

Balance is a computed sum over this table, not a stored, mutable field — append-only, same spirit as
`AuditLog` and the existing `TransactionTemplate(REQUIRES_NEW)` discipline around financial writes.

## 5. Explicitly out of scope for v1

Unchanged from the original draft, still correct:
- Complex invoicing engine, multi-currency, enterprise billing, affiliate marketplace, coupon
  management, tax invoice automation, revenue-forecasting AI.

**Also explicitly out of scope, added here:**
- Any specific pricing number — left to business decision (§10), not invented.
- Family/Future tiers — explicitly dropped by Product's taxonomy decision; not carried forward.
- Refund policy on downgrade (§3.1b) — a business decision, not defaulted by this proposal.
- Reusing `FeatureFlag`'s fail-open lookup for entitlements — architecturally wrong default (§3.2).
- Entitlement-check caching — evidence-gated, not built now (§3.2).

## 6. Admin portal requirements

As proposed in the original draft (revenue dashboard, subscription management, referral dashboard) —
no correction needed here; nothing conflicting exists today (no billing admin screens found).

## 7. Future Fino integration

As proposed — Fino checks entitlement (`hasEntitlement(userId, "FINO_AI")`) before responding, same
pattern as every other premium feature. Consistent with the fail-closed requirement in §3.2: if
Fino's entitlement check has a bug or the row is missing, the safe failure is "Fino doesn't respond,"
not "Fino responds to everyone for free."

## 8. Security & compliance considerations (new section — not in the original draft)

Given this is the first proposal in this set touching real payments:
- PCI-DSS scope should be minimized deliberately by using hosted checkout (§3.3) — storing any card
  data directly would put Finora's backend in PCI scope, which is a compliance burden worth avoiding
  entirely rather than managing.
- Subscription/payment state changes should audit-log through the existing `AuditService` convention,
  with the same "new-state-only, don't invent an old/new-value schema" discipline already established
  in the Remote Configuration proposal (§5.3 there).
- Referral reward crediting (§4) needs idempotency — the same referral event should not be able to
  credit twice on a retry, matching the idempotency-key discipline already designed into the
  Notification proposal (`notification_key`) for the same class of failure.

## 9. Estimated effort

| Component | Effort |
|---|---|
| `plans.ts` + `landing-claims.test.tsx` reconciliation (§2a — prerequisite, not backend) | S |
| `plans`/`subscriptions` entities + seed from `plans.ts` | S–M |
| `subscription_events` (lifecycle log) | S |
| `plan_changes` (upgrade/downgrade) | S–M |
| `feature_entitlements` (fail-closed) + lookup service + Product's mapping seed | S–M |
| Payment gateway integration (provider TBD) | M–L |
| `payment_events` (webhook idempotency) | S |
| Billing history view | S |
| Referral codes + tracking | M |
| `wallet_ledger` (if rewards include credit) | S–M |
| Abuse-detection reuse of `RefreshToken` signals | S |
| Admin billing/referral dashboards | M |

Larger than any other proposal in this set — the only one requiring an external vendor integration
handling real money, which is inherently higher-effort and higher-scrutiny than internal platform
work.

## 10. Open questions — business decisions, not engineering ones

- Actual pricing for Free/Plus/Premium — not decided here, and per `plans.ts`'s own enforced rule,
  should not be invented in this document either.
- Payment gateway selection (Razorpay/Cashfree/Stripe) — compliance and market-fit decision.
- Upgrade/downgrade timing (immediate vs. next-billing-date) and refund policy (§3.1b) — product
  decision; the schema supports either, doesn't presuppose one.
- Trial length and eligibility rules (§3.1) — `trial_start`/`trial_end` support any policy, none
  chosen here.
- Referral reward structure (wallet credit vs. free trial days vs. discount) — product decision;
  §4's `wallet_ledger` design supports any of these, doesn't presuppose one.

## 11. Approval request

Request approval to add this as a post-GA business capability, scoped to Product's approved
Free/Plus/Premium taxonomy, with entitlements designed fail-closed from the start.

**Decision requested:**
- ✅ Approve: Billing/Subscription/Entitlements/Referral as a roadmap item, scoped per this document
- ⏸ Defer: implementation until core product stabilization (C-8, 56 findings, safety remediation)
- 🔲 Prerequisite tracked, not blocking design approval: reconcile `plans.ts` and
  `landing-claims.test.tsx` with the approved taxonomy (§2a) before implementation begins
- 🔲 Separate decisions needed: pricing, payment provider, upgrade/downgrade timing, refund policy,
  trial terms (§10) — none of these block the architectural design being reviewed
