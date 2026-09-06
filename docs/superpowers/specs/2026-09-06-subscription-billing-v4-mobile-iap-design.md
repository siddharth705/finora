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
| `subscription_source` | New column, separate from `payment_provider`: `WEB` \| `IOS` \| `ANDROID` \| `ADMIN_GRANT`. `payment_provider=REVENUECAT` alone can't distinguish iOS from Android; RevenueCat's webhook `store` field (`APP_STORE`/`PLAY_STORE`) already carries this for free. |
| Webhook ledger | Reuses the existing `webhook_events` table (`WebhookEventService.claim(eventId, provider, eventType, payload)` already takes `provider` as a parameter for exactly this). No new `revenuecat_webhook_events` table. |
| Cancellation/plan changes on mobile | Not built as app-side controls — both App Store and Play Store policy require subscription cancellation to go through their own native subscription-management UI, not a button inside the app. Mobile's "My Subscription" screen deep-links out to it (`itms-apps://apps.apple.com/account/subscriptions` on iOS, the Play Store subscriptions center on Android) rather than implementing a custom cancel flow. |

## 3. Verified RevenueCat behavior this design relies on

Pulled from RevenueCat's own docs (`context7:/websites/revenuecat`), not assumed:

- **Webhook signature verification** is HMAC-SHA256 over `"{timestamp}.{raw_body}"`, delivered in a
  Stripe-style header (`t=<unix_ts>,v1=<hex_signature>`), with a timestamp-tolerance check against
  replay — not a bare static bearer token. This is comparably rigorous to Razorpay's HMAC scheme and
  adds replay protection Razorpay's own implementation in this codebase does not have.
- **Event types actually delivered**: `INITIAL_PURCHASE`, `RENEWAL`, `CANCELLATION`,
  `UNCANCELLATION`, `EXPIRATION`, `BILLING_ISSUE`, `PRODUCT_CHANGE`, `TRANSFER`,
  `SUBSCRIPTION_PAUSED`, `NON_RENEWING_PURCHASE`, `SUBSCRIBER_ALIAS`.
- **`CANCELLATION`** fires when the user turns off auto-renew — access continues until
  `expiration_at_ms`, exactly Razorpay's own "cancel flips `auto_renew`, doesn't revoke access"
  model (design spec V1 §6.3). It is not itself a downgrade.
- **`UNCANCELLATION`** fires if the user turns auto-renew back on before expiry — reverses a prior
  `CANCELLATION`.
- **`EXPIRATION`** (with `expiration_reason`, e.g. `"UNSUBSCRIBE"`) is the actual point access should
  drop to Free — the mobile equivalent of Razorpay's `subscription.halted`.
- **`BILLING_ISSUE`** signals a failed renewal charge — maps to `Subscription.STATUS_PAYMENT_FAILED`,
  same status Razorpay already uses for this.
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
subscription_source        VARCHAR(10)   NEW: 'WEB' | 'IOS' | 'ANDROID' | 'ADMIN_GRANT'. Existing rows backfilled to 'WEB' (RAZORPAY) or 'ADMIN_GRANT' (ADMIN_GRANT) in the same migration.
revenuecat_original_transaction_id  VARCHAR(50)   NEW, nullable. RevenueCat/store analog of razorpay_subscription_id — the stable id, not the per-renewal transaction_id.
```

Same single-row-per-user model as V1/V3 — no second table for IAP subscriptions. The existing
`idx_subscriptions_one_active_per_user` constraint and `payment_provider`-based ownership check
(§6.4) both extend naturally.

### 4.2 New: `RevenueCatProperties`

Mirrors `RazorpayProperties`: `webhookSigningSecret` (server-side only), plus the RevenueCat public
API keys (one per platform — these are meant to be embedded client-side, same posture as Razorpay's
`keyId` already returned to the frontend today).

### 4.3 No `subscription_orders` equivalent

Unlike Razorpay checkout, there is no server-initiated "create order, wait for activation" phase:
the purchase happens entirely client-side through the OS's own purchase sheet before the backend
hears anything. The backend's role for IAP is purely reactive — a webhook consumer, not a checkout
initiator. `subscription_orders` stays Razorpay-specific.

## 5. State machine

Identical `Subscription.status` vocabulary as V1 (`ACTIVE`/`PAST_DUE`/`PAYMENT_FAILED`/
`CANCELLED`/`EXPIRED`/`TRIAL`) — RevenueCat events map onto it rather than introducing new statuses:

| RevenueCat event | Effect |
|---|---|
| `INITIAL_PURCHASE` | `payment_provider=REVENUECAT`, `subscription_source` from `store`, `status=ACTIVE`, `auto_renew=true`, plan/cycle resolved from `product_id`/`entitlement_ids`, `revenuecat_original_transaction_id` set |
| `RENEWAL` | `status=ACTIVE` (idempotent if already), `renewal_date` updated from `expiration_at_ms` |
| `CANCELLATION` | `auto_renew=false` only — status/plan/renewal_date untouched, exactly Razorpay's `cancel()` |
| `UNCANCELLATION` | `auto_renew=true` |
| `EXPIRATION` | Downgrade to Free (mirrors `subscription.halted`'s handling) |
| `BILLING_ISSUE` | `status=PAYMENT_FAILED` |
| `PRODUCT_CHANGE` | Plan/cycle reconciled to the new `product_id`/`entitlement_ids`, mirroring `handleCharged`'s existing plan-id reconciliation |
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

## 11. Testing strategy

Same discipline as the Razorpay webhook fix this plan's own predecessor required: a dispatcher-level
test calling the handler directly with an already-shaped payload is not sufficient proof the
*controller* unwraps a real RevenueCat webhook body correctly. `RevenueCatWebhookControllerIT` must
send an HMAC-signed, realistically-shaped body (timestamp header included) through the actual HTTP
endpoint for at least `INITIAL_PURCHASE`, `CANCELLATION`, and `EXPIRATION`, asserting the real state
change — not just a 200 response.

## 12. Suggested build sequence (for the implementation plan)

1. Data model migration (`subscription_source`, `revenuecat_original_transaction_id`, backfill).
2. `RevenueCatWebhookController` + dispatcher + `RevenueCatProperties`, with the signature+timestamp
   verification from §3 — backend-only, testable without any mobile client changes yet.
3. Generalize `BillingCheckoutService.checkout()`'s duplicate-subscription guard (§6.4) — a small,
   independently testable fix, and the one change that touches existing (web) behavior.
4. Mobile: `useEntitlements` + `PremiumFeatureGate` port (§8) — needed before the purchase flow has
   anything to gate, and independently testable against the existing, unchanged backend endpoint.
5. Mobile: `react-native-purchases` wiring, Paywall screen, My Subscription screen with the two
   read-only/active variants (§8).
6. Web: disabled-controls variant for a `REVENUECAT`-owned subscription in the Billing Portal (§6.4).
7. End-to-end sandbox verification (App Store Sandbox + Play Console internal testing) before any
   store submission.
