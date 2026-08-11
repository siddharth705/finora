# ADR-003 — PostgreSQL is the system of record; brokers are distribution only

**Status:** Accepted · **Date:** 2026-08-07
**Supersedes:** nothing · **Related:** `IMPORT_ARCHITECTURE_REVIEW.md`,
`docs/engineering/import-pipeline-scaling-design.md`

---

## Context

Scaling the import pipeline to tens of thousands of concurrent statement uploads raised the
question of whether to adopt Kafka or RabbitMQ. Reviewing the existing implementation first
surfaced a property that changes the question.

`MerchantLearningEventPublisher.enqueue()` writes the queue row **inside the caller's transaction**,
deliberately. Its own javadoc states the requirement:

> The row is written in the CALLER's transaction. If the import rolls back, the queued learning must
> roll back with it — otherwise a worker later applies a confirmation for transactions that do not
> exist.

The queue is therefore not merely a transport. **It is part of the application's transactional
consistency guarantee.** Enqueueing and the business write either both happen or neither does.

No external broker can participate in a PostgreSQL transaction. Replacing the row insert with a
direct publish reintroduces the dual-write problem, which has no correct ordering:

| Order | Failure |
|---|---|
| Publish, then commit | Import rolls back, event is already in the broker → a worker applies learning for transactions that do not exist |
| Commit, then publish | Process dies between the two → the event is lost with no record it ever existed |

Both leave the system inconsistent. Neither is fixable with retries, because the failure is the
absence of atomicity, not a transient error.

## Decision

**PostgreSQL remains the system of record for all transactional work. External messaging systems
are distribution mechanisms, never transactional stores.**

Concretely:

1. Business logic writes **only** to PostgreSQL, inside the transaction. It never publishes to a
   broker directly.
2. Committed events are published onward by a **dedicated relay** — the transactional outbox
   pattern.
3. Consumers process **idempotently**, because relay-based delivery is at-least-once.
4. PostgreSQL remains the **recovery mechanism** when the broker is unavailable: an unpublished row
   is still a durable record of work to do, and the existing poller collects it.
5. Message brokers are therefore **replaceable infrastructure**, not a dependency of the domain.

### What this means for abstraction

Do **not** abstract queue operations — `claimBatch()`, `markCompleted()`, `updateStatus()`. Each
leaks a PostgreSQL capability: `SKIP LOCKED` has no RabbitMQ equivalent, and update-in-place has no
Kafka equivalent, where offsets only commit forward. An interface shaped that way would need
rewriting on contact with the first real broker.

Abstract **dispatch** instead, and keep **recording** transactional and PostgreSQL-specific:

- `JobStore` — durable, transactional, always PostgreSQL. Not abstracted; there is no second
  implementation and inventing one is what breaks.
- `JobDispatcher` — how a committed job reaches a worker. This is the swappable half.
  `InProcessDispatcher` today; a `BrokerDispatcher` later, if justified.

Dispatched messages carry a **job id, not a payload**. Workers re-read state from PostgreSQL. This
avoids message-size limits, schema evolution, and stale-payload bugs, and is what keeps the
transport genuinely swappable.

### What this means for sequencing

Kafka and RabbitMQ are explicitly **not** the next implementation step. Priority order:

1. `ImportJob` lifecycle
2. Durable `ImportJob` queue
3. Import worker
4. Progress API
5. Object storage on by default
6. **Profile and reduce per-row processing cost**
7. Outbox relay
8. Optional broker integration — only when operational metrics justify it

Adoption triggers are recorded in `docs/engineering/import-pipeline-scaling-design.md` §8. Until one
is measurably crossed, adopting a broker adds operational surface without addressing a demonstrated
constraint.

## Consequences

**Positive**

- The transactional guarantee that already exists is preserved rather than traded away for
  throughput the system does not need.
- Evolution to a broker becomes additive — a second `JobDispatcher` — instead of a rewrite.
- PostgreSQL backups already cover queue state; no separate broker DR story is needed today.
- The domain stays free of messaging concepts.

**Negative / accepted costs**

- The outbox relay is an extra moving part once a broker is introduced, and needs its own
  monitoring.
- At-least-once delivery obliges every consumer to be idempotent. The merchant-learning worker
  already is, via its unique constraint; **the import path will need an equivalent guard before
  workers scale out.**
- PostgreSQL write throughput becomes a real ceiling eventually. That is accepted deliberately: it
  is far away, and the triggers exist to detect it.

**Practical limit to plan for**

Connection management will bind before messaging throughput does. `DB_POOL_MAX_SIZE` is per
instance, so separate API and worker services multiply it: 10 API + 20 workers at 10 each is 300
connections against a PostgreSQL that will not permit them. Pool sizes must be budgeted globally
rather than left at per-service defaults, and this must be part of the scaling work, not discovered
during it.

## Evidence this decision rests on

- The transactional-enqueue requirement is documented in `MerchantLearningEventPublisher`'s javadoc
  and enforced by `MerchantLearningEventWorker`'s three-phase transaction boundaries.
- Measured import cost of ~16ms per transaction row (`E2E_TEST_REPORT.md`, Issue 02) means 50,000
  uploads of 5,000 rows is roughly 46 CPU-days of work. Queue operations are a rounding error at
  that ratio, so broker throughput is not the binding constraint — per-row processing cost is.
- `ImportConcurrencyLimiter`'s javadoc already identified multi-instance deployment as the condition
  under which its in-process gate stops being sufficient. That condition is what has changed; the
  reasoning was sound.
