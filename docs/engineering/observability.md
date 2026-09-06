# Observability

How Finora reports failures, what must never leave the platform, and how to extend it safely.

This document exists because the constraints below are not obvious from the code alone, and because
the cost of getting them wrong is customer financial data in a third-party system.

---

## 1. Why backend monitoring differs from frontend monitoring

The three client apps (`frontend`, `admin-portal`, `mobile`) have had Sentry since their own
`monitoring.ts` landed. Their leak surface is well understood: request URLs carry account and
transaction identifiers, the ledger search sends whatever the user typed as a query parameter, and a
registration body holds an email, a phone number and a plaintext password.

**The backend has all of that plus one more, and it is the one with no client equivalent: the
exception message itself.**

This service parses bank statements. Its parsers quote the input that failed:

```
Could not parse amount '1,23,456.78' in narration 'UPI/ACME STORES/paid'
Statement row 42 for account 50100234567890 failed validation
```

A stack trace is safe — it names classes, methods and line numbers. **The message attached to it very
often is not.** Any monitoring integration added from here must assume exception messages are
sensitive until explicitly sanitized.

There is precedent for treating this seriously: a real customer's name and 14-digit account number
once reached a source comment in this repository (see `scripts/check-fixture-hygiene.sh`). A crash
reporter is a second route to the same failure, except the data leaves the building rather than
sitting in git.

---

## 2. Principles

These apply to **any** future telemetry integration, not just Sentry.

| Principle | What it means in practice |
|---|---|
| **Privacy first** | The question is not "is this useful?" but "can this carry customer data?" If yes, it does not leave. |
| **Allowlist, never denylist** | Outbound payloads are *rebuilt* from known-safe fields, not stripped of known-bad ones. A field a future SDK version adds is then absent by construction. |
| **Explicit configuration** | Every safety-relevant option is stated, never inherited. An inherited default is one nobody reviewed. |
| **Secure defaults** | Off unless a DSN is configured. Development and CI transmit nothing, with no extra setup. |
| **Testable sanitization** | Scrubbing lives in pure, static, independently testable functions — never inside a config lambda. |
| **Fail-safe behaviour** | This code runs in the error path. Throwing there replaces a recorded failure with an unrecorded one. |
| **Independent verification** | Configuration is code. Security settings carry dedicated tests. |

---

## 3. What must never leave the platform

Non-negotiable. If you cannot prove a field is free of these, it does not ship.

- Statement contents — narrations, merchant names, amounts, balances, dates as they appear on a document
- Account numbers, card fragments, IFSC codes, reference numbers
- Personal identifiers — names, email addresses, phone numbers, addresses
- Credentials — passwords, OTPs, JWTs, refresh tokens, API keys, session cookies
- Request bodies and headers, in any form
- Free-text the user typed — most sharply the ledger search term

### What *is* allowed, and why

- **Stack frames** — class, method, line. Structural, not data.
- **Exception type** — `StatementParseException` says what broke without saying what it broke on.
- **Redacted messages** — see §4.
- **Allowlisted tags** — see §5.

---

## 4. The sanitization strategy

`SentryScrubber` is the single implementation. It is static and pure so it can be tested, because
**scrubbing that silently stops working looks exactly like scrubbing that works**: no error, no
failing test, events still flowing.

| Surface | Treatment |
|---|---|
| Exception message | UUIDs → `{id}`, 4+ digit runs → `{n}`, quoted runs → `'{redacted}'`, emails → `{email}`, phones → `{phone}` |
| URL | Query string **dropped entirely**; path identifiers redacted |
| Request | **Rebuilt** — method and scrubbed URL only. Headers, cookies, body, query, env absent by construction |
| Breadcrumbs (log/console) | **Dropped wholesale** |
| Breadcrumbs (http) | **Rebuilt** — method, status, scrubbed URL only |
| User, server name | Removed |
| Extras | Cleared |
| Tags | **Allowlisted** — see §5 |

### Two decisions worth understanding

**Redaction is deliberately aggressive, and it costs detail.** Quoted content is sometimes a field
name or an enum constant, not customer data, and that context is lost. This is accepted because the
alternative is deciding *per message* whether a quoted value came from a statement — which is the
"a filter has to be right every time" position this codebase avoids. Dropping only has to be right
once.

**Query strings are dropped rather than filtered per-parameter,** for the same reason.

---

## 5. Tags: the one structured channel

Extras are cleared wholesale, so tags are the only way to attach context an operator can actually
search. That makes them the one channel that must be *allowlisted rather than open* —
`ALLOWED_TAG_KEYS` in `SentryScrubber`:

`worker` · `phase` · `outcome` · `jobKind` · `jobId` · `correlationId`

**Every value under these keys must be an internal identifier or a bounded enum. Never customer
data.**

### Isn't `jobId` a UUID, which §4 says gets redacted?

No contradiction, and the distinction is the point. In a *message*, a UUID arrives embedded in free
text with unknown provenance — it could be a statement id sitting next to an account number. As a
*tag*, it is placed by code that knows exactly what it is: a queue row's own id, which identifies a
row in our database and nothing about the person it belongs to.

---

## 6. How to extend the scrubber safely

1. **Start from "what does this carry?", not "what do I want?"** If a new field could ever hold a
   narration, an amount or an identifier, the answer is no.
2. **Rebuild, do not delete.** If you handle a new SDK structure, construct a fresh object with the
   fields you want. Never `remove()` the ones you don't.
3. **Adding a tag key is a reviewed act.** Add to `ALLOWED_TAG_KEYS` in the same commit that adds
   the caller, with a comment saying why the value is safe.
4. **Never widen a message to compensate for a missing tag.** Messages are the riskiest surface.

### Testing requirements for new scrubbers

Non-negotiable, and enforced by review:

- **Assert on the whole serialised payload, not the field you changed.** `SentryScrubberTest.dump()`
  exists because a scrub that *relocated* data would pass a per-field check.
- **Use realistic Finora payloads** — a quoted amount, a narration, an account number — not `"foo"`.
- **Assert the event is still worth receiving.** A scrubber that emptied everything would pass every
  safety assertion and be useless. `theEventIsStillWorthReceivingAfterScrubbing` is that guard.
- **Test the bare case.** The `getExtras()` NPE described in §9 existed because every fixture was
  fully populated.
- **Fixtures must be obviously synthetic** — `example.com`, repeated-digit runs.
  `check-fixture-hygiene.sh` will block the commit otherwise, and annotating with `synthetic-ok` is
  the wrong fix when a genuinely fake value would do.

---

## 7. Background work: the worker contract

**Workers do not instrument themselves.** They obtain a `WorkerExecution` from `WorkerObservability`
and report lifecycle events against it. Correlation, metrics, breadcrumbs, exception capture, MDC
management, timing and cleanup are inherited. A new worker adds instrumentation by *using* this, not
by writing any.

### The standard lifecycle

```
queued -> claimed -> started -> completed
                        |
                        +-> retry scheduled -> (back to claimed)
                        +-> dead letter
                        +-> failure recording failed
                        +-> recovered   (a worker died mid-flight)
```

These names are the operational vocabulary: they are the metric names, the Sentry `outcome` tags and
the runbook headings, deliberately identical in all three so an alert, a dashboard panel and this
document all say the same word.

### How to instrument a new worker

```java
private static final String WORKER = "import";          // low-cardinality
private static final String JOB_KIND = "import-job";

// Once, in the constructor. A level, not an event -- the registry polls it.
observability.publishQueueDepth(WORKER, JOB_KIND,
        () -> repository.countByStatus(Status.PENDING));

public int drainOnce() {
    try (WorkerExecution execution = observability.begin(WORKER, JOB_KIND)) {
        List<UUID> claimed = claimBatch();
        execution.claimed(claimed.size());
        for (UUID id : claimed) {
            try {
                execution.started(id, row.getCreatedAt());   // queuedAt enables queue-wait timing
                doWork(id);
                execution.completed(id);
            } catch (RuntimeException e) {
                if (exhausted) execution.deadLettered(id, attempts, e);
                else           execution.retryScheduled(id, attempts);
            }
        }
        return claimed.size();
    }
}
```

`MerchantLearningEventWorker` is the reference implementation.

**Use `beginScheduled()`** when a scheduled trigger starts the pass, so the correlation id records
that origin.

**Use try-with-resources.** `close()` records duration, pops the Sentry scope and restores MDC — all
three must happen even when the body throws. Worker threads are pooled, so a leaked MDC entry or tag
attaches itself to every later job the thread picks up, attributing one job's failure to another's
id. That is why cleanup is a language construct rather than a convention.

### Required metrics

Provided automatically; names are fixed by the framework so one dashboard query covers every worker.

| Metric | Type | Meaning |
|---|---|---|
| `finora.worker.executions` | counter | Passes started |
| `finora.worker.completed` | counter | Jobs that succeeded |
| `finora.worker.retries` | counter | Jobs scheduled for another attempt |
| `finora.worker.dead_letters` | counter | Jobs that exhausted retries |
| `finora.worker.recovered` | counter | Rows returned after a worker died |
| `finora.worker.failures` | counter | Failures not retried away |
| `finora.worker.duration` | timer (p50/p95) | How long one pass took |
| `finora.worker.queue_wait_time` | timer (p50/p95) | Queued → started |
| `finora.worker.queue_depth` | gauge | Jobs waiting — **you must register this** |

A dead letter increments **both** `dead_letters` and `failures`; a retry increments **neither**.
Two questions, two counters: "how often do we give up" and "how often does work not get done".

### Required tags

Every meter carries `worker` and `jobKind`, plus a global `environment`. Keep both low-cardinality —
a logical name, not a row id.

Sentry events carry `worker`, `jobKind`, `correlationId`, and where relevant `phase`, `outcome` and
`jobId`. These are the *only* tag keys that survive scrubbing (§5).

### Required correlation

Automatic. Prefix convention:

| Prefix | Origin |
|---|---|
| `request-` | HTTP request (`CorrelationIdFilter`) |
| `worker-` | async or on-demand worker pass |
| `scheduler-` | pass from a scheduled trigger |

One MDC key, not three — `AuditService` stamps every row with whatever it finds there, so a
queue-driven write correlates with no change to that class. The prefix preserves origin without a
second lookup.

### Required tests

A worker is not production-ready without:

- **Correlation is restored, including when the body throws.** The pooled-thread leak above.
- **A retry is not a dead letter.** Assert the counters separately; this is what keeps alerting
  meaningful.
- **Reporting never throws.** It runs in the failure path; throwing there replaces a recorded
  failure with an unrecorded one.
- **The queue-depth supplier does not throw.** It runs on the scrape path, not the worker's thread.
- **Metrics reach the scrape.** `WorkerMetricsExportIT` — a meter in a registry nothing exports is
  as useful as no meter, and looks identical in a unit test.

### What is reported, and what deliberately is not

| Event | Reported? | Why |
|---|---|---|
| Retry scheduled | **No** — breadcrumb + counter | A transient failure the next attempt resolves is normal. Paging on it is how alerting gets muted. Alert on the *rate*. |
| Dead-lettered | **Depends on severity** | Always counted (`finora.worker.dead_letters`/`finora.worker.failures`). Whether it also reaches Sentry depends on `AlertSeverity`: `ERROR`/`WARNING` capture (matching every caller before `AlertSeverity` existed — e.g. `MerchantLearningEventWorker`, always `ERROR`), `NONE` does not. `ImportJobWorker` passes a severity derived from `ErrorCode.RetryPolicy` (Premium Import Reliability v1, §5.6): a `FAIL_FAST` dead-letter (a corrupt PDF, a locked document — an expected, customer-caused failure already visible via the failure UX and failure-analytics query) is `NONE` and never reaches Sentry; `RETRY`-exhausted is `WARNING`; `RETRY_ONCE_THEN_ALERT` is `ERROR`. The counter is the reliable signal regardless of severity — see `WorkerDeadLettersRising` below. |
| Failure not recorded | **Yes** | Double fault — the row is stranded. |
| Abandoned rows recovered | **Yes** | A worker process died. |

### Per-import evidence, and where metrics stop

Metrics answer questions about a *population*: how slow is parsing across every job, how often does
the queue stall. They cannot answer "what happened to **that** import", because the answer has a
cardinality of one and a dashboard label may not.

That question is answered by rows, not meters, and by one endpoint rather than three queries:

```
GET /api/v1/admin/imports/traces/by-analysis/{reference}    e.g. SA-20260806-0145
GET /api/v1/admin/imports/traces/by-job/{jobId}
```

Both return the same shape — upload, parsing, per-stage timing, verification, learning, completion —
gated on `PLATFORM_DIAGNOSTICS_VIEW`, carrying no file name, no user id and no statement content.

| Block | Table | Added by |
|---|---|---|
| Upload and parsing | `statement_analysis_sessions` | V59/V60 |
| Per-stage timing and status | `import_job_stages` | V72 |
| Verification outcomes | `import_verification_findings` | V72 |
| Learning | `merchant_learning_events` | V62/V63 |
| Queue progress | `import_jobs` | V66 |

**The join key is the staging session.** `merchant_learning_events.source_import_session_id` has
existed since V63; V72 is what lets the analysis row name the same session, plus a `correlation_id`
so a trace leads to its log lines. Before that the three tables each recorded their part and were
keyed on things that never met.

**Two rules this surface is held to**, both of which it would be easy to lose:

- **A stage row can say a stage did not run.** `SKIPPED` is recorded, and carries no timing rather
  than a zero. That is what lets someone prove an optimisation unnecessary — the falsifying property
  the diagnostics rule asks for, which a counter cannot have. A zero duration would enter every
  average and claim a stage that never ran was instantaneous.
- **Verification details are rebuilt from an allowlist**, never stripped of the monetary fields. The
  in-memory findings carry balances, totals and the raw ambiguous cell; a denylist would have to be
  right every time a rule adds a field. Same direction as §4, for the same reason, with the
  destination being our own database rather than a third party — which makes it easier to justify
  one more field each time and harder to walk back.

**Recording never breaks an import, and the boundary is subtler than it looks.** Both recorders run
their write through a `TransactionTemplate` with `REQUIRES_NEW` rather than under
`@Transactional(REQUIRES_NEW)`. With the annotation the proxy commits *after* the method body, so a
constraint violation leaves the transaction rollback-only and arrives as an
`UnexpectedRollbackException` at commit time — after the in-method `catch` has already reported
success — and reaches the caller. The caller is a customer's import. The catch has to enclose the
commit, and only an explicit template puts it there.

### Alert thresholds

Alert on rates and levels, never on individual retry events:

| Signal | Shape |
|---|---|
| Dead-letter rate | Any sustained non-zero rate. Work is silently not being done. |
| Retry rate | A step change, not an absolute — the healthy rate is workload-specific. |
| Queue depth | Rising *with a flat completion rate* is a stall; rising with a healthy one is load. |
| Queue age (`queue_wait_time` p95) | The user-visible symptom, and the best single alert. |
| `failures` without `dead_letters` | Stranded rows — suspect the database, not the worker. |

## 8. Deployment configuration

| Variable | Default | Notes |
|---|---|---|
| `SENTRY_DSN` | *(unset)* | **Unset disables monitoring entirely.** The safe default. |
| `SENTRY_ENVIRONMENT` | `development` | Set to `production` on the deployed backend. |
| `SENTRY_RELEASE` | *(unset)* | Set to the commit SHA so an error points at a deploy, not at "production". |

Fixed in `application.yml` and not intended to be overridden: `sentry.logging.enabled: false` (the
starter would otherwise ship every ERROR log as an event, and a log line is the likeliest place a
narration appears) and `sentry.send-default-pii: false`.

**Before setting a DSN in production for the first time**, run the observability suite and read
`SentryScrubberTest` — that is the contract you are relying on.

### Scheduled housekeeping, and how to tell it is running

Two things are enforced by `@Scheduled` rather than by user traffic. Both are silent when switched
off: nothing errors, nothing alerts, and the only symptom is data that should have gone still being
there.

| Variable / property | Default | What stops if it is off |
|---|---|---|
| `app.import.session-cleanup.enabled` | `true` | **Expired import sessions are never deleted.** Those rows carry raw statement bytes. |
| `app.import.session-cleanup.interval-ms` | `900000` (15 min) | Sweep frequency. Each run removes at most 50 rows, so the drain rate is ~4,800/day. |
| `app.learning.queue.enabled` | `true` | Queued merchant-learning confirmations are never applied. |
| `@EnableScheduling` (`BackgroundWorkConfig`) | on, unconditional | **Both of the above**, plus the import-job poller. |

**Why this table exists.** Until BH-047 the expired-session sweep ran opportunistically inside
whichever upload happened next, so retention was enforced as a side effect of traffic. It is
independent housekeeping now, which is correct — a stranger's failed upload can no longer roll back
someone else's deletion — but it moves the failure mode. Retention previously degraded when nobody
uploaded; it now degrades when the scheduler does not run. That is a better trade and a different
thing to watch.

**How to confirm it is actually running**, in increasing order of effort:

1. The sweep logs at INFO whenever it removes anything: `Removed N expired import session(s)`.
   Silence means either nothing was expired or nothing is sweeping — the two are indistinguishable
   from the log alone, which is the point of checks 2 and 3.
2. `SELECT count(*) FROM import_sessions WHERE expires_at < now() - interval '1 hour';` should sit
   near zero on a healthy deployment. A number that only grows is the signal.
3. `app.import.session-cleanup.enabled` and `SPRING_PROFILES_ACTIVE` in the deployment's own
   environment. Neither is reported by `/actuator/health`.

**There is deliberately no alert on this yet.** An alert needs a metric, the sweep publishes none,
and adding one is a change to the worker-metrics contract in §7 rather than a documentation edit.
Query 2 is the manual stand-in until then — recorded here so the gap is known rather than assumed
covered.

**What this does NOT cover.** Deleting the `import_sessions` row does not delete the statement
bytes from object storage. Database cleanup and object-storage cleanup are separate concerns and
only the first is automated (BH-017, open pending a retention decision). Do not read a shrinking
`import_sessions` table as evidence that stored documents were removed.

---

## 9. Operational runbook

One section per alert, named exactly after the alert so `runbook_url` can link straight to it.
`scripts/check-dashboard-metrics.py` fails the build if an alert has no section here, or a section
names no alert — the two must not drift apart.

Every section answers the same six questions in the same order, because at 3am the value of a
runbook is that you do not have to read it to know where to look.

---

### WorkerDeadLettersRising

**What happened.** A background job exhausted its retries. For merchant learning that means a user
confirmed a category and it silently did not take effect.

**Severity: critical.** This does not resolve itself. The work will not run again without a human.

**Check first.** The Sentry event, tagged `outcome=dead-letter`. It carries `jobId`, `worker` and the
redacted exception. The exception *type* is usually enough to classify it: a constraint violation and
a missing merchant need different responses.

For the import worker specifically, a `FAIL_FAST`-classified dead-letter (a corrupt PDF, a locked
document — an expected, customer-caused failure, not an engineering one) deliberately produces no
Sentry event at all (Premium Import Reliability v1, §5.6 — see the severity table above). If the
metrics below show a spike with no matching Sentry event, that is very likely a batch of routine
import failures, not a missing alert — go straight to logs/the admin failure queue instead of
searching Sentry for an event that was never sent.

**Metrics.** `finora_worker_dead_letters_total` by `worker` — one job or a pattern? Compare with
`finora_worker_retries_total`: a spike in both suggests a dependency degraded, then failed outright.

**Logs.** Take `correlationId` from the Sentry tags and search logs for it. That returns the whole
worker pass, plus any audit rows it wrote — `AuditService` stamps the same id.

**Recovery.** The row is in the admin Merchant Review Center with its `last_error`. Resolve the cause,
then Retry All. If the cause is not resolvable the row stays `FAILED` deliberately: it is a record
that the user's action did not take effect.

---

### QueueAgeExceedsSla

**What happened.** The oldest waiting job has waited more than 15 minutes.

**Severity: critical.** This is the user-visible symptom. Depth measures backlog; age measures how
long a person has been waiting.

**Check first.** Whether the worker is running at all. `finora_worker_executions_total` should rise
every 30s (the poll interval). A flat line means the scheduler is not firing, which is a different
problem from slow work and has a different fix.

**Metrics.** `finora_worker_oldest_pending_age_seconds` with `finora_worker_queue_depth`: age high
and depth low is one stuck row; both high is a genuine backlog.

**Logs.** Search `scheduler-` correlation ids for recent passes. Their absence is itself the finding.

**Recovery.** Scheduler not firing: restart the service. Firing but not draining: go to
`QueueGrowingWithoutProgress` — same causes.

---

### QueueGrowingWithoutProgress

**What happened.** Queue depth is rising while completions are flat. A stall, not load.

**Severity: critical.** Nothing is being processed.

**Check first.** The connection pool. `ConnectionPoolSaturated` may not have fired yet, but the pool
is capped at 10 and shared with request threads, so exhaustion stalls workers first.

**Metrics.** `hikaricp_connections_pending` (threads waiting), `finora_worker_duration_seconds` p95,
and `finora_worker_recovered_total` — repeated recovery of the same rows suggests a poison job being
reclaimed forever.

**Logs.** Look for one `jobId` appearing across several `worker-` correlation ids. That is a poison
job, not a slow one.

**Recovery.** Poison job: mark it `FAILED` manually so the queue drains, then investigate in
isolation. Pool exhaustion: find the query holding connections — raising `DB_POOL_MAX_SIZE` treats
the symptom and may exhaust the database's own ceiling.

---

### WorkerStrandingRows

**What happened.** Failures are being recorded faster than dead letters, meaning recording a failure
itself failed. Rows are stranded mid-flight.

**Severity: critical.** A double fault. Affected rows are invisible to the queue until recovery
sweeps them after 15 minutes.

**Check first.** Database health, not the worker. Recording a failure is a single small write; if
that fails, the database or the pool is the suspect.

**Metrics.** `hikaricp_connections_active` against `hikaricp_connections_max`, plus database
availability.

**Logs.** Search for `Could not record the failure of merchant learning event`. The Sentry event is
tagged `outcome=stranded`.

**Recovery.** `recoverAbandoned()` returns these rows automatically after the 15-minute
`PROCESSING_TIMEOUT` — usually no action beyond fixing the database. Verify by watching
`finora_worker_recovered_total` rise and depth fall.

---

### BackendDown

**What happened.** Prometheus cannot scrape the backend.

**Severity: critical**, with one important caveat below.

**Check first.** **Whether the service is down, or the scrape token expired.** These are
indistinguishable in Prometheus' target list. Open `/actuator/health` by hand; a 401 in the target's
Error column means the credential, not an outage.

**Metrics.** None — that is the point. Fall back to platform health checks and Sentry, which reports
independently of this scrape.

**Logs.** Platform logs (Railway), not application logs: if the app is down it is writing none.

**Recovery.** Expired token: mint a new one. Genuine outage: platform restart, then check Sentry for
what preceded it.

---

### WorkerRecoveringAbandonedJobs

**What happened.** Rows are repeatedly returned to the queue, meaning worker processes are dying
mid-apply.

**Severity: warning.** No work is lost — recovery is working. But something is killing workers.

**Check first.** Deploy timestamps. Railway restarts on every push to main, and a deploy during a
worker pass produces exactly this signal. Correlate times before investigating further.

**Metrics.** `jvm_memory_used_bytes` for OOM pressure, and the `finora_worker_recovered_total` rate:
a trickle around deploys is benign, a rising rate between deploys is not.

**Logs.** `Returning N merchant learning event(s) to the queue`. N matters — a full batch means a
process died holding an entire claim.

**Recovery.** Deploy-related: none needed. Otherwise treat as a crash investigation — heap, OOM
killer, container limits.

---

### WorkerRetryRateStepChange

**What happened.** The retry rate is well above this worker's own 24h baseline.

**Severity: warning.** Retrying is expected; a step change usually means a dependency is degrading
before it fails outright. This is the early warning for `WorkerDeadLettersRising`.

**Check first.** Whether dead letters are also rising. If so, treat this as that alert. If not, the
retries are still succeeding and there is time.

**Metrics.** `finora_worker_retries_total` against its own history — the absolute number is
workload-specific and meaningless alone.

**Logs.** Breadcrumbs on any Sentry event from this worker carry the retry history, which is exactly
why retries are breadcrumbed rather than reported.

**Recovery.** Usually resolves when the dependency recovers. Watch rather than act, unless dead
letters follow.

---

### WorkerExecutionSlow

**What happened.** p95 pass duration is above 30s.

**Severity: warning.** Slow, not stopped.

**Check first.** Whether depth is also rising. Slow passes with a stable queue are tolerable; slow
passes with a growing queue become `QueueAgeExceedsSla` shortly.

**Metrics.** p50 against p95 — a widening gap means some passes stall while most stay fast, pointing
at a subset of jobs rather than general slowness.

**Logs.** Find a slow pass by `correlationId` and look at what it claimed. Batch size is capped at
50, so an unusually slow pass is about the work, not the volume.

**Recovery.** Usually connection-pool contention or a slow dependency. Confirm with
`hikaricp_connections_pending` before changing anything.

---

### ConnectionPoolSaturated

**What happened.** HikariCP is over 90% utilised.

**Severity: warning.** Workers and request threads share this pool, so expect both to slow together.

**Check first.** Whether an import is running. Statement imports are the heaviest database consumers,
which is why `ImportConcurrencyLimiter` bounds them.

**Metrics.** `hikaricp_connections_pending` is the one that matters — active at max with zero pending
is a fully-used pool, which is fine. Pending above zero means threads are waiting.

**Logs.** Correlate `request-` and `worker-` ids active during the window to see which side is
consuming the pool.

**Recovery.** Raising `DB_POOL_MAX_SIZE` is the obvious move and often the wrong one: the database
has its own ceiling shared across replicas. Find the long-held connection first.

---

### JvmHeapPressure

**What happened.** Heap is over 90% used for 15 minutes.

**Severity: warning**, and a leading indicator — sustained pressure precedes the OOM kills that
appear later as `WorkerRecoveringAbandonedJobs`.

**Check first.** Whether a large import is in flight. Statement parsing holds the whole document in
memory, so a large PDF is the most likely benign cause.

**Metrics.** `jvm_memory_used_bytes{area="heap"}` trend and GC pause — rising pause with flat
throughput means the heap is genuinely too small rather than momentarily full.

**Logs.** Correlate with import activity by `requestId`.

**Recovery.** Import-driven: none, it will fall. Sustained with no import activity: suspect a leak
and capture a heap dump *out of band* — the actuator heapdump endpoint is deliberately not exposed.

---

### Nothing is arriving at all

Not an alert — the absence of one.

Check `SENTRY_DSN` is set. Unset, the application logs `No SENTRY_DSN configured -- backend error
monitoring is off` once at startup, and that is intended, safe behaviour rather than a fault. For
metrics, check the Prometheus target list: a missing target and a healthy silent system look
identical.

## 10. Known gaps

- **The scrape needs a credential or a private network path.** `/actuator/prometheus` is
  authenticated (`SecurityConfig` permits only `/actuator/health`), which is the right posture — the
  scrape carries queue depths, error rates and JVM internals. But it means Prometheus cannot scrape
  anonymously. **Resolving this by adding `/actuator/**` to `permitAll` would make Prometheus work
  and publish the same data to the internet in one move** — `WorkerMetricsExportIT` asserts the
  anonymous case specifically to catch that. The real options are a scrape credential or Railway's
  private network; that is a deployment decision, not a code one.
- **No dashboards yet.** The metrics are exported and labelled; Grafana panels and their queries are
  the next piece, and cannot be built or validated from the repository alone.
- **No alerting configured.** Thresholds are proposed in §7 but nothing evaluates them.
- **Import pipeline instrumentation is done at the queue and thin on the synchronous path.**
  `ImportJobWorker` reuses this framework and adds none of its own, as required, and per-import
  evidence is covered above. What is still missing is on the *synchronous* upload: it records a total
  duration and no per-stage breakdown, because it has no job row to hang stages off. Closing that
  means either routing every upload through the queue (item 5's threshold decision) or giving the
  synchronous path a stage owner of its own — a design choice, not a follow-up task.
- **The asynchronous path records no analysis session.** The worker stages through
  `parseAndStageAnyFormat`, which returns a `StagingResponse` and no `DocumentContext`, so there is
  no fingerprint or reason histogram to record and a job-anchored trace has no `analysis` block.
  V59's promise that *every upload leaves a record* therefore does not hold on this path yet. Fixing
  it is a change to the staging API's return shape, and it is the honest reason the trace leaves that
  field null rather than writing a thin row.
- **A synchronous confirm records no link back to its staging session,** so a trace reaches the
  resulting `statement_imports` row only through the learning events that carry both ids. An import
  that taught the system nothing therefore reports no statement import even though one exists.
  Closing it means a column on `statement_imports`, which the confirm path owns.
- **No distributed tracing.** `tracesSampleRate` is deliberately `0` — spans are keyed by URL, which
  would reintroduce the identifiers §4 strips. Revisit only with a scrubbing strategy for span names.

---

## 11. Reconciliation metrics

`ReconciliationMetrics` (`com.finora.observability`) — two counters, added once
`docs/proposals/reconciliation-benchmark/` established a measured baseline for
`ReconciliationService` and needed a way to answer, from production rather than a synthetic
benchmark, the two questions that benchmark could never answer on its own: how often the engine's
auto-decisions actually happen, and how often a user disagrees with one.

**Deliberately not built on the worker contract in §7.** Reconciliation is not a queue-drained
background job — it runs synchronously on the request thread after every transaction create/
update/delete and import confirm — so there is no queue depth, no retry, and no dead-letter concept
to report. Forcing it through `WorkerObservability`'s lifecycle would mean inventing meaning for
several of that contract's required states that this pass genuinely does not have.

| Metric | Type | Tag(s) | Meaning |
|---|---|---|---|
| `finora.reconciliation.transfers_matched` | counter | `relationshipMatch` (`true`/`false`) | A transfer pair the engine auto-matched between the user's own accounts. Incremented once per matched PAIR, matching `ReconciliationService`'s own `newTransfers++` counting convention. |
| `finora.reconciliation.duplicate_overrides` | counter | `source` (`MANUAL`/`CSV_IMPORT`/`GMAIL_IMPORT`) | A user rejected an auto-flagged duplicate via `TransactionService.confirmNotDuplicate` — the one user-facing correction that exists today for a wrong reconciliation verdict. There is no equivalent "not a transfer" action yet (a real gap this project's own remaining-failures-classification.md names), so this counter has no transfer-side sibling. |

**Tags follow §2's privacy principle exactly**, even though `/actuator/prometheus` is authenticated
rather than sent to a third party: both tag values are bounded — a boolean and a 3-value enum — and
neither can ever hold a narration, an amount, or any other field a bank statement could have
produced. No description, no merchant, no account identifier is ever a candidate tag value here.

**Dashboard, no alerting yet**: `ops/monitoring/grafana/dashboards/reconciliation.json` renders both
counters (`check-dashboard-metrics.py` validates its queries resolve to real emitted series, the
same guard the worker dashboard is held to). It is a measurement dashboard, not an alerting one —
these two counters answer a measurement question first (docs/proposals/reconciliation-benchmark/'s
own stated next step: production-data-driven prioritization over continued synthetic-benchmark
optimization), so panels are informational rather than threshold-colored. A ratio worth watching
once real traffic exists: `duplicate_overrides` against `finora_worker_*`-style expected volume
would need its own denominator (how many duplicates were auto-flagged in the first place), which is
not yet its own counter — proposed, not built, the same discipline this whole project holds every
reconciliation change to. Alerting waits on that same missing denominator: an override-rate alert
with no auto-flag count to divide by would either fire on raw volume (meaningless as traffic grows)
or not fire at all.

**Verified reaching the scrape**, not just registered: `ReconciliationMetricsExportIT` follows
`WorkerMetricsExportIT`'s own pattern exactly — calls `ReconciliationMetrics` directly, scrapes
`/actuator/prometheus` through a real authenticated request, and asserts both series and their tag
values are present.
