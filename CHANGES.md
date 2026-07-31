# Answer: yes, you need separate base URLs — and it's an active bug, not just a future concern

Checked the actual code rather than answering from first principles. Short version: **yes, add
`ADMIN_APP_BASE_URL`** — and this isn't a "nice to have for later," it's fixing something that's
broken right now.

## What I found

- `APP_BASE_URL` has exactly one consumer in the whole backend: `AuthService.forgotPassword()`,
  which builds the password-reset link every "Forgot Password" email/response uses.
- There is **no separate admin auth service** — admin accounts authenticate through the exact
  same shared `AuthController`/`AuthService`, just gated by role/permission afterward.
- The admin portal **does** have its own `/reset-password` and `/forgot-password` pages, as a
  completely separate deployed app at a different origin (`finora-admin.pages.dev`).

Put together: right now, if an admin uses "Forgot Password," the email links to
`https://finora-cng.pages.dev/reset-password?token=...` — the **user app**, not the admin portal
where their own reset page actually lives. This isn't hypothetical; it's the current, live
behavior, just not yet noticed because nobody's tried it in production yet.

## The fix

- **`EmailProperties`** — added `adminAppBaseUrl` (backed by new `ADMIN_APP_BASE_URL` env var,
  optional) alongside the existing `appBaseUrl`. New `resolveBaseUrl(requestOrigin)` picks the
  right one.
- **`AuthController`** — `forgotPassword` now reads the request's `Origin` header and passes it
  through.
- **`AuthService.forgotPassword()`** — uses `resolveBaseUrl()` instead of unconditionally using
  the single user-frontend URL.

**No frontend changes needed at all** — both apps already call the exact same shared
`/auth/forgot-password` endpoint, and a browser always sets the `Origin` header on a cross-origin
request itself (JS can't override it), so the backend can tell which app is calling for free.

## What you need to do

Add to Railway:
```
ADMIN_APP_BASE_URL=https://finora-admin.pages.dev
```
Leaving it unset costs nothing — every existing behavior (including everything for the user app)
stays exactly as it is today, since `resolveBaseUrl()` falls back to the current `APP_BASE_URL`
whenever the admin one isn't set or doesn't match.

Also corrected `CORS_ORIGINS` and `APP_BASE_URL`'s example values in the deployment guide to use
your actual real domains (`finora-cng.pages.dev` / `finora-admin.pages.dev`) instead of the
placeholder `.workers.dev` one from before we knew the real deployment domains — and made sure
the `CORS_ORIGINS` example explicitly includes **both** origins, not just the user app's.

## Tests

- `EmailPropertiesTest.java` **(new)** — 6 cases directly on `resolveBaseUrl()`: admin match, user
  match, case/trailing-slash insensitivity, null origin, unconfigured admin URL, origin matching
  neither.
- `AuthServiceEmailTest.java` — updated the 3 existing calls for the new method signature, added
  4 new tests covering the same logic end-to-end through `forgotPassword()` itself (admin origin,
  user origin, admin URL never configured, missing Origin header).

## Verification

Same constraint as always — no Maven in this sandbox. Reasoned through this by hand against the
real code (confirmed via direct inspection: single call site, no separate admin auth service,
admin portal's own reset-password page, shared endpoint). Please run `mvn test`.
