# Unified auth entry (`/login` + `/register` → `/auth`)

Status: approved, pending implementation plan.

## Goal

Collapse `/login`, `/register`, and `/auth` into one screen. `/auth` is the only
route a user ever navigates to; it internally steps through identify →
password/register → success, with no page navigation in between. `/login` and
`/register` remain reachable only as redirects, for old bookmarks/links.

## Explicit non-goals (reopened, then closed, during design)

A detailed security critique was raised during design review proposing several
changes beyond the UI merge. Each was checked against the actual backend before
deciding:

- **Enumeration mitigation stays as-is.** `IdentifyResponse` already collapsed
  `PASSWORD`/`GOOGLE`/`APPLE`/`CONTINUE` to `EXISTS`/`CONTINUE` in Phase 7
  (resolved 2026-08-23), with the tradeoff ("narrows rather than eliminates")
  explicitly documented and rate limiting named as the second half of the
  mitigation. Not reopened. An opaque-transaction-ID redesign is a real future
  direction, not part of this PR.
- **OAuth auto-linking stays as-is.** `loginWithOAuthIdentity` already refuses to
  auto-link into an existing account whose email isn't verified (a documented
  self-review fix against account hijacking). No explicit "link this account?"
  confirmation step is being added.
- **No consumer MFA branch.** MFA (`AdminMfaService`) is admin-portal-only and
  currently disabled by flag. There's nothing to branch on for regular users
  today; not designing for it speculatively.
- **Rate limiting stays IP-only.** `RateLimitFilter` keys on `getRemoteAddr()`/
  `X-Forwarded-For` only, same as every other rate-limited endpoint site-wide.
  This is a pre-existing, site-wide characteristic, not something introduced or
  fixed by this PR. Documented here as a known limitation, not built.
- **Session invalidation verified, not changed.** `resetPassword`,
  `PasswordChangeService.complete`, `UserAccountLifecycleService.deactivate`,
  and account deletion all already call `RefreshTokenService.revokeAllForUser`.
  No gap found.
- **Timing attack checked, not present.** Both `identify()` branches
  (`EXISTS`/`CONTINUE`) do the exact same single DB lookup; no asymmetric work
  to fix.
- **Registration-as-wizard (delay password to the end, email-verify-first)** is
  a real, larger UX idea — explicitly deferred to a future proposal, not part
  of collapsing three existing pages into one.

## Security boundaries (explicit, for implementation and review)

- React `step` state and React Router `location.state` are UI-only. They pick
  which form renders; they never grant access to anything. Every state
  transition that matters (password submit, registration submit, OAuth
  credential, reactivation) still round-trips through the real backend
  endpoint (`/auth/login`, `/auth/register`, `/auth/google`, `/auth/apple`,
  `/auth/reactivate`) exactly as today. A user manipulating React devtools to
  force `step = 'password'` gains nothing but an empty form — they still need
  a real password that the backend validates.
- `location.state` carries only what the user already typed (identifier) or a
  UI-only banner message — never a provider, userId, verification status, or
  any other server-decided fact. This matches what `Login.tsx`/`Register.tsx`
  already do today; the merge doesn't add anything new here, and this spec is
  the place that makes it explicit so it doesn't drift later.
- `location.state` is not a URL and not attacker-settable via a link — but it
  is also not a security control. It's a UX convenience for skipping a step
  the user just proved (e.g. just completed a password reset), never proof of
  identity by itself. The backend independently validates the reset token and
  phone verification on that path regardless of what the frontend shows.
- A `409` from `/auth/register` remains authoritative even if `/auth/identify`
  earlier returned `CONTINUE` — the DB unique constraint is the real check;
  the identify step is only ever a hint for which form to show first.

## Component structure

New folder `frontend/src/pages/auth-entry/`:
- `IdentifyStep.tsx` — today's `AuthEntry.tsx` form (identifier input, Continue
  button, calls `/auth/identify`)
- `PasswordStep.tsx` — today's `Login.tsx` form logic (password field,
  forgot-password link, Google/Apple buttons, `ReactivateAccountPrompt`)
- `RegisterStep.tsx` — today's `Register.tsx` form logic (name/email/phone/
  password/confirm/terms, referral code, Google/Apple buttons)
- `MarketingPanel.tsx` — the feature-list panel currently duplicated identically
  in `Login.tsx` and `Register.tsx`, extracted once, headline/badge copy passed
  as props per step

`AuthEntry.tsx` becomes an orchestrator: holds `step` state, the shared
identifier value, and renders the active step plus the (now-shared)
`MarketingPanel`. `GoogleSignInButton`, `AppleSignInButton`, `PasswordInput`,
`ReactivateAccountPrompt` are reused unchanged, no modifications.

## State machine

```
IDENTIFY (default)
   │ submit → POST /auth/identify
   ├── EXISTS   → PASSWORD (identifier carried over, no navigation)
   └── CONTINUE → REGISTER (identifier prefilled into email or phone field)

PASSWORD
   │ submit → POST /auth/login
   ├── success → navigate /app or /verify-phone (unchanged)
   ├── AUTH_ACCOUNT_DEACTIVATED → ReactivateAccountPrompt in place
   └── "Not you?" → full reset → IDENTIFY

REGISTER
   │ submit → POST /auth/register
   ├── success → navigate /app or /verify-phone (unchanged)
   └── 409 (email/phone taken) → PASSWORD, identifier prefilled, banner shown
       (replaces today's "Continue to login" cross-page link)

Deep link (reset-password success only):
   /auth arrives with router state → starts directly on PASSWORD,
   identifier + banner prefilled, skipping IDENTIFY
   (the reset itself is independently authenticated server-side;
   this is a UX shortcut, not a security decision — see boundaries above)
```

**"Not you?" full reset** (real gap raised in review, folding in): going back
to `IDENTIFY` clears identifier, password, confirmPassword, error,
reactivation token, and any OAuth-in-flight state — not just the step value.
Matters on shared computers. Add a regression test asserting all of these are
cleared, not just that `step` changes.

## Routing changes

`App.tsx`: `/login` and `/register` become `<Navigate to="/auth" replace />`.

Internal call sites updated from `/login` to `/auth` directly (avoids a double
redirect for in-app navigation): `ProtectedRoute.tsx`'s session-expiry
redirect, `Sidebar.tsx`'s logout, `api/client.ts`'s forced-signout
(`window.location.href`), `ResetPassword.tsx`'s post-success redirect (using
the deep-link state shape above), `VerifyPhone.tsx`, `VerifyEmail.tsx`,
`ForgotPassword.tsx`'s "back to sign in" links.

`Login.tsx`/`Register.tsx`'s own cross-links to each other are deleted
entirely — no longer needed, same page.

`/forgot-password` and `/reset-password` stay separate standalone routes —
not folded into `/auth`.

## Testing

TDD throughout. `Login.test.tsx`/`Register.test.tsx` retire once those files
are deleted; coverage moves into per-step-component tests plus a rewritten
`AuthEntry.test.tsx` covering: identify→password→success, identify→
register→success, register 409→password step with banner and prefill,
deep-link-to-password-step, reactivation flow, and the "Not you?" full-state
reset. New: a regression test asserting that setting `step` via React state
alone (without a corresponding real API success) never reaches an
authenticated view — the backend call is what the transition actually depends
on, not client state.

## Out of scope (this PR)

Opaque authentication-transaction architecture, MFA as a first-class consumer
auth state, registration-as-wizard (delayed password / email-verify-first),
shared mobile/web auth state machine, adaptive per-step marketing copy,
identifier-keyed rate limiting, explicit account-linking confirmation UI. Each
is a legitimate future direction; none is required to collapse three existing
pages into one.
