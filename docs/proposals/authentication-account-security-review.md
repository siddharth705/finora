# Finora Authentication & Account Security — Review & Design

Status: approved with edits (2026-08-23), amended same day. Committed via a
worktree per `CLAUDE.md` (primary checkout is a shared read-only-for-writes
checkout). This is a roadmap — each phase ships as its own ticket/PR.
**Phase 1 (Apple step-up verification) is already done** — PR #290 merged
before this document's audit ran, and the audit missed it; corrected here.
Phase 0's remaining open decisions still gate Phases 2–4.

---

## 1. Current State Audit

### 1.0 What already exists on this exact topic
`docs/proposals/user-security-center-proposal.md` (uncommitted) covers
session/device visibility and security alerting — login-history endpoint,
new-device email alerts, an audit `category` taxonomy, session-logout
confirmation. It does **not** touch unified entry, identifier-first flow, or
step-up auth. No overlap, no conflict — that proposal and this one compose.

### 1.1 Database model
`users` table (`User.java`, Flyway V1 + amendments) is flatter than it looks
but already has the right shape:

| Column | Notes |
|---|---|
| `email` | not null, unique only within `(LOWER(email), account_scope)` |
| `account_scope` | `USER` \| `ADMIN` — lets one person hold both under one email |
| `password_hash` | **never null** — OAuth accounts get a random 256-bit value nobody knows |
| `sign_in_method` | `PASSWORD` \| `GOOGLE` \| `APPLE` — this *is* the identity/method split you're asking for |
| `phone_number`, `phone_verified` | phone gates login readiness |
| `email_verified` | gates OAuth auto-link into an existing password account, not login itself |
| `failed_login_attempts`, `locked_until` | account lockout |
| `status` | `ACTIVE`\|`SUSPENDED`\|`DEACTIVATED`\|`PENDING_DELETION`\|`DELETED` |

**No separate auth-provider table exists, and per your own instruction ("do
not introduce a new authentication table unless there is a real
requirement"), none is needed yet** — `sign_in_method` cleanly encodes
"authentication method ≠ account identity" already. Introduce
`UserAuthentication` only when a single account needs *multiple concurrent*
methods (e.g. a password user later links Google) — not needed for what's
below.

Supporting tables already present: `phone_otps` (legacy, superseded by
Firebase), `email_verification_tokens`, `password_reset_tokens`,
`password_change_sessions`, `phone_change_sessions`, `refresh_tokens`.

### 1.2 Current flows

- **Login and Register are separate endpoints/pages** (`/login`, `/register`
  — `AuthController.java`, `frontend/src/pages/{Login,Register}.tsx`,
  `mobile/src/screens/{Login,Register}ScreenScreen}.tsx`).
- **Login is already identifier-first for the *login* case**: one field
  accepts email or phone, resolved server-side
  (`AuthService.resolveEmailForLogin`). There is **no `POST /auth/identify`
  endpoint** and no identify step before registration — a new user still has
  to consciously land on `/register`.
- **Google Sign-In** (`POST /auth/google`) and **Apple Sign-In**
  (`POST /auth/apple`) each serve signup-or-login from one endpoint,
  branching on whether the verified email already has an account. Auto-link
  into an existing password account requires that account's
  `email_verified = true` (anti-account-hijack fix, already shipped).
- **Phone OTP is not a standalone login method** — no such endpoint exists.
  It's a step inside registration, password reset, password change, and
  phone change, all via Firebase Phone Auth (the backend never sends SMS
  itself; the legacy `phone_otps` table is dead code path).

### 1.3 Password flows

- **Forgot password** (`POST /auth/forgot-password`) is enumeration-safe:
  always returns the same generic message, sends the reset email
  asynchronously after commit specifically to avoid a timing oracle.
- **It already refuses to send Google/Apple users into a dead-end**:
  `resolveResetPasswordPhone` checks `signInMethod` and returns *"This
  account signs in with Google and doesn't have a password to reset...
  choose 'Sign in with Google' instead."* This is done today, just
  server-side/reactive rather than via a pre-emptive identify step in the UI.
- **Reset password is already two-factor**: valid reset-link token *plus* a
  Firebase-verified phone OTP against the account's phone, before the
  password is actually changed.
- **Known open gap (BH-015, documented in code)**: the phone-resolution step
  for reset returns the account's real unmasked phone number to anyone
  holding a valid reset-link token, because the current 3-client Firebase
  architecture needs the real number client-side to send the OTP. Fixing
  this cleanly means inverting the flow (user types their own number) — a
  product change, not a bug fix. Worth deciding whether Phase 2 below should
  close it.

### 1.4 Sensitive actions

- **Not hardcoded to `verifyPassword()`.** `GoogleReauthVerifier` already is
  the `verifyIdentity()`-style abstraction your spec asks for: it branches
  on `signInMethod` — password accounts get a bcrypt check, Google accounts
  get a **freshly re-verified Google ID token** checked against the
  account's email at that moment. Used by password-change, deactivate, and
  data-export.
- **UPDATE (2026-08-23): the Apple gap is fixed.** PR #290 (`b2b80aee`,
  merged 2026-08-22 21:42, already on `origin/main`) added an
  `isAppleAccount()` branch to `GoogleReauthVerifier`, mirroring the Google
  one, and wired it through all five call sites (change-password,
  deactivate, delete, data-export, admin MFA-removal), plus a new
  rate limiter for `/account/delete` that was previously uncovered. Test
  coverage for the Apple branch exists in `GoogleReauthVerifierTest`. The
  original audit that produced this document missed this — it had already
  landed before the audit ran but wasn't picked up by the search. No
  Apple-step-up work remains; **Phase 1 in §3 is done, not planned.**
- **Deactivate** requires `GoogleReauthVerifier` + a mandatory reason;
  reversible; revokes refresh tokens; sends confirmation email.
- **Delete account** does not take a raw password — it requires an
  already-`OTP_VERIFIED` `PasswordChangeSession`, i.e. it reuses the
  password-change flow's (current-credential-or-Google-token) + phone-OTP
  state machine for a second purpose. This is genuinely step-up-style
  already, just implemented as session reuse rather than a named, declarable
  primitive. Deletion is instant/irreversible, not the 48h-delay design
  sometimes assumed — worth confirming that's still the intended UX.

### 1.5 Change email / change phone

- **Change phone**: fully built (`PhoneChangeService`, 3-step
  start/verify-otp/complete, OTP sent to the *new* number, rate-limited).
- **Change email**: **does not exist.** Only email *verification* exists
  (confirms a mailed link for a new or unverified address). There is no way
  for an existing user to change their email to a different address.

### 1.6 Security infrastructure

- **Rate limiting**: real, covers login/register/google/apple/forgot-password/
  reset-password/reactivate/verify-email/refresh/mfa/password-change/
  phone-change/deactivate/data-export/imports. Explicitly in-memory,
  per-instance, fixed-window by design (documented as fine for a
  single-instance deployment; Redis-backed sliding window is the named
  upgrade path once you scale horizontally — not urgent pre-launch).
- **Per-account lockout** is separate and already exists
  (`failed_login_attempts`/`locked_until`, configurable thresholds) and is
  deliberately timing-matched to a wrong-password response so a locked
  account isn't distinguishable from a bad password (BH-014).
- **Audit logging**: real append-only `audit_logs` table + `AuditService`
  with redaction/retention. **Gap**: no IP/user-agent columns on the table
  itself — device/IP data only lands in the JSONB `metadata` blob where a
  caller bothers to pass it (deactivate does; most flows don't). No
  `LOGIN_FAILED` action is recorded per attempt, only `ACCOUNT_LOCKED` once
  the threshold trips.
- **Enumeration protection** is genuinely mature: generic forgot-password
  responses, timing-matched lockout responses, fail-closed on case-collision
  lookups. This is further along than most fintech MVPs.

### 1.7 Test coverage
Substantial: dedicated unit/integration tests exist per flow (login,
register, Google, Apple, MFA, reactivate, reset-password, verify-email,
verify-phone, password-change, phone-change, account-lifecycle,
enumeration-oracle, pre-hijack-guard, rate-limiter, both OAuth verifiers,
controller-level `AuthFlowIT`). Nothing exists yet for: change-email
(feature doesn't exist), a generic step-up abstraction (doesn't exist), or
per-attempt login-failure audit (gap noted above).

### 1.8 Bottom line
This is **materially more built than a from-scratch audit request usually
finds**. The account-identity/auth-method split, enumeration protection,
reset two-factor flow, and a real (if partial) step-up primitive are already
in production and already correct in their design intent. The actual gaps
are narrow and specific:

1. No unified `/auth` entry page (register is a separate conscious step).
2. No `POST /auth/identify`-style endpoint.
3. ~~No Apple equivalent of `GoogleReauthVerifier`~~ — **fixed by PR #290,
   already on main; see §1.4 update.**
4. No change-email feature.
5. Step-up is wired individually into 5 call sites rather than a declarative,
   reusable primitive new sensitive actions can opt into.
6. Audit logs lack IP/UA columns; no per-attempt login-failure logging.
7. BH-015 (reset-flow phone number exposure) still open.

---

## 2. Recommended Authentication Design

### 2.1 Principle carried through everything below
`signInMethod` already correctly separates identity from method. Every
recommendation below is additive to that model — nothing here proposes
changing what `sign_in_method` means or introducing a provider table.

### 2.2 Authentication Entry (unified UX, not a backend merge)
Introduce a unified authentication entry *experience* with a single
identifier field, mirroring the flow already implemented server-side for
login:

```
Enter email or mobile number → [Continue]
------------------ OR ------------------
[Continue with Google]   [Continue with Apple]
```

Deliberately scoped as UX-first: the `/login` and `/register` routes can
stay in place internally for as long as needed — the new entry page fronts
them, it doesn't require deleting or merging them on day one. Backend route
consolidation, if ever wanted, is a later, independent decision, not a
precondition.

Backend: add an endpoint that drives the next-step UI without confirming
account existence as a raw boolean. **Security review required before
exposing authentication method externally** — this is the single biggest
decision in this document; the shape below is a starting proposal, not a
final answer:

```json
// request
{ "identifier": "user@example.com" }

// response — describes what to do next, not whether the account exists
{ "nextAction": "PASSWORD" }   // or "GOOGLE" | "APPLE" | "CONTINUE" (new-account path)
```

`nextAction: "CONTINUE"` covers the new-user case without an explicit
`exists: false`, so the response shape itself doesn't hand back a clean
existence boolean. This is a **mitigation, not a fix** — a determined
attacker can still distinguish "PASSWORD/GOOGLE/APPLE" (account exists) from
"CONTINUE" (probably doesn't) by response content alone; the value is
removing the explicit machine-readable `exists` field and making scraping
marginally more expensive, combined with the rate limiting below. Don't
present this as solving enumeration — it narrows it. Same underlying
resolution logic as login (`resolveEmailForLogin`) either way, so behavior
stays consistent between "identify" and "actually log in." Protect the
endpoint the same way `forgot-password` already is — aggressive per-IP rate
limiting (tighter than login's, since this is unauthenticated and cheap to
hit) — plus monitoring for scan patterns.

Frontend routes `/login` and `/register` can stay live and simply be reached
through the new entry page first; only 301/redirect or remove them once
mobile deep links and any external bookmarks are confirmed clear — that's a
later cleanup, not part of this phase.

### 2.3 Signup — no change to underlying mechanics
Google/Apple/Phone signup flows are already correct (`sign_in_method` set at
creation, no password prompted). Email signup already correctly separates
verification from password creation. The only change is *entry* — reaching
signup via `/auth` → "no account, want to create one?" instead of a
separate `/register` page. No backend changes needed here beyond `/identify`.

### 2.4 Forgot / Reset password — tighten, don't rebuild
Already correct in principle (refuses OAuth users, two-factor reset). Two
changes:

1. **Move the OAuth-user rejection earlier**, into the `/auth` flow itself:
   once `/identify` returns `method: GOOGLE`, the frontend never shows a
   password field or a "forgot password" link at all for that identifier —
   today's server-side rejection becomes a defense-in-depth backstop instead
   of the primary UX. Better experience, same server-side guarantee kept.
2. **Decide on BH-015** (unmasked phone exposure to anyone holding a valid
   reset token) as part of this work, since you're touching this flow
   anyway. Recommend inverting the flow — user types their own phone number,
   backend verifies it matches on the server side before issuing the OTP —
   but this is a real scope decision for you, not mine to make unilaterally.

### 2.5 Change password — align UI to existing backend truth
Backend already refuses this correctly for non-password accounts via
`signInMethod`. Frontend "Change Password" settings entry should check
`signInMethod` up front and show either the existing current/new-password
form (password users) or "This account uses Google Sign-In — no password to
change" with no dead-end form (OAuth users). No backend change required.

### 2.6 Deactivate / Delete — Apple gap closed, generalization still pending
**Update (2026-08-23): the narrow fix already shipped.** PR #290 added the
Apple-equivalent branch to `GoogleReauthVerifier` (behavior-only, no
rename), closing the real broken user path across all five call sites. What
remains is only the later, structural piece, and the original reasoning for
sequencing it after the fix (not bundling refactor with behavior change in
one PR) still applies now that the fix is independently verified in
production:

- **Later, structural-only refactor**: rename/extract
  `GoogleReauthVerifier` → something like
  `StepUpVerifier.verifyIdentity(user, credential)` that branches over all
  three methods plus wraps the existing `PasswordChangeSession`
  OTP-second-factor pattern behind one call, so a *new* sensitive action
  (e.g. change-email below) can declare "requires step-up" without
  re-deriving the branching logic. This directly answers your §11
  architecture ask (`verifyPassword()` → `verifyIdentity()`) using what's
  already built. Deferred to Phase 5 (§3) — no urgency now that the bug
  itself is fixed.

Delete-account's retention/irreversibility question (currently instant and
irreversible once the OTP-verified session is consumed) is **explicitly
out of scope for this document** — see the separate decision item below.
This is a legal/compliance/support policy question, not an authentication
mechanism question, and should not be resolved inside an auth PR.

### 2.7 Change email — new feature, matches the change-phone pattern
Build it as a mirror of the already-working `PhoneChangeService`:

```
Authenticated user → step-up verify current identity (StepUpVerifier)
                   → enter new email
                   → verification link/code sent to NEW email
                   → confirm
                   → update `users.email`, reset `email_verified = true`
```

Same session-table pattern as `phone_change_sessions`
(`email_change_sessions`), same controller shape as
`POST /users/me/phone-change/{start,verify,complete}`. This is genuinely new
work (§1.5 confirmed nothing exists today), but it's low-novelty — copy the
phone-change architecture, don't invent a new one.

### 2.7a Session invalidation audit (add as a checklist item, not a new phase)
For every authentication-lifecycle action — password reset, password
change, delete account, deactivate account — confirm: are all refresh
tokens revoked, and are active sessions invalidated? Deactivate already does
this (revokes all refresh tokens). Verify the same holds for
password-reset, password-change, and delete before Phase 2 is called
done — inconsistency here (e.g. a stale session surviving a password reset)
is a real vulnerability class, not a hypothetical. Standing rule going
forward: **authentication lifecycle actions must invalidate sessions
consistently** — treat any new sensitive action against this checklist too.

### 2.8 OTP-login as a first-class method — scope check
Your brief lists Phone OTP as a *login* method equal to password/Google/
Apple, but today it's only a step inside other flows — there's no
`sign_in_method = PHONE` path for *ongoing login* (only for phone
verification during other flows). Before building this: confirm whether you
actually want phone-OTP as a standalone recurring login method (tap "Send
OTP" every time you log in) versus its current role as a verification step.
That's a real product decision with cost (SMS spend per login) — flag it
back to you rather than assuming yes.

---

## 3. Implementation Plan

Each phase below is its own ticket/PR — this document is a roadmap, not a
single implementation task. Do not turn it into one giant PR.

**Phase 0 — Documentation + decisions**
- Resolve the 4 remaining open decisions at the end of this document
- Confirm `/identify`/`nextAction` response shape after security review (§2.2)
- No code changes in this phase

**Phase 1 — P0 fix: Apple step-up verification — ✅ DONE, shipped 2026-08-22**
`fix(auth): support Apple reauthentication for sensitive account actions`
(PR #290, `b2b80aee`, already on `origin/main`)
- Apple branch added to `GoogleReauthVerifier`, wired through all 5 call
  sites (change-password, deactivate, delete, data-export, admin
  MFA-removal); new rate limiter added for `/account/delete`
- Tests: `GoogleReauthVerifierTest` covers Apple success/failure
- No further action needed for this phase

**Phase 2 — P1 UX: Unified authentication entry flow**
`feat(auth): unified authentication entry flow`
- Split into sub-phases by platform risk, not shipped together:
  - **2A — Web**: entry page + backend `nextAction` endpoint (§2.2),
    `/login`/`/register` stay live underneath
  - **2B — Mobile**: mobile already has native Google/Apple/Firebase-phone
    screens in production — higher blast radius, sequence after web is
    verified in production, not in parallel
- Rate limit the new endpoint (tighter than login's)
- Tests: next-action response for each `sign_in_method`, non-existent
  identifier, enumeration/rate-limit test mirroring `LoginExistenceOracleIT`

**Phase 2.5 — Session invalidation audit** (checklist item inside Phase 2,
not a separate ticket)
- Verify refresh-token revocation / session invalidation is consistent
  across password-reset, password-change, deactivate, delete (§2.7a)

**Phase 3 — P2 feature: Change email**
`feat(account): add email change flow`
- `email_change_sessions` table + `EmailChangeService` mirroring
  `PhoneChangeService`
- `POST /users/me/email-change/{start,verify,complete}`, gated by existing
  step-up (`GoogleReauthVerifier`, not yet `StepUpVerifier`)
- Frontend/mobile settings entry
- Tests mirroring `PhoneChangeServiceTest`/`PhoneChangeServiceIT`
- Not blocking — useful but lower priority than Phases 1–2

**Phase 4 — P1 security: Audit/observability hardening**
`security(auth): improve authentication audit logging`
- Add IP/user-agent columns to `audit_logs` (or at minimum standardize
  `RequestMetadata` capture into `metadata` across all auth actions, not
  just deactivate)
- Add `LOGIN_FAILED` per-attempt audit action
- Composes with, doesn't duplicate, the separately-proposed
  `user-security-center-proposal.md` login-history work — sequence together
  since they touch the same table (a decision, not a merge — see open
  decisions)
- Fintech-app requirement, treat as P1 not a nice-to-have

**Phase 5 — Deferred: future providers**
Truecaller, Passkeys, standalone phone-OTP login, and the
`StepUpVerifier` structural refactor (§2.6) all live here. None of these
are scheduled — they're documented as extension points. `sign_in_method`
and the existing step-up call sites are designed to accept this later
without a rewrite, but nothing here is greenlit. The auth-provider table is
explicitly deferred alongside these, not planned.

---

## 4. Testing
Each phase's tests are listed inline above. General approach: follow the
repo's existing pattern per flow — unit test on the service (e.g.
`StepUpVerifierTest` mirroring `GoogleReauthVerifierTest`), integration test
on the controller/session flow (e.g. `EmailChangeServiceIT` mirroring
`PhoneChangeServiceIT`), and a dedicated oracle/enumeration test for
`/auth/identify` mirroring `LoginExistenceOracleIT` — that test already
proves the team's bar for this kind of endpoint.

---

## Open decisions for you (not mine to make unilaterally)
1. `nextAction` endpoint (§2.2) — confirmed direction after security review,
   or want something stricter still (e.g. always-generic response + a
   separate "check your messages" step)?
2. BH-015 fix — invert the reset-password phone flow as part of Phase 2, or
   defer to Phase 5?
3. **Account deletion retention policy** — separate decision from this
   document, not an auth-mechanism question. Needs legal/compliance/support
   input: keep instant/irreversible, or add a delay + recovery window? Track
   as its own ticket, not folded into Phase 2's entry flow or any other
   phase here.
4. Is standalone phone-OTP login (§2.8) actually wanted, given per-login SMS
   cost — or is current "OTP as verification step" model sufficient?
   (Deferred to Phase 5 either way, but worth confirming intent early.)
5. Sequencing Phase 4 with `user-security-center-proposal.md` — same PR
   window or independent?
