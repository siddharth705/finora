# Notification & Communication Infrastructure — Design Proposal

**Status:** Proposal only. **Nothing here is implemented.** "Pre-launch" in this document means
architecture and design now, not implementation now. Implementation is explicitly sequenced after
all of: C-8 Track B, the 56 open bug-hunt findings, backup/recovery readiness, and monitoring/Sentry
setup. Notifications are not more important than operational reliability — a notification system
bolted onto a backend that can't yet recover from data loss or alert on its own failures creates more
risk than it removes. Same sequencing rationale as `support-help-feedback-proposal.md` in this
directory.

The long-term goal is the fuller architecture described below — provider-agnostic, transactionally
durable, template-driven, preference-aware — built as a modular monolith (no message broker, no
microservice split). That target doesn't change; only the start date does.

**One correction to the originating proposal's premise:** it describes "OTP generation flow / OTP
verification flow / Resend OTP functionality" as existing Finora infrastructure to route through a
new centralized service. That's not accurate. `FirebaseConfig.java`'s own doc comment records that
Finora's previous custom OTP stack (`OtpService`/`PhoneOtp`/`SmsService`) was already removed in
favor of Firebase Phone Authentication — OTP generation, delivery, expiry, and rate limiting are
Google's infrastructure, called client-side, with Finora's backend verifying the resulting Firebase
ID token server-side in `FirebasePhoneVerificationProvider` (called from `AuthService` and
`PasswordChangeService.verifyOtp()`); `PhoneVerificationFilter` is a separate, downstream request
gate that only checks a stored `phoneVerified` boolean, not the token itself. There is no
Finora-owned OTP send/resend path to centralize. This changes what "notification infrastructure"
actually needs to cover — see §2.

**No urgent exception required.** The originating proposal asked to harden OTP/resend now, ahead of
other launch-blocking work, on the assumption it's an under-hardened live GA path. A read-only audit
found the opposite: the Finora-owned surface around it (password-change sessions, password reset)
already has tested rate limiting (`RateLimitFilter`), session expiry (15 min,
`PasswordChangeSession`), and audit logging (`AuditService`). Two minor gaps exist — no attempt
counter specific to OTP verification (today it's bounded only by the shared rate limit and session
expiry, not a retry cap), and no SMS/email delivery-status tracking — but neither is launch-blocking.
Both are folded into this proposal's scope (§2.4) rather than jumping the queue.

---

## 1. Objective

Build a single notification layer for security alerts, financial updates, and (later) Fino-triggered
insights — across email, SMS, and mobile push — replacing today's pattern of each caller talking
directly to a provider.

## 2. What exists today (baseline)

Finora already has more of this than the originating proposal assumed:

- **Email**: `EmailProvider` interface, `ResendEmailProvider` (real), `NoOpEmailProvider` (dev
  fallback), `EmailProviderHealthProvider` for health checks. Used today for password-reset links
  and similar transactional mail.
- **SMS**: `SmsProvider` interface, `TwoFactorSmsProvider` (real, via 2Factor — currently scoped to
  manual transaction-entry alerts only, per `TransactionService.create()`), `NoOpSmsProvider`,
  `SmsProviderHealthProvider`.
- **OTP / phone verification**: entirely Firebase Phone Authentication, client-side. Not
  Finora-owned, not in scope to re-architect (see correction above).
- **Push notifications (Android/iOS)**: nothing exists. No FCM, no APNs, no device-token storage,
  confirmed by a repo-wide search.
- **Audit logging**: `AuditService` already logs the security-relevant events a notification system
  would key off (`PASSWORD_CHANGED`, `PHONE_VERIFIED`, login events, etc.) — a notification service
  can subscribe to or reuse this rather than inventing a parallel event log.

## 3. Proposed scope (v1 — the only thing being designed here)

### 2.1 Notification module — provider-agnostic, transactionally durable

A dedicated `com.finora.notification` module, structured so later capabilities (Fino insights,
premium alerts, an in-app inbox) plug in without a redesign:

```
com.finora.notification
├── api          — NotificationService (what callers use)
├── domain       — Notification, NotificationType, NotificationPriority, NotificationCategory
├── provider     — EmailNotificationProvider, SmsNotificationProvider, PushNotificationProvider
├── template     — TemplateRenderer
├── worker       — NotificationDispatcher
└── repository
```

**Architecture principle.** The notification system is an internal platform module, not a separate
service. It stays inside the Finora modular monolith — one Spring Boot deployable, one database —
until actual scale or operational requirements justify extraction. The package boundary above exists
for internal clarity and future optionality, not because a `finora-notification-service`, a message
broker, or a separate deployment pipeline are being planned. Don't read "module" as an invitation to
build any of those now (see §4).

`provider/` wraps the *existing* `EmailProvider`/`SmsProvider` interfaces (don't replace them —
they're already tested and working) and adds push as the one genuinely new channel:

```
Caller (Auth/Budget/Import service)
        |
        v   writes a Notification row, in its OWN existing DB transaction
NotificationService.request(NotificationRequest)
        |
        v
   notifications table  (durable — survives a crash before dispatch)
        |
        v   polled by
NotificationDispatcher (worker)
        |
   ┌────┼────────┐
   |    |         |
 Email  SMS      Push
   |    |         |
(existing)  (existing)  FCM (Android) / APNs (iOS) — new
```

**Triggering is a transactional outbox, not an in-memory event bus.** Earlier drafts of this
proposal described this as "event-driven" (`BudgetLimitReachedEvent` → engine → channels). That
wording is dropped: this codebase doesn't use Spring's `ApplicationEventPublisher`/`@EventListener`
anywhere, and an in-memory event fired before a crash is lost — there's nothing to replay. Instead,
`NotificationService.request(...)` writes a row into `notifications` inside the caller's *own*
existing transaction, the same way `AuditService.record()` already writes its row inside
`PasswordChangeService`'s and `UserAccountLifecycleService`'s transactions today — a pattern already
proven in this codebase. A separate `NotificationDispatcher` worker, shaped like the existing
`MerchantLearningEventWorker` (poll → process → `retryScheduled`/`deadLettered`, see
`docs/engineering/observability.md` §7), picks up undispatched rows and sends them. Callers still
name the trigger semantically (`BudgetLimitReached`, `PasswordChanged`, ...) via the `type` field
below — only the delivery mechanism is a durable table-and-worker, not a pub/sub bus. No caller talks
to a provider directly once this lands.

**Notification templates.** Callers pass a `type` (`PASSWORD_CHANGED`, `BUDGET_LIMIT_REACHED`,
`STATEMENT_IMPORTED`, `PAYMENT_REMINDER`, `FINO_INSIGHT`, ...) and a small parameter map, not a
hardcoded title/body string:

```
notification_templates
├── id
├── type
├── channel           — title/body wording can differ by channel (push is terse, email has room)
├── title_template     — e.g. "Budget Alert"
├── body_template       — e.g. "You have used {{percentage}}% of your {{category}} budget."
├── active
```

Without this, notification copy ends up hardcoded inside `BudgetService`, `ImportService`, etc. —
scattered across callers, unreviewable in one place, and impossible for a future Fino integration to
reuse consistently. Centralizing copy is the same instinct as centralizing delivery.

**Priority.** Not every notification deserves equal urgency, and this matters more for a financial
app than most:

```
priority — CRITICAL, HIGH, NORMAL, LOW
```

`PASSWORD_CHANGED` / new-device login is `CRITICAL` or `HIGH`; a marketing/product-update
notification is `LOW`. Priority can inform channel selection (e.g. push+email for CRITICAL, push
only for LOW) — exact policy is implementation-time, but the field needs to exist from the start so
it isn't bolted on after the first incident where a security alert got buried among low-priority
noise.

### 2.2 Push notification providers (the genuinely new piece)

- Firebase Cloud Messaging for Android — reuses the Firebase project already configured for phone
  auth (`GOOGLE_APPLICATION_CREDENTIALS`), no new vendor relationship needed.
- Apple Push Notification Service for iOS — new credential/certificate setup required.
- New: a `device_tokens` table — mobile app registers/refreshes its token on login and app-open.
  Device tokens are a sensitive credential (they let anyone holding one push notifications to that
  device), so they're never logged raw.

```
device_tokens
├── id
├── user_id
├── platform          — ANDROID, IOS
├── encrypted_token     — encrypted at rest, NOT hashed
├── created_at
├── last_seen_at        — updated on each successful send, or explicit refresh
├── revoked_at          — set on logout/uninstall detection, not a hard delete
```

**Encrypted, not hashed — this is a correction from an earlier draft.** A password/JWT is only ever
*compared*, so a one-way hash works. A device token is different: `NotificationDispatcher` has to
hand the actual token to FCM/APNs on every send, so it must be recoverable — a hash can't be
reversed and would make the table useless for its own purpose. That makes encryption-key management
part of this work's scope, not an afterthought: which key/KMS, rotation policy, and where the key is
held all need to be decided at implementation time, following whatever pattern the codebase already
uses for other at-rest secrets.

### 2.3 Notification preferences

```
notification_preferences
├── user_id
├── category      — SECURITY, FINANCIAL, MARKETING (mirrors the originating proposal's grouping)
├── channel        — PUSH, EMAIL, SMS
├── enabled
```

Security-category notifications (new-device login, password changed) should default on and likely
shouldn't be fully disable-able for the channel the user actually has verified (mirrors how the
existing `RESEND_API_KEY` incident treated security-relevant delivery as non-optional) — exact
policy is an implementation-time product decision, flagged here rather than decided.

### 2.4 OTP-adjacent hardening (folded in, not urgent)

Small items identified by the audit, sized to fit alongside this work rather than jumping ahead of
it:
- Add an attempt counter to OTP verification (`PasswordChangeService.verifyOtp`), separate from the
  existing rate limit and session expiry — mirrors the login lockout pattern already in
  `AuthService`.
- Basic delivery-status capture for the notification log (§3), so "we called the provider" and "it
  was actually delivered" stop being conflated — applies to the new push channel and existing
  email/SMS alike.

### 2.5 Notification log (for admin visibility, not analytics)

```
notifications                          notification_logs
├── id                                 ├── id
├── notification_key   (see below)     ├── notification_id
├── user_id                            ├── provider
├── category / channel / priority      ├── response
├── title / message                    ├── status
├── status                             ├── retry_count
├── sent_at / read_at / created_at     ├── next_retry_at
                                        ├── timestamp
```

**Lifecycle states — scoped to what's actually verifiable today:**
`CREATED → QUEUED → PROCESSING → SENT`, with failures going `FAILED → RETRYING → DEAD_LETTER`
(mirroring the `retryScheduled`/`deadLettered` vocabulary `MerchantLearningEventWorker` already
uses). `SENT` means the provider's synchronous API call returned success — that's the only
confirmation any provider gives us today. `DELIVERED` and `READ` are deliberately **not** in this
state machine yet: neither Resend nor 2Factor has a webhook wired up in this codebase, so there's no
mechanism to ever move a notification into those states truthfully. Adding them now would leave
permanently-stale fields. They come back once provider delivery webhooks exist (§4).

`read_at` on the `notifications` table is unrelated to that and stays: it's for a future in-app
inbox to record when a user opened the notification in the app, a client-reported signal, not a
provider-confirmed one — no webhook dependency.

**Idempotency.** A backend retry (a request timeout, a queue redelivery, a worker recovering an
abandoned job — the same class of failure `MerchantLearningEventWorker` already has to handle) must
not re-send the same notification. `notification_key` is a caller-supplied deterministic string
(e.g. `BUDGET_ALERT_{userId}_{yyyyMM}`) checked before send: already sent for this key → skip. This
matters more here than in most systems, because the callers are financial events — a duplicate
"budget exceeded" push is a trust problem, not just noise.

**Retry.** Delivery failures (push/email/SMS) get a bounded backoff — `retry_count` /
`next_retry_at` on `notification_logs`, escalating (e.g. 5 min → 30 min → mark failed), not an
immediate infinite retry loop. Same shape as the existing worker retry/dead-letter pattern
documented in `docs/engineering/observability.md` §7 — reuse that vocabulary (`retryScheduled` /
`deadLettered`) rather than inventing a second one.

**In-app inbox — resolved for v1, not deferred as an open question.** Don't build a dedicated inbox
UI now, but the `notifications` table above already gives the mobile/web app what it needs to add
one later (`GET /notifications`, `read_at`) without a schema change. So: no inbox screen in v1, but
nothing about this design blocks adding one once there's a reason to.

Same spirit as the Support proposal's admin scope: a list + basic send-outcome counts (sent, failed —
`delivered` joins this once provider webhooks exist, §4), not trend analytics or clustering.
Concretely, the admin screen is:

```
Notification Dashboard
Today
------
Sent / Failed

By channel
------
Push / Email / SMS

Failures
------
Provider · Error reason · Time
```

Nothing beyond this — no trend charts, no engagement scoring.

## 4. Explicitly out of scope for v1

- **Any change to OTP generation, storage, or delivery itself** — that's Firebase's, not ours to
  rebuild.
- Fino-triggered notifications — the architecture should leave room for it (Fino creates a
  notification *request* through this same service, never sends directly — matches the originating
  proposal's own principle), but no Fino integration is built now. Fino itself is still parked
  (`docs/roadmap/fino-v2-readiness.md`).
- Rich admin analytics (open rates over time, engagement trends, provider-failure clustering) —
  basic counts only, same reasoning as the Support proposal: no real volume yet to make analytics
  meaningful.
- SMS-based OTP of any kind — Firebase owns this; don't build a parallel path.
- Marketing/product-update notifications — category placeholder only, no send logic.
- In-app notification inbox/history UI — schema supports it later (§2.5), no screen built now.
- `DELIVERED`/`READ` provider-confirmed delivery states — deferred until Resend and 2Factor webhook
  endpoints exist to actually populate them (§2.5). Building the states first would just leave them
  permanently unset.
- Localization/i18n for notification copy (e.g. Hindi/Marathi templates) — the app has zero
  localization infrastructure today (no message bundles, no locale resolver, no locale field on
  `User`, no i18n library in the mobile app). This is a real, separate initiative — locale
  resolution strategy plus mobile plumbing plus translated copy — not a line item under templates.
  `notification_templates` ships English-only for v1; a `language` column can be added later without
  breaking the schema.

**Explicitly not needed at this scale, and a reason not to reach for them later without new
evidence:** Kafka, RabbitMQ, or any message broker; a notification microservice; a workflow/rules
engine for notifications; marketing automation or customer segmentation. A Spring Boot module plus a
few tables plus provider adapters is the right amount of engineering for Finora's current volume —
the same modular-monolith-first reasoning already applied to the Support proposal (basic-counts admin
scope, no event bus) and to Fino itself.

## 5. Rough sizing

| Item | Effort |
|---|---|
| `com.finora.notification` module + `NotificationService` over existing Email/SMS providers | S–M |
| Transactional-outbox write path (`notifications` row in caller's own transaction) | S |
| `NotificationDispatcher` worker (poll/send/retry, `MerchantLearningEventWorker`-shaped) | S–M |
| FCM (Android) integration | M |
| APNs (iOS) integration | M |
| `device_tokens` table + registration endpoint + `encrypted_token` encryption/key management | S–M |
| `notification_preferences` table + settings UI | M |
| `notifications`/`notification_logs` tables + admin list view | S–M |
| `notification_templates` table + rendering (English-only) | S–M |
| Priority field + channel-selection policy | S |
| Retry/backoff + idempotency key checking | S–M |
| OTP verify attempt-cap | S |
| Delivery-status capture (`SENT`/`FAILED` only — no webhooks yet) | S |

Push (FCM + APNs) and the outbox/dispatcher worker are the largest genuinely new pieces — everything
else extends existing, working infrastructure. This is larger than a thin wrapper; it's a real module
with its own tables and a background worker, sized appropriately for something meant to outlast v1.

## 6. Open questions for whoever implements this

- Should security-category notifications be forcibly on regardless of user preference, or just
  defaulted on?
- FCM reuses the existing Firebase project — confirm no conflict with the phone-auth service
  account's existing scopes before implementation.
- Exact backoff schedule for retries (§2.5) — the observability doc's worker pattern is the
  reference, but the specific intervals are an implementation-time tuning decision.
- Which existing at-rest-secret pattern to reuse for `device_tokens.encrypted_token`'s key
  management (§2.2) — needs to match whatever the codebase already does for other encrypted secrets,
  not invent a new one.
- When Resend/2Factor webhook endpoints are eventually added (unblocking `DELIVERED`), confirm
  webhook signature verification is in scope alongside them — an unauthenticated delivery-status
  endpoint is a new attack surface.

## 7. Implementation kickoff — read this first when picking this up

This proposal is frozen. Implementation is approved to begin once the safety gate below is fully
cleared, and not before. Implement against the architecture already locked in §2–§5 — this is a
proposal to execute, not a proposal to redesign. Revisit the architecture only if new codebase
evidence contradicts something stated here (e.g. a referenced class was renamed or removed); a
preference for a different pattern discovered mid-implementation is not sufficient reason on its own.

**Safety gate — confirm all four before starting:**
- [ ] C-8 Track B closed
- [ ] All 56 bug-hunt findings closed
- [x] Backup/recovery validated — closed 2026-08-16, owner-confirmed (`project-plan-v1.0.md` R-4)
- [ ] Sentry + production monitoring ready

**Locked, not open for reconsideration** (see §2.1/§2.2/§2.5/§4 for the full reasoning):
- Modular-monolith module (`com.finora.notification`) — not a separate service, no message broker.
- Transactional outbox + `NotificationDispatcher` worker — not an in-memory event bus.
- Existing `EmailProvider`/`SmsProvider` stay as-is; push (FCM/APNs) is the only new channel.
- `device_tokens.encrypted_token`, not a hash, with key management designed alongside it.
- Lifecycle capped at `CREATED/QUEUED/PROCESSING/SENT/FAILED/RETRYING/DEAD_LETTER` — no
  `DELIVERED`/`READ` until provider webhooks exist.
- Deferred stays deferred: delivery/read tracking, localization/i18n, marketing notifications,
  Kafka/RabbitMQ/service extraction, advanced analytics.
