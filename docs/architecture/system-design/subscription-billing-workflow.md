# Subscription & billing workflow — as-built

**Status:** describes the current implementation (D-28 PR4-A/B/C), not the product vision. For the
original design intent and open product decisions, see
[`docs/proposals/billing-subscription-entitlements-proposal.md`](../../proposals/billing-subscription-entitlements-proposal.md).
This document exists because that proposal predates implementation and doesn't track what actually
shipped versus what's still open.

## Headline

**There is no payment gateway.** Every account gets a Free subscription automatically at signup,
and the only way anyone reaches Plus/Premium today is an admin manually changing a dropdown in the
admin portal. Entitlement checks, billing history, data export, and account deletion are all fully
built and wired for a future gateway, but the payment/webhook/renewal layer itself doesn't exist.

## 1. Data model

| Table | Purpose | Mutability |
|---|---|---|
| `plans` | Free/Plus/Premium catalog, seeded from `frontend/src/pages/landing/plans.ts` | Static catalog |
| `subscriptions` | A user's current plan + status | One row per user, updated in place |
| `subscription_events` | Append-only lifecycle log (`SUBSCRIPTION_CREATED`, `PLAN_CHANGED`, etc.) | Write-once |
| `plan_changes` | Append-only upgrade/downgrade history | Write-once |
| `feature_entitlements` | (plan, feature_key) → enabled, **fails closed** | Static catalog |
| `payments` | Schema exists, zero rows ever inserted — no gateway to write them | Dormant |

Defined in
[`V99__billing_entitlements.sql`](../../../backend/src/main/resources/db/migration/V99__billing_entitlements.sql)
and
[`V100__billing_history.sql`](../../../backend/src/main/resources/db/migration/V100__billing_history.sql).
A DB-level partial unique index (`idx_subscriptions_one_active_per_user`) guarantees at most one
`ACTIVE`/`TRIAL` subscription per user — enforced by Postgres, not just service-layer discipline.

## 2. Step-by-step workflow

### Step 1 — Signup provisions Free automatically

Both signup paths in
[`AuthService.java`](../../../backend/src/main/java/com/finora/service/AuthService.java) call
`SubscriptionService.provisionFreeSubscription(userId)`, which:

1. Looks up the `FREE` plan
2. Creates a `subscriptions` row: `status=ACTIVE`, `startDate=today`, no end/renewal date
3. Writes a `SUBSCRIPTION_CREATED` event

Every existing user was backfilled the same way in the V99 migration itself, because entitlement
checks fail closed — without the backfill, everyone would have silently lost `BASIC_DASHBOARD`
access the moment the check went live.

### Step 2 — Entitlements gate features, everywhere

- Backend:
  [`EntitlementService.hasEntitlement(userId, featureKey)`](../../../backend/src/main/java/com/finora/service/EntitlementService.java)
  — no active/trial subscription, no entitlement row, or `enabled=false` all resolve to **no
  access**. This is deliberately the opposite default from `FeatureFlagRepository.isEnabled`, which
  fails *open* — a mistyped feature key must never become a revenue leak.
- Frontend:
  [`PremiumFeatureGate`](../../../frontend/src/components/PremiumFeatureGate.tsx) calls
  `GET /api/v1/entitlements`
  ([`EntitlementController`](../../../backend/src/main/java/com/finora/controller/EntitlementController.java))
  and also fails closed while loading/erroring, so a slow request never briefly flashes a gated
  feature.
- The seeded entitlement map (from V99): `BASIC_DASHBOARD` → all plans; `ADVANCED_REPORTS` /
  `EXTENDED_HISTORY` → Plus+Premium; `INVESTMENT_INSIGHTS` / `FINO_AI` / `PRIORITY_SUPPORT` →
  Premium only.

### Step 3 — The only upgrade path: admin manual override

No self-service upgrade UI exists — `plans.ts` explicitly documents "no plan field on User, no
payment integration." The real path:

1. Admin opens **Subscriptions** in the admin portal
   ([`Subscriptions.tsx`](../../../admin-portal/src/pages/Subscriptions.tsx)) — gated behind
   `SUBSCRIPTION_MANAGEMENT_VIEW`
2. Picks FREE/PLUS/PREMIUM from a dropdown next to a user's row →
   `PUT /api/v1/admin/subscriptions/{userId}/plan`, gated behind `SUBSCRIPTION_MANAGEMENT_MANAGE`
3. [`AdminSubscriptionController.changePlan`](../../../backend/src/main/java/com/finora/controller/AdminSubscriptionController.java)
   → [`SubscriptionService.changePlan`](../../../backend/src/main/java/com/finora/service/SubscriptionService.java):
   - Updates the subscription's `plan_id` **immediately** (no proration, no scheduled-at-renewal
     logic — upgrade/downgrade timing is still an open product decision, proposal §10)
   - Writes a `PlanChange` row with `reason=ADMIN_OVERRIDE`
   - Writes a `PLAN_CHANGED` subscription event
   - Records an audit log entry, distinguishing the admin's `actorId` from the affected user's
     `userId`
   - Calls `ReferralService.onPlanChanged` — if the user was referred and still `REGISTERED`, this
     is the one automatic trigger that flips the referral to `SUBSCRIBED` (everything else in the
     referral lifecycle is admin-manual by design)

The view/manage permissions are split on purpose, mirroring this codebase's convention elsewhere
(e.g. `PLATFORM_STATS_VIEW` vs. mutating counterparts).

### Step 4 — Billing history (dormant)

`GET /api/v1/billing/history`
([`BillingHistoryController`](../../../backend/src/main/java/com/finora/controller/BillingHistoryController.java)
→ [`BillingHistoryService`](../../../backend/src/main/java/com/finora/service/BillingHistoryService.java))
reads the `payments` table and always returns an empty list for every user, correctly — no code
path has ever inserted a payment row. This is the service a future gateway's webhook handler would
populate, not a placeholder to rewrite.

### Step 5 — Account deletion / data export touch subscriptions too

- [`AccountPurgeSweepService`](../../../backend/src/main/java/com/finora/service/AccountPurgeSweepService.java):
  hard-deletes `payments` before `subscriptions` (payments reference `subscription_id`), then
  `subscriptions` — `subscription_events`/`plan_changes` need no separate cleanup since both cascade
  via `ON DELETE CASCADE`.
- [`DataExportService`](../../../backend/src/main/java/com/finora/service/DataExportService.java):
  includes the user's full subscription + plan-change history in the export bundle
  (`subscriptions.json`, `plan_changes.json`), explicitly excluding `subscription_events` as an
  internal analytics log rather than user-provided data.

## 3. What's genuinely not built

- **No payment gateway** — no Razorpay/Stripe integration, no checkout flow, no webhook handler
- **No self-service upgrade/downgrade** — a user cannot change their own plan
- **No trial automation, no renewal/expiry job** — `trial_start`/`trial_end`/`renewal_date`/
  `STATUS_PAYMENT_FAILED` exist as schema/status values but nothing populates or transitions them
  automatically
- **No proration or refund logic**

These are explicitly flagged as open in
[`docs/proposals/billing-subscription-entitlements-proposal.md`](../../proposals/billing-subscription-entitlements-proposal.md)
§10, referenced throughout the code comments — this isn't a gap discovered while writing this
document, it's a gap the team already recorded.

## 4. Operational note

The admin plan-change UI lets an admin set Plus/Premium on any account right now, with real
entitlement effects (Fino AI, investment insights, etc.). Since there's no payment behind it, this
is effectively a free-grant mechanism — worth keeping in mind if the admin portal's access list to
`SUBSCRIPTION_MANAGEMENT_MANAGE` ever grows.
