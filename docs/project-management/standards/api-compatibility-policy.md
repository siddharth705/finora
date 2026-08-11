# API Compatibility Policy

Why a change to `/api/v1` is safe or unsafe, how deprecation works, and how long a mobile build in
the wild is owed support. Exists because mobile changes the calculus that web never did — see
[Why this exists](#why-this-exists).

## Contents

1. [Why this exists](#why-this-exists)
2. [Versioning strategy](#versioning-strategy)
3. [Breaking vs. non-breaking changes](#breaking-vs-non-breaking-changes)
4. [Deprecation policy](#deprecation-policy)
5. [Mobile support window](#mobile-support-window)
6. [Making a change safely](#making-a-change-safely)
7. [Worked examples](#worked-examples)

---

## Why this exists

`frontend/` and `admin-portal/` redeploy on every push — a user reloads the page and gets whatever
the API currently is. There is no "old client" to account for, because there's no way for one to
persist.

`mobile/` breaks that assumption. Once a build is in the App Store or Play Store, some fraction of
users will run it for weeks after a backend change ships, and there is no reload that fixes it. A
backend change that silently alters an existing response can break an installed app with nothing on
the backend side to blame — no deploy, no error spike traceable to a cause, just a client that
stopped working for reasons invisible from here.

This document exists so that judgment call — "is this change safe to ship?" — has a written answer
instead of being re-derived per pull request, and drifting depending on who's asking.

## Versioning strategy

The API is versioned by URL prefix: `/api/v1` today. `/api/v2` is created **only** when a change
genuinely cannot be made backward-compatible under the rules below — not on a schedule, not because
enough small changes have accumulated. Most of what feels like "this needs v2" is actually an
additive change described the wrong way; see [Worked examples](#worked-examples).

When `/api/v2` does get created, `/api/v1` keeps running unmodified behind it for the
[deprecation window](#deprecation-policy) — the two are separate route trees, not a version flag
threaded through shared handlers. A handler that branches on API version internally is the same
mistake as a handler that branches on client type (`if (mobile)`), and for the same reason: it
means one code path is quietly serving two contracts, and the day the older one is deleted the
newer one has never been tested without it.

## Breaking vs. non-breaking changes

**Non-breaking (safe on `/api/v1`, no announcement needed):**

- Adding a new optional field to a response. A client that doesn't know about it ignores it.
- Adding a new endpoint.
- Adding a new enum value that existing clients don't need to handle to keep working (e.g. a new
  `FinancialProductType` that only appears once the client opts into a newer capability).
- Adding a new `ErrorCode` for a case that previously had no code (was `null`) — a client checking
  `errorCode === 'X'` for a specific case is unaffected by a new code appearing elsewhere.
- Loosening a validation constraint (accepting something that used to be rejected).
- Adding a new required field to a *request*, as long as every existing client already always sends
  a value for it in practice (rare — verify, don't assume).

**Breaking (requires `/api/v2`, or must wait for a coordinated rollout):**

- Removing a field a client reads.
- Renaming a field. This is the one people reach for out of habit ("let's call it
  `closingBalance` instead of `balance` while we're in here") — from a compatibility standpoint a
  rename **is** a removal plus an addition, not a refactor.
- Changing a field's type or unit (`amount: number` → `amount: string`; paise → rupees).
- Changing what a status code or `errorCode` *means* for an existing case. Repurposing `AUTH_002`
  to cover a different failure than "session expired" breaks every client's existing branch on it,
  even though the code itself didn't change.
- Tightening request validation (rejecting something that used to be accepted) for a field an
  installed client is already sending the old way.
- Changing default/omitted-field behavior a client relies on.

## Deprecation policy

1. `/api/v2` ships with `/api/v1` still fully functional, not frozen-but-crashing.
2. `/api/v1` stays live for the [mobile support window](#mobile-support-window) below, minimum.
3. Deprecation is announced in this file's changelog (bottom of this doc) the day `/api/v2` ships,
   with the planned sunset date — not decided retroactively when someone asks "can we delete v1
   yet?"
4. Sunsetting `/api/v1` is itself a breaking change and follows the same process: it doesn't happen
   until the support window has actually elapsed, confirmed against real client telemetry if it
   exists, not just the calendar.

## Mobile support window

**The last two released mobile app versions remain supported against the current API at all
times.** Concretely: shipping mobile version N obligates the backend to keep N-1 working too, until
N+1 ships. This is the number to revisit once there's real install-base data on how long people
actually take to update — it's a starting policy, not a measured one, and should be corrected
against evidence rather than left as a guess once that evidence exists.

Web (`frontend/`, `admin-portal/`) has no equivalent window — see
[Why this exists](#why-this-exists). This section applies to `mobile/` only.

## Making a change safely

Before changing an existing endpoint or DTO:

1. **Is this addition or modification?** Check it against
   [Breaking vs. non-breaking](#breaking-vs-non-breaking-changes) directly — most "is this safe?"
   questions resolve immediately once framed this way.
2. **If it's a modification, can it be reframed as an addition instead?** Add the new field
   alongside the old one rather than replacing it; have the client migrate to the new field on its
   own schedule; remove the old field only once nothing reads it (verified, not assumed) or once
   the support window for the last client that could read it has elapsed.
3. **If it genuinely can't be reframed**, it needs `/api/v2` for that route, or it needs to wait
   for a coordinated mobile release that ships alongside the backend change — not go out
   unilaterally from the backend.
4. **Don't create a client-specific DTO to route around this.** `mobile/`'s own architecture doc
   already establishes the rule this policy assumes:
   > "Types and endpoints port verbatim." — `docs/engineering/mobile-architecture.md`, Porting rules
   A second DTO (`MobileAccountDto` alongside `AccountDto`) is warranted only when there's a
   *measured* constraint — payload size, a genuinely different aggregation, offline shape — driving
   it, not as a way to make an incompatible change without calling it one. See the same document's
   [Deliberate divergences from web](../../engineering/mobile/mobile-architecture.md#deliberate-divergences-from-web) table
   for what an actually-justified, evidenced divergence looks like.

## Worked examples

| Change | Breaking? | Why |
|---|---|---|
| Add `investmentKind` to `AccountDto` | No | New optional field; old clients ignore it |
| Add `AUTH_SESSION_REVOKED` (`AUTH_004`) alongside existing `AUTH_001`–`AUTH_003` | No | New code, existing codes unchanged in meaning |
| Rename `ConfirmedRow.category` to `ConfirmedRow.categoryName` | **Yes** | Every client reading `.category` breaks the instant this ships |
| Add a new `FinancialProductType` enum value (e.g. a future `NPS`) | No, *if* clients treat unknown values gracefully | Breaking only for a client that exhaustively switches on the enum with no default case — worth checking mobile's handling before treating this as automatically safe |
| Change `IMPORT_NO_HEADER_DETECTED`'s HTTP status from 422 to 400 | **Yes** | A client branching on status code (`if (status === 422))` silently stops matching |
| Add a new `GET /api/v1/accounts/:id/insights` endpoint | No | Purely additive |
| Make `phoneNumber` required on a request DTO that previously allowed it to be omitted | **Yes**, unless verified every live client already always sends it | Old client omitting it now gets rejected where it previously succeeded |

---

## Changelog

- **2026-08-03** — Policy created. No deprecations issued yet; `/api/v1` is the only version.
