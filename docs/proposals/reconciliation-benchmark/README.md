# Reconciliation accuracy benchmark

This is an **analysis-only** benchmark suite. It measures how `ReconciliationService` (and its two
collaborators, `GmailReconciliationMatcher` and `CreditCardFlowReconciliationValidator`'s sibling
import-time validators) actually behaves against 59 realistic Indian-bank scenarios, so that any
future improvement work starts from a measured baseline instead of a guess. **No production
reconciliation logic was changed to produce this benchmark or its results.**

See [`benchmark-report.md`](benchmark-report.md) for the full write-up: current-behavior summary,
scenario coverage, execution results, failure analysis, and a ranked improvement roadmap.

## Where the tests live

Six JUnit classes under `backend/src/test/java/com/finora/service/`, one per category:

- `DuplicateDetectionBenchmark`
- `TransferBenchmark`
- `InvestmentTransferBenchmark`
- `RefundReversalBenchmark`
- `GmailMatchingBenchmark`
- `CreditCardPaymentBenchmark`

...plus a shared harness, `ReconciliationBenchmarkSupport`, that mocks every repository the same
way `ReconciliationServiceTest` already does (so the real `ReconciliationService` pass logic runs
unmodified — only persistence is faked).

## Why these classes are invisible to a plain `mvn test`

Every class name ends in `Benchmark`, not `Test`/`Tests`/`TestCase` — the same convention
`ReconciliationScalingBenchmark` already established in this codebase. `backend/pom.xml`'s surefire
`<includes>` only match `*Test*.java`/`*Tests.java`/`*TestCase.java`, so a bare `mvn test` never
picks these up.

**This is deliberate, not an oversight.** 18 of the 59 scenarios encode what a CORRECT
reconciliation verdict is and currently fail against the real engine — that is this benchmark's
whole purpose (see the report). A suite where roughly a third of assertions are *expected* to fail
must never sit inside the gate that blocks every PR.

## How to run it

```bash
cd backend
mvn -o test -Dtest="DuplicateDetectionBenchmark,TransferBenchmark,InvestmentTransferBenchmark,RefundReversalBenchmark,GmailMatchingBenchmark,CreditCardPaymentBenchmark"
```

`-Dtest` REPLACES surefire's own includes rather than intersecting with them, so this explicit
selection runs regardless of the naming exclusion above (same footnote `ReconciliationScalingBenchmark`
already carries).

A red assertion here is a **finding**, not a broken build. Every failing test's `.as(...)` message
explains, in the moment, why the current behavior is wrong and what it costs a real user — read
that message; it is the finding, condensed.

## What to do with a red test

Nothing, yet. Per the brief this benchmark was built to satisfy: establish the baseline first,
prioritize from measured evidence, and only then decide which gaps are worth fixing and in what
order — see `benchmark-report.md`'s roadmap section. Do not "fix" a failing benchmark test by
loosening its assertion; that would just delete the finding it exists to record.
