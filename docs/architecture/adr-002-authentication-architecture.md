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

The cookie is issued and inert: a browser only stores a `Set-Cookie` from a cross-origin response
if the request was credentialed, and no client has opted in. Migrating the two web apps is the one
remaining piece, and it needs CSRF handled deliberately — `SameSite=Lax` plus a required custom
header — rather than as a side effect of switching transports.

Mobile does not migrate. It already keeps tokens in `SecureStore` (iOS Keychain / Android
Keystore), which is the correct answer for a native client.

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
