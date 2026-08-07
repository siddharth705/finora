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
| Dead-lettered | **Yes** | The user's action silently did not take effect. |
| Failure not recorded | **Yes** | Double fault — the row is stranded. |
| Abandoned rows recovered | **Yes** | A worker process died. |

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

---

## 9. Operational runbook

### An event arrives tagged `outcome=dead-letter`

The user's merchant-category confirmation did not take effect and will not retry.

1. Take `jobId` from the tags → find the row in `merchant_learning_events`.
2. `attempt_count` and `last_error` on the row say what failed. The Sentry event's redacted message
   and exception type say where.
3. The row is visible in the admin Merchant Review Center. Resolve there.
4. Use `correlationId` to find the worker pass's log lines and any audit rows it wrote.

### An event arrives tagged `outcome=stranded`

Recording a failure itself failed. The row is stuck in `PROCESSING` and `recoverAbandoned()` will
return it to the queue after 15 minutes. **If these are frequent, the database is the suspect**, not
the worker — recording a failure is a small write that should not fail.

### `Returned N abandoned job(s) to the queue`

A worker process died mid-apply. One row is a blip; a batch means a process died holding a full
claim — check for a deploy, an OOM, or a container restart at that timestamp.

### Nothing is arriving at all

Check `SENTRY_DSN` is set on the environment. With it unset the application logs
`No SENTRY_DSN configured -- backend error monitoring is off` once at startup, and that is the
intended, safe behaviour rather than a fault.

### A retry rate that climbs without dead letters

Retries are not reported as errors by design, so this is only visible as the
`finora.worker.retry` counter. Until metrics are exported (§7) that means it is effectively
invisible — **this is the largest remaining gap in worker observability** and the reason the metrics
milestone follows immediately.

---

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
- **Import pipeline is not instrumented,** because it still runs inline on the request thread and is
  covered by the starter. **When import moves to a durable queue it must reuse this framework rather
  than redesign one** — that is a design requirement of the async import milestone, not a follow-up.
- **No distributed tracing.** `tracesSampleRate` is deliberately `0` — spans are keyed by URL, which
  would reintroduce the identifiers §4 strips. Revisit only with a scrubbing strategy for span names.
