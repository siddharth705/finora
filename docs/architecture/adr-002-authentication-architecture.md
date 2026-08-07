# ADR-002: Authentication Architecture

## Status

Accepted — 2026-08-06

## Context

Finora holds bank statements, tax documents and salary slips. Its authentication model was
inherited from ordinary web-app defaults — a 15-minute access token and a rotating refresh token
good for 30 days — and nobody had asked whether those defaults suited the product.

The question surfaced concretely: returning to `app.finoratech.info` after nine hours away landed
straight on the statements page with no password. That was not a bug. The refresh token was valid,
the client refreshed silently, and everything worked exactly as specified. **The specification was
what was wrong** — it was the answer a shopping site gives, and this is not a shopping site.

Working through it turned up a second, worse problem: a session that never ended. Every rotation
issued a token with a *fresh* 30 days, so a session used once a month survived indefinitely. And a
third, found only at the very end: the account-wide revocation that reuse detection performs was
being silently rolled back, so a suspected stolen token invalidated nothing.

This ADR records the resulting model so the reasoning does not have to be reconstructed from
commit messages.

## Decision

### The session is the unit, not the token

A **session** is one sign-in on one device. It has a stable `session_id`, generated at sign-in and
carried forward unchanged by every rotation, plus a `session_started_at`.

`refresh_tokens.id` identifies a **token** and is replaced roughly every fifteen minutes by
rotation. It cannot answer "how old is this session" or "is this the device I am using", and using
it for either produces answers that go wrong at the next refresh. Idle timeout, absolute lifetime,
current-device identification and anything added later (device naming, trusted devices, risk
scoring) all key off the session.

The access token carries `sid`, the registered claim name for a session identifier. The server can
therefore answer "which session is asking" from the caller's own token, so no client stores or
sends a session id and there is no second identifier to keep in sync across three clients.

### A revocation has to reach the access token

Every revocation below writes to `refresh_tokens`. None of it could touch an access token already
in circulation, because a JWT is valid on its signature and expiry alone — so the platform could
conclude a token was stolen, sign the user out of every device, and the attacker kept working for
the remainder of the fifteen minutes.

`JwtAuthFilter` now asks `SessionValidator`, on every authenticated request, whether the session
named by `sid` still has a live refresh token. A request whose session has none is simply not
authenticated, and Spring Security's entry point answers 401 exactly as it does for an expired
token. No new response shape reaches any client: the *reason* a session ended is still reported by
`/auth/refresh`, which is where all three clients already read it.

Fifteen minutes was never the thing to measure. The defect was that a decision the platform had
made did not take effect; shortening the access token narrows that window and never closes it, and
lengthening it — a reasonable thing to want on mobile — silently widens it.

**Keyed on the session, not the token.** Rotation revokes the presented refresh token roughly every
fifteen minutes and writes a successor carrying the same `sid`. A check keyed on `refresh_tokens.id`
would therefore sign every active user out at their first refresh, while looking exactly like a
working security fix. That inverse property is held by its own test.

**A null `sid` fails closed** — it means the token names no session, so its revocation status is
unknowable, and the check would otherwise be bypassable by omitting the claim. This is deliberately
stricter than the reading `DeviceController` applies to the same claim, where null means "cannot
tell which device is asking" and badging nothing is the right answer.

**Cost:** one indexed existence check per authenticated request, alongside the two reads that path
already performs. `idx_refresh_tokens_live_session` (V71) is partial over the unrevoked rows, so it
holds one entry per *live* session rather than one per rotation — V57's index makes the question
answerable, not cheap, because nothing purges the revoked rows a long-lived session accumulates.

### Account scope is an authorization input, not just a login input

V52 introduced `account_scope` and recorded the model as "login disambiguates on it; authorization
does not". That left the wall between the consumer app and the admin portal resting on
`RoleService.requireScopeCanHold` refusing to *attach* a permission-bearing role to a USER-scope
account — a guard that is only ever as good as the completeness of the set of granting paths, which
nothing checks.

`AuthorizationService` now withholds permission authorities from any account that is not an
admin-portal account, so a USER-scope account holding admin permissions by any route — a row
predating the guard, a future path that forgets it, a direct database edit — exercises none of them.
Withholding *permissions* is exact rather than approximate: every `@PreAuthorize` in the application
is `hasAuthority('<PERMISSION>')`, every seeded permission gates an `/api/v1/admin/**` or setup
endpoint, and no user-facing endpoint carries a `@PreAuthorize` at all. `ROLE_*` authorities are
still granted in full, because nothing authorizes on them and keeping them makes the anomaly legible
rather than making a role look like it vanished.

The token carries a `scope` claim, and `JwtAuthFilter` checks it against the `PORTAL_ADMIN` /
`PORTAL_USER` authority computed for the same request — a set lookup, not a second query. An absent
claim falls back to the row rather than failing closed, unlike an absent `sid`: the row being
checked against is the same row the claim was copied from, so nothing is lost.

### Token lifecycle

| | Lifetime | Notes |
|---|---|---|
| Access token | 15 min | Bearer, `Authorization` header, all clients |
| Refresh token | 30 days | Rotates on every use, reuse detected |
| **Idle timeout** | 30 min | `JWT_IDLE_TIMEOUT_MS` |
| **Absolute session** | 7 days | `JWT_ABSOLUTE_SESSION_MS` |

Rotation revokes the presented token and issues a new one. Presenting an already-rotated token is
the theft signal: it revokes **every** session for that user.

Idle is measured from the current token's `createdAt`, not `lastSeenAt`. Rotation mints a token
whenever the client needs an access token, so the current token's age is the time since the last
activity — and `lastSeenAt` is best-effort device metadata that is silently skipped when there is
no request context, so a null would quietly disable the check.

### Routine expiry ends one session; compromise ends all of them

| Event | Scope |
|---|---|
| Idle timeout | this session |
| Absolute cap | this session |
| Voluntary logout | this session |
| **Refresh token reuse** | **every session** |
| Password change | every session |

Every row above now takes effect on the *access* token as well, on the revoked session's next
request — see [A revocation has to reach the access token](#a-revocation-has-to-reach-the-access-token).
Previously each of them ended only the ability to *refresh*, and the token already in hand kept
working for up to fifteen more minutes.

The absolute cap deliberately does **not** revoke everything. Each device carries its own
`session_started_at`, so a phone that signed in on day 3 reaches its own cap on day 10 regardless
of what the laptop does — signing everything out would take down sessions nowhere near their limit
and would put a seven-day expiry in the same bucket as a suspected theft, dulling the signal that
actually matters.

`AUTH_005` (idle) and `AUTH_006` (max age) are distinct codes, not folded into `AUTH_002`. All
three end a session, but "signed out after a period of inactivity" is self-explanatory where
"session expired" reads as a fault — and a spike in `AUTH_005` is the measurement that says 30
minutes is too aggressive for how people actually work.

### Transport is a client concern; authentication is not

`/auth/refresh` and `/auth/logout` accept the refresh token from an `HttpOnly` cookie **or** the
request body. Precedence: cookie, then body, then 401.

Both are supported permanently. Mobile is a native client with no cookie jar and will always send
a body. Branching into separate endpoints would duplicate rotation, reuse detection and the
session limits across paths that must never disagree — so the transport varies and the
authentication logic does not.

**Cookie attributes**, asserted by test on every path that writes one:

```
HttpOnly · Secure · SameSite=Lax · Path=/api/v1/auth · no Domain
```

*Host-only* — no `Domain`. `Domain=.finoratech.info` would send the durable credential to every
present and future subdomain when only the API host that issued it ever needs it. The frontend
never reads the cookie, so sharing it buys nothing.

*`SameSite=Lax` suffices* because `app.` and `api.` share the registrable domain
`finoratech.info` — same-site though cross-origin. This only became possible when the API moved
onto that domain. The alternative, `SameSite=None`, is what browsers are progressively restricting
as third-party cookies, so a credential depending on it ships with a deprecation clock.

### Web clients still use `localStorage` today

The cookie transport itself works — `0205e8b` set `withCredentials` on both apps' axios instances,
which is what a browser needs before it will store or return a cross-origin `Set-Cookie`. What has
not happened is removing the `localStorage` copy, and until that happens the XSS mitigation the
cookie exists for is not delivered: script on the page can still read the durable credential.

**Two named dependencies, neither of them the transport.** This is recorded here because "remove the
localStorage write" reads like a one-line change and is not one.

1. **`/users/me/password-change/complete` requires the client to read its own refresh token.**
   `CompleteRequest.currentRefreshToken` is `@NotBlank`, and it is how the backend identifies which
   device to *exclude* from `revokeAllOtherSessionsForUser`. A web client that cannot read the token
   cannot send it — and `revokeAllOtherSessionsForUser`'s documented fallback for a token that
   matches nothing is to revoke everything *including this device*. So removing the copy without
   changing this endpoint first would sign the user out of the device they just changed their
   password on. The fix is for the endpoint to identify the current device from the access token's
   `sid`, which is what that claim exists for; it has to be additive, because mobile legitimately
   holds its refresh token in `SecureStore` and installed builds will keep sending the field.

2. **CSRF, still.** `SameSite=Lax` stops a cross-*site* POST, but `app.` and `api.` are same-site by
   design, so any present or future `finoratech.info` subdomain can reach `/auth/refresh` with the
   cookie attached. CORS stops it *reading* the response, which bounds the impact to forcing a
   rotation — but a forced rotation makes the victim's real tab replay a rotated token, which is the
   theft signal, which signs them out everywhere. The mitigation is the required custom header named
   above, and it also needs `CorsConfig.allowedHeaders` (today `Authorization` and `Content-Type`
   only) or the preflight fails.

Mobile does not migrate. It already keeps tokens in `SecureStore` (iOS Keychain / Android
Keystore), which is the correct answer for a native client.

**The order this has to happen in.** Each step is safe to ship on its own; the sequence is what
matters, because doing step 3 first is what breaks the password-change flow.

1. **Backend, additive — identify the current device from `sid`.**
   `RefreshTokenService.revokeAllOtherSessionsForUser(userId, currentRawToken)` gains a sibling that
   takes a **session id** instead of a raw token, and `PasswordChangeService.complete` prefers it,
   reading `JwtAuthFilter.SESSION_ID_ATTRIBUTE` from the request. `CompleteRequest.currentRefreshToken`
   relaxes from `@NotBlank` to optional and stays supported: installed mobile builds keep sending it,
   and per [api-compatibility-policy.md](../engineering/api-compatibility-policy.md) loosening a
   validation constraint is non-breaking while removing the field is not. Needs a test that a
   password change with **no** `currentRefreshToken` still leaves the calling device signed in —
   that is the assertion the whole sequence rests on.
2. **Backend + both SPAs — the CSRF header.** A required custom header (e.g. `X-Finora-Client`) on
   `/auth/refresh` and `/auth/logout` *when the cookie transport is used*, never when a body token
   is, so mobile is unaffected. `CorsConfig.setAllowedHeaders` must list it in the same change or
   every preflight fails. Ship before step 3, not with it: once the cookie is the only credential,
   this is load-bearing.
3. **Both SPAs — stop writing the token.** `AuthContext.persist` / `persistAdminSession` drop the
   refresh-token key; the 401 interceptors call `authApi.refresh()` with no body and let the cookie
   carry it. Note the interceptors currently gate on `if (refreshToken)` and fall straight to
   `clearSessionAndRedirect()` when absent — that branch has to become "always attempt the refresh",
   or the first 401 after this change signs everyone out. `ChangePasswordModal` stops reading
   `finora_refresh_token`. Client unit tests that assert on the storage key
   (`frontend/src/api/client.test.ts`, both `AuthContext.test.tsx`, the admin equivalents,
   `ChangePasswordModal.test.tsx`) move with it. The Playwright suites reference no storage key, so
   they are unaffected.

`mobile/` is untouched throughout. The access token stays in `localStorage` either way — that is a
fifteen-minute credential and, since the session check above, a revocable one; the durable
credential is what this sequence is about.

## Consequences

**A session can end mid-task.** 30 minutes of inactivity is the point, and it is what banks do. If
support sees people signed out mid-task, `AUTH_005` in the logs is the evidence and
`JWT_IDLE_TIMEOUT_MS` is the dial.

**Users can be signed out of every device by their own stale tab.** Replaying a rotated token
looks identical to theft, and the system cannot tell them apart. That is the intended trade.

**`noRollbackFor` is load-bearing and fragile.** Three paths write a revocation and then throw to
reject the request; `ApiException` is a `RuntimeException`, so the default rollback rule discarded
all three. The fix has to sit on `AuthService.refresh`, the **outer** boundary — `rotate` joins
that transaction, so marking only the inner method reads as correct and changes nothing.

This is a rule about a transaction *boundary*, not about the operation, so any future caller that
wraps `refresh()` in its own transaction has to repeat it. **Preferred future direction:** commit
the revocation in its own transaction (`REQUIRES_NEW`, in a separate bean — Spring does not proxy
self-invocation), so a security state change cannot be undone by whatever business operation
happens to be reporting the failure.

## How this is held

The tests below are the enforcement. Each was written because the property it checks can break
without anything else failing.

| Test | Property |
|---|---|
| `RefreshTokenTransportIT.issuanceRotationInvalidationAndReuseDetectionComposeOverTheCookieTransport` | Reuse detection actually **commits** account-wide revocation. Witnessed by an untouched second session dying — the rotated token would have been stale from ordinary rotation and proves nothing. |
| `RefreshTokenTransportIT.assertSecurityAttributes` (3 paths) | Cookie flags. Every one is silently downgradeable: the flow keeps working perfectly while the protection is gone. |
| `RefreshTokenTransportIT.theCookieWinsWhenBothAreSupplied` | Transport precedence. |
| `RefreshTokenSessionLimitsTest.rotationCarriesTheORIGINALSessionStartForward` | The absolute cap exists at all. Stamping `now` here resets the clock every 15 minutes and the cap silently never fires. |
| `RefreshTokenSessionLimitsTest.refreshTokenReuseStillSignsOutEverything` | The blast-radius contrast that makes the per-device cap safe to narrow. |
| `AccessTokenSessionRevocationIT.aRevokedSessionsAccessTokenIsRejectedLongBeforeItExpires` | A revocation reaches the access token. Every rejection here is paired with an assertion that the token is **still signed and unexpired** — "it 401s after revocation" is satisfied equally well by the token having simply run out. |
| `AccessTokenSessionRevocationIT.rotationDoesNotEndTheSession` | The inverse, and the one that catches the plausible wrong fix: keying the check on the token rather than the session signs every active user out at their first refresh. |
| `AccessTokenSessionRevocationIT.signingOutOneDeviceLeavesTheOtherOneWorking` | Blast radius. A check that ended *every* session would satisfy the revocation test above and be useless. |
| `AccessTokenSessionRevocationIT.anAccessTokenCarryingNoSessionClaimIsRejected` | Fail-closed on a missing `sid` — otherwise the check is bypassable by omitting the claim. |
| `AuthorizationServiceTest.aConsumerScopeAccountHoldingAnAdminRole_getsNoneOfItsPermissions` | Scope is read where authorization happens, not only where a grant is made. |
| `AuthorizationServiceTest.meAccessAgreesWithWhatTheServerWillActuallyAllow` | The admin portal's own gate cannot advertise permissions the server will refuse, or it admits an account and 403s every section inside it. |
| `AuthServiceLoginTest.login_withSuspendedAccount_andAWrongPassword_revealsNothingAboutTheAccount` | A caller who has not proved the password learns nothing about whether the account exists. |

**A note on how these were written.** Several passed initially while asserting less than their
comments claimed — a badge existing rather than being on the right row; a token being rejected
without distinguishing ordinary rotation from reuse detection. The rollback bug survived three
green unit tests because they mocked the repository: `saveAll` was called, `verify()` passed, and
no transaction existed to undo it.

The heuristic that catches this: **could this observation be true for two different reasons?** If
yes, the witness is too weak. Worth applying to the document-intelligence work, where "one layout
fingerprint exists" is satisfied by a hash function returning a constant, and "statements X and Y
produce the same fingerprint" is not.

## Related

- [ADR-001: One Backend, One Database, Three Clients](adr-001-client-architecture.md) — why all
  three clients share one route tree, which is what makes a single dual-transport endpoint the
  right shape rather than a per-client one.
- `docs/engineering/repository-guardian.md` — the ArchUnit rules that hold the layering these
  services sit in.
