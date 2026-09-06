# Subscription billing V4 — mobile in-app purchase (RevenueCat) — Design

> Companion to [`2026-09-05-subscription-billing-v1-design.md`](2026-09-05-subscription-billing-v1-design.md)
> (backend core), [`2026-09-05-subscription-billing-v1-backend-core.md`](../plans/2026-09-05-subscription-billing-v1-backend-core.md),
> and the web UI built in Plan 3
> ([`2026-09-06-subscription-billing-v3-web-ui.md`](../plans/2026-09-06-subscription-billing-v3-web-ui.md)).
> `docs/project-management/plans/mobile-web-parity-matrix.md` §4.2 flagged mobile billing as
> "decision-gated... may be deliberately left web-only" pending a pricing/margin call (D-7/D-28).
> This spec is that call, made explicitly: full native purchase via Apple IAP + Google Play
> Billing, through RevenueCat, both platforms together.

## 1. Goal

Let a user subscribe to Plus/Premium from inside the iOS and Android apps, using each store's
native purchase flow (required by App Store/Play Store policy for digital subscriptions consumed
in-app), while keeping the backend's existing `subscriptions` table as the single source of truth
for entitlements — the same model web already uses, extended to a second payment rail.

## 2. Decisions made during design (binding for this spec)

| Topic | Decision |
|---|---|
| IAP layer | RevenueCat (`react-native-purchases`), not direct StoreKit/Play Billing. Chosen over a from-scratch integration (App Store Server API + Server Notifications V2, Google Play Developer API + RTDN) because it collapses two platform-specific receipt-validation/webhook pipelines into one SDK and one normalized webhook shape — comparable in backend size to the existing Razorpay webhook receiver, not a second full billing backend. |
| Platforms | iOS and Android together in one plan. Play Console enrollment, previously the blocking unknown (`project-plan-v1.0.md` line 42 recorded it as "still not started"), is confirmed done as of this spec. |
| RevenueCat `appUserID` | The real Fynora `User.id` (UUID), not a separate synthetic id. Matches this codebase's own existing convention (Razorpay's `notes.fynoraUserId` already embeds the raw user id, not a mapping id) and is required for RevenueCat's own cross-platform subscriber-identity resolution (§6.5) to work for free. |
| Pricing | Same nominal price as web — Plus ₹399/₹3,500, Premium ₹799/₹8,000 — set as the App Store Connect / Play Console product price in each store's own pricing tool. Fynora absorbs Apple/Google's commission (15-30%); no mobile-specific markup. |
| Free trial | None. Matches the web spec's explicit V1 decision to defer free trials everywhere — one `FREE → PAID` state machine, not two. |
| Existing web (Razorpay) subscriber opens mobile | Read-only: mobile shows their current plan and a note that it's managed on web. The purchase flow is not offered. Prevents ever charging the same user through two payment rails for the same entitlement. |
| Existing mobile (RevenueCat) subscriber opens web | Symmetric to the above: Billing Portal shows plan controls disabled, with "This subscription is managed through the App Store/Play Store" and a deep link to the relevant store's subscription-management page — not hidden entirely, so the user isn't confused about why they don't see their real plan. |
| Cross-store/cross-device | Entitlements are account-scoped, not device- or platform-scoped — a purchase on iPhone is visible on Android and web the moment they're signed into the same Fynora account, because all three read the same `subscriptions` row keyed by `user_id`. No new mechanism needed; stated here so it isn't assumed to require one. |
| `store_platform` | New column, nullable, meaningful **only** when `payment_provider=REVENUECAT`: `IOS` \| `ANDROID`, taken from RevenueCat's webhook `store` field. Deliberately *not* a parallel `WEB`/`ADMIN_GRANT`-inclusive enum (an earlier draft called this `subscription_source` with those extra values) — that would create two columns both claiming to represent "origin" for the same Razorpay/admin-grant rows, exactly the drift risk a reviewer flagged. `payment_provider` alone already answers "web vs. mobile vs. admin"; this column only answers the one question it can't: which store. |
| Webhook ledger | Reuses the existing `webhook_events` table (`WebhookEventService.claim(eventId, provider, eventType, payload)` already takes `provider` as a parameter for exactly this) — with its primary key widened from `event_id` alone to `(provider, event_id)`. Today it's `event_id`-only, which has only ever avoided collision by accident (a single provider using it); adding a second provider means this needs to be correct by design, not by luck of two different id formats. |
| Cancellation/plan changes on mobile | Not built as app-side controls — both App Store and Play Store policy require subscription cancellation to go through their own native subscription-management UI, not a button inside the app. Mobile's "My Subscription" screen deep-links out to it (`itms-apps://apps.apple.com/account/subscriptions` on iOS, the Play Store subscriptions center on Android) rather than implementing a custom cancel flow. |
| Purchase requires authentication | The mobile purchase flow (Paywall, `purchasePackage()`) is unreachable until the user is signed in — `Purchases.configure()` only ever runs with the real Fynora user id already known (§2's `appUserID` decision), never RevenueCat's own anonymous `$RCAnonymousID`. This is what makes §9's "TRANSFER out of scope" claim actually true rather than merely hoped for — nothing in the mobile app's existing navigation exposes billing screens pre-auth today, so this needs verifying, not building. |
| Ownership-source rule (named) | **A user has at most one active paid subscription, owned by exactly one provider at a time.** Moving from one provider to the other requires the existing one to end first (expire or be cancelled through its own store/portal) — there is no in-place transfer. §6.3/§6.4's read-only views and the guard in §6.4 are both direct consequences of this one rule, not independent decisions. |
| Admin override vs. a RevenueCat subscriber | Same rule as an existing Razorpay subscriber (design spec V1 §2: admin override blocked while a live paid mandate exists) — **but with no symmetric release valve**. `SubscriptionService.cancelPaidSubscription` is a Razorpay-specific API call (`gateway.cancelSubscription(razorpaySubscriptionId, ...)`); there is no backend-reachable way to force-release a real App Store/Play Store mandate the way there is for Razorpay. So for a `REVENUECAT`-owned row, admin override stays blocked with no "cancel it first" escape hatch — support has to wait for the subscription to actually end. See §6.8. |

### 2.1 System invariants

Everything above is an application of these. Stated once, explicitly, so a future change is checked
against them rather than against scattered individual behaviors:

1. At most one active paid subscription per user.
2. Every paid subscription is owned by exactly one payment provider at a time (the named
   ownership-source rule above).
3. Entitlements are derived exclusively from the `subscriptions` table — never from a client's own
   claim about what it just purchased.
4. A client's purchase success (Razorpay Checkout resolving, or `purchasePackage()` resolving) never
   grants entitlement directly — only a verified webhook does (design spec V1 §6.1 step 6; this
   spec's §6.1 step 5 extends it to IAP).
5. Webhooks are the only source of activation, renewal, plan change, and expiration — for both
   providers.
6. RevenueCat's `appUserID` is always the authenticated Fynora user id — never an anonymous one
   (the "purchase requires authentication" row above is what guarantees this holds).
7. **`subscriptions.payment_provider` is non-null if and only if a real external mandate still
   exists or is still winding down** — it is not a permanent historical stamp. Checked against the
   real code, not assumed: `handleHalted` and the reconciliation sweep both explicitly null it out
   when a subscription's paid access truly ends. The one nuance this invariant has to account for:
   `handleCancelled` sets `status=CANCELLED` but deliberately leaves `payment_provider` stamped until
   the sweep runs at period end — because per V1's own decision, a cancelled-but-not-yet-swept
   subscription still has real paid access (access continues until `current_period_end`). This is
   exactly why `BillingCheckoutService.checkout()`'s duplicate-subscription guard (§6.4) checks
   provider presence, not status: narrowing it to "status is ACTIVE" would let someone start a
   *second* mandate during that legitimate cancelled-but-still-active window.

## 3. Verified RevenueCat behavior this design relies on

Pulled from RevenueCat's own docs (`context7:/websites/revenuecat`), not assumed:

- **Webhook authentication — two tiers, re-verified against current docs before writing this
  version**: RevenueCat's default is a static `authorization_header` value set in its dashboard,
  compared verbatim (simpler, comparable to a shared secret). Separately, **HMAC signing is an
  explicit opt-in "for stronger verification"**: when enabled, every delivery carries
  `X-RevenueCat-Webhook-Signature: t=<unix_ts>,v1=<hmac_sha256_hex>`, computed over
  `"{timestamp}.{raw_body}"`. This design deliberately enables HMAC — not the simpler default —
  matching Razorpay's own signature-based scheme already in this codebase, and adding replay
  protection (timestamp tolerance) Razorpay's implementation here does not have.
- **Verification must run over the raw, unparsed request body** — RevenueCat's own docs warn that
  re-serializing a parsed object changes the bytes and silently breaks verification on valid
  requests. This is the exact same reasoning `RazorpayWebhookController` already documents for
  itself (`@RequestBody String rawBody`, never a typed DTO) — `RevenueCatWebhookController` follows
  the identical pattern, not a new one.
- **Event types actually delivered**: `INITIAL_PURCHASE`, `RENEWAL`, `CANCELLATION`,
  `UNCANCELLATION`, `EXPIRATION`, `BILLING_ISSUE`, `PRODUCT_CHANGE`, `TRANSFER`,
  `SUBSCRIPTION_PAUSED`, `NON_RENEWING_PURCHASE`, `SUBSCRIBER_ALIAS`.
- **`CANCELLATION`** fires when the user turns off auto-renew — access continues until
  `expiration_at_ms`, exactly Razorpay's own "cancel flips `auto_renew`, doesn't revoke access"
  model (design spec V1 §6.3). It is not itself a downgrade.
- **`UNCANCELLATION`** fires if the user turns auto-renew back on before expiry — reverses a prior
  `CANCELLATION`.
- **`EXPIRATION`** (with `expiration_reason`, e.g. `"UNSUBSCRIBE"`) is the actual point access should
  drop to Free — the mobile equivalent of Razorpay's `subscription.halted`, which (checked against
  the real code, `RazorpayWebhookDispatcher.handleHalted`) resets the plan to FREE, clears the
  provider-specific fields, and sets `status=ACTIVE` on FREE **directly** — no intermediate status.
- **`BILLING_ISSUE`** signals a failed renewal charge the store may still retry — maps to
  `Subscription.STATUS_PAST_DUE`, mirroring `handlePending`'s handling of Razorpay's own
  `subscription.pending` (retry in progress, access untouched) exactly. Not
  `STATUS_PAYMENT_FAILED`: that status is declared and counted on the admin health dashboard but has
  no live writer anywhere in the current Razorpay flow — mirroring it here would be inventing a new
  first user of a status the precedent this design follows doesn't actually reach.
- **`PRODUCT_CHANGE`** fires when the user changes plan tier/cycle through the store's own native
  "Change Plan" UI — something Razorpay has no equivalent for, since web's plan changes are
  Fynora-initiated. `entitlement_ids`/`product_id` on this event carry the new plan.
- Every event carries `app_user_id` (set at SDK init to Fynora's real user id, per §2), `store`
  (`APP_STORE`/`PLAY_STORE`), `environment` (`PRODUCTION`/`SANDBOX` — the mobile equivalent of
  Razorpay test-mode vs. live-mode keys), `expiration_at_ms`, and `original_transaction_id` (the
  stable per-subscription id to persist, since `transaction_id` changes on renewal).
- **`TRANSFER`** (a subscription moved between `app_user_id`s, e.g. an anonymous purchase later
  logged into an account) is real but not expected to occur here: purchase is only ever initiated
  after Fynora sign-in, so `appUserID` is always the real user id from the very first purchase —
  never RevenueCat's own anonymous `$RCAnonymousID:...`. Explicitly out of scope (§9); logged and
  ignored like an unhandled Razorpay event type, not silently dropped.

## 4. Data model

### 4.1 `subscriptions` — extended again

```
payment_provider   VARCHAR   gains 'REVENUECAT' as a value (already free-text; no migration needed for the column itself)
store_platform      VARCHAR(10)   NEW, nullable. 'IOS' | 'ANDROID', set only for REVENUECAT rows (see §2 — not a general-purpose origin field).
revenuecat_original_transaction_id  VARCHAR(100)  NEW, nullable. RevenueCat/store analog of razorpay_subscription_id — the stable id, not the per-renewal transaction_id. Wider than Razorpay's own 50-char id column deliberately: real samples (RevenueCat's docs, Apple's own forum examples) stay in the 13-17 digit range today, but there's no documented hard ceiling, and the cost of extra headroom on a rarely-joined column is negligible.
```

Same single-row-per-user model as V1/V3 — no second table for IAP *subscriptions*. The existing
`idx_subscriptions_one_active_per_user` constraint and `payment_provider`-based ownership check
(§6.4) both extend naturally.

### 4.2 New: `iap_products` — product-to-plan mapping table

Resolving a RevenueCat `product_id` to a Fynora plan/cycle belongs in data, not a `switch` statement
in the dispatcher — exactly the reasoning `billing_prices` already embodies for Razorpay's
`plan_id`+`billing_cycle` → `razorpay_plan_id` mapping. A dedicated table rather than extending
`billing_prices` itself, since the two providers' product identifiers are genuinely different shapes
(one Razorpay Plan object per plan/cycle vs. one App-Store-Connect-*and*-Play-Console product per
plan/cycle/platform — twice as many rows, keyed by platform too):

```
iap_products
------------
id                    UUID PK
provider_product_id   VARCHAR(100)  NOT NULL   -- e.g. "plus_monthly" (see UNIQUE note below)
plan_id               UUID NOT NULL REFERENCES plans(id)
billing_cycle         VARCHAR(10)   NOT NULL   -- MONTHLY | YEARLY
platform              VARCHAR(10)   NOT NULL   -- IOS | ANDROID
active                BOOLEAN NOT NULL DEFAULT true

UNIQUE (provider_product_id, platform)
```

`UNIQUE` is on the pair, not `provider_product_id` alone: nothing requires App Store Connect and
Play Console product ids to be globally distinct from each other (a developer could legitimately
name both stores' monthly-Plus product `plus_monthly`), so a same-named product on each platform
must resolve as two separate, valid rows, not a unique-constraint violation.

`RevenueCatWebhookDispatcher` looks up this table by `(provider_product_id, platform)` (both taken
from the webhook's `product_id`/`store`) to resolve plan/cycle deterministically — the same
lookup-not-branch pattern `BillingCheckoutService` already uses via
`billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue`.

### 4.3 `webhook_events` — primary key widened

`(event_id)` → `(provider, event_id)`. See §2's "Webhook ledger" row for why.

### 4.4 New: `RevenueCatProperties`

Mirrors `RazorpayProperties`: `webhookSigningSecret` (server-side only), plus the RevenueCat public
API keys (one per platform — these are meant to be embedded client-side, same posture as Razorpay's
`keyId` already returned to the frontend today).

### 4.5 No `subscription_orders` equivalent

Unlike Razorpay checkout, there is no server-initiated "create order, wait for activation" phase:
the purchase happens entirely client-side through the OS's own purchase sheet before the backend
hears anything. The backend's role for IAP is purely reactive — a webhook consumer, not a checkout
initiator. `subscription_orders` stays Razorpay-specific.

## 5. State machine

Identical `Subscription.status` vocabulary as V1 (`ACTIVE`/`PAST_DUE`/`PAYMENT_FAILED`/
`CANCELLED`/`EXPIRED`/`TRIAL`) — RevenueCat events map onto it rather than introducing new statuses:

| RevenueCat event | Effect |
|---|---|
| `INITIAL_PURCHASE` | `payment_provider=REVENUECAT`, `store_platform` from `store`, `status=ACTIVE`, `auto_renew=true`, plan/cycle resolved via `iap_products` (§4.2) from `product_id`, `revenuecat_original_transaction_id` set |
| `RENEWAL` | `status=ACTIVE` (idempotent if already), `renewal_date` updated from `expiration_at_ms` |
| `CANCELLATION` | `auto_renew=false` only — status/plan/renewal_date untouched, exactly Razorpay's `cancel()` |
| `UNCANCELLATION` | `auto_renew=true` |
| `EXPIRATION` | Downgrade to Free, directly (mirrors `handleHalted` exactly — see §3) |
| `BILLING_ISSUE` | `status=PAST_DUE` (mirrors `handlePending` exactly — see §3) |
| `PRODUCT_CHANGE` | **Both** plan tier and billing cycle reconciled via `iap_products` from the new `product_id` — a Premium Monthly → Premium Yearly change is not a tier change but still needs the same reconciliation, mirroring `handleCharged`'s existing plan-id reconciliation |
| `SUBSCRIPTION_PAUSED`, `TRANSFER`, `NON_RENEWING_PURCHASE`, `SUBSCRIBER_ALIAS` | Logged, not acted on — explicitly out of scope (§9), same posture as the Razorpay dispatcher's `default -> log.info` for unhandled types |

## 6. Flows

### 6.1 First-time mobile purchase

1. App configures `Purchases.configure({ apiKey, appUserID: user.id })` at sign-in (real id, §2).
2. User taps Plus/Premium on the mobile Paywall screen (§8) → `react-native-purchases`'
   `purchasePackage()` → OS native purchase sheet → App Store/Play Store handles payment directly;
   Fynora's backend is not involved in this step at all.
3. RevenueCat validates the receipt server-side and fires `INITIAL_PURCHASE` to
   `POST /api/v1/webhooks/revenuecat`.
4. Backend verifies the HMAC signature + timestamp (§3), looks up `User` by `app_user_id` (direct
   id, no mapping table), and applies the effect in §5.
5. Mobile polls `GET /api/v1/entitlements/mine` (§8) after the client-side purchase resolves, same
   `useActivationPoll` pattern web already uses (design spec V3) — activation is still only ever
   trusted from the verified webhook, never the client's own purchase-success callback.

### 6.2 Renewal / cancellation / expiration

Entirely passive from the app's perspective — RevenueCat and the store handle the renewal charge or
lapse, and deliver `RENEWAL`/`CANCELLATION`/`EXPIRATION` to the same webhook. No app-side action.

### 6.3 User already has a Razorpay (web) subscription, opens mobile

Mobile reads `mySubscription()` (same endpoint web uses) — `payment_provider=RAZORPAY` on the
response means the Paywall is not shown; a read-only "your plan, managed on web" view is shown
instead (§2).

### 6.4 User already has a RevenueCat (mobile) subscription, opens web — the guard fix

`BillingCheckoutService.checkout()`'s existing duplicate-subscription guard
(`s.getRazorpaySubscriptionId() != null`) does not see a RevenueCat-owned row at all — a IAP
subscriber could check out again on web today. Generalized to a provider-agnostic check:

```java
subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
        // Provider PRESENCE, not status — deliberately. A cancelled-but-not-yet-swept Razorpay
        // subscription (status=CANCELLED, payment_provider still stamped) still has real paid
        // access per V1's own decision; narrowing this to "status is ACTIVE" would let a second
        // mandate start during that legitimate window. See invariant 7 (§2.1).
        .filter(s -> s.getPaymentProvider() != null && !"ADMIN_GRANT".equals(s.getPaymentProvider()))
        .ifPresent(s -> { throw new ApiException(HttpStatus.CONFLICT, ...); });
```

Symmetrically, the web Billing Portal shows disabled plan controls with an explanatory note and a
deep link when `payment_provider=REVENUECAT` (§2's "Option 2" — chosen over hiding the section
entirely, for the same reason mobile's own read-only view isn't a blank screen: a user who knows
they're paying should always see what they're paying for, even when they can't change it here).

### 6.5 Same account, iPhone then Android

Because `appUserID` is the real Fynora user id on both platforms (§2), RevenueCat's own
cross-platform subscriber-identity resolution recognizes both as the same subscriber without any
Fynora-side reconciliation — this is the concrete benefit of not using a separate synthetic id.

### 6.6 Restore Purchases

1. User taps "Restore Purchases" (My Subscription screen, §8) → `react-native-purchases`'
   `restorePurchases()`.
2. RevenueCat re-associates any purchase history for the signed-in Apple/Google account with the
   current `appUserID` and may itself emit a `TRANSFER` or `SUBSCRIBER_ALIAS` event server-side —
   these still land on the same webhook and are still logged-not-acted-on per §5/§9; the backend's
   `subscriptions` row remains authoritative regardless of what RevenueCat's own bookkeeping does
   internally.
3. Client does not trust the SDK call's own return value as "entitlement restored" — it re-fetches
   `GET /api/v1/entitlements/mine`/`mySubscription()` afterward, same "backend webhook is the only
   source of truth" rule as every other flow in this spec (§6.1 step 5).

### 6.7 User deletes the app, reinstalls months later

No special handling needed: the `subscriptions` row is server-side and keyed by `user_id`, not
anything device-local. Logging back in fetches the same entitlement it always would — there is no
"restore" step required unless the *reinstall* is also a *different Apple/Google account* than the
one that made the original purchase, which §6.6 already covers.

### 6.8 Admin support, RevenueCat subscriber

Same guard as an existing Razorpay subscriber — `SubscriptionService.changePlan` generalizes from
its current literal `"RAZORPAY".equals(subscription.getPaymentProvider())` check to match either
paid provider, blocking a complimentary-plan override while either is active. Unlike Razorpay,
there is no `cancelPaidSubscription` equivalent an admin can call first to release a RevenueCat-
owned mandate — `cancelPaidSubscription` is a Razorpay-specific gateway call with nothing to
generalize to, since Apple/Google don't expose mandate cancellation to third parties. Support's only
options for a RevenueCat subscriber needing an admin-granted plan are: wait for their subscription
to actually expire, or ask them to cancel it themselves through the store first. Worth stating
plainly rather than leaving a future engineer to discover it while trying to build the
generalization.

The blocked error itself should say why in terms support can act on and relay to the user, not just
that it's blocked — e.g. `"This account has an active Apple/Google subscription. Ask the user to
cancel it through the App Store/Play Store first, then retry."` — the same "the error explains what
to do, not just what went wrong" standard this codebase already holds itself to elsewhere (e.g.
`checkout()`'s own 409 telling the caller exactly which endpoint to hit first).

## 7. API surface (new/changed)

```
POST /api/v1/webhooks/revenuecat     Unauthenticated (HMAC-signed), mirrors RazorpayWebhookController
```

No new user-facing REST endpoints: `GET /api/v1/billing/my-subscription` (existing) and
`GET /api/v1/entitlements/mine` (existing — see §8's correction) already return enough for mobile to
decide Paywall vs. read-only view and to gate individual features.

## 8. Frontend / mobile

**Correction found while writing this spec, not before**: mobile has no entitlement/feature-gating
code at all today. `PremiumFeatureGate` and `entitlementsApi` (`frontend/src/api/endpoints.ts`,
`frontend/src/components/PremiumFeatureGate.tsx`) exist only on `frontend` (web) — confirmed by
`grep`, zero matches in `mobile/src`. The parity-matrix doc's §4.2 sentence ("shipping
`entitlementsApi`/`PremiumFeatureGate` to mobile means...") is future-tense, describing what
*would* need to exist, not something already there; an earlier draft of this spec misread it as
already built. The backend endpoint itself (`GET /api/v1/entitlements/mine`) needs no changes —
mobile just needs its own client, which this plan must now build:

- **`useEntitlements` hook** (mobile equivalent of web's inline `useQuery(['entitlements'], ...)`):
  thin wrapper over `GET /api/v1/entitlements/mine`.
- **`PremiumFeatureGate` (mobile)**: same fail-closed contract as web's version (no access while
  loading or on error — never briefly reveal a gated feature), adapted to React Native's view
  primitives instead of web's `<div>`-based markup. Small (web's version is ~40 lines); not a new
  design, a port.
- **Paywall screen** (mobile equivalent of `Pricing.tsx`): shown only when `mySubscription()` shows
  no active paid `payment_provider`. Presents Plus/Premium at the same prices as web.
- **My Subscription screen** (mobile equivalent of `Billing.tsx`, structurally different per §2):
  current plan, renewal/expiry date, and:
  - `payment_provider=REVENUECAT`: a "Manage subscription" action deep-linking to the OS
    subscription-management screen; a "Restore Purchases" action (Apple guideline 3.1.2 requires
    this be available, not just implied by re-login).
  - `payment_provider=RAZORPAY`: read-only, "managed on web" note, no controls.

## 9. Explicitly out of scope for V4

- Free trials / introductory offers (§2).
- `TRANSFER`, `SUBSCRIPTION_PAUSED`, `NON_RENEWING_PURCHASE`, `SUBSCRIBER_ALIAS` events — logged,
  not acted on (§5).
- Family Sharing (`is_family_share` on the webhook payload) — not addressed; a family-shared
  subscriber's entitlement handling is deferred.
- Proration on a mobile plan change — same V1-inherited decision as web (design spec V1 §2:
  "V1 does not implement proration").
- Admin-portal visibility into RevenueCat-specific subscription state beyond what the existing
  Subscription Health dashboard (Plan 3, Task 7) already shows via `payment_provider`/status counts.
- Gating any *specific* premium feature on mobile beyond the gate component itself — §8 builds the
  mechanism (`PremiumFeatureGate`, ported); wiring it around a real mobile feature (mobile's own
  `AdvancedReports`-equivalent, if one exists) is separate, follow-on work, matching how web's own
  gate shipped before its first real call site did (PR #1044, weeks after the gate component itself).

## 10. External dependencies (blocking, not engineering work)

- RevenueCat project + API keys (test/sandbox and production) — dashboard account, not something
  this session can create.
- Subscription products created in App Store Connect (Plus/Premium × Monthly/Yearly) and Play
  Console, priced to match §2, then mapped to RevenueCat Entitlements/Offerings in its dashboard —
  same category of manual, one-time setup as the Razorpay Plan objects created for V3/production.
- RevenueCat webhook registered (URL + signing secret) — becomes `RevenueCatProperties.webhookSigningSecret`.
- Sandbox/TestFlight and Play Console internal-testing accounts for verifying a real purchase
  end-to-end before submission, mirroring how V3's production gap was caught by a real test-mode
  dry run rather than trusted from tests alone.
- **A specific unknown this design cannot resolve from documentation alone**: whether `PRODUCT_CHANGE`
  arrives before, after, or interleaved with the entitlement actually changing on Apple's and
  Google's sides (the two stores are not guaranteed to behave identically here). This has to be
  observed against a real sandbox upgrade/downgrade before production, the same way the Razorpay
  webhook payload-unwrap bug was only ever found by a real dry run, not by reading Razorpay's docs
  more carefully.

## 11. Testing strategy

Same discipline as the Razorpay webhook fix this plan's own predecessor required: a dispatcher-level
test calling the handler directly with an already-shaped payload is not sufficient proof the
*controller* unwraps a real RevenueCat webhook body correctly. `RevenueCatWebhookControllerIT` must
send an HMAC-signed, realistically-shaped body (timestamp header included) through the actual HTTP
endpoint for at least `INITIAL_PURCHASE`, `CANCELLATION`, `EXPIRATION`, and **`PRODUCT_CHANGE`**
(mandatory, not optional — it's the one event type with no Razorpay precedent to lean on: the plan
change is store-initiated, not Fynora-initiated, and it's the transition most likely to have a
reconciliation bug slip through if only cloned from `handleCharged`'s logic without its own test),
asserting the real state change — not just a 200 response.

## 12. Suggested build sequence (for the implementation plan)

1. Data model migration: `store_platform`/`revenuecat_original_transaction_id` on `subscriptions`,
   the new `iap_products` table, and widening `webhook_events`' primary key to `(provider, event_id)`.
2. `RevenueCatWebhookController` + dispatcher + `RevenueCatProperties`, with the signature+timestamp
   verification from §3 — backend-only, testable without any mobile client changes yet.
3. Generalize `BillingCheckoutService.checkout()`'s duplicate-subscription guard (§6.4) and
   `SubscriptionService.changePlan`'s admin-override guard (§6.8) — both small, independently
   testable fixes, and the two changes that touch existing (web/admin) behavior.
4. Mobile: `useEntitlements` + `PremiumFeatureGate` port (§8) — needed before the purchase flow has
   anything to gate, and independently testable against the existing, unchanged backend endpoint.
5. Mobile: `react-native-purchases` wiring, Paywall screen, My Subscription screen with the two
   read-only/active variants (§8).
6. Web: disabled-controls variant for a `REVENUECAT`-owned subscription in the Billing Portal (§6.4).
7. End-to-end sandbox verification (App Store Sandbox + Play Console internal testing) before any
   store submission.
