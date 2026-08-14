# Distributed-system resilience patterns — audit, 2026-08-14

Requested review: does Finora protect against (1) invalid-identifier lookup storms via a Bloom
filter, (2) cache-stampede/thundering-herd on a popular expiring key, (3) duplicate concurrent
requests re-running the same expensive work, (4) hot-key overload on Redis/DB/a single instance.

**Headline: none of the four exist today, and evidence from this codebase's own load testing says
that's currently correct, not a gap.** The measured bottleneck ([`load-testing-baseline-2026-08-14.md`](load-testing-baseline-2026-08-14.md),
[`hikaricp-bottleneck-investigation-2026-08-14.md`](hikaricp-bottleneck-investigation-2026-08-14.md))
is HikariCP connection-pool exhaustion driven by fixed per-request auth overhead and import's
disproportionate connection hold time — CPU contention, not slow queries, not a missing index, not
a specific overloaded key. Every query measured executes in **sub-millisecond** time. None of the
four patterns below would move that ceiling; building them now would be solving a problem this
system doesn't have yet, at the cost the investigation's own §8 explicitly warns against (a lever
pulled without evidence made things *worse* once already — raising the pool size).

This audit reads code, not intuition, for each pattern. Method: grepped the whole backend for the
relevant APIs/libraries (`@Cacheable`, `CacheManager`, Redis, `BloomFilter`, `Semaphore`,
`Redisson`/`ShedLock`), read every hit, and cross-referenced against the two performance
investigations above plus the "Enterprise-Scale" design package (`enterprise-scale-milestone-design.md`,
2026-08-07, status **design only, nothing implemented**) for what's already been scoped.

---

## 1. Bloom filter — protection against invalid-identifier lookups

**Status: does not exist. Not currently justified.**

**What it would solve:** a probabilistic in-memory check that lets you answer "definitely not
present" without touching the database, cutting DB round-trips for lookups that are frequently
invalid — e.g. a public API hammered with guessed IDs, or a cache in front of a huge negative-space
key set.

**What's actually in this codebase:** every ID lookup that can fail (`ImportSessionService.getOwnedSession`,
transaction/account lookups, etc.) sits behind JWT authentication and an ownership check
(`OwnershipGuard`) — there is no public, unauthenticated, high-volume surface where an attacker or
client can cheaply generate a flood of guessed IDs. Volume on these paths is bounded by "one
authenticated user's own actions," not by an open lookup surface.

**Why it's not justified right now:**
- The bottleneck investigation ran `EXPLAIN ANALYZE` on the real generated queries and found **no
  missing index, every query sub-millisecond** (§4 of the Hikari investigation). A Bloom filter
  optimizes query *count* for negative lookups; here, query *cost* is already negligible, and the
  measured problem is connection-pool exhaustion from breadth and volume of *valid, authenticated*
  work (auth checkout overhead × request volume, import's long connection hold) — not wasted
  round-trips on invalid keys.
- No lookup path in this codebase currently sees "many requests, majority invalid" traffic shape.

**Revisit when:** a public or high-volume lookup surface is added that a client can hit with
mostly-nonexistent keys at real volume (e.g., a public webhook receiver, a bulk existence-check
endpoint, or an unauthenticated identifier-resolution API). None of those exist today.

---

## 2. Thundering herd / cache stampede

**Status: no caching layer exists anywhere. Not currently applicable — but see the one real trigger below.**

**Investigated:** grepped for `@Cacheable`, `@EnableCaching`, `CacheManager`, `RedisTemplate`,
Caffeine — zero hits for an actual cache. `AdminDiagnosticsService` injects
`ObjectProvider<CacheManager>`, but only to report on a diagnostics tile whether a `CacheManager`
bean exists; nothing configures one, so it's always empty. There is genuinely no cache to stampede.

**Directly answered by the load-testing baseline itself** (§5): "Nothing here identifies a
cacheable hot read yet — the dashboard/transactions/accounts endpoints all failed *the same way*
(pool timeout) rather than one being disproportionately slow... caching one endpoint would not move
this ceiling, because the shared constraint is upstream of all of them." Introducing a cache today,
for the sole purpose of having stampede protection ready, would add complexity in service of a
problem that isn't the one actually measured.

**The one concrete, already-scoped trigger:** the Hikari investigation names **auth-overhead
caching** (caching the per-request role/permission lookups — `JwtAuthFilter`'s ~5 fixed connection
checkouts on *every* authenticated request) as one of four remediation candidates, assessed
"Highest breadth — every endpoint... Medium risk — cache invalidation on role/permission change is
security-sensitive" (`project-plan-v1.0.md` §5a item 4). **Deliberately not started** — sequenced
behind Phase 4 (the 56 open bug-hunt findings) per the plan's own §8 rule.

**Recommendation, for when that candidate is picked up (not now):** use a `LoadingCache` (e.g.
Caffeine) rather than a bare `ConcurrentHashMap` for this. A `LoadingCache`'s `get(key, loader)`
already deduplicates concurrent loads for the same key — the same mechanism that answers Q3 below —
so stampede protection for this specific cache is free if it's built this way from the start, and
expensive to retrofit if it's built as a plain map first. This is a design note for that future
work, not something to build today with no cache to attach it to.

**Revisit when:** the auth-overhead caching candidate (or any other cache) is actually scheduled.

---

## 3. Request coalescing / single-flight

**Status: no generic framework exists — but the correctness problem this pattern exists to solve
is already handled, per-case, at the database layer. That's the right choice here, not a gap.**

**What exists today, and it's a closer match than it first looks:**
- `ImportConcurrencyLimiter` (`imports/ImportConcurrencyLimiter.java`) bounds *concurrency*
  (max 6 imports in flight, FIFO semaphore) — this is throughput shaping, not deduplication. Two
  identical concurrent import requests still both run.
- `ImportSessionService.claimForConfirmation` uses an **atomic DB `UPDATE`** specifically to close
  a double-submission race (a double-click or retried confirm request) — the class's own doc
  comment is explicit that a plain read-then-save "wouldn't have been enough."
- **BH-053** (fixed today, `54a9616`): `MerchantLearningService.confirm()` had exactly the failure
  mode Q3 asks about — two concurrent first-time confirmations of the same `(user, merchant,
  category)` pair could both decide "doesn't exist yet" and race to insert, one of them failing the
  whole caller transaction on `merchant_category_learning`'s `UNIQUE` constraint. Fixed via the
  constraint plus proper conflict handling, not an application-level lock.

**Why atomic DB constraints, not a generic in-process single-flight cache, is the right existing
pattern for this codebase:** an in-process request-coalescing layer (e.g. a
`ConcurrentHashMap<Key, CompletableFuture<Result>>`) only deduplicates within *one instance*. This
system's own stated direction (`enterprise-scale-milestone-design.md`) is toward multiple API
instances; a single-flight cache would silently stop providing its guarantee the moment a second
instance exists, while looking like it still works. The DB-constraint pattern already in use
(`UNIQUE` + atomic `UPDATE`) works identically whether there's one instance or ten, which is exactly
why it was the correct fix for BH-053 and `claimForConfirmation` rather than an in-memory lock.

**What's genuinely absent:** coalescing for expensive, idempotent *reads* (e.g. two users' concurrent
dashboard requests independently recomputing the same aggregation). No evidence this costs anything
right now — the Hikari investigation measured dashboard's own computation as part of a 174ms total
connection hold, with the pool/CPU contention identified as the actual constraint, not redundant
computation. Nothing points at duplicate work being the expensive part.

**Recommendation:** keep using atomic DB operations (`UNIQUE` constraints, `claimForConfirmation`-style
atomic updates) for future correctness-critical concurrent-write races — that pattern is proven and
already caught a real bug. Do not build a generic single-flight/request-coalescing layer for reads;
nothing measured justifies it, and it would need re-solving anyway once a second instance exists.

---

## 4. Hot key handling (Redis / DB / single instance)

**Status: not applicable to the current architecture. No Redis exists, one backend instance runs
today by explicit decision.**

**Investigated:** every mention of "Redis" in the codebase is a doc comment explicitly stating it
*isn't* used yet — `ImportConcurrencyLimiter`, `RateLimiter` (auth-endpoint abuse protection), and
`HealthProvider` all say the same thing in different words: single Railway instance, in-process
primitives are the right amount of engineering *now*, Redis is "the natural upgrade path once
there's a second instance" — not before.

**Does Finora even have a "hot key" shape?** The classic hot-key problem is one shared resource
(a viral post, a popular product) drawing disproportionate traffic from *many different users*.
Finora's core domain doesn't have that shape — dashboards, transactions, and accounts are all
scoped to one user's own data; there's no shared, globally-hot resource in the request path.
`BankRegistry` (a plausible candidate — shared reference data, e.g. bank logos/metadata) is a
`static final` in-memory registry with a private constructor — it's compiled Java data with **zero**
database or cache access per lookup, so it's already maximally fast with nothing to protect.

**What Finora actually has instead is a "hot moment," not a hot key** — many *different* users'
requests arriving simultaneously, each independent, each paying the same fixed auth-overhead tax.
That's precisely what the load test's `constant-vus` executor measured, and precisely what it found:
a fixed-cost-times-volume problem (CPU/connection-pool exhaustion), not one key or row being
disproportionately hammered. General overload protection for that shape already exists in scoped
form: `ImportConcurrencyLimiter` (import-specific) and `RateLimiter` (auth-endpoint abuse), both
correctly sized for one instance today.

**Revisit when:** (a) horizontal scaling actually ships — the Enterprise-Scale design package
already names Redis as the point where its own in-process limiters and rate limiter stop being
sufficient (each instance would otherwise enforce its own limit independently); or (b) a genuinely
shared, disproportionately-hot resource is identified in the product (none is today).

---

## Summary

| Pattern | Exists today? | Needed now? | Real trigger to revisit |
|---|---|---|---|
| Bloom filter | No | No | A public/high-volume lookup surface with mostly-invalid keys — none exists |
| Cache stampede protection | No (no cache exists) | No | When auth-overhead caching (already scoped, not started) is picked up — use a `LoadingCache`, not a bare map |
| Request coalescing / single-flight | No generic layer; atomic DB constraints solve the correctness case per-instance-safely | No | An expensive, measurably-redundant idempotent read appears — none measured |
| Hot key handling | No (no Redis, single instance, no hot-key-shaped resource) | No | Horizontal scaling ships, or a genuinely shared hot resource appears |

**Consistent with this codebase's own standing practice** (see the Hikari investigation's §8, and
`enterprise-scale-milestone-design.md`'s explicitly-named risk "designing for scale never reached"):
none of these four patterns are free — each adds a real piece of infrastructure or a new failure
mode to reason about. The evidence gathered here points the *actual* current remediation effort at
four already-scoped, already-prioritized candidates (accounts N+1, dashboard transaction narrowing,
auth-overhead caching, import transaction redesign — `project-plan-v1.0.md` §5a item 4, sequenced
behind Phase 4). Building Bloom filters, stampede protection, single-flight, or hot-key sharding
today would be solving problems downstream of ones that haven't been fixed yet, for a scale this
system hasn't reached.
