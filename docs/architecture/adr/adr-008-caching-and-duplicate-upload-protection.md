# ADR-008: Caching Layer and Duplicate-Upload Protection

## Status

Accepted — 2026-08-14

## Context

[`distributed-resilience-patterns-audit-2026-08-14.md`](../../investigations/performance/distributed-resilience-patterns-audit-2026-08-14.md)
audited Finora against four distributed-system resilience patterns (Bloom filters, cache
stampede protection, request coalescing, hot-key handling) and found none of them justified by
measured evidence at the time. Two of that audit's findings pointed at real, already-measured
problems worth fixing on their own terms, independent of whether the *pattern* that solves them
was one of the original four:

1. **A real N+1** — `BankManagementService.resolve()` queries `bankRepository.findById` once per
   account (`project-plan-v1.0.md` §5a), and `RecurringService.detectForUser` (8 call sites: every
   import confirm and every transaction mutation) reads a feature flag from Postgres on every call,
   bypassing `FeatureFlagService` entirely despite that service's own doc comment saying it's the
   intended path.
2. **A real gap named but not yet closed** — the audit's Q3 (request coalescing) found that
   duplicate-concurrent-request protection exists for the async import job queue (BH-019,
   `idx_import_jobs_live_content`, V74) but has no equivalent on the synchronous stage path
   (`POST /csv/stage`, `/pdf/stage`), which had no protection against a double-click or a retried
   request at all.

This ADR records what was actually built in response: a small, reusable caching foundation
(Caffeine), applied to the two real N+1s above, and a duplicate-upload protection layer for the
synchronous stage path mirroring BH-019's own proven shape. **Bloom filters, hot-key handling, and
Redis remain out of scope** — nothing in this pass changed the evidence that made the audit
conclude they aren't justified yet.

## Decision

### 1. Caching foundation — `CacheConfig`

**Problem.** No caching abstraction existed anywhere in the codebase (confirmed by the earlier
audit's own grep). Any future cached resource would either hand-roll a `ConcurrentHashMap` (no
TTL, no eviction, no stampede protection — the "ad-hoc cache logic" explicitly to be avoided) or
each reinvent its own Caffeine wiring.

**Before → after.**
```
Before: no CacheManager bean exists. AdminDiagnosticsService's own ObjectProvider<CacheManager>
        reports "caching configured: false" -- a diagnostic anticipating this, unmet.
After:  @EnableCaching + a CaffeineCacheManager bean (com.finora.config.CacheConfig), with each
        named cache getting its own Caffeine spec (TTL via expireAfterWrite, size via
        maximumSize) rather than one blanket spring.cache.caffeine.spec applied everywhere.
```

**Why Caffeine in-process, not Redis.** Same reasoning `ImportConcurrencyLimiter`/`RateLimiter`
already document for their own in-process primitives: one Railway instance today, not a
distributed system. This is exactly the "Redis is the correct upgrade once a second instance
exists, not before" line the audit already drew — reaching for it now would be the premature
complexity the audit found no evidence for.

**Why this closes cache-stampede/single-flight for free.** `@Cacheable(sync = true)` resolves to
`Cache.get(key, Callable)`, which `CaffeineCache` delegates to Caffeine's own
`Cache.get(key, mappingFunction)` — documented as atomic per key. Concurrent callers that miss the
same key block behind the first load instead of each independently repeating the expensive work.
This is a property of the underlying cache, not a separate mechanism layered on top — the same
answer the audit gave for what stampede protection would need to look like *if* a cache were ever
added.

**Code.** [`CacheConfig.java`](../../../backend/src/main/java/com/finora/config/CacheConfig.java) —
two named caches: `customBanks` (10 min TTL, 500 max size) and `featureFlags` (60s TTL, 200 max
size). `pom.xml` gained `spring-boot-starter-cache` and `caffeine`.

### 2. Custom-bank lookups — `CustomBankLookup` / `BankManagementService`

**Problem.** `AccountService.listForUser` calls `BankManagementService.resolve(bankId)` once per
account, each call independently querying `bankRepository.findById` — a small (admin-managed,
low-tens-of-rows), rarely-changing table re-queried on every account in every page load.

**Before → after.**
```
Before: resolve()/listAll()/search()/listCustom() each call bankRepository.findAllByOrderBy...()
        or findById() directly -- N accounts on a page = N database round trips to the same table.
After:  All four read through CustomBankLookup.all() -- one cached list, sync=true, TTL 10 min.
        resolve() does an in-memory linear scan over the cached list (dataset is tiny; this costs
        nothing measurable next to a database round trip). Cache evicted explicitly, via
        AfterCommit.run (post-commit, not mid-transaction) on createCustom/updateCustom/
        deleteCustom, so an admin's own change is visible on the very next read.
```

**Why a separate bean (`CustomBankLookup`), not `@Cacheable` directly on `BankManagementService`
methods.** `@Cacheable`/`@CacheEvict` only take effect through Spring's proxy — a method calling
another `@Cacheable` method via `this.` from inside the same bean bypasses the proxy and silently
never hits the cache. `resolve()`, `listAll()`, `search()`, and `listCustom()` are all *in*
`BankManagementService`, so the cached read had to live on a different bean for any of them to
actually benefit.

**Why eviction is a direct `AfterCommit.run` call, not `@CacheEvict`.** Relying on
`@Transactional`/`@CacheEvict` interceptor ordering to guarantee eviction happens strictly after
commit is implicit — the order those two advisors chain in isn't something to bet correctness on
without checking, and getting it wrong means a concurrent read repopulates the cache with
pre-commit (stale) data moments before the real write lands. An explicit post-commit callback
removes that question entirely.

**Code.**
[`CustomBankLookup.java`](../../../backend/src/main/java/com/finora/service/CustomBankLookup.java)
(new), [`BankManagementService.java`](../../../backend/src/main/java/com/finora/service/BankManagementService.java)
(`resolve`/`listAll`/`search`/`listCustom` now read through it; `createCustom`/`updateCustom`/
`deleteCustom` evict after commit).

**Tests.**
[`AdminBankControllerIT.theListEndpoint_reflectsCreateUpdateAndDelete_immediately_notAfterTheCacheTtl`](../../../backend/src/test/java/com/finora/controller/AdminBankControllerIT.java) —
primes the cache with a GET before each mutation, asserts the very next GET reflects it. Verified
this actually depends on the eviction code (temporarily disabled it, confirmed the test fails with
a stale list, restored it, confirmed green).

**Performance impact.** Not independently re-measured against the original load-testing baseline
(that would need its own tiered run); the shape of the improvement is direct, though — an N-account
page collapses from N `bankRepository` round trips to at most one per cache window, the same
practical effect §5a's own "batch the N+1" remediation candidate targeted, via caching instead of
query restructuring.

**Operational considerations.** None beyond the cache's own bounded memory (500 entries max,
trivial for a table this size) — no new external dependency, no new failure mode. A bug in
eviction would surface as "admin changed a bank, it takes up to 10 minutes to appear" rather than
anything silently wrong, since the TTL bounds the worst case.

### 3. Feature-flag reads — `FeatureFlagService` / `RecurringService`

**Problem.** `RecurringService.detectForUser` — called from 8 sites (every import confirm, every
transaction create/update/delete/bulk-recategorize) — read `featureFlagRepository.isEnabled(...)`
**directly**, bypassing `FeatureFlagService` entirely, on every single call. A database round trip
for a rarely-changing admin-toggled boolean, on some of this codebase's hottest paths.

**Before → after.**
```
Before: RecurringService holds a FeatureFlagRepository directly; detectForUser() queries Postgres
        on every call, 8 call sites deep.
After:  RecurringService holds FeatureFlagService instead (matching what
        FeatureFlagRepository.isEnabled's own doc comment already said was the intended path).
        FeatureFlagService.isEnabled(key) is @Cacheable(sync=true). setEnabled(...) evicts the
        one changed key via a direct CacheManager call inside AfterCommit.run -- same
        explicit-post-commit pattern as CustomBankLookup, for the same reason.
```

**Code.**
[`FeatureFlagService.java`](../../../backend/src/main/java/com/finora/service/FeatureFlagService.java),
[`RecurringService.java`](../../../backend/src/main/java/com/finora/service/RecurringService.java)
(constructor now takes `FeatureFlagService`, not `FeatureFlagRepository`).

**Tests.**
[`FeatureFlagServiceTest.setEnabled_evictsTheCachedValueForThatFlagsKey`](../../../backend/src/test/java/com/finora/service/FeatureFlagServiceTest.java) —
unit-level, mocked `CacheManager`. More importantly,
[`AdminFeatureFlagControllerIT.admin_canDisableAFlag_andRecurringDetectionActuallyStopsRunning`](../../../backend/src/test/java/com/finora/controller/AdminFeatureFlagControllerIT.java) —
an **already-existing** test that, without any change on its own part, became a genuine end-to-end
proof of correct eviction: it reads `/recurring` (populating the cache with `true`), disables the
flag via the admin API, then reads `/recurring` again and asserts the pattern is no longer
detected. A broken eviction would leave the cached `true` in place for the TTL window and this
test would fail. Verified directly (disabled eviction, confirmed this exact test fails, restored
it, confirmed green) rather than assumed.

**Performance impact.** Every one of the 8 `detectForUser` call sites now pays a cached read
instead of a database round trip for this specific check, after the first call per 60-second
window. Not independently load-tested against the original baseline's tiers.

**Operational considerations.** TTL is intentionally shorter here (60s vs. the bank cache's 10
min) — a feature flag gates real behavior, so bounding staleness tighter costs nothing against a
dataset this small, and gives a faster self-heal if an eviction path is ever missed.

### 4. Duplicate-upload protection — synchronous stage path

**Problem.** The audit's own Q3 finding: `ImportConcurrencyLimiter` bounds *concurrency* (max 6
imports in flight) but never deduplicated identical concurrent requests. The async job queue
already closed this for itself (BH-019, `idx_import_jobs_live_content`, V74) — a double-clicked
upload or a lost-response retry returns the *same* job rather than creating two. The synchronous
stage path (`POST /csv/stage`, `/pdf/stage` → `ImportSession`, not `ImportJob`) had no equivalent
at all: the same statement uploaded twice became two fully independent parses and two staged
sessions.

**Before → after.**
```
Before: parseAndStageWithSession/parseAndStagePdfWithSession always parse first, then call
        ImportSessionService.createSession()/createMultiSection() unconditionally. Two concurrent
        requests for the same file both pay for a full parse and both get their own session.
After:  Both methods hash the raw bytes (ContentAddress.hashOf, independent of whether object
        storage is configured) and check ImportSessionService.findLiveSessionByContentHash BEFORE
        parsing. A match short-circuits straight to the existing session's already-staged
        response -- no second parse, no second row. idx_import_sessions_live_content (V79), a
        partial UNIQUE index on (user_id, content_hash) WHERE status='STAGED', is the actual
        correctness guarantee for the rare case where two requests race past that check: the
        loser's INSERT is rejected and GlobalExceptionHandler's existing
        DataIntegrityViolationException handler answers 409, the same shape V74 already
        established.
```

**Why checked before parsing, not just before the write.** The expensive part of a duplicate
upload is the parse itself (PDFBox extraction, table detection, categorization), not the row it
produces. A check placed only at session-creation time would still prevent the duplicate *row*
but would still pay for the duplicate *parse* — missing the actual cost the "double-click" and
"retry while processing" scenarios exist to avoid.

**Why a database constraint, not only an application check.** Exactly V74's own reasoning, applied
to a second table: an application-level check is a read followed by a possible write, so two
genuinely simultaneous requests can both see "no match" before either commits. The constraint is
what decides then, not the check — the check only makes the *common* case (a request that arrives
after the first has already finished) avoid a redundant parse.

**A real bug this surfaced and fixed along the way.** `ImportSessionService.storeContent` only set
`content_hash` when an object-storage address was returned — meaning a deployment with no storage
provider configured (the shape this codebase's own test suite runs under) left every session's
`content_hash` null, which would have made this entire feature silently inert on exactly that
deployment shape. Fixed by computing the hash directly (`ContentAddress.hashOf`) in the
no-storage-provider branch too — a `StatementImport`'s equivalent field is untouched, since it
doesn't participate in this feature.

**What this does not fully close.** A request that arrives while the *first* request is still
mid-parse (not yet a session row) won't be caught by the app-level pre-check, since there's
nothing to match against yet — only the database constraint's window matters there, and by then
both requests have already paid for their own parse. The 100-user tier of the original load-testing
baseline measured stage/confirm at 10–17ms median, so this window is narrow for ordinary statement
sizes; it widens for large PDFs or under degraded conditions. Closing it fully would mean an
early "claim" row created before parsing starts (the shape `ImportJob`'s own status machine already
has) — a larger structural change to the synchronous path's lifecycle, not undertaken here. Recorded
as a known, bounded limitation rather than left implicit.

**Expired-but-unswept sessions are handled explicitly.** A partial index predicate must be
immutable, so it can't express "and not expired." `findLiveSessionByContentHash` deletes an
expired match on the way past instead of treating it as a block, so a genuinely new upload of a
statement whose earlier session merely expired (not yet reached by the periodic sweep) succeeds
rather than failing with a false duplicate.

**Code.**
[`V79__import_session_stage_idempotency.sql`](../../../backend/src/main/resources/db/migration/V79__import_session_stage_idempotency.sql) (new),
[`ImportSessionRepository.java`](../../../backend/src/main/java/com/finora/repository/ImportSessionRepository.java)
(new query method),
[`ImportSessionService.findLiveSessionByContentHash`](../../../backend/src/main/java/com/finora/imports/ImportSessionService.java),
[`ImportService.java`](../../../backend/src/main/java/com/finora/imports/ImportService.java)
(both stage methods, plus response-reconstruction helpers).

**Tests.**
[`V79ImportSessionStageIdempotencyMigrationIT`](../../../backend/src/test/java/com/finora/imports/migration/V79ImportSessionStageIdempotencyMigrationIT.java) —
same shape as V74's own migration test: builds the pre-V79 schema by hand, seeds adversarial rows,
proves cleanup and the resulting constraint (5 tests).
[`ImportStageIdempotencyIT`](../../../backend/src/test/java/com/finora/controller/ImportStageIdempotencyIT.java) —
real HTTP round trips proving same-file-twice returns one session (not two), per-user scoping
(two different users uploading identical bytes don't collide), different content never triggers
the dedup path, and a direct two-call `createSession` race proving the raw constraint
independent of the app-level check (4 tests). `ImportSessionServiceTest` gained 3 unit tests for
`findLiveSessionByContentHash` itself. Every test that asserted `verifyNoInteractions
(importSessionService)` for a rejected upload was updated to verify `createSession`/
`createMultiSection` specifically instead, since the pre-check is now a real, expected
interaction even for content that's ultimately rejected.

Each new/changed behavior in this section was verified by temporarily reverting the fix and
confirming the relevant test fails before restoring it — not assumed from the test passing once.

**Performance impact.** Prevents wasted parses under the "double-click" / "retry-after-completion"
shape specifically; not a general throughput change, since it doesn't touch the measured
connection-pool bottleneck from the earlier baseline. No re-measurement against that baseline was
run for this specific change, since the mechanism it improves (redundant parse avoidance) isn't
what that baseline's tiers were measuring.

**Operational considerations.** A duplicate upload that races past the app-level check now
surfaces as `409 CONFLICT` instead of two silently-independent sessions — a behavior change a
client integration should be aware of, though the common case (sequential double-click) never
reaches this path at all.

## What stays out of scope

Per the earlier audit and unchanged by this pass:

- **Bloom filters** — no lookup surface exists with the "high volume, mostly invalid keys" shape
  they solve.
- **Hot-key handling** — no Redis, one instance, no shared/globally-hot resource in this product's
  domain.
- No pattern here was introduced because it exists in the literature; each was introduced because
  a specific, already-measured or already-diagnosed problem pointed at it.

## Consequences

- Two new runtime dependencies (`spring-boot-starter-cache`, `caffeine`) — both from Spring Boot's
  own managed BOM, no version pinned by hand.
- A new migration (V79) and a new partial unique index on `import_sessions` — mirrors V74's
  already-proven shape, so no new class of migration risk.
- `RecurringService`'s constructor signature changed (`FeatureFlagRepository` → `FeatureFlagService`);
  `BankManagementService`'s constructor gained a `CustomBankLookup` dependency. Both are
  internal-only changes — no public API/DTO shape changed.
- `AdminDiagnosticsService`'s existing "caching configured" diagnostic now reports `true`.

## How this is held

| Test | Property |
|---|---|
| `AdminBankControllerIT.theListEndpoint_reflectsCreateUpdateAndDelete_immediately_notAfterTheCacheTtl` | Bank-cache eviction actually fires post-commit |
| `AdminFeatureFlagControllerIT.admin_canDisableAFlag_andRecurringDetectionActuallyStopsRunning` | Feature-flag cache eviction actually fires post-commit (pre-existing test, now also proves this) |
| `FeatureFlagServiceTest.setEnabled_evictsTheCachedValueForThatFlagsKey` | Eviction targets the correct cache key |
| `V79ImportSessionStageIdempotencyMigrationIT` (5 tests) | The migration's cleanup and resulting constraint, against an adversarial pre-existing schema |
| `ImportStageIdempotencyIT` (4 tests) | Same-file-twice returns one session; per-user scoping; the raw database constraint independent of the app-level check |
| `ImportSessionServiceTest` (+3 tests) | `findLiveSessionByContentHash`'s found/expired/not-found branches |

## Related

- [`distributed-resilience-patterns-audit-2026-08-14.md`](../../investigations/performance/distributed-resilience-patterns-audit-2026-08-14.md) —
  the audit this ADR responds to; still the authoritative record for why Bloom filters/hot-key
  handling/Redis remain out of scope.
- [`load-testing-baseline-2026-08-14.md`](../../investigations/performance/load-testing-baseline-2026-08-14.md),
  [`hikaricp-bottleneck-investigation-2026-08-14.md`](../../investigations/performance/hikaricp-bottleneck-investigation-2026-08-14.md) —
  the measured evidence naming the bank/feature-flag N+1s as real remediation candidates
  (`project-plan-v1.0.md` §5a item 4).
