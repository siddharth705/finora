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

## 7. Background work

The Sentry starter reports what fails on a request thread. Background work needed explicit
reporting, and `WorkerObservability` provides it.

### Correlation

`CorrelationIdFilter` puts `requestId` in MDC for HTTP requests, and `AuditService` stamps every
audit row with whatever it finds there. Background work had none — a queue-driven audit row carried
a null request id and could not be tied to anything.

`WorkerObservability.run()` sets **the same MDC key**, prefixed `worker-`. Three things then line
up for free, with no change to either class: the worker's log lines, the audit rows it writes, and
its Sentry events all share one id.

The previous MDC value is *restored*, not cleared — the async nudge runs from a request thread that
has its own id, and clearing would detach the rest of that request's logs. Restoration happens in a
`finally`, because these threads are pooled: a leaked id would attribute one job's failure to
another's.

### What is reported, and what deliberately is not

| Event | Reported? | Why |
|---|---|---|
| Retry scheduled | **No** — breadcrumb + counter | A transient failure the next attempt resolves is normal operation. Paging on it is how alerting gets muted. |
| Dead-lettered | **Yes** | The user's action silently did not take effect. Previously only a log line plus a screen someone must think to open. |
| Failure not recorded | **Yes** | Double fault — the row is stranded in `PROCESSING`. |
| Abandoned rows recovered | **Yes** | A worker process died. Reported as a message; there is no exception to attach. |

### Metrics

`finora.worker.retry`, `.dead_letter`, `.failure_not_recorded`, `.recovered`, tagged by `worker` and
`jobKind`. Micrometer is already present via Actuator, so these cost no new dependency.

**They are collected but not yet exported** — only `health` is exposed on the actuator endpoint. A
Prometheus registry and endpoint is the next milestone; until then these are visible in tests and in
a debugger, not on a dashboard.

---

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

- **Metrics are collected but not exported.** Next milestone.
- **No alerting.** Thresholds for queue growth, retry rate and worker crashes are a design task.
- **Import pipeline workers are not yet instrumented.** `MerchantLearningEventWorker` is the only
  durable queue today; the import path runs inline on the request thread and is covered by the
  starter.
- **No distributed tracing.** `tracesSampleRate` is deliberately `0` — spans are keyed by URL, which
  would reintroduce the identifiers §4 strips. Revisit only with a scrubbing strategy for span names.
