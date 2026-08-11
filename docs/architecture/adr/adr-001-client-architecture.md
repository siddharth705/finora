# ADR-001: One Backend, One Database, Three Clients

## Status

Accepted — 2026-08-05

## Context

Finora has three clients: `frontend/` (web user portal), `admin-portal/` (web admin portal), and
`mobile/` (React Native/Expo). All three talk to one Spring Boot backend, which talks to one
Postgres database. This came up for explicit re-litigation when `mobile/` was added: does a third
client change the calculus, and should it get its own API namespace, its own DTOs, or its own
backend?

This ADR records the answer so the question doesn't get reopened from scratch each time it
resurfaces — the answer is not "we haven't gotten around to splitting it up yet," it's "splitting
it up is not justified by anything currently true about this system."

## Decision

**One backend. One database. Three clients treated as clients, not as separate systems.**

Concretely:

- All three clients call the same `/api/v1/*` routes. There is no `/mobile/*` or `/web/*`
  namespace, and none should be added without a proven requirement — see
  [Consequences](#consequences) for what "proven" means here.
- All three clients consume the same DTOs. `mobile/`'s own architecture doc already states this as
  a porting rule: "Types and endpoints port verbatim. They're plain TypeScript over axios."
  (`docs/engineering/mobile-architecture.md`). A `MobileDashboardDto` alongside `DashboardDto` is
  not introduced speculatively; see the same principle applied to API changes generally in
  [api-compatibility-policy.md](../../project-management/standards/api-compatibility-policy.md).
- Admin capability lives behind permission checks (`@PreAuthorize`, e.g. `RELATIONSHIP_MANAGE`,
  `PLATFORM_ANALYTICS_VIEW`), not behind a separate deployable. `admin-portal/` is a client with
  more permissions, not a different backend.
- The backend is one Spring Boot deployable with feature-based packages
  (`com.finora.imports`, `com.finora.accounts`, ...). Package boundaries are the isolation
  mechanism between features today, not service boundaries.

From the backend's perspective, mobile and web are the same kind of thing: an authenticated HTTP
client calling the same JSON API. The fact that one happens to be a native app and two happen to be
browsers is a client-side concern, not a reason to model them differently on the server.

## Why

- **One database is the actual source of truth for one product.** A user's transactions, accounts,
  and budgets are the same data whether viewed from the phone or the browser. Splitting the
  database per client would mean solving data consistency between copies of the same facts, for no
  benefit — nothing about Finora's data has a natural per-client partition.
- **Shared DTOs and shared endpoints are correct today, not merely convenient.** Every domain
  object (accounts, transactions, budgets, goals) means the same thing to every client. There is no
  currently-known case where mobile needs a materially different shape of the same data — see
  `mobile-architecture.md`'s "Deliberate divergences from web" table for the actual, evidenced
  exceptions (there are a small, named few; they are not a general pattern).
- **A single deployable is a smaller, cheaper system to operate correctly** than three (or more)
  coordinated services, for a team at Finora's current size and load. Package-private visibility
  inside one JVM enforces "this code shouldn't reach into that code" at zero operational cost — no
  network hop, no second CI/CD pipeline, no distributed-failure mode (partial failure, version skew
  between services) to defend against.
- **Rate limiting, concurrency limits, and permission checks already work per-endpoint and
  per-permission**, not per-client (`RateLimitFilter`'s endpoint-specific limiters,
  `@PreAuthorize`-gated admin controllers). Splitting clients into separate backends would mean
  re-deriving all of this coordination across a network boundary instead of reusing what already
  works in-process.

## Why not separate services

The candidates that come up when this question is reopened — and why none apply right now:

- **A separate mobile backend / `/mobile/*` API**: would fork the API surface mobile depends on
  from the one web depends on, the exact drift `api-compatibility-policy.md` exists to prevent.
  Nothing about mobile's actual requirements needs a different contract than web's.
- **A separate admin backend**: admin capability is already correctly isolated by permission, not
  by deployment. Making it a separate service would mean re-implementing authentication and
  authorization coordination across a network boundary for no isolation gain over what
  `@PreAuthorize` already provides in-process.
- **Splitting the backend into microservices by domain** (imports, accounts, budgets, ...): no
  domain currently has an independent scaling need, an independent deployment cadence, distinct
  ownership, or a genuine isolation requirement that the existing package structure can't satisfy.
  See [scaling-triggers.md](../system-design/scaling-triggers.md)'s "Service extraction" section for
  the exact conditions that would change this answer.

## Consequences

- New endpoints are added to `/api/v1/*` and consumed by whichever clients need them; no client
  gets a namespace of its own by default.
- A client-specific DTO or endpoint requires a stated, specific reason at the time it's proposed
  (a measured payload-size constraint, a genuinely different aggregation, an offline requirement) —
  not "mobile might need this differently someday."
- This decision is revisited only when one of the concrete triggers in
  [scaling-triggers.md](../system-design/scaling-triggers.md) actually fires (replicas > 1 for Redis,
  a measured synchronous bottleneck for workers, a demonstrated independent-scaling/deployment/
  ownership/isolation need for service extraction) — not on a schedule, and not because the
  codebase has grown larger in the meantime.
