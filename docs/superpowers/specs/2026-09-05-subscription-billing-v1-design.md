# Subscription billing V1 — design spec

**Status:** approved design, pre-implementation. Architectural path (new payment-gateway
subsystem; changes interfaces `EntitlementService`, `ReferralService`, and the admin portal
already depend on).

**Predecessor docs:**
[`docs/proposals/billing-subscription-entitlements-proposal.md`](../../proposals/billing-subscription-entitlements-proposal.md)
(pre-implementation vision, §10 explicitly left the gateway open) and
[`docs/architecture/system-design/subscription-billing-workflow.md`](../../architecture/system-design/subscription-billing-workflow.md)
(as-built description of the admin-only-manual system this spec replaces).

## 1. Goal

Replace "admin manually flips a dropdown" with real self-service paid subscriptions — checkout,
recurring renewal, upgrade, downgrade, cancellation, and payment-failure handling — for web,
mobile, and the admin portal, backed by Razorpay Subscriptions.

## 2. Decisions made during design (binding for this spec)

| Topic | Decision |
|---|---|
| Entitlement keying | Plan/tier only (`FREE`/`PLUS`/`PREMIUM`). Billing cycle never affects entitlements. |
| Billing cycle | Stored on the subscription/order/price side, never on `plans` or `feature_entitlements`. |
| Renewal mechanism | Razorpay **Subscriptions API** (recurring mandate), not one-time Orders re-created per period. |
| Pricing | Plus: ₹399/mo or ₹3,500/yr. Premium: ₹799/mo or ₹8,000/yr. INR only — no multi-currency in V1. |
| `subscription_orders` | Kept, as a business/audit layer independent of the Razorpay-mirroring `payments` table (funnel metrics, abandoned-checkout visibility, support queries). |
| Cancellation | `auto_renew=false` + cancel the Razorpay subscription; access continues until `current_period_end`, then downgrades to Free. |
| Upgrade | V1 does not implement proration. Cancel the old Razorpay subscription, create+activate a new one for the target plan, grant access on activation. No mid-cycle credit. |
| Downgrade | Scheduled at period end via Razorpay's native `schedule_change_at=cycle_end`, not an immediate plan swap. |
| Grace period | None separate from Razorpay's own retry window. React to `subscription.halted` directly; access is revoked then, not before. |
| Admin override | **Blocked** (not silently overridden) while the user has an active Razorpay-backed subscription. Admin must cancel the paid subscription first, confirm, then grant a complimentary plan. |
| Free trial | Deferred out of V1. Schema already supports `trial_start`/`trial_end`; no automation built now. |
| Mobile | **In scope for V1** — pricing, checkout hand-off, billing portal, and entitlement gating ship on mobile alongside web. Reconsidered once during review (splitting mobile into a V1.1 was raised as a delivery-risk option) and reconfirmed in scope. |
| Webhook idempotency | Mandatory. A `webhook_events` table records every processed event id; duplicates are ignored before any state change. |

## 3. Verified Razorpay behavior this design relies on

Pulled from Razorpay's own docs, not assumed, per this repo's no-guessing rule:

- **Retry schedule (cards/UPI): fixed, not configurable.** T (fail) → T+1 → T+2 → T+3, three
  retries, then `halted`. ([Payment Retries](https://razorpay.com/docs/payments/subscriptions/payment-retries/?preferred-country=IN))
- **`subscription.pending` does not revoke access** — Razorpay's own docs state it "will not affect
  the charge cycle for the subsequent months." Only `subscription.halted` (retries exhausted) is a
  real access-revoking signal. ([Subscriptions Webhook Events](https://razorpay.com/docs/webhooks/subscriptions/))
- **`halted` is not fully terminal** — Razorpay keeps generating invoices for a halted subscription
  but stops auto-charging them; resuming needs a manual charge or new mandate. V1 does not build a
  "resume a halted subscription" flow — see §9 (out of scope) — the user re-subscribes via normal
  checkout instead.
- **Scheduled plan changes are a native Razorpay feature**: `PATCH` subscription with
  `schedule_change_at: cycle_end` defers a plan/amount change to the next billing cycle, cancellable
  before it goes live via Razorpay's Cancel-an-Update endpoint.
  ([Update a Subscription](https://razorpay.com/docs/payments/subscriptions/update/))
- **Unverified, flagged for sandbox testing before implementation relies on them** — none of these
  were answered by Razorpay's public docs, confirm all against a live test subscription rather than
  guessing in code:
  - Which exact webhook fires the moment a `cycle_end`-scheduled plan change actually applies (§6.4).
  - Whether a second Subscription for the same customer can reuse a saved mandate, or always
    requires fresh checkout authorization (§6.5).
  - Which webhook fires immediately when `cancel_at_cycle_end=true` is requested (i.e. the moment
    cancellation is *scheduled*, not when it takes effect) versus which webhook fires when the
    cancellation actually *applies* at period end (§6.3) — these may not be the two you'd guess
    (e.g. `subscription.updated` at request time vs. `subscription.cancelled` at period end), and
    the reconciliation sweep's correctness depends on knowing which is which.

## 4. Data model

### 4.1 `plans` — unchanged shape, drop stale columns

Tier catalog only (`FREE`/`PLUS`/`PREMIUM`). Migration drops `price` and `billing_cycle` columns —
both become meaningless once price is cycle-dependent and lives in `billing_prices`. `active`
stays.

### 4.2 `billing_prices` (new)

```
billing_prices
--------------
id                  UUID PK
plan_id             UUID FK -> plans.id
billing_cycle       VARCHAR(10)   MONTHLY | YEARLY
price               NUMERIC(10,2)
currency            VARCHAR(3)    always 'INR' in V1
razorpay_plan_id    VARCHAR(50)   Razorpay-side Plan object id
active              BOOLEAN
UNIQUE (plan_id, billing_cycle) WHERE active
```

Seeded with 4 rows (Plus/Premium × Monthly/Yearly). `razorpay_plan_id` is populated by a one-time
setup step against the live Razorpay account (see §10 — blocked on account creation). FREE has no
row here; it's never checked out through Razorpay.

### 4.3 `subscriptions` — extended

Existing columns (`user_id`, `plan_id`, `status`, `start_date`, `end_date`, `trial_start`,
`trial_end`, `payment_provider`) are kept. `renewal_date` is repurposed as "current period end /
next charge date" rather than added as a new column — same concept, now actually populated from
webhooks. New columns:

```
billing_cycle             VARCHAR(10)   MONTHLY | YEARLY, null for FREE
razorpay_subscription_id  VARCHAR(50)   null for FREE and ADMIN_GRANT
auto_renew                BOOLEAN       default true; false after user-initiated cancel
```

Status values gain two: `PAST_DUE` (Razorpay `pending`, access still active) alongside the existing
`ACTIVE`/`CANCELLED`/`EXPIRED`/`TRIAL`/`PAYMENT_FAILED`. `PAYMENT_FAILED` is redefined as the
post-`halted`, access-revoked terminal state (previously unused).

`payment_provider` is populated as `RAZORPAY` for gateway-backed subscriptions and `ADMIN_GRANT`
for admin-issued complimentary ones — this is how §6.6 tells the two apart.

No `pending_plan_id`/`pending_billing_cycle` columns are added. A scheduled downgrade is
represented by a `plan_changes` row (see 4.5) with `effective_at` in the future — that table's
existing doc comment already anticipated exactly this case ("a change can be recorded now but take
effect at the next renewal date").

### 4.4 `subscription_orders` (new — kept per explicit decision, business/audit layer)

```
subscription_orders
--------------------
id                          UUID PK
user_id                     UUID FK -> users.id
plan_id                     UUID FK -> plans.id
billing_cycle               VARCHAR(10)
razorpay_subscription_id    VARCHAR(50)
status                      VARCHAR(20)   PENDING | COMPLETED | FAILED | ABANDONED
amount                      NUMERIC(10,2)
created_at                  TIMESTAMPTZ
completed_at                TIMESTAMPTZ
```

Written `PENDING` the moment Fynora calls Razorpay's create-subscription API (before the user pays
anything), with the returned `razorpay_subscription_id` stored immediately — this is what lets the
webhook handler correlate an incoming `subscription.activated` event back to the order that
requested it (§6.1/§6.5 both depend on this correlation, especially during an upgrade where the
user's existing `subscriptions` row already points at a *different* `razorpay_subscription_id`).
Flipped to `COMPLETED` on activation, or `FAILED` if the create-subscription API call itself errors.
Nothing in the read path (entitlements, `payments`) depends on this table — it exists for
funnel/support visibility, not correctness. An `ABANDONED` sweep (checkout started, no webhook after
N hours) is a reporting nicety, not required for V1 launch; a manual query is enough to start.

### 4.5 `plan_changes` — reused, no schema change

Already supports `reason` and a future `effective_at`. Add one new `reason` constant:
`REASON_DOWNGRADE_SCHEDULED`. A scheduled downgrade writes this row immediately (at the moment the
user requests it) with `effective_at = current_period_end`; the billing portal reads "is there an
unapplied row for this subscription" to show "Downgrading to Plus on Oct 5." When the downgrade
actually takes effect (§6.4), `subscriptions.plan_id`/`billing_cycle` are updated to match — no
separate "apply" job needed beyond the webhook handler already reconciling state.

### 4.6 `payments` — expanded, first real writes

Schema already has `subscription_id`, `amount`, `currency`, `provider`, `provider_transaction_id`,
`status`, timestamps (V100), and its status vocabulary (`PENDING`/`SUCCESS`/`FAILED`/`REFUNDED`)
already fits without adding anything new. No new columns needed — `provider_transaction_id` stores
Razorpay's `payment_id`. First code path that ever inserts a row: the webhook handler.

`subscription.pending` writes `PENDING`, not `FAILED` — a mid-retry attempt is not a terminal
failure, and a customer who ultimately pays successfully (the common case, per Razorpay's own
retry behavior in §3) shouldn't have their billing history read as a string of `FAILED` rows ending
in one `SUCCESS`. `FAILED` is reserved for `subscription.halted` — retries genuinely exhausted.
(`subscriptions.status='PAST_DUE'` and `payments.status='PENDING'` both stem from the same
`subscription.pending` event but aren't duplicating each other — one is current standing, the other
is this attempt's outcome.)

### 4.7 `webhook_events` (new — mandatory idempotency)

```
webhook_events
---------------
event_id      VARCHAR(50) PK    Razorpay's event id
provider      VARCHAR(20)       'RAZORPAY'
event_type    VARCHAR(50)       e.g. subscription.charged
payload       JSONB             raw body, for replay/debugging
status        VARCHAR(20)       PROCESSED | FAILED
processed_at  TIMESTAMPTZ
```

`status` lets a production incident distinguish "we saw this event and handled it" from "we saw it
and our handler threw" without digging through logs — a webhook that errors mid-processing still
gets its row (so a Razorpay retry of the same event is recognized), but marked `FAILED` rather than
silently looking identical to a success.

Webhook handler flow: verify signature → `INSERT (event_id, provider, event_type, payload) ...
ON CONFLICT (event_id) DO NOTHING` → if no row was inserted (already existed, whatever its status),
return 200 and stop; if inserted, process the event, then `UPDATE ... SET status = 'PROCESSED' |
'FAILED', processed_at = now()`. The insert-first ordering matters: it closes the race between two
concurrent deliveries of the same event id by claiming the row before any state change happens, not
after.

## 5. State machine

| Razorpay webhook | `subscriptions.status` | Access | Other effects |
|---|---|---|---|
| `subscription.authenticated` / `.activated` | `ACTIVE` | full | `subscription_orders` → `COMPLETED`; `SUBSCRIPTION_CREATED` or `SUBSCRIPTION_RENEWED` event; referral trigger (§6.7) |
| `subscription.charged` | stays `ACTIVE` | full | insert `payments` row (SUCCESS); resolve charged `razorpay_plan_id` → `(tier, cycle)` via `billing_prices` and reconcile `subscriptions.plan_id`/`billing_cycle` if they've drifted (this is what makes a scheduled downgrade take effect — see §6.4) |
| `subscription.pending` | `PAST_DUE` | **unchanged** | insert `payments` row, status `PENDING` (this is a retry-in-progress, not a terminal failure — see §4.6) |
| `subscription.halted` | `PAYMENT_FAILED` → then downgraded to `FREE` | revoked | mark the outstanding `payments` row(s) `FAILED`; `SUBSCRIPTION_CANCELLED`-shaped event with reason=payment failure |
| `subscription.cancelled` | `CANCELLED` | continues until `current_period_end`, then Free (via the reconciliation sweep, §6.3 — not this webhook alone) | `auto_renew` already false from the cancel request |
| `subscription.completed` | `EXPIRED` | revoked | not expected in V1 (all plans recur indefinitely, no fixed cycle count) — handled defensively, not actively used |

## 6. Flows

### 6.1 Checkout (web + mobile)

1. User picks a plan + cycle on the pricing page (web) or an equivalent mobile screen.
2. `POST /api/v1/billing/checkout {planCode, billingCycle}` → backend looks up `billing_prices`,
   calls Razorpay's create-subscription API with the resolved `razorpay_plan_id`, writes a
   `subscription_orders` row (`PENDING`), returns the Razorpay subscription id / checkout params.
3. **Web:** Razorpay Checkout JS opens with the `subscription_id`. **Mobile:** `react-native-razorpay`
   opens the same way, passed the same `subscription_id` — the backend contract is identical across
   platforms; only the client-side SDK differs.
4. User authorizes the mandate (first payment).
5. Razorpay sends `subscription.authenticated`/`.activated`. Backend verifies the webhook signature,
   checks `webhook_events` idempotency, then activates: `subscriptions` row created/updated to
   `ACTIVE`, `subscription_orders` → `COMPLETED`, entitlements take effect immediately (no caching
   to wait out, per `EntitlementService`'s existing no-cache design).
6. **The frontend success page never activates anything.** It polls or re-fetches
   `GET /api/v1/entitlements` and shows "activating..." until the webhook has landed. This is the
   one rule from your original plan I'd flag as absolutely non-negotiable, and it's already how
   `PremiumFeatureGate` behaves (fails closed while loading).

### 6.2 Renewal

Fully passive from Fynora's side: Razorpay charges automatically, sends `subscription.charged`,
webhook handler extends access implicitly (status stays `ACTIVE`, a `payments` row is added). No
cron job.

### 6.3 Cancellation

`POST /api/v1/billing/cancel` → `auto_renew=false`, call Razorpay's cancel-subscription API with
`cancel_at_cycle_end=true`. Access continues untouched. No separate "reactivate" flow in V1 — a user
who cancelled and wants back in before period end can't undo it (Razorpay's cancel-at-cycle-end is
not itself reversible via a simple flag); if this turns out to matter, it's a fast-follow, not a
blocker.

**Downgrade-to-Free at period end is driven by a reconciliation sweep, not by absence of a
webhook.** Treating "we stopped hearing `subscription.charged`" as a state transition is not a
reliable signal — a missed or dropped webhook (Razorpay disables a webhook endpoint after 24h of
failed deliveries) would leave paid access active indefinitely with nothing to notice. Instead, a
new `SubscriptionReconciliationSweepService` — same shape as `AccountPurgeSweepService` and
`NetWorthSnapshotSweepService` (`@Scheduled(fixedDelayString =
"${app.subscription-reconciliation.sweep.interval-ms:...}")`) — periodically finds subscriptions
where `auto_renew=false AND status='CANCELLED' AND renewal_date < now()` and downgrades them to
`FREE`. `subscription.cancelled` remains the *expected* trigger and will usually fire first (a
webhook arriving before the sweep's next tick is not a race — the sweep's `WHERE` clause is already
false once the transition has happened); the sweep is the safety net for when it doesn't, not the
primary mechanism. Worth widening slightly at implementation time: the same sweep can also re-fetch
live status from Razorpay's API for any subscription whose `renewal_date` has passed with no
corresponding `payments` row at all (a missed `subscription.charged` *or* `subscription.halted`),
since both are instances of the same underlying risk — a webhook we needed never arrived.

### 6.4 Downgrade (Plus↔Premium, lower tier)

`POST /api/v1/billing/change-plan {planCode: PLUS}` while on Premium → backend calls Razorpay's
Update Subscription API with the target `razorpay_plan_id` and `schedule_change_at: cycle_end`,
then writes the `plan_changes` row from §4.5. Billing portal shows the pending change immediately.
At cycle end, the `subscription.charged` webhook's plan-id-reconciliation step (§5) updates
`subscriptions.plan_id`/`billing_cycle` to match — this is the point flagged in §3 as needing
sandbox verification (confirm the webhook actually carries the new plan id at that moment, and that
no separate event needs handling).

### 6.5 Upgrade (higher tier)

`POST /api/v1/billing/change-plan {planCode: PREMIUM}` while on Plus → **create the new subscription
and wait for its activation webhook before cancelling the old one**, not the other way around. This
is a safety refinement on top of the agreed "cancel + recreate, no proration" approach: cancelling
the old subscription first and only then creating the new one would leave the user with no active
paid access at all if they abandon the new checkout.

**This creates a real, external, briefly-dual-subscription state on Razorpay's side — but never a
second row in Fynora's own `subscriptions` table.** That table keeps its existing one-row-per-user
model (the same mutate-in-place row `SubscriptionService.changePlan` already uses today, protected
by `idx_subscriptions_one_active_per_user`); an upgrade never inserts a second `subscriptions` row,
so the DB constraint is never at risk of being violated. The mechanics:

1. `change-plan` creates a *new* Razorpay subscription and writes a `subscription_orders` row
   (`PENDING`, carrying the new `razorpay_subscription_id` — §4.4) — the existing `subscriptions`
   row is untouched: still `ACTIVE`, still pointing at the *old* `razorpay_subscription_id`, still
   on the old plan.
2. **The old subscription remains authoritative and entitlements stay on the old (lower) tier for
   the entire duration of this window.** This isn't a compromise, it's the same "never grant from a
   frontend success page, only from a verified webhook" rule from §6.1 applied consistently — the
   user simply hasn't paid for Premium yet as far as Fynora's state is concerned, no matter what the
   checkout UI shows them in the meantime.
3. When `subscription.activated` arrives for the *new* `razorpay_subscription_id`, the webhook
   handler looks it up via the `subscription_orders` row from step 1 (this is why that row needed
   the new id stored — a plain "find the user's active subscription" lookup would find the *old*
   one instead) and **atomically updates the same existing `subscriptions` row in place**:
   `plan_id`/`billing_cycle` → new values, `razorpay_subscription_id` → the new id. Still one row.
   Still satisfies the unique index. Access flips to Premium at this instant, not before.
4. Only now does the handler call Razorpay's cancel API on the *old* `razorpay_subscription_id`
   (which the local row no longer references) to stop that mandate from charging again.

The realistic overlap window here is however long checkout takes (seconds to minutes), not a
meaningful fraction of a billing cycle — so the "both mandates could charge" risk this might suggest
isn't practically live; noting it here so it's a documented, considered non-issue rather than an
unexamined one.

### 6.6 Admin override

`AdminSubscriptionController.changePlan` gains a guard: if `subscriptions.payment_provider =
'RAZORPAY'` and `status` is `ACTIVE`/`PAST_DUE`/`TRIAL`, the endpoint returns 409 with "This user has
an active paid subscription. Cancel it first before granting a complimentary plan," and does not
touch anything. A separate admin action, `POST
/api/v1/admin/subscriptions/{userId}/cancel-paid-subscription`, cancels the Razorpay subscription
immediately (not at cycle end — this is an admin support action, not a user-initiated one) and
clears `razorpay_subscription_id`. Only after that does the existing `changePlan` succeed, writing
`payment_provider = 'ADMIN_GRANT'`.

### 6.7 Referral trigger

`ReferralService.onPlanChanged` currently fires from the admin's manual `changePlan` call. It moves
to fire from the webhook handler on `subscription.activated` (real payment received), not from
checkout initiation and not from the admin path. `SubscriptionService.changePlan` (now
admin-override-only, per §6.6) stops calling it — an admin-granted complimentary plan should not
pay out a referral reward, matching "a referral should count only when money is received."

## 7. API surface (new/changed)

- `POST /api/v1/billing/checkout` (new) — initiate a subscription purchase
- `POST /api/v1/billing/cancel` (new) — user-initiated cancellation
- `POST /api/v1/billing/change-plan` (new) — user-initiated upgrade/downgrade (distinct from the
  admin endpoint of a similar name)
- `POST /api/v1/webhooks/razorpay` (new) — signature-verified, idempotent webhook receiver
- `GET /api/v1/billing/history` — unchanged shape, now actually returns rows
- `PUT /api/v1/admin/subscriptions/{userId}/plan` — gains the 409 guard from §6.6
- `POST /api/v1/admin/subscriptions/{userId}/cancel-paid-subscription` (new)

## 8. Frontend / mobile

- **Web**: pricing page's existing "coming soon" cards become live checkout buttons for Plus/Premium
  (`frontend/src/pages/landing/plans.ts` availability flips to `available`, real prices replace
  `null`). New billing portal page: current plan, renewal date, upgrade/downgrade/cancel actions,
  history list (already has a backend endpoint, just never had data).
- **Mobile**: new screens under `mobile/src/screens/settings/` (matching existing structure)
  mirroring the web billing portal — current plan, renewal date, actions, history. Checkout uses
  `react-native-razorpay` against the same `POST /api/v1/billing/checkout` contract as web.
  `PremiumFeatureGate`-equivalent gating already has no mobile counterpart today (per
  `ProfileScreen.tsx`'s note that no plan/subscription control exists yet) — this ships it for the
  first time on mobile, following the same fail-closed pattern as web's `PremiumFeatureGate`.
- **Admin portal**: `Subscriptions.tsx`'s plan dropdown gains the blocked/confirm flow from §6.6
  instead of firing `changePlan` directly when a real subscription is active.

## 9. Explicitly out of scope for V1

- Payment gateway account creation/KYC (yours to do — nothing here can substitute for it)
- Proration on upgrade/downgrade
- Refunds (schema supports `STATUS_REFUNDED` on `payments`; no flow triggers it)
- Free trial automation
- Resuming a `halted` subscription without going through fresh checkout
- Coupons, promotional pricing, regional pricing, multi-currency
- A Fynora-side grace-period clock distinct from Razorpay's own retry window
- Mobile push/email renewal reminders (could layer onto the existing notification platform later,
  not part of this spec)

## 10. External dependencies (blocking, not engineering work)

- Razorpay business account + KYC + live/test API keys — not something this session can create.
- Once test keys exist: create the 4 Razorpay Plan objects (Plus/Premium × Monthly/Yearly) and
  populate their ids into `billing_prices.razorpay_plan_id` — I can write the setup script, it just
  can't run without your keys.
- Confirm the three items flagged in §3 as unverified against a live sandbox subscription before
  finishing the §6.3/§6.4/§6.5 webhook-reconciliation code.
- Confirm `subscription.activated` only fires after a real successful first charge, not merely
  mandate authorization with no funds movement, before relying on it as the referral-payout trigger
  (§6.7) — a referral should count only when money is actually received.

## 11. Testing strategy

- Unit tests around the webhook handler's state-machine mapping (§5) — one test per transition,
  including idempotency (same `event_id` twice → single state change).
- Signature verification: a request with a tampered/missing signature must be rejected before
  touching `webhook_events` at all.
- Integration test for the full checkout→webhook→entitlement-active path, using a stubbed Razorpay
  client (no real network calls in CI) — matches this codebase's existing IT conventions.
- Corpus-style regression is not applicable here (no import/parsing surface); the equivalent
  discipline is: every state transition in §5 has an explicit test, not just the happy path.

## 12. Suggested build sequence (for the implementation plan)

Not a commitment to separate PRs necessarily, but the natural dependency order:

1. Schema migration (§4) + `Plan`/`Subscription` entity updates, no behavior change yet
2. Razorpay client wrapper + webhook receiver + signature verification + idempotency (§4.7) —
   buildable and testable against Razorpay's documented payload shapes before real checkout exists
3. Checkout flow (§6.1) end to end on web
4. Renewal reconciliation + cancellation, including `SubscriptionReconciliationSweepService`
   (§6.2, §6.3)
5. Upgrade/downgrade (§6.4, §6.5) — the two flows needing sandbox verification from §3/§10
6. Admin override guard + new admin action (§6.6)
7. Referral trigger move (§6.7)
8. Mobile screens (§8) — can start once the backend API surface (§7) is stable, doesn't block on
   Razorpay UI details already proven on web
