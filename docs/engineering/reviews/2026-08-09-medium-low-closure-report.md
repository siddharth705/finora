# Bug Hunt — Medium/Low Remediation Closure Report

**Status:** closes the **Medium and Low** tranche assigned after Round 1. Not a claim that the
repository is defect-free, and explicitly not a claim about the performance cluster — see §2.
**Branch:** `fix/bug-hunt-medium-low`
**Base:** `origin/main` @ `44406e6`
**Round 1 report:** [`2026-08-09-bug-hunt-closure-report.md`](2026-08-09-bug-hunt-closure-report.md)
**Hunt report:** [`2026-08-08-repo-wide-bug-hunt.md`](2026-08-08-repo-wide-bug-hunt.md)

> **1877 backend tests pass. That is not the claim being made here.**
> It says this branch did not break what the suite covers. What each finding is actually *proven*
> to have fixed is in §3, one row at a time, and [§6](#6-what-this-pass-did-not-cover) is the part
> that matters most.

This report keeps Round 1's two grades and its rule:

- **CLOSED — VERIFIED** — the broken behaviour was demonstrated, then demonstrated gone. Either the
  break was observed directly (a failing test written before the fix, a running system probed) or
  the regression test was mutation-checked against the restored defect.
- **CLOSED — REVIEWED** — root cause established, fix understood, suite green, but nobody watched
  it break.

**Do not upgrade a REVIEWED item because the suite is green** — a green suite is already part of
what REVIEWED means.

---

## 1. At a glance

| ID | Class | Disposition | Grade |
|---|---|---|---|
| **BH-053** | Test gap | Race reproduced; propagation contract pinned | **CLOSED — VERIFIED** (test only; the race itself stays OPEN) |
| **BH-036** | Security (latent) | CORS allows and exposes `X-Request-Id` | **CLOSED — VERIFIED** |
| **BH-032** | Security (minor) | DB-password check widened, measured first | **CLOSED — VERIFIED** |
| **BH-037** | Security (dev) | Postgres bound to loopback | **CLOSED — VERIFIED** (observed on a live stack) |
| **BH-029** | Design | Parser format persisted on `import_jobs` | **CLOSED — VERIFIED** |
| **BH-018** | Design | Store moved outside the transaction | **CLOSED — VERIFIED** (transaction half only) |
| **BH-044** | Privacy / storage | Emission fixed; **retention deliberately not** | **CLOSED — VERIFIED** (emission half only) |
| **(new) BH-059** | Financial correctness | Filename truncation stripped the extension | **CLOSED — VERIFIED at the point of failure**; chain to reimport **REVIEWED** |
| BH-042 · BH-043 · BH-045 | Performance | **DESCOPED mid-run** — see §2 | **NOT ATTEMPTED** |

Seven assigned findings closed, all VERIFIED. One new finding found and fixed. Three descoped.

---

## 2. Scope, and why three findings are missing from it

**BH-042 (remainder), BH-043 and BH-045 were removed from this pass mid-run by the repo owner,
not left undone by oversight.** The distinction matters for the record, so it is stated plainly:

A separate live engineering session owns the performance cluster and had already created
`perf/bh-042-measurement` off this same base. Continuing here would have produced duplicate
measurement work and a likely conflict on the least mechanical part of the sweep. The owner chose
to let that session keep the cluster.

**This branch contains no Tier 3 work at all** — nothing was written and then abandoned, so there
is nothing on it to discard or double-apply. Verified by file: no diff touches
`ImportConcurrencyLimiter`, `R2StatementStorage`, or any of `DashboardService`, `InsightsService`,
`AnalyticsService`, `WorkspaceDashboardService`, `RecurringService`, `ReportService`,
`RelationshipService`.

**One overlap the other session should know about.** `ReconciliationService` is *also* on BH-042's
list of seven full-history loaders (`ReconciliationService:74`), and BH-044 required changing that
file. The change is confined to the audit-emission block at the end of `reconcile()` plus one added
parameter. **`reconcileForUser`'s `transactionRepository.findByUserId(userId)` — the actual BH-042
defect — is untouched.** Same file, different region: a textual conflict is possible, a semantic
one is not.

Also out of scope by instruction and untouched: **BH-017** and **BH-025** (blocked on product
decisions), **BH-046** (open PR #67), and everything under `imports/pdf/`, `imports/ocr/` and
ground-truth code (owned by a parallel session).

---

## 3. Closed — with evidence

### BH-053 — the documented check-then-act race in merchant learning had no test

**Commit:** `dd3ae28` · **Grade: CLOSED — VERIFIED** (as a *test* deliverable)

`MerchantLearningService.confirm` carries the most careful defect documentation in the repository:
a class comment and a Javadoc that describe a check-then-act race against V7's
`UNIQUE(user_id, merchant_id, category_id)` and explain why `Propagation.REQUIRES_NEW` is not the
fix. Nothing asserted either. A comment is not a guard.

**The race, reproduced.** `ConfidenceEngine.topCategory` is called between `confirm()`'s read and
its write, so spying it parks one caller exactly inside the check-then-act window while a second
runs through untouched and commits. The parked caller then fails on the real constraint —
observed in the run log, not inferred:

```
ERROR: duplicate key value violates unique constraint
       "merchant_category_learning_user_id_merchant_id_category_id_key"
```

A `CyclicBarrier` on the two calls would have been racing the race; it would pass or fail on
scheduler luck.

**The propagation guards, mutation-checked** by applying
`@Transactional(propagation = REQUIRES_NEW)` to `confirm()`:

| Test | Failure under the mutation |
|---|---|
| `confirmSeesParentRowsTheCallerHasNotCommittedYet` | `ERROR: insert or update on table "merchant_category_learning" violates foreign key constraint "merchant_category_learning_merchant_id_fkey"` |
| `aCallerRollbackAfterConfirmTakesTheLearningAndItsAuditRowWithIt` | `[no learning may survive the transaction whose evidence it came from] Expecting empty but was: [MerchantCategoryLearning@2abe66b4]` |

Exactly the two failures the Javadoc predicts, on the right assertions.

**What did not fail under the mutation, recorded because it is the honest half:** the race test
itself kept passing. Its fixture is committed, so it never exercises the parent-visibility property
the other two exist for. Three tests, two of which catch the wrong fix by different mechanisms.

**The race itself remains OPEN.** No production code changed. The first test asserts a defect on
purpose and says so in its own comment; closing the race means rewriting it to assert the loser's
confirmation is kept.

---

### BH-036 — CORS forbade the correlation header the app advertises

**Commit:** `20996de` · **Grade: CLOSED — VERIFIED**

Reproduced first, over real HTTP through the real Spring Security CORS processor:

```
preflight with Access-Control-Request-Headers: X-Request-Id
  expected: 200 OK
   but was: 403 FORBIDDEN
```

**A second half the finding did not name, found while writing the test for the half it did.**
`CorrelationIdFilter` sets `X-Request-Id` on every response "so a client can report 'this is the
request that failed' without needing to parse logs" — and a cross-origin response header is
invisible to JavaScript unless it appears in `Access-Control-Expose-Headers`, which nothing set:

```
Access-Control-Expose-Headers on a 401
  Expecting ListN: [] to contain: ["x-request-id"]
```

That direction has no preflight to fail loudly. It reads as `null` in a browser and works perfectly
under curl — the same shape as the bug already recorded in `CorsConfig`'s own class comment.

Both pass after the change. Tested over the wire rather than against the `CorsConfiguration` bean,
because `CorsConfig`'s comment records a prior bug where the bean was correct and what a browser
got back was not. The expose-headers case deliberately drives an **unauthenticated** request: the
response whose ID a user is asked to quote is a failing one.

Both directions were latent — no client in any of the three apps sends or reads the header (grep).

---

### BH-032 — the DB password check only rejected one literal

**Commit:** `da9c6f1` · **Grade: CLOSED — VERIFIED**

Five things reached production through `DEFAULT_DB_PASSWORD.equals(dbPassword)`, each demonstrated
failing before the change and passing after:

| Value | Why it got through |
|---|---|
| `"Finora"` / `"FINORA"` | equality is case-sensitive |
| `"  finora  "` | Spring does not trim property values |
| `""` / `"   "` / `null` | **no placeholder in it at all** |
| `"change-me-…"` | self-announcing; the JWT scan already caught this class |
| `"postgres"` / `"root"` | what an operator types when not generating |

The blank case is the easiest to miss. `${DB_PASSWORD:finora}` substitutes the default only when
the variable is *unset*, so `DB_PASSWORD=` resolves to the empty string and `"finora".equals("")`
is false — while the guard's own message claimed to cover "unset".

**The part worth reading is what was measured and then deliberately not built.**

BH-033 is ACCEPTED, not fixed: the marker scan can reject a legitimate secret. Its closure names
the condition that would reopen it — applying the check *"to a value whose alphabet the operator
does not choose"*. A Railway-generated database password is exactly that, and unlike `JWT_SECRET`
the operator cannot regenerate it to get past a false rejection. So the widening was measured
first, same method as BH-033, **2,000,000 trials per alphabet** against
`SecureRandom.getInstanceStrong()`:

| Alphabet | Markers as substrings | Weak words as substrings |
|---|---:|---:|
| base62 × 32 (Railway-shaped) | **1** (5.0 × 10⁻⁷, `dummy`) | **73** (3.7 × 10⁻⁵, almost all `root`) |
| hex × 32 | **0** | **0** |
| base64url × 43 | **2** (1.0 × 10⁻⁶, `dummy`) | **65** (3.3 × 10⁻⁵) |

Positive control: both matchers fire on a value that should match.

The marker scan is the same order BH-033 already accepted, and stays. The weak-password list as a
substring scan is **seventy times worse** — essentially all of it `root` at four characters — and
would refuse roughly one correct deployment in twenty-seven thousand while buying nothing: an
operator who picks a weak database password types `root`, they do not generate a value containing
it. **So markers are matched as substrings and the weak list by equality.**

That split is the entire product of the measurement. *The substring version is what would have been
written without it.* A test pins it: a generated password that merely **contains** `root` must be
accepted, and it fails on a one-word change to the implementation.

`deployment-guide.md` updated in both places it described the old behaviour, including its existing
hex-vs-base64 measurement section, which now records why `DB_PASSWORD` is the case where "generate
another one" is not available.

---

### BH-037 — Postgres published on all interfaces in the dev stack

**Commit:** `9336c05` · **Grade: CLOSED — VERIFIED (runtime observation)**

Round 1 lists *"No runtime observation. No server, no browser, no real deploy"* among the things
the hunt never had. This finding now has one. While working it, the repository's own dev stack was
already running on the machine:

```
finora-postgres-1   postgres:16-alpine   0.0.0.0:5432->5432/tcp

$ nc -z -v 192.168.1.101 5432
Connection to 192.168.1.101 port 5432 [tcp/postgresql] succeeded!
```

That is the machine's LAN address, not loopback, with `finora/finora` three lines above it in the
compose file.

The fix was verified the same way rather than from the Docker docs — two throwaway containers on
free ports, so the running stack was left alone:

| Mapping | Listener | From the LAN address |
|---|---|---|
| `-p 15432:5432` | `TCP *:15432` | **succeeded** |
| `-p 127.0.0.1:15433:5432` | `TCP 127.0.0.1:15433` | **Connection refused** |

`docker compose config` on the committed file resolves the mapping to `host_ip: 127.0.0.1`.

**Nothing loses access.** The backend container reaches Postgres over the compose network by
service name (`DB_HOST: postgres`), which does not involve a published port at all.

**The backend's own 8080 is deliberately not narrowed**, and the asymmetry is documented in the
file rather than left as an inconsistency: `mobile-setup.md` tells you to point a real device at
that port over the LAN, and the Android emulator reaches it via `10.0.2.2`. Postgres has no
equivalent caller, which is what made it free and 8080 a trade.

**No automated guard**, and that is a deliberate refusal rather than an omission. Infrastructure
rules are Phase 4 of the Repository Guardian and explicitly *"not started"*; that document says
they need a new script in `scripts/` with a self-test, following the tiered BLOCK/WARN precedent.
Building that category for one dev-only Low is a bigger commitment than this finding earns, and
claiming a guard that does not exist is worse than saying it does not.

---

### BH-029 — parser selection for queued jobs was filename-only

**Commit:** `09bbf3b` · **Grade: CLOSED — VERIFIED**

`ImportJobService.formatOf(fileName)` was called at upload, to choose what `StatementUpload`
validated against, and again in `ImportJobWorker.stage()` minutes later, to choose the parser. The
class comment called this "decided once". It was decided *zero* times: the format was never a fact
anywhere, only a function re-evaluated against a stored string, and the two call sites agreed
because they read the same string through the same function.

`statement_imports.source_format` (V36) exists for precisely this reason on the confirmed import —
added after re-inferring a format from a filename routed a PDF's bytes through `CsvParser`. The job
that produces that row had no equivalent.

**V75** adds `import_jobs.source_format`, `NOT NULL`, backfilled with the same rule the code used.
Deliberately *not* nullable-with-a-filename-fallback: that shape leaves the old derivation alive on
a branch nothing exercises.

**The test can tell the two designs apart**, which a test that uploads a `.csv` as CSV and a `.pdf`
as PDF cannot. It stores a row whose `file_name` and `source_format` **disagree** — CSV bytes,
`"statement.csv"`, `source_format = PDF` — the only arrangement where the implementations answer
differently. Mutation-checked by restoring `formatOf(job.getFileName())`:

```
theWorkerParsesByTheStoredFormatEvenWhenTheFilenameDisagrees
  -> [PDF was recorded, so the PDF parser must have been given CSV bytes and failed.
      COMPLETED means the worker went back to reading the filename]
```

A control runs identical bytes and an identical filename with CSV recorded and asserts the job
**completes**, so the failure above cannot be blamed on a broken fixture.

**A first draft that was wrong, recorded:** the assertion expected `FAILED` and got `QUEUED`. That
is the worker being right, not the test — one parse failure is retryable and dead-lettering waits
for `MAX_ATTEMPTS`. It now asserts "did not import, was actually attempted, recorded an error", so
it does not break the day the retry policy moves.

Changing `enqueue()`'s and `accept()`'s signatures rather than adding an overload was the point:
the compiler named all eight call sites, and a filename-derived one could not survive by being
missed.

---

### BH-059 *(new — not in the hunt report)* — filename truncation silently changed a statement's format

**Commit:** `ce56615` · **Grade: CLOSED — VERIFIED at the point of failure; the chain to reimport is REVIEWED**

BH-029 says the stored `sourceFormat` "is derived the same way and is what reimport routes on — so
a statement whose name lost its extension somewhere re-imports through the wrong parser", and
records it as a hypothetical. **It is not hypothetical. This repository loses the extension itself,
on a path every synchronous upload takes.**

`StatementUpload.safeFileName` bounded length with `name.substring(0, MAX_FILE_NAME_LENGTH)`.
`ImportService:788` derives `statement_imports.source_format` from the name it returns
(`endsWith(".pdf") ? "PDF" : "CSV"`), and `parseAndStageAnyFormat` routes `reimport()` on that
column. So a PDF whose filename exceeds 120 characters — net-banking exports carrying a bank name,
an account number and a date range get there — is recorded as **CSV**, and re-importing it hands a
PDF's bytes to `CsvParser`. That is the exact regression V36's column was added to prevent, arriving
through the length bound rather than through the routing it was watching.

It stayed invisible because the CSV direction fails safe: a truncated `.csv` lands in the same
default branch as a name with no extension. The existing `boundsTheLength` test used a `.csv`
fixture and asserted only length, so it passed either way.

**Demonstrated before fixing**, a 200-character `.pdf` name through the real method:

```
[the extension is what statement_imports.source_format is derived from]
Expecting actual: ... to end with: ".pdf"
```

Truncation now trims the stem and keeps the suffix. `MAX_EXTENSION_LENGTH` stops that becoming a
way around the bound; two negative tests cover a long name with no extension and a 60-character
tail that must not be mistaken for one.

**What is proven and what is not.** Proven: the extension used to be dropped and no longer is.
**Not proven end to end** — the chain from a >120-character PDF filename through `confirm()` to a
reimport parsed as CSV is argued from reading three call sites and demonstrated only at the first
of them.

**Deliberately not done here:** threading the format through `ImportSession` into `confirm()` so
the derivation stops being a derivation. That is the more thorough answer and needs a new column on
`import_sessions` plus a change to the confirm path. Fixing the truncation removes the only
mechanism found that makes the derivation wrong today; the derivation itself remains one.

---

### BH-018 — `accept()` documented an ordering it did not implement

**Commit:** `aa50631` · **Grade: CLOSED — VERIFIED (transaction half); memory half NOT ADDRESSED**

The class comment states: *"**Store the bytes** — outside the transaction… holding a database
transaction open across a network upload would tie up a connection from a pool capped at 10."* The
method was `@Transactional` over its whole body and stored inside it. An inline comment conceded
this and argued it was harmless because the store touches no database — true only while no JDBC
statement has been issued first, which rests on Hibernate's delayed connection acquisition, a
property of every caller above the method and of Hibernate's configuration.

Both halves of the finding were offered as the fix. **The claim is the correct one, so the code
changed.**

Mutation-checked by restoring `@Transactional` on `accept()`:

```
theUploadToObjectStorageRunsWithNoTransactionOpen
  -> [class comment, step 2: the bytes are stored OUTSIDE the transaction]
     Expecting value to be false but was true
```

**The test asserts the ordering, not its consequence.** Counting pool connections measures the
symptom and would have passed against the old code too — delayed acquisition means none had been
taken on the path the endpoint uses. The defect was never that a connection was held *today*; it
was that whether one was held depended on configuration and callers, with nothing saying so.

**A wrong fix that was written first, and is recorded because the next person will reach for the
same shape.** Extracting an `@Transactional` method and calling it on `this` bypasses Spring's
proxy and applies no transaction at all — the dedup check and the enqueue silently stop being
atomic, and `isSynchronizationActive()` becomes false, so the post-commit worker nudge never
registers and every upload waits up to 15s for the next poll. It compiles, reads correctly, and
breaks nothing the suite covers. A `TransactionTemplate` is used instead.

**What this does not guarantee**, asserted as its own test rather than left implied: a *caller*
that wraps `accept()` in its own transaction still holds one across the upload. Removing
`@Transactional` stops this method opening one; it cannot stop someone above it. No caller does
today. The class comment now states the limit in the same place it makes the claim.

**BH-018's second half is untouched** — `file.getBytes()` materialising the whole upload on the
heap. That is BH-045 at a different layer and belongs with that measurement, which is descoped
(§2).

---

### BH-044 — a `RECONCILIATION_RUN` audit row per write

**Commit:** `7d4517c` · **Grade: CLOSED — VERIFIED (emission half). Retention half NOT IMPLEMENTED, by instruction and by judgement.**

Reconciliation runs synchronously after every transaction create, update and delete, and every run
wrote an audit row — so ordinary ledger editing wrote two rows per action, one of which said
nothing happened, into a table with no retention, no partitioning and no archival.

**Measured** through the real `TransactionService` against real Postgres:

| | all-zero `RECONCILIATION_RUN` rows |
|---|---:|
| 12 ordinary creates, before | **12** |
| 12 ordinary creates, after | **0** |

The fixture uses distinct amounts and descriptions on purpose — identical rows would pair as
duplicates and every run *would* have reclassified something, so the benchmark would measure the
case this change does not touch and pass while proving nothing.

Mutation-checked by restoring unconditional emission — both tests fail, and the second is the more
interesting:

```
ordinaryLedgerEditingWritesNoReconciliationRunRows
  -> Expecting empty but was: [AuditLog@45c5a61, ...]

aRunThatReclassifiesSomethingIsStillRecorded
  -> allSatisfy(recordedBecause=reclassified) fails: the quiet first create
     contributes a second row that says nothing
```

A run keeps its record when it **reclassified** something, when it was **slow**, or when the caller
asks **unconditionally**. `recordedBecause=reclassified|slow|scope` names which.

**The third condition is the part worth reading, and it came from a broken test.** The first
version had only the first two, and it broke
`MultiSectionReconciliationCostIT.theCandidateSetExcludesHistoryOutsideTheWindow` with
*"no RECONCILIATION_RUN was audited"*. That test reads `candidatesLoaded`, `windowFrom` and
`windowTo` **off this row** to prove BH-041's candidate window narrows anything. **The audit row is
not only a trail — it is the only telemetry reconciliation scope has**, and a failing test was the
only thing that said so. So `reconcileForImport` records unconditionally: an import is one event
per uploaded statement rather than one per ledger edit, so it was never the volume BH-044 named,
and it is the sole producer of those three fields.

Recorded rather than quietly patched, because the cheaper fix — adjusting that fixture — would have
deleted BH-041's evidence and left a suite that looked green.

**A documented decision is being reversed, not overlooked.** The service said *"Recorded even when
every counter is 0: 'ran and found nothing new' is itself the answer to 'when did this last run'"*,
and `ReconciliationServiceTest` asserted it. Since reconciliation is synchronous and unconditional
after each of those actions, an all-zero row lands at the same instant as the `TRANSACTION_*` row
that triggered it and carries no fact that row does not — **the trigger *is* the answer.** That
test is inverted rather than deleted, and its comment says what changed and why.

#### The retention seam — open, and marked as open

`AuditService` now carries an explicit `SEAM` section. It is **not** a TODO; it states:

- what `audit_logs` holds that makes retention a privacy question and not a storage one
  (`TRANSACTION_DELETED` carries `amount` and the full `description`; `BUDGET_UPSERTED` carries the
  limit — a second, indefinitely-retained copy of ledger content readable by any admin with
  `AUDIT_VIEW`);
- the three things that must be decided before a sweep can be written: the compliance retention
  period, whether data-subject deletion reaches these rows, and whether the answer is truncation,
  **redaction of `metadata`** (the only option that keeps the trail's own purpose), or off-database
  archival;
- **where** a sweep belongs when that is answered — beside `record()`, which is already the single
  write point — and why it must **not** be a bare `deleteBy…` on the repository, since the trail is
  append-only by design.

Inventing a window here would be the same mistake as guessing a statement-retention period, which
is why **BH-017 is still deferred**.

---

## 4. Verification results

Run against this branch with **no concurrent Maven** sharing `backend/target/`.

| Suite | Result |
|---|---|
| **Backend** | **1877 tests, 0 failures, 0 errors** — `./mvnw verify` BUILD SUCCESS (359 classes analysed) |
| Frontend | **Not re-run.** No file under `frontend/` is touched by this branch |
| Admin portal | **Not re-run.** No file under `admin-portal/` is touched |
| Mobile | **Not re-run.** No file under `mobile/` is touched |
| E2E | **Not re-run.** No file under `e2e/` is touched |
| Pre-commit guards | Pass on every commit (`check-imports`: 644 files, 0 problems; executable-bit and fixture-hygiene checks clean) |

**25 tests added or changed by this branch**, across 8 files:

| Class | Cases |
|---|---:|
| `MerchantLearningConfirmRaceIT` *(new)* | 3 |
| `CorrelationIdCorsIT` *(new)* | 3 |
| `ProductionConfigValidatorTest` | +6 |
| `ImportJobSourceFormatIT` *(new)* | 4 |
| `StatementUploadTest` | +3 |
| `ImportJobStoreOutsideTransactionIT` *(new)* | 3 |
| `ReconciliationServiceTest` | +1 (and one inverted) |
| `ReconciliationAuditVolumeIT` *(new)* | 2 |

**An arithmetic caveat, stated rather than smoothed over.** The brief quoted a baseline of 1837.
1877 − 1837 = 40, which does not equal the 25 above. The difference is that `origin/main` moved
after that baseline was quoted (PRs #69, #70, #71 merged). **I did not re-run the suite at the base
commit to confirm this**, because doing so requires a second Maven build and this repository's own
rule forbids two builds sharing `backend/target/`. The number that is directly verified is
**1877 / 0 failures on this branch**.

---

## 5. Cross-cutting notes

- **Every closure here is VERIFIED.** That is a property of the tranche, not of the effort: these
  are small, well-bounded findings where reproducing the break was cheap. Round 1's 22 REVIEWED
  closures were mostly larger changes where it was not.
- **Two measurements changed a design decision rather than confirming one.** BH-032's false-
  rejection run turned a substring scan into an equality check; BH-044's broken test turned a
  two-condition emission rule into a three-condition one. Both are cases of a diagnostic earning
  its place by proving a proposed capability unnecessary or wrong.
- **Three wrong first drafts are recorded in commit messages** rather than silently corrected: the
  `FAILED`-vs-`QUEUED` assertion (BH-029), the self-invocation `@Transactional` (BH-018), and the
  two-condition emission rule (BH-044). The last two would have shipped green.

---

## 6. What this pass did not cover

The most important section. Read it before treating any of the above as broader than it is.

### Not attempted

- **BH-042 (remainder), BH-043, BH-045.** Descoped mid-run by the owner in favour of
  `perf/bh-042-measurement` (§2). **No measurement was taken and no conclusion about them is
  offered here** — in particular, nothing in this report should be read as evidence that any of
  them is or is not worth fixing.
- **BH-017, BH-025, BH-046** — out of scope by instruction.
- **`imports/pdf/`, `imports/ocr/`, ground-truth code** — owned by a parallel session, untouched.

### Closed, but only partly

- **BH-053's race is still OPEN.** Only the test landed. `confirm()` still has the check-then-act,
  and a lost race still takes the caller's transaction down with it.
- **BH-018's memory half is untouched.** `file.getBytes()` still materialises the whole upload.
- **BH-044's retention half is untouched**, deliberately, and is a blocking product decision.
- **BH-059's derivation is still a derivation.** `statement_imports.source_format` is still
  computed from a filename at write time; only the mechanism that corrupted the filename is fixed.

### Proven weakly, or not at the level it matters

- **BH-059's full chain was never executed.** A >120-character PDF filename was never carried
  through `confirm()` to an actual reimport. The truncation is proven; the consequence is reasoned.
- **BH-018's guarantee is conditional on callers.** A transactional caller would still hold a
  connection across the upload. Asserted as a test so the limit is on the record, but it is a limit.
- **BH-037 has no automated guard**, by choice (§3). Nothing prevents the port mapping regressing.
- **V75 has never been applied to a non-test database.** Testcontainers proves it runs against a
  schema built by the preceding migrations; it has not met production data. Its backfill is a data
  mutation, and the same caveat Round 1 recorded for V73/V74 applies unchanged.
- **BH-032's measurement models a generated password, not Railway's actual generator.** Three
  plausible alphabets were sampled 2,000,000 times each. Railway's real alphabet and length were
  not read from Railway; if it generates something outside those three shapes, the rate is
  unmeasured.

### Structural gaps this pass did not close

- **No load or concurrency testing**, beyond BH-053's two-thread race. Unchanged from Round 1.
- **The BH-058 class of test defect was still not swept.** Round 1 flagged that other tests may
  share its table-wide assumption and that nobody had looked. Nobody has looked yet.
- **`ReconciliationService`'s own audit row is load-bearing telemetry** and nothing says so outside
  a code comment and one test. That was discovered by breaking it. Whether other observability in
  this repository is similarly carried by incidental audit rows is **not known and was not
  investigated.**
