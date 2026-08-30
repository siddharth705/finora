# Finora Authentication & Account Security — Review & Design

**Status: Approved.** Every implementation phase (1, 2, 3/3A/3B, 3.5, 4, 6,
7) is now shipped. Of Phase 0's 6 open decisions, only #3 (account deletion
retention — needs legal/compliance input, tracked as its own ticket, not
this doc's to close) remains genuinely open; the other 5 are resolved.
Phase 5 (deferred, unscheduled) is what's left: Truecaller, Passkeys, the
`StepUpVerifier` structural refactor (§2.6), and account-recovery design
(§2.9) — none of these are scheduled. Implementation history: Phase 1 done,
Phase 2's audit-hardening slice done, Phase 3's backend (`/auth/identify`,
PR #327) merged, both its 3A (web) and 3B (mobile) entry-flow UX merged and
then revised by Phase 7. Phase 3.5's audit done (no gap in its own
checklist; a bonus phone-change session-revocation gap found and fixed).
Phase 4 fully merged (backend, web, mobile). Phase 6 (BH-015 fix) merged.
Phase 7 (`/auth/identify` enumeration hardening) merged. Committed via a
worktree per `CLAUDE.md` (primary checkout is
a shared read-only-for-writes checkout). This is a roadmap — each phase
ships as its own ticket/PR, never as one combined PR.

**Phase 1 (Apple step-up verification) is already done** — PR #290 merged
before this document's audit ran, and the audit missed it; corrected here.
Phase ordering was revised 2026-08-23 (audit/observability now precedes the
unified entry UX — see §3's note). Phase 2's audit-hardening slice shipped
2026-08-23, sequenced with `user-security-center-proposal.md` per open
decision 5 below (resolved: coordinate, not independent). Phase 4's
backend slice also shipped 2026-08-23, ahead of the remaining 5 open
decisions being resolved — see its own amendment for why none of them
actually constrain it. Phase 0's other 5 open decisions still partly gate
Phase 3 (see its amendment).

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
| `password_hash` | `password_hash` is currently required by the schema. OAuth accounts receive an unusable random 256-bit value because the existing schema predates provider-only authentication — a compatibility workaround, not a deliberate permanent design. Long-term, this constraint may be revisited if authentication methods become more flexible (see the new account-linking decision, §Open decisions). |
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
  product change, not a bug fix. Worth deciding whether Phase 3 (§3) should
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
  with redaction/retention. **Gap, as of the original audit**: no
  IP/user-agent columns on the table itself — device/IP data only landed in
  the JSONB `metadata` blob where a caller bothered to pass it (deactivate
  did; most flows didn't). No `LOGIN_FAILED` action was recorded per
  attempt, only `ACCOUNT_LOCKED` once the threshold tripped. **UPDATE
  (2026-08-23): closed by Phase 2 for the login family specifically** — see
  §3's Phase 2 entry. Still no dedicated IP/UA *columns* (metadata-only, by
  design — see that entry), and non-login flows (password-change,
  deactivate, etc.) are unchanged by this slice.
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

1. No unified `/auth` entry page (register is a separate conscious step) —
   **backend piece (item 2) done; the page itself is not (Phase 3, §3).**
2. ~~No `POST /auth/identify`-style endpoint~~ — **fixed by PR #327,
   already on main.**
3. ~~No Apple equivalent of `GoogleReauthVerifier`~~ — **fixed by PR #290,
   already on main; see §1.4 update.**
4. No change-email feature.
5. Step-up is wired individually into 5 call sites rather than a declarative,
   reusable primitive new sensitive actions can opt into.
6. ~~Audit logs lack IP/UA columns; no per-attempt login-failure logging.~~
   — **closed for the login family by Phase 2, 2026-08-23; see §1.6
   update.** No dedicated columns added (metadata-only, by design); other
   flows unchanged.
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

### 2.7a Session invalidation audit (add as a checklist item, not a new phase) — ✅ AUDITED 2026-08-23
For every authentication-lifecycle action — password reset, password
change, delete account, deactivate account — confirm: are all refresh
tokens revoked, and are active sessions invalidated? Deactivate already does
this (revokes all refresh tokens). Verify the same holds for
password-reset, password-change, and delete before Phase 3 is called
done — inconsistency here (e.g. a stale session surviving a password reset)
is a real vulnerability class, not a hypothetical. Standing rule going
forward: **authentication lifecycle actions must invalidate sessions
consistently** — treat any new sensitive action against this checklist too.

**Audit result: all 4 already correct, no gap.** Password reset and delete
account both call `RefreshTokenService.revokeAllForUser` unconditionally.
Deactivate does the same (the reference pattern above). Password change
revokes all *other* sessions (`revokeAllOtherSessionsForUser`, current
device spared) via a client-supplied `signOutOtherDevices` flag — opt-in
at the API level, but both the web and mobile clients already default that
toggle to `true`, so in practice a user has to actively choose not to
revoke. No backend change needed for the 4 items this checklist names.

**One gap found outside the checklist, closed anyway:** phone-number
change (`PhoneChangeService.complete`) had no session revocation at all —
not even opt-in. Worse than password change, since this flow authorizes
itself purely by proving control of the number being moved *to* (no
"re-verify current credential" step the way password-change's `start()`
has one), a lower bar than a password change to begin with. Fixed by
adding the same `revokeAllOtherSessionsForUser` call, unconditional (no
UI toggle exists here), current device spared.

### 2.8 OTP-login as a first-class method — scope check
Your brief lists Phone OTP as a *login* method equal to password/Google/
Apple, but today it's only a step inside other flows — there's no
`sign_in_method = PHONE` path for *ongoing login* (only for phone
verification during other flows). Before building this: confirm whether you
actually want phone-OTP as a standalone recurring login method (tap "Send
OTP" every time you log in) versus its current role as a verification step.
That's a real product decision with cost (SMS spend per login) — flag it
back to you rather than assuming yes.

### 2.9 Account recovery — future consideration, not scoped here
None of Phases 1–4 answer: what happens when a user loses access to
*every* method their account has? E.g. an Apple-only user who loses that
Apple ID, changes their phone number (breaking any SMS-based reset step),
and can no longer read the email on file. Today there is no path back for
that user beyond manual support intervention against the raw database — a
gap that gets worse, not better, once §2.7's change-email and any future
account linking (see the new open decision below) exist, since those add
more state that could itself be lost. Not scoped for implementation in
this document; recommend
tracking as its own future proposal once Phases 1–4 are further along,
covering options like verified-identity re-checks, support-assisted
recovery with an audit trail, recovery codes issued at signup, or trusted
devices. Deferred to Phase 5 (§3).

---

## 3. Implementation Plan

Each phase below is its own ticket/PR — this document is a roadmap, not a
single implementation task. Do not turn it into one giant PR.

Reordered 2026-08-23 per review feedback: audit/observability hardening now
precedes the unified entry UX, on the reasoning that a fintech app should
not increase authentication surface area before improving visibility into
it. **Note on sequencing in practice**: backend work on the unified-entry
`nextAction` endpoint (now Phase 3) was already started in this repo before
this reorder was requested — see the amendment note at the end of this
section. The phase numbers below reflect the intended priority order for
anything not yet started; already-in-flight work is not being unwound to
match.

**Phase 0 — Documentation + decisions**
- Resolve the 5 remaining open decisions at the end of this document
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
- Success metric: Apple-only users completing a sensitive action
  (deactivate/delete/data-export/password-change) without a failed
  reauthentication attempt, tracked from zero (previously impossible) to
  parity with Google/password users

**Phase 2 — P1 security: Audit/observability hardening — ✅ DONE (this slice)**
`security(auth): audit-log hardening + self-service login history`
- Done, sequenced with `user-security-center-proposal.md` per open decision
  5 (also committed by this same change, previously only uncommitted in the
  primary checkout): `RequestMetadata` (ip/device) now captured into
  `metadata` on every login-family audit event (`USER_LOGIN`,
  `USER_LOGIN_GOOGLE`, `USER_LOGIN_APPLE`, MFA completion, reactivation
  login), not just `ACCOUNT_REACTIVATED`/deactivate as before
- `LOGIN_FAILED` per-attempt audit action added, at both failure points in
  `login()` (bad credentials, and the BH-014 locked-account branch, tagged
  with a `reason` so the two are distinguishable server-side without
  changing the caller-facing response) — fires only when the identifier
  resolved to a real account, mirroring `registerFailedLogin`'s own
  null-user guard, so an unknown identifier still can't be probed via the
  audit trail either
- `GET /api/v1/users/me/login-history` (security-center §3.1, option (a)):
  self-service view of the caller's own last 50 login-family events,
  behind `AuditService.findLoginHistory` (not a direct repository call from
  the controller — `LayerDependencyDirectionTest` enforces this)
- Not done in this slice, left for a later pass: dedicated IP/UA *columns*
  on `audit_logs` (metadata-only for now, matching the security-center
  doc's own recommendation to prefer (a) over a schema change);
  new-device detection/alerting (§3.2, blocked on the notification
  platform proposal); security event categories (§3.4); IP/device
  retention sweep (§3.5, needs a decided number first); non-login audit
  actions (password-change, deactivate, etc.) still only carry metadata
  where they already did before this change
- Success metrics: security-event audit coverage % (proportion of login
  events carrying IP+device, now effectively 100% of `USER_LOGIN`/
  `LOGIN_FAILED` writes going forward); self-service login-history adoption
  once shipped to a settings UI (not yet built — backend only so far)

**Phase 3 — P1 UX: Unified authentication entry flow**
`feat(auth): unified authentication entry flow`
- Split into sub-phases by platform risk, not shipped together:
  - **3A — Web**: entry page + backend `nextAction` endpoint (§2.2),
    `/login`/`/register` stay live underneath
  - **3B — Mobile**: mobile already has native Google/Apple/Firebase-phone
    screens in production — higher blast radius, sequence after web is
    verified in production, not in parallel
- Rate limit the new endpoint (tighter than login's)
- Tests: next-action response for each `sign_in_method`, non-existent
  identifier, enumeration/rate-limit test mirroring `LoginExistenceOracleIT`
- Success metrics: registration completion %, login completion %, OAuth
  abandonment rate, and password-reset attempts initiated by an OAuth user
  right after login (a proxy for how often the old flow was confusing them
  about which method their account uses)
- **Amendment (2026-08-23)**: backend for 3A (`POST /auth/identify`,
  returning `nextAction`, plus a dedicated rate limiter) was implemented
  and tested (unit + integration) in worktree `auth-identify-endpoint`
  before this reordering was requested. Noted here rather than silently
  resequenced, since the phase-ordering rationale above (observability
  before surface area) was agreed after the backend endpoint already
  existed.
- **3A (web) — ✅ DONE, shipped 2026-08-23**: `AuthEntry.tsx` at `/auth` --
  single identifier field, `POST /auth/identify`, then routes to `/login`
  (prefilled, and per `nextAction` hides the password field/forgot-password
  link for a `GOOGLE`/`APPLE` account -- §2.4's "move the OAuth-user
  rejection earlier") or `/register` (prefilled into whichever of its email
  or mobile-number fields the identifier looked like) for `CONTINUE`.
  `/login` and `/register` stay fully live on their own, exactly as scoped.
  Landing-page CTA wiring (whether "Sign in"/"Get started" should route
  through `/auth` instead of straight to `/login`/`/register`) is left as
  its own decision, not folded into this slice -- changing those is a
  conversion-funnel/marketing call, not an auth-mechanism one.
  Self-review bug found and fixed in the same pass: hiding the password
  field/submit button for a `GOOGLE`/`APPLE` account left the identifier
  input as the form's only field, so the browser's implicit-submission-on-
  Enter behavior still fired `handleSubmit` and showed "Enter your
  password." even though no password field existed to fill in -- fixed by
  short-circuiting submission entirely while that hint is shown.
  BH-015 (§2.4 item 2, unmasked-phone exposure on password reset) is
  explicitly NOT addressed by this slice -- still open, tracked below.
- **3B (mobile) — ✅ DONE, shipped 2026-08-23**: `AuthEntryScreen.tsx`,
  registered as the Auth stack's default screen -- same single-identifier
  field, same `POST /auth/identify` call (added to `authApi`/the
  no-auth-header allowlist), routing to `Login`/`Register` with the
  identifier or email/phone prefilled via route params (React Navigation's
  counterpart to web's router `location.state`). `Login`/`Register` stay
  directly reachable via their own footer links. Unlike web, mobile's
  native `AppleSignInButton` actually works, so the `APPLE` hint keeps the
  social row visible (only the password form/link are hidden) rather than
  hiding it the way web had to.
  Self-review bug found and fixed before this ever reached a PR: making
  `AuthEntryScreen` the Auth stack's first screen would have silently
  changed what a forced sign-out (session expiry) or explicit logout lands
  on -- `AuthContext.clearLocalState`'s own comment already documented
  "clearing the token lands on Login" as the intended behavior, which a
  plain first-screen-wins default would have broken by sending an
  already-authenticated-then-signed-out user back through the identify
  step. Fixed with a small `useAuthStackInitialRoute` hook (unit-tested
  directly, no navigation-container integration test needed) that starts
  on `AuthEntry` for a session that's never been signed in, but switches to
  `Login` once a previously-signed-in session is cleared -- passed to the
  stack navigator's `initialRouteName`.
  Also built in from day one (learned from web's own post-merge fix):
  Login's OAuth hint is derived from whether the identifier still matches
  what AuthEntry resolved it for, not captured once at mount, so editing
  the field brings the password form back immediately instead of leaving a
  dead end.

**Phase 3.5 — Session invalidation audit — ✅ DONE, audited + fixed 2026-08-23**
- Verify refresh-token revocation / session invalidation is consistent
  across password-reset, password-change, deactivate, delete (§2.7a)
- Result: all 4 already correct in production, no fix needed for the
  checklist itself
- Bonus fix found and closed in the same pass: `PhoneChangeService.complete`
  had no session revocation at all — now revokes every other session on a
  successful phone-number change, current device spared, same pattern
  password-change already uses (§2.7a for the full writeup)

**Phase 4 — P2 feature: Change email — ✅ DONE (backend, web, mobile)**
`feat(account): add email change flow`
- `email_change_sessions` table + `EmailChangeService` mirroring
  `PhoneChangeService`
- `POST /users/me/email-change/{start,verify,complete}`, gated by existing
  step-up (`GoogleReauthVerifier`, not yet `StepUpVerifier`)
- Web frontend done (PR #357): `ChangeEmailModal` (Profile settings) +
  `VerifyEmailChange` confirmation page.
- Tests mirroring `PhoneChangeServiceTest`/`PhoneChangeServiceIT`
- Not blocking — useful but lower priority than Phases 1–3
- **Frontend follow-up bug (2026-08-23)**: wiring up the verify page found
  that `start()`'s emailed link only carried the token, not the sessionId
  `VerifyRequest` also needs — fixed with a regression test, before this
  was ever live in production (caught in the same session as the
  frontend work, one PR after the backend slice merged).
- **Mobile — ✅ DONE, shipped 2026-08-23**: `ChangeEmailSheet` (Settings'
  Security section, next to Change Password) + `VerifyEmailChangeScreen`,
  reached via a new `finora://email-change-verify?sessionId=...&token=...`
  deep link -- Phase 4 mobile's first deep-link consumer, since mobile had
  none at all before this (no `expo-linking` usage, no
  `NavigationContainer.linking`).
  Password-only step-up (no `signInMethod` branch): no mobile settings
  flow, including `ChangePasswordSheet`, has a Google-reauth step-up path
  yet, so this doesn't add a first one speculatively -- add the `GOOGLE`
  branch once that groundwork exists for step-up generally.
  Deliberately a custom scheme, not a true universal/app link: iOS
  Associated Domains + a hosted `apple-app-site-association`, and Android
  App Links + a hosted `assetlinks.json` signed with the release keystore's
  fingerprint, both need real Apple Developer/Play Console access this
  environment doesn't have, and neither is something a code change alone
  can stand up or verify. The web confirmation page keeps emailing the
  same `https://` link it always did (works from any device/client
  unchanged) and separately offers an "Open in the Finora app" link using
  the custom scheme, for anyone reading that email on their phone.
  Revisit true universal links once the native hosting/signing pieces
  exist -- tracked here, not silently scoped out.
  **Self-review fix (2026-08-23, follow-up PR after this shipped)**:
  `RootNavigator` mounts one of three mutually-exclusive navigator trees
  depending on auth state (signed-out `AuthStack`, a bare
  phone-unverified `AppStack`, or `AppTabs`), but the deep link's target
  screen only exists inside `AppTabs`. Registering the path in React
  Navigation's own declarative `linking.config` (as originally shipped)
  meant a signed-out or phone-unverified tap silently dropped the link --
  no error, `sessionId`/`token` just gone, no retry path short of
  reopening the email. Replaced with `useEmailChangeDeepLink`, an
  imperative, auth-state-aware hook (unit-tested directly, no
  `NavigationContainer` integration test needed) that listens for the raw
  URL independently of whichever tree is mounted, stashes it if the app
  isn't ready, and replays it via a `navigationRef` the moment sign-in and
  phone verification complete.
- **Amendment (2026-08-23)**: implemented ahead of the remaining 5 open
  decisions being resolved, same situation Phase 3's backend slice was in
  (see its own amendment above). Checked each one against this specific
  slice rather than treating the header note's blanket "gates Phase 4" as
  automatically applying: none of the 5 (`nextAction` shape, BH-015,
  deletion retention, phone-OTP login, account linking) constrain how
  email-change itself works — it's additive, doesn't touch identify/login,
  and doesn't introduce multi-method-per-account state. Session revocation
  on complete() is unconditional (no `signOutOtherDevices`-style toggle,
  since no frontend exists yet to carry one) -- the Phase 3.5 audit's
  established default for a flow with no UI toggle, applied here from day
  one rather than needing its own follow-up fix.

**Phase 6 — BH-015 fix: invert the reset-password phone flow — ✅ DONE, shipped 2026-08-23**
`fix(auth): stop revealing the account's real phone number on password reset`
- Problem (§1.3): `POST /auth/reset-password/phone` used to take just the
  reset-link token and return the account's REAL, unmasked phone number
  — needed because Firebase Phone Auth's client SDK sends the OTP
  directly, so the number has to reach the browser/app. Anyone holding a
  valid reset-link token (e.g. an intercepted/forwarded email) learned
  the account's phone number even if the reset never completed.
- Shipped direction: same URL (`POST /auth/reset-password/phone`, kept
  as-is rather than renamed, so the existing rate-limiter path entry and
  its guard tests didn't need touching), inverted contract. Request now
  carries `{token, phoneNumber}` — the user's own typed number, not
  server-revealed. Response is `{message}` on a match; a mismatch throws
  a generic 400 (`"That doesn't match the phone number on this
  account."`), never revealing what the real number is. Backend reuses
  `phoneNumbersMatch()` — the exact digit-only comparison `resetPassword()`
  already applies to the Firebase-verified number — so the pre-check and
  the real gate can never disagree about what counts as a match. Still a
  phone-number-guessing oracle in principle (confirms/denies one guessed
  number at a time), but bounded by the same precondition as before
  (already holding a valid, unguessable, single-use, time-limited reset
  token) and the same rate limiter (10 req / 10 min, shared with
  `resetPassword`) — a materially smaller exposure than the guaranteed
  full reveal this replaces.
- `ResetPassword.tsx` (web) gained a new first step: a phone-number input
  (10-digit + fixed `+91` prefix, same pattern as `Register.tsx`'s own
  field) that must be confirmed via the new endpoint before the OTP step
  (auto-fetch/auto-send on mount is gone) — the confirmed number is what
  gets handed to Firebase, never anything the backend returned. Self-
  review catch before this reached a PR: an earlier draft rendered the
  Firebase reCAPTCHA anchor div separately inside each of the two step
  branches, which would have unmounted/remounted it across the
  phone→OTP transition — a real race against `RecaptchaVerifier`'s
  synchronous construction against that DOM node. Fixed by hoisting the
  anchor to render once, outside the step conditional, with a regression
  test asserting exactly one anchor exists in the DOM across the
  transition.
  Mobile needed no UI change — password-reset completion has been
  web-only on mobile since Phase 3B (`ForgotPasswordScreen`'s own doc
  comment); its two now-unused `authApi` wrapper functions were
  signature-matched to the new contract for whenever an in-app
  completion screen is built, not left to drift.
- No `frontend/src/pages/ResetPassword.test.tsx` existed before this —
  net-new coverage (9 tests), not a migration of existing tests.

**Phase 7 — `/auth/identify` enumeration hardening — ✅ DONE, shipped 2026-08-23**
`feat(auth): reduce what /auth/identify's response reveals`
- Problem: the shipped `{"nextAction": "PASSWORD" | "GOOGLE" | "APPLE" |
  "CONTINUE"}` response still let a caller distinguish "this identifier
  has an account" (any non-`CONTINUE` value) from "it doesn't"
  (`CONTINUE`), and for an existing account, which method it used.
  §2.2's own text already named this as a mitigation, not a fix.
- Shipped design: option (b) — kept the identify step's exists-vs-
  doesn't-exist routing (Phase 3's Login-vs-Register win stays), but
  dropped which method an existing account uses from the response.
  `nextAction` is now `"EXISTS" | "CONTINUE"` only —
  `PASSWORD`/`GOOGLE`/`APPLE` collapsed into the single `EXISTS` value.
  `Login.tsx`/`LoginScreen`'s OAuth hint (hide the password field for a
  known GOOGLE/APPLE account, §2.4's "move the OAuth-user rejection
  earlier") is gone entirely — the password field and Google/Apple
  buttons are always shown together for an `EXISTS` identifier now, same
  as a direct visit to `/login` already did. That UX win is given up
  deliberately as the cost of closing this leak; the backend's own
  `signInMethod` refusal on an actual password-login attempt remains the
  real, unaffected guarantee either way.
  Options (a) (fully generic, no identify step at all) and (c) were not
  chosen — (a) would have given up the CONTINUE-vs-EXISTS routing too, a
  bigger UX regression than this decision called for.
- Touched: `AuthService.identify`/`IdentifyResponse` (backend, `nextAction`
  narrowed from 4 values to 2), `AuthEntry` + `Login` (web, `method`
  dropped from the router-state payload and the OAuth-hint branch removed
  entirely), `AuthEntryScreen` + `LoginScreen` (mobile, same). All three
  had shipped once already for the 4-value shape, so this was a revision,
  not a fresh build — existing tests asserting on `GOOGLE`/`APPLE`/
  `PASSWORD` values and the OAuth-hint UI were updated/removed, not just
  added to.

**Phase 5 — Deferred: future providers, recovery**
Truecaller, Passkeys, the `StepUpVerifier` structural refactor (§2.6), and
account-recovery design (§2.9) live here. None of these are scheduled —
they're documented as extension points. Standalone phone-OTP login and
account-linking policy, previously also parked here, are both **closed**
(§Open decisions 4 and 6, resolved 2026-08-23: not wanted / one method per
account) — removed from this list rather than left looking open. The
auth-provider table is explicitly deferred alongside what's left here, not
planned.

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
1. ~~`nextAction` endpoint (§2.2) — confirmed direction after security
   review, or want something stricter still?~~ — **resolved 2026-08-23:
   revisit toward stricter.** The shipped shape (`{"nextAction": "PASSWORD"
   | "GOOGLE" | "APPLE" | "CONTINUE"}`) still lets a determined caller
   distinguish "account exists" from "account doesn't" by response
   content. Concrete redesign options (and which one to build) tracked as
   its own phase — see §3's note below; this reopens already-shipped
   Phase 3 code, not just the design.
2. ~~BH-015 fix — invert the reset-password phone flow as part of Phase 3,
   or defer to Phase 5?~~ — **resolved 2026-08-23: fix it now**, as its
   own phase (not folded into Phase 3's entry flow, which already
   shipped). Scope tracked in §3's note below.
3. **Account deletion retention policy** — separate decision from this
   document, not an auth-mechanism question. Needs legal/compliance/support
   input: keep instant/irreversible, or add a delay + recovery window? Track
   as its own ticket, not folded into Phase 3's entry flow or any other
   phase here.
4. ~~Is standalone phone-OTP login (§2.8) actually wanted, given per-login
   SMS cost — or is current "OTP as verification step" model
   sufficient?~~ — **resolved 2026-08-23: not wanted.** Current
   verification-step model stays; standalone phone-OTP login is closed,
   not deferred-and-open.
5. ~~Sequencing Phase 2 (audit logging) with
   `user-security-center-proposal.md` — same PR window or independent?~~ —
   **resolved 2026-08-23: coordinate.** Phase 2 shipped using that
   proposal's own recommended design (§3.1 option (a): metadata, not a
   schema change), and its login-history endpoint was pulled forward into
   the same change rather than left for a separate pass.
6. ~~**Account linking policy** — should Finora eventually let one account
   hold multiple authentication methods, or enforce one account = one
   method, as it does today?~~ — **resolved 2026-08-23: one method per
   account, current model stays.** `UserAuthentication` (§1.1) is not
   needed; `sign_in_method` as a single flat column remains correct.
   Account-recovery design (§2.9) and any future `/auth/identify`
   cross-method-match handling should assume this.
