# User Security Center — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

**Major correction to the originating draft's premise:** it proposes building active-session
listing/logout and device tracking as new work. **This is already shipped, end to end, not a gap:**

- `RefreshToken` entity already captures `sessionId`, `sessionStartedAt`, `browser`, `device`,
  `lastSeenIp`, `lastSeenAt`, `expiresAt`, `revokedAt` per session
  (`RefreshTokenService.captureDeviceMetadata`, populated from the `User-Agent` header and
  `ClientIpResolver` on every issue/rotate).
- A user-facing endpoint already exists: `GET/DELETE /api/v1/users/me/devices`
  (`DeviceController`), returning browser/device/IP/last-seen/current-session-flag per row.
- **The frontend already consumes it** — `frontend/src/api/endpoints.ts` (`deviceApi.list/revoke`)
  and an "Active Sessions" section already rendered in `frontend/src/pages/Settings.tsx`.
- Theft detection is real and already working: reuse of a rotated/revoked refresh token
  revokes every session for that user (`RefreshTokenService.rotate`); idle/absolute session-cap
  breaches revoke individually.

The draft's "Active Sessions" and "Login History" sections (its two biggest asks) are therefore
mostly already done or partially done. What's real and missing is narrower than the draft assumed.

## 1. Objective

Close the two genuine gaps identified by investigation: a **self-service login-history view** (today
login events are audit-logged but with no IP/device enrichment and no user-facing endpoint), and
**security-alert notifications** for new-login/new-device events (today only "password changed" sends
an email — new-device login sends nothing at all, silent even to the account holder).

## 2. What exists today (baseline — see correction above for full detail)

- Active sessions: fully shipped (backend + frontend).
- Theft detection: real, backend-only, silent (no notification when it fires — a session just stops
  working, discoverable only indirectly).
- Login audit: `AuthService` logs `USER_LOGIN` via `AuditService`, but `AuditLog` carries no IP/UA
  columns, and no `LOGIN_FAILED` action exists anywhere in the codebase — failed attempts aren't
  audited at all today (only the login *lockout counter* on the `User` entity tracks failures, not an
  auditable event log of them).
- Security emails: exactly three exist (`EmailProvider`) — password-reset, welcome, password-changed.
  No new-login or new-device email/push exists.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Self-service login history (new — small, reuses existing tables)

```
GET /api/v1/me/login-history
```

Two options, not a default — flagged as a real decision rather than picked here:
- **(a)** Add IP/user-agent columns to the existing `USER_LOGIN` audit path (`AuditService` metadata
  map can already carry arbitrary fields — this may need no schema change at all, just populating
  `ip`/`userAgent` in the metadata passed to the existing `record()` call at login).
- **(b)** Derive login history from `RefreshToken` rows instead, since they already carry IP/device
  per session and a login always creates one — possibly redundant with (a) rather than a second
  source of truth.

Recommendation for whoever implements: prefer (a), since audit's job is exactly "what happened," and
(b) would make `RefreshToken` (a session/auth-token table) double as a history log, mixing concerns.
Also close the `LOGIN_FAILED` audit gap noted in §2 — failed attempts should be as visible as
successful ones for the same reason.

### 3.2 Security-alert notifications (new — depends on the notification platform proposal)

New-login-from-new-device and (already-working) password-changed alerts should route through
`NotificationService` once `notification-communication-platform-proposal.md` is built — this
proposal should not invent a second, parallel email-sending path. "New device" detection itself is
cheap given §3.1/existing `RefreshToken` device fingerprinting: a login whose browser/device
combination hasn't been seen before for that user triggers the alert.

**Sequencing note:** this item is blocked on the notification platform proposal landing first, or
on a narrower interim path (reuse `EmailProvider` directly, matching how `password-changed` already
works, and migrate to `NotificationService` later) if the two are implemented out of order. Flagged
here rather than resolved, since the actual build order across all these proposals isn't decided yet.

### 3.3 Trusted devices / biometric / app lock — explicitly future, not designed here

The original draft listed these as "Future." Agreed — no design work done here; they depend on
mobile-platform-specific capability (biometric APIs) that's a different kind of engineering effort
from everything else in this proposal set.

### 3.4 Security event categories (new — small, aids §3.1/§3.2 and Fino later)

`LOGIN_SUCCESS`/`LOGIN_FAILED`/`PASSWORD_CHANGED` and the rest accumulate as flat action strings today
(consistent with the existing `AuditLog` convention). A `category` alongside each security-relevant
action type — reusing whatever mechanism the Audit & Activity Intelligence proposal's `visibility`
column (§3.4 there) ends up using, not a second parallel taxonomy — makes both this proposal's own
work and later consumers (Fino, an admin security dashboard) able to query "everything
authentication-related" instead of enumerating action strings by hand:

```
AUTHENTICATION   — LOGIN_SUCCESS, LOGIN_FAILED
SESSION          — SESSION_REVOKED, NEW_DEVICE_DETECTED
ACCOUNT          — EMAIL_CHANGED, PHONE_CHANGED, PASSWORD_CHANGED
PAYMENT_SECURITY — reserved for the Billing proposal's payment/webhook events, not populated by
                    this proposal — named here only so the two proposals agree on the taxonomy in
                    advance rather than each inventing one
```

### 3.5 Privacy and access rules for IP/device data (new — this data is sensitive)

IP addresses and device fingerprints are personal data, not incidental metadata, and this proposal
adds more of them (login history, new-device detection) without yet stating who can see them or how
long they're kept:

- **Retention.** Not designed here as a specific number, but flagged as a required decision before
  `RefreshToken`'s IP/device columns (already collected today) and any new login-history rows
  accumulate indefinitely by default. `docs/engineering/observability.md`'s existing
  session-cleanup/retention pattern (§8 there, the import-session sweep) is the precedent to follow —
  a scheduled, monitored sweep, not silent indefinite accumulation.
- **User access.** A user can see their own login history/IP/device data (§3.1) — this is their own
  account activity, same principle as the existing Active Sessions page.
- **Admin access.** Should require a security-scoped permission, not blanket `AUDIT_VIEW` — same
  reasoning as the Audit & Activity Intelligence proposal's `AUDIT_VIEW_SYSTEM`/`AUDIT_VIEW_FINANCIAL`
  split (§3.5 there); this proposal doesn't invent a third permission scheme, it should reuse
  whichever of those two categories fits once that proposal lands, or fall back to `AUDIT_VIEW_SYSTEM`
  in the interim since login/session events are exactly that proposal's "system" category.

### 3.6 Logout-all-devices confirmation (small UX correction to an existing feature)

`DELETE /api/v1/users/me/devices` (already shipped) is destructive — it signs the user out
everywhere, including the device they're currently using it from. Confirming today's frontend
actually gates this action behind a confirmation step is worth checking as part of this proposal's
implementation (not assumed either way here); if it doesn't, add one: *"This will sign you out from
all active sessions, including this one."* Cheap, and consistent with how the Remote Configuration
proposal treats `force_update` — the one action in that proposal's scope that can also lock a user
out immediately gets a confirmation step, not a plain toggle.

### 3.7 Suspicious-activity scoring and "this wasn't me" recovery — explicitly future, noted not built

Two related capabilities worth recording so they aren't rediscovered later, neither designed or
sized here:

- **Risk scoring** (new country + new device + repeated failures → elevated risk) — needs real
  login-history volume (§3.1) to calibrate against before it's buildable at all; premature before
  that data exists.
- **A "this wasn't me" recovery flow** (report suspicious login → lock account → forced credential
  reset → session review) — this is a support/fraud-response workflow, not a data-model change, and
  depends on decisions this proposal set doesn't make (does it route through the Support proposal's
  ticket system? who actions it?). Flagged as a real future dependency, not designed here.

## 4. Explicitly out of scope

- Rebuilding active-session listing, device capture, or theft detection — already shipped.
- Any new session/token storage mechanism — `RefreshToken` already carries what's needed.
- Notification *delivery* mechanics — owned by the notification platform proposal; this proposal
  only defines what triggers an alert, not how it's sent.
- Biometric login, app lock, trusted-device flagging — future, unscoped.
- Suspicious-activity risk scoring and the "this wasn't me" recovery flow (§3.7) — future, explicitly
  not designed here.
- A specific IP/device data retention period — flagged as required (§3.5), not chosen here.

## 5. Estimated effort

| Component | Effort |
|---|---|
| ~~Active sessions list + revoke~~ | Already built |
| ~~Device/IP capture~~ | Already built |
| Self-service login-history endpoint + `LOGIN_FAILED` audit | S |
| New-device detection logic | S |
| Security-alert notification wiring | S (once notification platform exists) |
| Security event categories (§3.4) | S |
| IP/device retention sweep (§3.5) | S–M |
| Logout-all-devices confirmation check/fix (§3.6) | S |

## 6. Open questions for whoever implements this

- §3.1 option (a) vs (b) — needs a decision, not just a recommendation, before implementation.
- Should a new-device login alert be blocking (require confirmation) or purely informational for v1?
  Recommend informational-only given no fraud-response workflow exists yet to act on a "no, that
  wasn't me" response.
- Exact IP/device retention period (§3.5) — needs a number, not just the sweep mechanism.
- Which admin permission gates login-history access (§3.5) — depends on the Audit proposal's
  permission split landing first; interim fallback is `AUDIT_VIEW_SYSTEM`.
