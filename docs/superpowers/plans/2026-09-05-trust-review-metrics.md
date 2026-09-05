# Held Statement Review — Plan 4 of 4: Metrics & False-Positive Tracking

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Answer the two questions the first three plans left open: *how often does each trust
condition actually fire, and how often was firing wrong?* Without this, nobody can tell whether
`TrustPredicate`'s three conditions are well-calibrated, and Brief Phase 11 (future trust signals,
deliberately deferred until telemetry volume exists) has no volume to look at.

**Architecture:** A "false positive" is captured as a deliberate operator action at approve time —
never inferred from event-log shape — following the repository owner's explicit decision (see
Decisions table). A held statement's trigger reasons get a machine-readable category alongside the
existing free-text `trigger_summary`, snapshotted the same way `parser_version`/`reliability_status`
already are. Both feed a read-only, counts-only aggregate service that follows
`AdminImportTelemetryService`'s own established discipline exactly: cheap live queries, nothing
scheduled, nothing materialized, ratios never computed server-side.

**Tech Stack:** Spring Boot / JPA / Flyway, Postgres (native SQL for aggregates, matching
`ImportJobRepository`'s existing telemetry queries), React + TypeScript (admin-portal). No new
libraries.

**Spec:** The repository owner's approved brief, "Held Statement Review System — Implementation
Plan" (in-conversation, 2026-09-03), Phase 10, per the scope table in
[Plan 1](2026-09-03-trust-review-held-statements.md#scope-check). The brief's own Phase 10 text is
not stored anywhere in this repo — grounded instead in what Plans 1-3 actually built and in the
existing telemetry precedent (`AdminImportTelemetryService`/`AdminOperationalDashboardService`),
per the research pass recorded in this plan's Decisions table. Where the brief's exact intent
could not be recovered, this plan says so rather than guessing.

## Global Constraints

- **Counts, never rates, server-side.** Same discipline `AdminImportTelemetryService`'s own class
  doc states: "Every number here is a count, and the denominator is returned alongside them rather
  than divided in... choosing that denominator wrongly is the specific error this phase exists to
  avoid." A percentage bakes in one choice of denominator and hides it behind a decimal point. The
  admin-portal page may compute a percentage for display, but the API never does.
- **No new scheduled job, no materialized rollup table.** Every number is answered live, on
  request, from `held_statements` directly — matching `AdminImportTelemetryService`,
  `AdminOperationalDashboardService`, and `AdminLearningStatsService`, none of which schedule
  anything or persist a second copy of their own answer.
- **A false positive is an explicit, deliberate mark — never inferred.** Confirmed with the
  repository owner directly (2026-09-05): an operator marks a resolved hold false-positive at
  approve time; nothing is derived from whether `root_cause`/`fix_reference` were filled in or
  whether a `rerunParser` call happened first. See the Decisions table for the full reasoning this
  forecloses.
- **Storage is immutable, same as every prior plan in this series** — this plan adds columns and
  queries, never a second copy of anything already stored.

## Decisions this plan makes and why

| Question | Decision | Why |
|---|---|---|
| How does an operator mark a false positive? | **An optional flag on the existing `approve` action, not a new page or button** | Confirmed with the repository owner via AskUserQuestion before writing this plan, over two alternatives (inferring it from event history; a hybrid). Inferring loses the difference between "an operator who already knew from memory" and "a genuine miss nobody caught" — the two would be indistinguishable in the data forever. An explicit mark, made at the one moment an operator is already deciding the hold's fate, costs one field and reuses `approve`'s existing optional-body pattern (`note` already works this way). |
| How are per-condition hold counts made queryable? | **A new `hold_reason_categories VARCHAR(64)[]` column, snapshotted alongside `trigger_summary`** | `trigger_summary` is free text (`TEXT`, no CHECK constraint, confirmed by reading V144 directly) — a `String.join("; ", reasons)` of whatever `TrustPredicate` produced. Querying "how many holds had a period-integrity problem" against that column means parsing prose tied to `TrustPredicate`'s exact reason-sentence wording, which breaks the moment a sentence is reworded for clarity. A structured array column, populated from `TrustPredicate`'s own internal knowledge of which check produced which reason (not by parsing its own output back), avoids this entirely. `VARCHAR(64)[]` matches an existing precedent in this exact codebase (`transactions.tags`, `V1__init_schema.sql:64`), so no new column-type idiom is introduced. |
| Does this touch `HoldDecision`'s existing shape? | **Adds a third field, `categories`, via a non-canonical 2-arg convenience constructor so every existing call site keeps compiling unchanged** | `HoldDecision(boolean, List<String>)` is constructed at 3 real sites (checked directly): `HoldDecision.RELEASE`, `TrustPredicate.evaluate`, and — critically — `HeldStatementService.rerunParser` (Plan 3's own code), which builds one manually for its extraction-failure case. Widening the canonical constructor to 3 args would force Plan 3's code to specify a category for a case that isn't one of `TrustPredicate`'s three conditions at all. A 2-arg constructor delegating to the 3-arg one with `categories = List.of()` keeps that call site correct by construction — an extraction failure legitimately has no category, and nothing has to be edited for this plan to compile against Plan 3's code. |
| How is hold-reason-category breakdown queried without hardcoding each category name? | **`SELECT unnest(hold_reason_categories), count(*) ... GROUP BY 1`** | Hardcoding one `count(*) FILTER (WHERE 'X' = ANY(...))` clause per category (matching `ImportJobRepository.telemetryFlagCounts`'s own FILTER idiom) would need a new migration line every time Brief Phase 11 adds a fourth trust condition. `unnest()` + `GROUP BY` answers "which categories exist and how often" without the query knowing the vocabulary in advance. |
| What permission gates the new metrics endpoint? | **`PLATFORM_DIAGNOSTICS_VIEW`, not `TRUST_REVIEW_MANAGE`** | Matches `AdminImportTelemetryController`'s own precedent exactly: its doc states `PLATFORM_DIAGNOSTICS_VIEW` because it is "engineering telemetry about how the pipeline behaves, not access to anyone's statements," and its response is "counts only: no user, no job id, no file name, no statement content." This plan's endpoint carries the identical shape — aggregate counts, nothing scoped to an individual — so it gets the identical gate. `TRUST_REVIEW_MANAGE` is reserved for what it already means: reaching a specific customer's hold. `PLATFORM_DIAGNOSTICS_VIEW` is already present in admin-portal's `ADMIN_PORTAL_PERMISSIONS` allowlist (`AdminAuthContext.tsx:31`, confirmed directly) — no repeat of the missing-permission bug Plan 2 hit once. |
| Is resolution time reported as a mean or a median? | **Median, via Postgres `PERCENTILE_CONT`** | No existing precedent for a duration-based metric exists anywhere in this codebase (checked directly — `AdminHeldImportService`, `AdminLearningStatsService`, `SupportTicketService` all compute counts, never durations). A mean is dominated by the longest-open outlier hold; a queue where 90% resolve in an hour and one sits open for three weeks under investigation would report a mean that describes neither group. Median is the more honest single number for a skewed, unbounded-tail distribution like "how long was this open." |
| Does a rejected hold's `false_positive` flag mean anything? | **No — the flag is only ever set on `approve`, `reject` doesn't accept it** | "False positive" means the trust predicate flagged something that was actually fine. A rejection means the predicate's suspicion was validated — the opposite claim. Accepting the flag on `reject` would let an operator record a self-contradictory fact, and there's no legitimate reading of "this reached the ledger... no wait, it's a false positive and didn't."  |
| Does `hold_reason_categories` ever change after the hold opens? | **No — snapshotted once at `openHold`, never touched again, including by `rerunParser`** | Confirmed directly against Plan 3's shipped code: `rerunParser` calls only `held.markReadyForImport(...)` and writes an event — it never calls `recordSnapshot` or otherwise touches `holdReasonCategories`. So this column always answers "what did the ORIGINAL parse trigger on," never "what would the current build trigger on" — the same snapshot discipline `parser_version`/`reliability_status` already established in Plan 1, restated here because an external review correctly flagged this as worth confirming rather than assuming. Task 1 Step 8's own doc comment states this explicitly. |
| Does the unchecked false-positive checkbox send `false` or omit the field? | **Omits it — unchecked-and-approved must reach the backend as `null`, exactly like never having asked** | Caught by an external review of this plan's first draft: the original Task 4 draft passed a plain `useState(false)` straight into `adminHeldStatementApi.approve(...)`, so EVERY approval through this UI would have sent an explicit `false` — collapsing `Boolean falsePositive`'s intended null/false distinction (Task 2's own migration comment: "null means not marked, not false") the moment this UI shipped, since nothing else in the codebase calls `approve`. Fixed by sending `markFalsePositive || undefined`, mirroring `approveNote || undefined`'s already-established idiom exactly — the same pattern, not a new one. This deliberately leaves "operator saw the checkbox and explicitly left it unchecked" indistinguishable from "never asked" — a genuine three-state selector (flagged / confirmed-not-flagged / unset) would resolve that, but is a bigger UI change than this plan's own "one field, no new page" scope calls for; recorded here as a real, deliberate scope boundary, not an oversight. |
| What is the actual denominator for a "false positive proportion"? | **`approved`, not `resolved`** | A second external review caught a real analytical error in this plan's own first-draft DTO doc: `resolved = approved + rejected`, but `reject` never accepts a `falsePositive` flag (see the row above on rejected holds), so a rejection is never eligible to be marked one in the first place. `falsePositives / resolved` therefore understates the true proportion by diluting it with holds that couldn't have counted either way. `approved` was already an exposed field — no API change, only a doc-comment correction on `HeldStatementTelemetryDto` stating which field means what, so a future consumer doesn't repeat the same mistake this plan's own first draft made. |
| Should `HeldStatement`'s stored categories ever be raw strings a person could hand-type? | **No at the decision boundary, yes at the persistence boundary** | An external review suggested `List<Category>` (the enum) everywhere, including in the JPA entity, to close any typo risk (`COUNT_MISMATCHH`, `count_mismatch`). Adopted partially: `HoldDecision.categories()` is `List<TrustPredicate.Category>` (Task 1 Step 5/6 below), so the one and only place these values are ever produced (`TrustPredicate.evaluate`) can never mistype one — a typo would be a compile error, not a runtime string. `HeldStatement.holdReasonCategories` itself stays `List<String>` at the persistence boundary: adding a full `AttributeConverter` to map `List<Category>` onto a plain `VARCHAR(64)[]` column is real added complexity for a boundary that already has exactly one producer and no path for a hand-typed string to reach it, and it would also make a future Brief-Phase-11 category rename a schema-affecting change instead of a pure string value in old rows (the same reason `held_statement_events.event_type` was deliberately left as free text rather than a DB enum, per Plan 1 — old rows keep meaning what they meant). The conversion from enum to string happens in exactly one place: `HeldStatementService.openHold`, immediately before `recordSnapshot`. |
| Does `byCategory` count every hold or only resolved ones? | **Every hold, open or resolved (Option A)** | An external review confirmed this plan's own instinct: the tile answers "which trust signals generate operator work," which is true the moment a hold opens, not only once it resolves. Task 3's `telemetryCategoryCounts` query already had no status filter in this plan's first draft; this row makes that a stated decision instead of an unstated default. The frontend's own empty-state placeholder text was still wrong under this decision ("No holds resolved yet" implies a cause — nothing has resolved — that isn't the only real one; a hold that predates Task 1's migration has no recorded category either, resolved or not) — fixed to a cause-neutral label rather than by changing which condition gates the section, since `Object.keys(byCategory).length === 0` was already the correct rendering condition. |
| How does the "resolved" count stay correct if `HeldStatement.Status` grows a new terminal value later? | **The native query takes the resolved status list as a parameter, built from `HeldStatement.Status.RESOLVED` in Java, never hardcoded as SQL string literals** | An external review flagged that `status IN ('IMPORTED', 'REJECTED')` hardcoded directly in `telemetryFalsePositiveCounts`'s SQL would silently stop meaning "resolved" the moment a status like `EXPIRED` or `CANCELLED` is added to the entity's own `Status.RESOLVED` `EnumSet` without anyone remembering to update this query too — two definitions of "resolved" that could drift apart with no compiler or test to catch it. Passing `HeldStatement.Status.RESOLVED` in as a query parameter makes the entity's own enum set the only definition that exists. |

## File Structure

- Create: `backend/src/main/resources/db/migration/V152__held_statement_reason_categories.sql`,
  `V153__held_statement_false_positive.sql` (verify both numbers are still free before writing —
  re-run the check in Task 1 Step 1; two migrations, not one, so each lands as its own reviewable
  commit matching this plan's own task boundaries).
- Modify: `backend/src/main/java/com/finora/imports/trust/HoldDecision.java`,
  `backend/src/main/java/com/finora/imports/trust/TrustPredicate.java` — categories.
- Modify: `backend/src/main/java/com/finora/entity/HeldStatement.java` — `holdReasonCategories`,
  `falsePositive` fields; `HeldStatementService.java` — snapshot wiring, `approve` signature.
- Modify: `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java` —
  `approve`'s request body.
- Create: `backend/src/main/java/com/finora/dto/HeldStatementTelemetryDto.java`,
  `backend/src/main/java/com/finora/service/HeldStatementTelemetryService.java`,
  `backend/src/main/java/com/finora/controller/AdminHeldStatementTelemetryController.java`.
- Modify: `backend/src/main/java/com/finora/repository/HeldStatementRepository.java` — telemetry
  queries.
- Modify tests: `TrustPredicateTest.java` (or wherever its existing tests live — read the file
  first), `HeldStatementTest.java`, `HeldStatementServiceRerunIT.java` or a sibling,
  `AdminHeldStatementControllerIT.java`; new
  `backend/src/test/java/com/finora/service/HeldStatementTelemetryServiceIT.java`.
- Create: `admin-portal/src/pages/TrustReviewMetrics.tsx`,
  `admin-portal/src/pages/TrustReviewMetrics.test.tsx`.
- Modify: `admin-portal/src/types/index.ts`, `admin-portal/src/api/endpoints.ts`,
  `admin-portal/src/App.tsx`, `admin-portal/src/components/Sidebar.tsx`,
  `admin-portal/src/pages/HeldStatementDetail.tsx` (the false-positive checkbox),
  `admin-portal/src/pages/HeldStatementDetail.test.tsx`.

---

### Task 1: Structured hold-reason categories

**Files:**
- Create: `backend/src/main/resources/db/migration/V152__held_statement_reason_categories.sql`
- Modify: `backend/src/main/java/com/finora/imports/trust/HoldDecision.java`,
  `backend/src/main/java/com/finora/imports/trust/TrustPredicate.java`,
  `backend/src/main/java/com/finora/entity/HeldStatement.java`,
  `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Test: find and extend `TrustPredicate`'s own existing test file (read the file at
  `backend/src/test/java/com/finora/imports/trust/TrustPredicateTest.java` first — if it doesn't
  exist at that path, search for it; do not assume the path), and
  `backend/src/test/java/com/finora/entity/HeldStatementTest.java`

**Interfaces:**
- Produces: `HoldDecision.categories() : List<String>` (new), `HeldStatement.getHoldReasonCategories()
  : List<String>`, `HeldStatement.recordSnapshot(...)` extended with a `categories` parameter —
  consumed by `HeldStatementTelemetryService` in Task 3.

- [ ] **Step 1: Confirm V152/V153 are still free**

```bash
git fetch origin
ls backend/src/main/resources/db/migration | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -3
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -3
```

If either is taken, use the next free numbers and rename both migration files in this plan
accordingly (V152 stays Task 1's, V153 stays Task 2's).

- [ ] **Step 2: Read `HoldDecision`, `TrustPredicate.evaluate`, and every real `new HoldDecision(`
      call site before changing anything**

```bash
grep -rn "new HoldDecision(" backend/src/main backend/src/test
```

Confirm this still finds exactly the three sites this plan's Decisions table names
(`HoldDecision.RELEASE`, `TrustPredicate.evaluate`, `HeldStatementService.rerunParser`) — if a
fourth exists now that didn't when this plan was written, read it before proceeding; the 2-arg
convenience constructor in Step 4 needs to keep it compiling too.

- [ ] **Step 3: Write the failing test**

In `TrustPredicateTest.java` (or wherever the existing tests for this class live):

```java
@Test
void countMismatchCarriesTheCountMismatchCategory() {
    ImportDto.VerificationFinding finding = new ImportDto.VerificationFinding(
            SummaryTotalsValidator.RULE, "FAILED",
            Map.of("suspectedCause", "ROW_GROUPING"));
    HoldDecision decision = TrustPredicate.evaluate(
            List.of(new ImportDto.VerificationReport(List.of(finding))), List.of(), LocalDate.now());

    assertThat(decision.hold()).isTrue();
    assertThat(decision.categories()).containsExactly(TrustPredicate.Category.COUNT_MISMATCH);
}

@Test
void twoDifferentCountMismatchCausesStillProduceOneDeduplicatedCategory() {
    // Two sections, two different named causes, both COUNT_MISMATCH -- the category list must
    // not report the same category twice just because two different reason sentences fired.
    ImportDto.VerificationFinding a = new ImportDto.VerificationFinding(
            SummaryTotalsValidator.RULE, "FAILED", Map.of("suspectedCause", "ROW_GROUPING"));
    ImportDto.VerificationFinding b = new ImportDto.VerificationFinding(
            SummaryTotalsValidator.RULE, "FAILED", Map.of("suspectedCause", "DIRECTION"));
    HoldDecision decision = TrustPredicate.evaluate(
            List.of(new ImportDto.VerificationReport(List.of(a)),
                    new ImportDto.VerificationReport(List.of(b))),
            List.of(), LocalDate.now());

    assertThat(decision.categories()).containsExactly(TrustPredicate.Category.COUNT_MISMATCH);
    assertThat(decision.reasons()).hasSize(2); // the two distinct sentences are still both kept
}

@Test
void releaseCarriesNoCategories() {
    assertThat(HoldDecision.RELEASE.categories()).isEmpty();
}
```

Match the exact constructor signatures `ImportDto.VerificationFinding`/`VerificationReport`
already have — read `ImportDto.java` first rather than guessing the parameter order (this plan's
own research pass read them as `VerificationFinding(String rule, String outcome, Map<String,
Object> details)` and `VerificationReport(List<VerificationFinding> findings)`, but verify against
the current source before trusting that).

- [ ] **Step 4: Run it, confirm it fails to compile** (`categories()` doesn't exist yet)

```bash
cd backend && ./mvnw test -Dtest=TrustPredicateTest -q
```

- [ ] **Step 5: Extend `HoldDecision`**

`categories` is `List<TrustPredicate.Category>` here, the enum, not `List<String>` -- see this
plan's own Decisions table ("Should `HeldStatement`'s stored categories ever be raw strings a
person could hand-type?"). `TrustPredicate.Category` needs to exist before this compiles; if
writing this step before Step 6 below, either reorder or add a forward stub.

```java
public record HoldDecision(boolean hold, List<String> reasons, List<TrustPredicate.Category> categories) {

    public static final HoldDecision RELEASE = new HoldDecision(false, List.of(), List.of());

    public HoldDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** Convenience constructor for callers that have no category to report -- {@code
     *  HeldStatementService.rerunParser}'s extraction-failure case is the one real example: it is
     *  not one of {@link TrustPredicate}'s three conditions, so it legitimately has none. Kept so
     *  every call site written before this plan keeps compiling unchanged. */
    public HoldDecision(boolean hold, List<String> reasons) {
        this(hold, reasons, List.of());
    }

    public String summary() {
        return String.join("; ", reasons);
    }
}
```

- [ ] **Step 6: Add the category enum and wire it through `TrustPredicate.evaluate`**

```java
/** The machine-readable tag behind each of {@code evaluate}'s reason sentences -- see this
 *  plan's own Decisions table for why {@code held_statements.hold_reason_categories} exists
 *  rather than parsing {@code trigger_summary}'s prose back apart. */
public enum Category { COUNT_MISMATCH, DROPPED_TRANSACTION, PERIOD_INTEGRITY }
```

Add this as a nested type on `TrustPredicate`. Then change `evaluate`'s body:

```java
public static HoldDecision evaluate(List<ImportDto.VerificationReport> reports,
                                    List<LocalDate[]> periods,
                                    LocalDate today) {
    Set<String> reasons = new LinkedHashSet<>();
    Set<Category> categories = new LinkedHashSet<>();

    if (reports != null) {
        for (ImportDto.VerificationReport report : reports) {
            if (report == null || report.findings() == null) continue;
            for (ImportDto.VerificationFinding finding : report.findings()) {
                if (finding == null) continue;
                countMismatch(finding).ifPresent(r -> {
                    reasons.add(r);
                    categories.add(Category.COUNT_MISMATCH);
                });
                droppedTransaction(finding).ifPresent(r -> {
                    reasons.add(r);
                    categories.add(Category.DROPPED_TRANSACTION);
                });
            }
        }
    }
    if (periods != null) {
        for (LocalDate[] period : periods) {
            periodIntegrity(period, today).ifPresent(r -> {
                reasons.add(r);
                categories.add(Category.PERIOD_INTEGRITY);
            });
        }
    }

    return reasons.isEmpty() ? HoldDecision.RELEASE
            : new HoldDecision(true, List.copyOf(reasons), List.copyOf(categories));
}
```

`categories` holds the enum constants directly now, never `.name()` — the string conversion moves
to exactly one place, `HeldStatementService.openHold`, in Step 10 below.

Confirm the exact original method body first (`sed -n` or Read the file) — this plan's own
research quoted it verbatim from an earlier pass, but re-read it before editing rather than
trusting the quote, since intervening commits may have touched it.

- [ ] **Step 7: Run the `TrustPredicate` tests, confirm they pass**

```bash
cd backend && ./mvnw test -Dtest=TrustPredicateTest -q
```

- [ ] **Step 8: Add the column to `HeldStatement` and thread it through the snapshot**

```java
@Column(name = "hold_reason_categories")
private List<String> holdReasonCategories;
```

Extend `recordSnapshot`, and its own doc comment — this is the one place to state the immutability
this plan's Decisions table promises, since it's the only method that ever writes this field:

```java
/**
 * The extraction snapshot, recorded once when the hold is created.
 *
 * <p>{@code holdReasonCategories} is part of that same one-time snapshot, not a live view: it
 * answers "what did the ORIGINAL parse trigger on," and stays exactly what it was even after a
 * later {@code rerunParser} call re-evaluates the current build against the same bytes.
 * {@code rerunParser} never calls this method — confirmed by reading its full body — so a rerun
 * changing the hold's status can never retroactively change what this column says caused the
 * hold in the first place.
 */
public void recordSnapshot(String parserVersion, String reliabilityStatus, String textSource,
                           Boolean headerReconstructionUncertain, List<String> holdReasonCategories) {
    this.parserVersion = parserVersion;
    this.reliabilityStatus = reliabilityStatus;
    this.textSource = textSource;
    this.headerReconstructionUncertain = headerReconstructionUncertain;
    this.holdReasonCategories = holdReasonCategories;
}
```

This changes `recordSnapshot`'s arity — find and update its one real call site
(`HeldStatementService.openHold`) in Step 10. Add the getter:

```java
public List<String> getHoldReasonCategories() { return holdReasonCategories; }
```

- [ ] **Step 9: Write the migration**

```sql
-- The machine-readable tag behind each of TrustPredicate's reason sentences, snapshotted at hold
-- time the same way parser_version/reliability_status already are. Not derived from
-- trigger_summary -- that column is free text (see its own comment), and parsing it back apart
-- would tie a metrics query to TrustPredicate's exact reason-sentence wording. Populated from
-- TrustPredicate's own internal knowledge of which check produced which reason instead.
--
-- VARCHAR(64)[], matching the array-column precedent already in this codebase (transactions.tags,
-- V1__init_schema.sql). Nullable: a hold created before this migration has none, which is a fact,
-- not a zero -- a metrics query must be able to tell "no categories recorded" apart from "recorded
-- as empty," the same distinction VerificationTelemetry.isEmpty() already draws elsewhere in this
-- codebase for the identical reason.
ALTER TABLE held_statements ADD COLUMN hold_reason_categories VARCHAR(64)[];

COMMENT ON COLUMN held_statements.hold_reason_categories IS
    'Which of TrustPredicate''s conditions fired, snapshotted at hold time. Null for holds created before this column existed.';
```

- [ ] **Step 10: Update `HeldStatementService.openHold`'s call site**

```java
held.recordSnapshot(parserVersion,
        telemetry.reliabilityStatus() == null ? null : telemetry.reliabilityStatus().name(),
        telemetry.textSource(),
        telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain(),
        decision.categories().stream().map(Enum::name).toList());
```

`decision.categories()` is `List<TrustPredicate.Category>` (Step 5); `recordSnapshot` takes
`List<String>` (Step 8) — this is the one place that conversion happens, deliberately, per this
plan's own Decisions table ("Should `HeldStatement`'s stored categories ever be raw strings a
person could hand-type?"). `decision` is already in scope in `openHold` (it's a parameter) —
confirm this by reading the method's current signature before editing, don't assume it hasn't
changed since this plan's research pass read it.

- [ ] **Step 11: Add the entity test**

```java
@Test
void recordSnapshotCarriesTheHoldReasonCategories() {
    HeldStatement held = held();

    held.recordSnapshot("build-1", "NEEDS_ATTENTION", "NATIVE", false,
            List.of("COUNT_MISMATCH", "PERIOD_INTEGRITY"));

    assertThat(held.getHoldReasonCategories()).containsExactly("COUNT_MISMATCH", "PERIOD_INTEGRITY");
}
```

- [ ] **Step 12: Run the full `HeldStatement`/`HeldStatementService` test files, confirm no other
      call site broke**

```bash
cd backend && ./mvnw test -Dtest=HeldStatementTest,HeldStatementServiceRerunIT,HeldStatementRepositoryIT,HeldStatementQueryIT,AdminHeldStatementControllerIT,AdminHeldStatementAssignmentIT,AdminHeldStatementDownloadIT -q
```

- [ ] **Step 13: Self-review this task**

Check for bugs and gaps missed: confirm the mutation test from Step 3's dedup case actually
exercises the `LinkedHashSet` dedup on *categories*, not just on reasons (temporarily change
`categories.add(...)` to a `List` instead of the shared `Set` and confirm
`twoDifferentCountMismatchCausesStillProduceOneDeduplicatedCategory` fails, then revert). Confirm
`HeldStatementService.rerunParser`'s manual `new HoldDecision(true, List.of(...))` call still
compiles unchanged (it should, via the 2-arg constructor) and still means what it always meant —
an extraction failure with no category, not a silently-added `PERIOD_INTEGRITY` or similar.

- [ ] **Step 14: Commit**

```bash
git add backend/src/main/resources/db/migration/V152__held_statement_reason_categories.sql \
        backend/src/main/java/com/finora/imports/trust/HoldDecision.java \
        backend/src/main/java/com/finora/imports/trust/TrustPredicate.java \
        backend/src/main/java/com/finora/entity/HeldStatement.java \
        backend/src/main/java/com/finora/service/HeldStatementService.java \
        <the TrustPredicate test file> \
        backend/src/test/java/com/finora/entity/HeldStatementTest.java
git commit -m "feat: record which trust condition triggered each hold, structured"
```

---

### Task 2: Explicit false-positive marking on approve

**Files:**
- Create: `backend/src/main/resources/db/migration/V153__held_statement_false_positive.sql`
- Modify: `backend/src/main/java/com/finora/entity/HeldStatement.java`,
  `backend/src/main/java/com/finora/service/HeldStatementService.java`,
  `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java`,
  `backend/src/main/java/com/finora/dto/HeldStatementDto.java`
- Test: extend `HeldStatementTest.java`, `HeldStatementServiceRerunIT.java` (or wherever `approve`
  is already tested at the service level — read first), `AdminHeldStatementControllerIT.java`

**Interfaces:**
- Produces: `HeldStatement.getFalsePositive() : Boolean`, `HeldStatement.markImported(UUID, Instant,
  Boolean)` (extended), `HeldStatementService.approve(UUID, String, String, Boolean)` (extended) —
  consumed by Task 3's telemetry queries and Task 4's frontend checkbox.

- [ ] **Step 1: Write the failing tests**

In `HeldStatementTest.java`:

```java
@Test
void markImportedRecordsFalsePositiveWhenGiven() {
    HeldStatement held = held();

    held.markImported(UUID.randomUUID(), NOW, true);

    assertThat(held.getFalsePositive()).isTrue();
}

@Test
void markImportedLeavesFalsePositiveNullWhenNotGiven() {
    HeldStatement held = held();

    held.markImported(UUID.randomUUID(), NOW, null);

    assertThat(held.getFalsePositive()).isNull();
}

@Test
void aFreshHoldCanNeverCarryAFalsePositiveMark() {
    // Documents an invariant this plan relies on rather than enforces with a runtime check: the
    // entity exposes no way to set falsePositive except atomically with the IMPORTED transition
    // inside markImported -- there is no setFalsePositive(...), and no other mutator touches the
    // field. An external review asked for either a guard clause or a test proving the
    // relationship; a guard clause would be defending against a state the API surface already
    // cannot produce, so this test documents that instead of adding a redundant runtime check.
    // If a future change adds a second way to set this field, this test's own existence is the
    // signal to ask whether that new path also needs the same "only alongside IMPORTED" rule.
    HeldStatement held = held();

    assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.HELD);
    assertThat(held.getFalsePositive()).isNull();
}
```

In the service-level test file:

```java
@Test
void approveRecordsFalsePositiveOnTheHoldAndInTheEvent() {
    HeldStatement held = seedHold(CLEAN_CSV); // match this file's own existing seed helper

    heldStatementService.approve(admin(), held.getHeldId(), "looked fine after all", true);

    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getFalsePositive()).isTrue();
    List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
    HeldStatementEvent approved = events.stream()
            .filter(e -> "APPROVED".equals(e.getEventType())).findFirst().orElseThrow();
    assertThat(approved.getNotes()).containsIgnoringCase("false positive");
}

@Test
void rejectHasNoFalsePositiveParameter() {
    // Documents the Decisions-table call: reject's signature is unchanged by this plan. This
    // test exists to fail loudly if a future edit adds one without an explicit decision to widen
    // what "false positive" can mean.
    HeldStatement held = seedHold(CLEAN_CSV);

    heldStatementService.reject(admin(), held.getHeldId(), "genuinely bad extraction");

    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getFalsePositive()).isNull();
}
```

- [ ] **Step 2: Run, confirm compile failure**

- [ ] **Step 3: Write the migration**

```sql
-- Whether an operator determined, at approve time, that the trust predicate's flag was wrong --
-- the extraction was actually fine. Explicit and deliberate, never inferred from whether
-- root_cause/fix_reference were filled in or a parser re-run happened first: see this plan's own
-- Decisions table for why an inferred signal would be indistinguishable from an operator who
-- simply already knew, from memory, that nothing was wrong.
--
-- Nullable, with no default: null means "not marked either way" -- most holds are never asked
-- about, and a hold rejected instead of approved never gets this set at all (see the reject-has-
-- no-such-parameter decision in the same table). Only ever set once, at approve.
ALTER TABLE held_statements ADD COLUMN false_positive BOOLEAN;

COMMENT ON COLUMN held_statements.false_positive IS
    'Set only by an explicit operator mark at approve time. Null means never marked, not false.';
```

- [ ] **Step 4: Extend `HeldStatement.markImported`**

```java
public void markImported(UUID adminId, Instant now, Boolean falsePositive) {
    refuseIfResolved("imported");
    this.status = Status.IMPORTED;
    this.resolvedBy = adminId;
    this.resolvedAt = now;
    this.falsePositive = falsePositive;
}
```

Add the field and getter:

```java
@Column(name = "false_positive")
private Boolean falsePositive;
```
```java
public Boolean getFalsePositive() { return falsePositive; }
```

Find every existing call site of `markImported` (there is exactly one, in
`HeldStatementService.approve` — confirm this with `grep -rn "\.markImported(" backend/src/main
backend/src/test` before editing, in case a test constructs it directly) and update it in Step 5.

- [ ] **Step 5: Extend `HeldStatementService.approve`**

```java
@Transactional
public HeldStatementDto approve(UUID actingAdminId, String heldId, String note, Boolean falsePositive) {
    HeldStatement held = require(heldId);
    refuseIfResolved(held, "approved");

    ImportJob job = requireJob(held);
    Instant now = Instant.now();
    HeldStatement.Status from = held.getStatus();

    held.markImported(actingAdminId, now, falsePositive);
    job.releaseAfterTrustReview(now);
    repository.save(held);
    importJobRepository.save(job);

    String eventNote = (falsePositive != null && falsePositive)
            ? (note == null || note.isBlank() ? "Marked false positive." : note + " (marked false positive)")
            : note;
    eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "APPROVED",
            from.name(), held.getStatus().name(), eventNote));
    auditService.record(actingAdminId, "TRUST_REVIEW_APPROVED", "HeldStatement", held.getId(),
            Map.of("actorId", actingAdminId.toString(),
                    "subjectUserId", held.getUserId().toString(),
                    "heldId", held.getHeldId(),
                    "note", note == null ? "" : note,
                    "falsePositive", falsePositive == null ? "unmarked" : falsePositive.toString()));

    importSessionService.renewExpiry(job.getImportSessionId());
    notifyStatementReady(job);
    return HeldStatementDto.from(held);
}
```

Read the CURRENT `approve` method in full first (this plan's own research pass quoted it from an
earlier plan's writing, and Plan 3 may have touched code near it) — match its actual current body
exactly rather than assuming the quote above is still verbatim correct, and preserve every comment
already there (the sweep-exemption timing comment above `renewExpiry`, in particular).

- [ ] **Step 6: Update `HeldStatementDto`**

Add `falsePositive` after `fixReference`:

```java
        String rootCause,
        String fixReference,
        Boolean falsePositive,
        Instant createdAt,
```
```java
                held.getFixReference(),
                held.getFalsePositive(),
                held.getCreatedAt(),
```

- [ ] **Step 7: Update `AdminHeldStatementController.approve`**

```java
@PostMapping("/{heldId}/approve")
public ApiResponse<HeldStatementDto> approve(@PathVariable String heldId,
                                             @RequestBody(required = false) Map<String, String> body) {
    String note = body == null ? null : body.get("note");
    Boolean falsePositive = body == null || body.get("falsePositive") == null
            ? null : Boolean.valueOf(body.get("falsePositive"));
    return ApiResponse.ok(heldStatementService.approve(currentUser.id(), heldId, note, falsePositive),
            "Import released");
}
```

`Boolean.valueOf(String)` rather than `Boolean.parseBoolean` deliberately -- the latter treats
anything not exactly `"true"` (case-insensitively) as `false` with no way to detect a malformed
value; for a field going into permanent aggregate metrics, silently miscounting a typo as `false`
is worse than the difference not mattering here in practice. (`Boolean.valueOf` has the identical
behavior for well-formed input; this is a defensive choice, not a functional one — confirm this
reasoning still holds before shipping it, since both do return non-null `Boolean` for any string.)

- [ ] **Step 8: Run the tests, confirm they pass**

- [ ] **Step 9: Mutation-test the reject/approve asymmetry**

Temporarily add a `falsePositive` parameter to `reject` too (mirroring `approve`'s), confirm
`rejectHasNoFalsePositiveParameter` still compiles and passes trivially (it would, since the test
doesn't call the new parameter) — this shows that test alone is a weak guard. Strengthen it if
needed: assert that `HeldStatementService.reject`'s method signature has exactly the historical
arity via reflection, or accept that this is a design-intent test better enforced by code review
than by a runtime assertion, and say so in the test's own comment rather than leaving a false
sense of security.

- [ ] **Step 10: Self-review this task**

Check for bugs and gaps missed: confirm `approve`'s existing controller test(s) that call it with
only `note` (no `falsePositive` key at all) still pass — the new field must default to `null`
cleanly through the whole chain, not throw on a missing map key. Confirm the audit map's
`"falsePositive"` value is always a String (never a raw `Boolean` — `Map.of` typed as
`Map<String,Object>` would accept either, but check what `auditService.record`'s signature actually
expects and match it, don't assume).

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/resources/db/migration/V153__held_statement_false_positive.sql \
        backend/src/main/java/com/finora/entity/HeldStatement.java \
        backend/src/main/java/com/finora/service/HeldStatementService.java \
        backend/src/main/java/com/finora/controller/AdminHeldStatementController.java \
        backend/src/main/java/com/finora/dto/HeldStatementDto.java \
        backend/src/test/java/com/finora/entity/HeldStatementTest.java \
        <the service test file> <the controller IT file>
git commit -m "feat: let an operator mark a resolved hold as a false positive"
```

---

### Task 3: Backend telemetry aggregation

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/HeldStatementRepository.java`
- Create: `backend/src/main/java/com/finora/dto/HeldStatementTelemetryDto.java`,
  `backend/src/main/java/com/finora/service/HeldStatementTelemetryService.java`,
  `backend/src/main/java/com/finora/controller/AdminHeldStatementTelemetryController.java`
- Test: Create `backend/src/test/java/com/finora/service/HeldStatementTelemetryServiceIT.java`,
  `backend/src/test/java/com/finora/controller/AdminHeldStatementTelemetryControllerIT.java`

**Interfaces:**
- Consumes: `HeldStatement.holdReasonCategories`/`falsePositive` from Tasks 1-2.
- Produces: `HeldStatementTelemetryService.summary() : HeldStatementTelemetryDto.Summary`, `GET
  /api/v1/admin/held-statements/telemetry` — consumed by Task 4's frontend page.

- [ ] **Step 1: Write the failing IT**

```java
@Test
void summaryCountsHoldsResolutionsAndCategories() {
    // Seed: one open HELD, one IMPORTED (not false positive), one IMPORTED (false positive),
    // one REJECTED -- each with a real hold_reason_categories array via a real seedHold helper
    // matching this file's own pattern (read HeldStatementServiceRerunIT.seedHold first).
    seedResolvedHold(HeldStatement.Status.IMPORTED, List.of("COUNT_MISMATCH"), false);
    seedResolvedHold(HeldStatement.Status.IMPORTED, List.of("PERIOD_INTEGRITY"), true);
    seedResolvedHold(HeldStatement.Status.REJECTED, List.of("COUNT_MISMATCH", "DROPPED_TRANSACTION"), null);
    seedOpenHold(List.of("COUNT_MISMATCH"));

    HeldStatementTelemetryDto.Summary summary = telemetryService.summary();

    assertThat(summary.totalHolds()).isEqualTo(4);
    assertThat(summary.resolved()).isEqualTo(3);
    assertThat(summary.approved()).isEqualTo(2);
    assertThat(summary.rejected()).isEqualTo(1);
    assertThat(summary.falsePositives()).isEqualTo(1);
    assertThat(summary.byCategory()).containsEntry("COUNT_MISMATCH", 3L);
    assertThat(summary.byCategory()).containsEntry("PERIOD_INTEGRITY", 1L);
    assertThat(summary.byCategory()).containsEntry("DROPPED_TRANSACTION", 1L);
}

@Test
void medianResolutionHoursIsNullWhenNothingHasResolvedYet() {
    seedOpenHold(List.of("COUNT_MISMATCH"));

    HeldStatementTelemetryDto.Summary summary = telemetryService.summary();

    assertThat(summary.medianResolutionHours()).isNull();
}

@Test
void aHoldWithNoRecordedCategoriesIsCountedButExcludedFromByCategory() {
    // Simulates a hold created before Task 1's migration -- hold_reason_categories is genuinely
    // NULL, not an empty array. An external review specifically asked for this as its own test:
    // the "excluded, not counted as zero" claim appears in several doc comments across this plan
    // but nothing before this test actually proved unnest() on a NULL array column behaves the
    // way those comments assume, against real Postgres.
    seedResolvedHold(HeldStatement.Status.IMPORTED, null, false); // null categories, not List.of()

    HeldStatementTelemetryDto.Summary summary = telemetryService.summary();

    assertThat(summary.totalHolds()).isEqualTo(1);
    assertThat(summary.resolved()).isEqualTo(1);
    assertThat(summary.byCategory()).isEmpty();
}
```

Write `seedResolvedHold`/`seedOpenHold` helpers matching `HeldStatementServiceRerunIT`'s own
`seedHold` pattern (real storage-backed bytes are NOT needed here — this test never calls
`dryRunParse`/`download`, so a fake content hash/object key is fine, matching
`AdminHeldStatementControllerIT.seedHold`'s simpler pattern instead). Read both existing patterns
first and pick whichever this new file's actual needs match — don't default to the heavier one
out of habit.

- [ ] **Step 2: Run, confirm compile failure**

- [ ] **Step 3: Add the repository queries**

```java
/** One row per (status, count) -- the resolution breakdown. Native, matching {@code
 *  ImportJobRepository}'s own telemetry-query idiom in this codebase. */
@Query(value = "SELECT status, count(*) FROM held_statements GROUP BY status", nativeQuery = true)
List<Object[]> telemetryStatusCounts();

/**
 * How many resolved holds were explicitly marked false positive. {@code FILTER} matches this
 * codebase's own established telemetry idiom ({@code ImportJobRepository.telemetryFlagCounts}).
 *
 * <p>{@code resolvedStatuses} is passed in rather than hardcoded as {@code status IN ('IMPORTED',
 * 'REJECTED')} in the SQL string, so this query's idea of "resolved" can never drift from
 * {@code HeldStatement.Status.RESOLVED}'s own {@code EnumSet} -- the caller passes that set's
 * {@code .name()}s, not a second, hand-maintained copy of the same two strings. See this plan's
 * own Decisions table.
 */
@Query(value = """
       SELECT count(*) FILTER (WHERE status = ANY(:resolvedStatuses)),
              count(*) FILTER (WHERE false_positive = true)
         FROM held_statements
       """, nativeQuery = true)
List<Object[]> telemetryFalsePositiveCounts(@Param("resolvedStatuses") String[] resolvedStatuses);

/** Which trust condition fired, across every hold that has any recorded (older holds predating
 *  V152 have null and are correctly excluded, not counted as zero). {@code unnest} rather than
 *  one FILTER per category, so a category Brief Phase 11 adds later needs no new query -- see
 *  this plan's own Decisions table. */
@Query(value = """
       SELECT category, count(*)
         FROM held_statements, unnest(hold_reason_categories) AS category
        GROUP BY category
       """, nativeQuery = true)
List<Object[]> telemetryCategoryCounts();

/**
 * Median hours from created to resolved, over every hold that has resolved. Median, not mean --
 * see this plan's own Decisions table for why. Null (via a single-row, single-null-column result
 * when nothing has resolved) rather than a division-by-zero exception.
 *
 * <p>Intentionally live and unsummarized, matching every other query in this class -- computed
 * fresh from the full table on every request, nothing cached or scheduled. {@code
 * PERCENTILE_CONT} over the whole table is one of the more expensive queries this class runs;
 * fine at this system's current and expected near-term volume, but if {@code held_statements}
 * ever reaches the scale where this becomes a real cost, revisit rather than assume it stays
 * cheap forever -- an external review of this plan flagged this explicitly so it's a documented
 * trade-off, not a surprise later.
 */
@Query(value = """
       SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (
                  ORDER BY EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0)
         FROM held_statements
        WHERE resolved_at IS NOT NULL
       """, nativeQuery = true)
Double telemetryMedianResolutionHours();
```

- [ ] **Step 4: Add the DTO**

```java
package com.finora.dto;

import java.util.Map;

/**
 * The aggregate readout over held_statements. Same "cheap live aggregate, counts never rates"
 * discipline as {@link ImportTelemetryDto} -- see that class's own doc for the full reasoning.
 *
 * @param totalHolds           every held statement ever created, open or resolved
 * @param resolved             totalHolds that reached IMPORTED or REJECTED. NOT the denominator
 *                             for falsePositives -- see that field's own doc.
 * @param approved             resolved via approve (IMPORTED). The actual denominator for
 *                             falsePositives: a rejection can never be marked one (see this
 *                             plan's Decisions table), so falsePositives / resolved understates
 *                             the true proportion by folding in rejections that were never
 *                             eligible to be a false positive in the first place.
 * @param rejected             resolved via reject (REJECTED)
 * @param falsePositives       of `approved` (not `resolved` -- see that field's own doc), how
 *                             many an operator explicitly marked false positive at approve time.
 *                             Never divided by anything here -- see this plan's Global
 *                             Constraints. A caller computing a proportion should divide by
 *                             `approved`.
 * @param byCategory           which TrustPredicate condition fired, across every hold that
 *                             recorded one -- a hold predating V152 (Plan 4) is excluded, not
 *                             counted as zero
 * @param medianResolutionHours median hours from created to resolved, over resolved holds only.
 *                             Null when nothing has resolved yet.
 */
public record HeldStatementTelemetryDto(
        long totalHolds,
        long resolved,
        long approved,
        long rejected,
        long falsePositives,
        Map<String, Long> byCategory,
        Double medianResolutionHours) {

    public record Summary(
            long totalHolds, long resolved, long approved, long rejected, long falsePositives,
            Map<String, Long> byCategory, Double medianResolutionHours) {}
}
```

(The outer record and the nested `Summary` end up structurally identical here, unlike
`ImportTelemetryDto`'s own shape which has a real `ParserVersionBreakdown` sub-record alongside its
`Summary` -- collapse this to just `HeldStatementTelemetryDto.Summary` with no outer wrapper if,
when actually writing this, a second nested shape never materializes. Don't keep a pointless
wrapper record only because `ImportTelemetryDto` happens to have one for a different reason.)

- [ ] **Step 5: Add the service**

```java
package com.finora.service;

import com.finora.dto.HeldStatementTelemetryDto;
import com.finora.repository.HeldStatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The aggregate view over held-statement trust-review outcomes. Same "cheap live aggregate, not a
 * reporting subsystem" shape as {@link AdminImportTelemetryService} -- a handful of grouped
 * counts answered from the table on request, nothing materialised, nothing scheduled.
 *
 * <p><b>Counts, never rates.</b> See {@link AdminImportTelemetryService}'s own doc for the full
 * reasoning this plan's Global Constraints restate.
 */
@Service
public class HeldStatementTelemetryService {

    private final HeldStatementRepository repository;

    public HeldStatementTelemetryService(HeldStatementRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public HeldStatementTelemetryDto.Summary summary() {
        long totalHolds = repository.count();

        long approved = 0;
        long rejected = 0;
        for (Object[] row : repository.telemetryStatusCounts()) {
            String status = (String) row[0];
            long count = toLong(row[1]);
            if ("IMPORTED".equals(status)) approved = count;
            if ("REJECTED".equals(status)) rejected = count;
        }

        // HeldStatement.Status.RESOLVED is this codebase's own existing EnumSet (already defined
        // in Plan 1, unchanged by this plan) -- reading it here rather than writing 'IMPORTED',
        // 'REJECTED' a second time as SQL string literals is exactly what keeps the two
        // definitions from drifting apart. See this plan's own Decisions table.
        String[] resolvedStatuses = HeldStatement.Status.RESOLVED.stream()
                .map(Enum::name).toArray(String[]::new);
        List<Object[]> fp = repository.telemetryFalsePositiveCounts(resolvedStatuses);
        long resolved = fp.isEmpty() ? 0 : toLong(fp.get(0)[0]);
        long falsePositives = fp.isEmpty() ? 0 : toLong(fp.get(0)[1]);

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : repository.telemetryCategoryCounts()) {
            byCategory.put((String) row[0], toLong(row[1]));
        }

        Double medianHours = repository.telemetryMedianResolutionHours();

        return new HeldStatementTelemetryDto.Summary(
                totalHolds, resolved, approved, rejected, falsePositives, byCategory, medianHours);
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
```

Check whether `AdminImportTelemetryService`'s own `toLong` helper is private-per-class or already
shared somewhere reusable before duplicating it — if it's already a small private static method
repeated once, duplicating it again here is consistent with the existing pattern (don't invent a
shared utility class for a three-line method two classes both want; if the codebase later wants
that, it's a separate, deliberate refactor).

- [ ] **Step 6: Add the controller**

```java
package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.HeldStatementTelemetryDto;
import com.finora.service.HeldStatementTelemetryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The aggregate readout over trust-review holds. Same gate and the same reasoning as {@code
 * AdminImportTelemetryController}: {@code PLATFORM_DIAGNOSTICS_VIEW}, not {@code
 * TRUST_REVIEW_MANAGE} -- this is engineering telemetry about how the pipeline behaves, not
 * access to any customer's hold. The response is counts only: no held id, no user id, no bank
 * name tied to an individual row. See this plan's own Decisions table.
 */
@RestController
@RequestMapping("/api/v1/admin/held-statements/telemetry")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminHeldStatementTelemetryController {

    private final HeldStatementTelemetryService telemetryService;

    public AdminHeldStatementTelemetryController(HeldStatementTelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @GetMapping
    public ApiResponse<HeldStatementTelemetryDto.Summary> summary() {
        return ApiResponse.ok(telemetryService.summary());
    }
}
```

Confirm this route does not collide with `AdminHeldStatementController`'s own
`/api/v1/admin/held-statements/{heldId}/...` paths -- `/telemetry` as a literal path segment where
`{heldId}` is a path variable could ambiguously match `GET /api/v1/admin/held-statements/telemetry`
against `GET /{heldId}` on the OTHER controller, if Spring's route matching doesn't disambiguate a
literal segment from a path variable the way expected. Verify this concretely in Step 8 with a
real request rather than assuming Spring resolves it the intuitive way -- this is exactly the kind
of thing this plan's "no guessing" discipline exists for.

- [ ] **Step 7: Run the tests, confirm they pass**

- [ ] **Step 8: Verify the route doesn't collide, with a real request**

Add (or confirm, if Step 6's concern turns out unfounded) a test hitting
`GET /api/v1/admin/held-statements/telemetry` and asserting it reaches
`AdminHeldStatementTelemetryController`, not `AdminHeldStatementController.detail("telemetry")`
(which would otherwise 404 or misbehave treating "telemetry" as a `heldId`). If it collides, the
fix is a distinct URL prefix (e.g. `/api/v1/admin/held-statement-telemetry`) — do not restructure
`AdminHeldStatementController`'s existing, already-shipped routes to avoid it.

- [ ] **Step 9: Self-review this task**

Check for bugs and gaps missed: confirm `telemetryCategoryCounts`'s `unnest` query correctly
excludes rows where `hold_reason_categories IS NULL` (Postgres's `unnest` on a NULL array
column produces zero rows for that row automatically — verify this is actually true against real
Postgres in the IT, don't assume the SQL semantics). Confirm `medianResolutionHours` genuinely
returns `null` (not `0.0` or `NaN`) when no hold has ever resolved — `PERCENTILE_CONT` over zero
rows: verify what it actually returns, don't assume. Confirm `telemetryFalsePositiveCounts(String[]
resolvedStatuses)`'s `status = ANY(:resolvedStatuses)` binding actually works with a plain Java
`String[]` passed through a Spring Data `@Param` on a native query — Hibernate's native-query array
binding for Postgres sometimes needs an explicit cast or a different parameter type
(`Collection<String>` bound as `IN :resolvedStatuses` instead, if `ANY(:resolvedStatuses)` doesn't
bind cleanly) — this is exactly the class of thing to catch by actually running the IT with a real
`HeldStatement.Status.RESOLVED` array, not by reading the SQL and assuming it compiles the way it
reads.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/finora/repository/HeldStatementRepository.java \
        backend/src/main/java/com/finora/dto/HeldStatementTelemetryDto.java \
        backend/src/main/java/com/finora/service/HeldStatementTelemetryService.java \
        backend/src/main/java/com/finora/controller/AdminHeldStatementTelemetryController.java \
        backend/src/test/java/com/finora/service/HeldStatementTelemetryServiceIT.java \
        backend/src/test/java/com/finora/controller/AdminHeldStatementTelemetryControllerIT.java
git commit -m "feat: aggregate telemetry over trust-review holds"
```

---

### Task 4: Admin-portal metrics page + the false-positive checkbox

**Files:**
- Create: `admin-portal/src/pages/TrustReviewMetrics.tsx`,
  `admin-portal/src/pages/TrustReviewMetrics.test.tsx`
- Modify: `admin-portal/src/types/index.ts`, `admin-portal/src/api/endpoints.ts`,
  `admin-portal/src/App.tsx`, `admin-portal/src/components/Sidebar.tsx`,
  `admin-portal/src/pages/HeldStatementDetail.tsx`,
  `admin-portal/src/pages/HeldStatementDetail.test.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/admin/held-statements/telemetry` from Task 3;
  `adminHeldStatementApi.approve` (Task 2's backend change needs a frontend counterpart here).

- [ ] **Step 1: Extend the approve mutation with the false-positive checkbox**

In `admin-portal/src/types/index.ts`, add `falsePositive: boolean | null;` to `HeldStatementRow`
(after `fixReference`, matching the backend's field order) and a new
`HeldStatementTelemetrySummary` interface mirroring `HeldStatementTelemetryDto.Summary` exactly
(field-for-field, same names).

In `admin-portal/src/api/endpoints.ts`:

```ts
approve: (heldId: string, note?: string, falsePositive?: boolean) =>
  api.post<HeldStatementRow>(`/admin/held-statements/${heldId}/approve`, { note, falsePositive })
    .then((r) => r.data),
```

(Widening an existing method's signature with an optional trailing parameter — confirm every
existing call site of `adminHeldStatementApi.approve` still compiles with no changes, since
`falsePositive` is optional and trailing.)

```ts
telemetry: () =>
  api.get<HeldStatementTelemetrySummary>('/admin/held-statements/telemetry').then((r) => r.data),
```

- [ ] **Step 2: Write the failing tests for the checkbox**

In `HeldStatementDetail.test.tsx`, near the existing approve tests:

```tsx
it('sends falsePositive when the checkbox is checked at approve time', async () => {
  mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
  renderPage();
  await screen.findByText(/count disagree/i);

  fireEvent.click(screen.getByLabelText(/mark as false positive/i));
  fireEvent.click(screen.getByRole('button', { name: /^approve$/i }));

  await waitFor(() => expect(adminHeldStatementApi.approve)
    .toHaveBeenCalledWith('HLD-2026-100001', undefined, true));
});

it('omits falsePositive entirely when the checkbox is left unchecked, never sends false', async () => {
  // Caught by an external review of this plan's first draft: a plain useState(false) fed
  // straight into the mutation would send an explicit `false` on every single approval,
  // permanently collapsing the backend's null ("never marked") vs false ("explicitly not a false
  // positive") distinction the moment this UI shipped. This test exists specifically to catch
  // that regression, not just to describe the happy path.
  mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
  renderPage();
  await screen.findByText(/count disagree/i);

  fireEvent.click(screen.getByRole('button', { name: /^approve$/i }));

  await waitFor(() => expect(adminHeldStatementApi.approve)
    .toHaveBeenCalledWith('HLD-2026-100001', undefined, undefined));
});
```

- [ ] **Step 3: Run, confirm they fail** (no checkbox yet)

- [ ] **Step 4: Add the checkbox to `HeldStatementDetail.tsx`**

Near the existing `approveNote` input, in the Resolution section:

```tsx
const [markFalsePositive, setMarkFalsePositive] = useState(false);
```

```tsx
const approve = useMutation({
  // markFalsePositive || undefined, not the bare boolean -- see this plan's own Decisions table.
  // The checkbox defaults to false, so passing it straight through would send an explicit
  // `false` on every approval where the operator never touched it, which is indistinguishable
  // from "explicitly reviewed and confirmed not a false positive." Only `true` (checked) is ever
  // a real signal from this control; unchecked must reach the backend as an absent field, so the
  // nullable `Boolean falsePositive` Task 2 built stays reachable as `null`.
  mutationFn: () => adminHeldStatementApi.approve(
      heldId, approveNote || undefined, markFalsePositive || undefined),
  onSuccess: () => { setActionError(null); invalidate(); },
  onError,
});
```

```tsx
<label className="flex items-center gap-2 text-xs text-muted">
  <input
    type="checkbox"
    checked={markFalsePositive}
    onChange={(e) => setMarkFalsePositive(e.target.checked)}
    disabled={resolved}
  />
  Mark as false positive -- the trust predicate flagged this, but the extraction was actually fine
</label>
```

Place it directly above the Approve button, inside the same `<div>` the note input and Approve
button already share, so it reads as "what you're about to do" rather than a separate section.

- [ ] **Step 5: Run the tests, confirm they pass**

- [ ] **Step 6: Write the failing test for the metrics page**

`admin-portal/src/pages/TrustReviewMetrics.test.tsx`, following `HeldStatements.test.tsx`'s own
setup pattern (mock `adminHeldStatementApi`, `useAdminAuth`):

```tsx
it('is gated on PLATFORM_DIAGNOSTICS_VIEW', () => {
  mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']); // has queue access, not diagnostics
  renderPage();

  expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
});

it('shows the total, resolution, and false-positive counts', async () => {
  vi.mocked(adminHeldStatementApi.telemetry).mockResolvedValue({
    totalHolds: 12, resolved: 8, approved: 6, rejected: 2, falsePositives: 2,
    byCategory: { COUNT_MISMATCH: 7, PERIOD_INTEGRITY: 1 },
    medianResolutionHours: 4.5,
  });
  mockAuth(['PLATFORM_DIAGNOSTICS_VIEW'], ['ADMIN']);
  renderPage();

  expect(await screen.findByText('12')).toBeInTheDocument();
  expect(screen.getByText('8')).toBeInTheDocument();
  expect(screen.getByText(/COUNT_MISMATCH/)).toBeInTheDocument();
});

it('shows a category placeholder for a hold that predates category tracking, not a broken number', async () => {
  // totalHolds: 1 with an empty byCategory is a real, reachable state -- a hold created before
  // Task 1's migration, which was never snapshotted with a category. It is deliberately NOT the
  // same as "zero holds" (the tile above still correctly shows 1), so the placeholder text must
  // not claim a specific cause ("no holds resolved yet") that isn't the one this scenario has.
  vi.mocked(adminHeldStatementApi.telemetry).mockResolvedValue({
    totalHolds: 1, resolved: 0, approved: 0, rejected: 0, falsePositives: 0,
    byCategory: {}, medianResolutionHours: null,
  });
  mockAuth(['PLATFORM_DIAGNOSTICS_VIEW'], ['ADMIN']);
  renderPage();

  await screen.findByText('1');
  expect(screen.getByText(/no trust-condition data recorded yet/i)).toBeInTheDocument();
});
```

- [ ] **Step 7: Run, confirm they fail** (no page yet)

- [ ] **Step 8: Write the page**

```tsx
import { useQuery } from '@tanstack/react-query';
import { ShieldAlert, CheckCircle2, XCircle, AlertTriangle, Clock } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { StatCard } from '../components/StatCard';
import { adminHeldStatementApi } from '../api/endpoints';

function TrustReviewMetricsContent() {
  const { data, isLoading } = useQuery({
    queryKey: ['trust-review-telemetry'],
    queryFn: () => adminHeldStatementApi.telemetry(),
  });

  if (isLoading) return <p className="text-muted text-sm">Loading…</p>;
  if (!data) return <p className="text-muted text-sm">Could not load telemetry.</p>;

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-4">
        <StatCard icon={ShieldAlert} label="Total holds" value={data.totalHolds} />
        <StatCard icon={CheckCircle2} label="Approved" value={data.approved} />
        <StatCard icon={XCircle} label="Rejected" value={data.rejected} />
        <StatCard icon={AlertTriangle} label="False positives" value={data.falsePositives} tone="warning" />
        <StatCard
          icon={Clock}
          label="Median resolution"
          value={data.medianResolutionHours == null ? '—' : `${data.medianResolutionHours.toFixed(1)}h`}
        />
      </div>

      <section className="bg-card border border-border rounded-xl2 p-6">
        <h3 className="text-sm font-semibold text-ink mb-3">Holds by triggering condition</h3>
        <p className="text-xs text-muted mb-3">All holds, open or resolved -- which trust signals generate review work.</p>
        {Object.keys(data.byCategory).length === 0 ? (
          <p className="text-muted text-xs">No trust-condition data recorded yet.</p>
        ) : (
          <dl className="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
            {Object.entries(data.byCategory).map(([category, count]) => (
              <div key={category}>
                <dt className="text-muted text-xs font-mono">{category}</dt>
                <dd className="text-ink text-lg">{count}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>
    </div>
  );
}

export default function TrustReviewMetrics() {
  return (
    <AdminLayout title="Trust Review Metrics" subtitle="Aggregate counts over the held-statement queue -- no customer data.">
      <RequirePermission permission="PLATFORM_DIAGNOSTICS_VIEW">
        <TrustReviewMetricsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
```

Match `StatCard`'s exact prop names by reading `admin-portal/src/components/StatCard.tsx` first
(this plan's own research read them as `icon`/`label`/`value`/`tone`, but confirm before trusting
it). The placeholder still tests `Object.keys(data.byCategory).length === 0` -- that condition was
always the right one for deciding whether there is anything to map over, empty is empty regardless
of cause. What this plan's first draft got wrong was the LABEL: "No holds resolved yet" claims a
specific reason (nothing has resolved) for a state that has at least two real causes -- genuinely
zero holds, or holds that exist but predate V152 (Task 1's migration) and so were never snapshotted
with a category at all. `byCategory` is populated from every hold that has ever recorded a
category, open or resolved (Task 3's `telemetryCategoryCounts` query carries no `status` filter, by
deliberate decision -- see this plan's own Decisions table, "Does `byCategory` count every hold or
only resolved ones?"), so a hold count of 1 with an empty `byCategory` is a real, reachable state
(one legacy hold, no recorded category) that "No holds resolved yet" would describe wrongly. "No
trust-condition data recorded yet" is accurate under every cause of the section being empty, not
just one of them.

- [ ] **Step 9: Wire the route and nav entry**

`App.tsx`: add a lazy route for `TrustReviewMetrics`, matching the existing `HeldStatements`/
`HeldStatementDetail` lazy-route pattern.

`Sidebar.tsx`: add a nav entry near the existing Held Statements entry:

```tsx
{ to: '/trust-review-metrics', label: 'Trust Review Metrics', icon: BarChart3, end: false, permission: 'PLATFORM_DIAGNOSTICS_VIEW' },
```

Confirm `PLATFORM_DIAGNOSTICS_VIEW` really is already in `AdminAuthContext.tsx`'s
`ADMIN_PORTAL_PERMISSIONS` array before assuming the nav entry alone is sufficient — this plan's
own research read it as already present (`AdminAuthContext.tsx:31`), but re-confirm by reading the
file directly rather than trusting the quote, the same discipline every other plan in this series
already applies to itself.

- [ ] **Step 10: Run the tests, confirm they pass**

```bash
cd admin-portal && npx vitest run TrustReviewMetrics.test.tsx HeldStatementDetail.test.tsx
```

- [ ] **Step 11: Self-review this task**

Check for bugs and gaps missed: confirm the false-positive checkbox resets to unchecked after a
successful approve, or after navigating to a different held statement (same stale-state class of
bug [[the Plan 3 cross-task review]] found once already for `rerunResult` — read whether this
needs its own `useEffect` reset keyed on `heldId`, the same fix Plan 3 applied). Confirm the
checkbox is not shown/enabled on an already-resolved hold (`disabled={resolved}` from Step 4,
verify it's actually wired, not just written in the draft above).

- [ ] **Step 12: Commit**

```bash
git add admin-portal/src/pages/TrustReviewMetrics.tsx admin-portal/src/pages/TrustReviewMetrics.test.tsx \
        admin-portal/src/types/index.ts admin-portal/src/api/endpoints.ts admin-portal/src/App.tsx \
        admin-portal/src/components/Sidebar.tsx \
        admin-portal/src/pages/HeldStatementDetail.tsx admin-portal/src/pages/HeldStatementDetail.test.tsx
git commit -m "feat: add trust-review metrics page and false-positive checkbox"
```

---

### Task 5: Full verification

- [ ] **Step 1:** `cd backend && ./mvnw clean verify` — run alone, never concurrently with another
      Maven invocation against the same `target/`.
- [ ] **Step 2:** `cd admin-portal && npx vitest run && npx tsc -b && npx eslint . --max-warnings 0`
- [ ] **Step 3: Re-run the corpus calibration and confirm the VERDICT distribution is unchanged.**
      Unlike Plan 3, this plan touches `TrustPredicate.evaluate` itself (Task 1 adds category
      bookkeeping alongside the existing reason bookkeeping) — the hold/no-hold boolean logic is
      unchanged, but this needs confirming with the actual instrument, not asserted from reading
      the diff. Run with `FINORA_CORPUS_DIR` set and confirm the report still reads
      `would be HELD by TrustPredicate: 0 of 27 that parsed` (or whatever the corpus baseline is at
      execution time — re-check the current baseline first if this plan sits unexecuted for a
      while and other work has touched `TrustPredicate` meanwhile).
- [ ] **Step 4: Cross-task review.** Before committing anything further, re-read Tasks 1-4
      together, not one at a time — this has found real bugs on both of the prior two plans in
      this series ([[cross-task-review-catches-what-per-task-review-misses]]). Specifically check:
      (a) does the `byCategory` resolved-vs-all-holds question from Task 4 Step 8 actually get
      resolved consistently between the backend query and the frontend's empty-state condition;
      (b) does `HeldStatementTelemetryDto`'s field names match `HeldStatementTelemetrySummary`'s
      TypeScript field names exactly; (c) does anything in Tasks 1-2's doc comments assert something
      about Task 3's code that turned out different once written; (d) does the false-positive
      checkbox's stale-state behavior (Task 4 Step 11) actually get fixed, not just flagged.
- [ ] **Step 5:** Commit any fixes from Step 4, then follow whatever branch/PR granularity the
      user directs at execution time — this plan does not presume either of the prior two plans'
      choices applies here unless re-confirmed.

---

## Self-Review

**Spec coverage:** Brief Phase 10, per Plan 1's scope table, covers "Metrics & False-Positive
Tracking." Metrics → Task 3 (aggregate telemetry service) + Task 4 (the page that shows it).
False-positive tracking → Task 2 (the explicit mark) + Task 3's `falsePositives`/`resolved` counts
+ Task 4's checkbox. The one piece of Phase 10 this plan does NOT claim to cover: the brief's own
exact text was never recovered (see this plan's own Spec section), so this is Phase 10 as
reconstructed from what Plans 1-3 already built plus the repository owner's direct answer on the
one genuinely ambiguous point (false-positive capture mechanism) — not a verified match against
brief text nobody could read.

**Placeholder scan:** Every step shows real code or names the exact existing file to read and
match first (Task 3 Step 5's `toLong` helper explicitly says to check the existing one rather than
assume). Task 3 Step 1's seed-helper guidance explicitly says which of two existing patterns to
prefer and why, rather than "add appropriate test fixtures."

**Type consistency:** `HeldStatementTelemetryDto.Summary`'s six fields
(`totalHolds`/`resolved`/`approved`/`rejected`/`falsePositives`/`byCategory`/`medianResolutionHours`)
are used identically in Task 3 (Java), Task 4 (`HeldStatementTelemetrySummary` TS interface), and
the page component. `HoldDecision.categories()` is `List<TrustPredicate.Category>` (the enum, not
raw strings — see this plan's Decisions table), converted to `.name()` strings exactly once, in
`HeldStatementService.openHold`; those strings (`"COUNT_MISMATCH"`, `"DROPPED_TRANSACTION"`,
`"PERIOD_INTEGRITY"`) are what actually reaches Postgres and are the exact keys Task 3's
`byCategory` map and Task 4's page both key on — no second translation layer anywhere else, checked
explicitly in Task 5's cross-task review.

**Reviewed and revised before implementation:** an external review of this plan's first draft
raised five points. All five changed the plan directly: (1) confirmed `hold_reason_categories`'
immutability against Plan 3's actual `rerunParser` code rather than leaving it merely implied — now
stated explicitly in `recordSnapshot`'s own doc; (2) the resolved-status count now takes
`HeldStatement.Status.RESOLVED` as a query parameter instead of hardcoding `'IMPORTED', 'REJECTED'`
as SQL literals a second time; (3) the route-collision check (already planned) was reaffirmed as
worth keeping explicitly, not weakened; (4) — the sharpest catch — this plan's own first-draft
frontend sent an explicit `false` on every unchecked approval, which would have silently defeated
`Boolean falsePositive`'s entire reason for being nullable the moment the UI shipped; fixed to omit
the field instead, verified against the actual mutation code, not just reasoned about abstractly;
(5) the `byCategory` all-holds-vs-resolved-only question this plan had already flagged as open was
resolved in favor of all holds, and the frontend's own placeholder text (not its rendering
condition, which was already correct) was fixed to match — a smaller, more precise fix than this
plan's own first attempt at addressing it, corrected again after re-reasoning about the actual edge
case rather than accepting the first fix that compiled.

A second, independent external review of the revised plan found the `byCategory` question already
resolved (confirmed against this document as it then stood, not re-litigated) and raised five more
points, four of which changed the plan: (1) a test now documents, rather than merely implies, that
a fresh `HeldStatement` can never carry a false-positive mark — `HeldStatementTest.aFreshHoldCanNeverCarryAFalsePositiveMark`;
a runtime guard clause was considered and rejected as
redundant, since the entity already exposes no mutator that could produce that state, and the
reasoning for rejecting it is recorded rather than left implicit; (2) a real analytical error in
this plan's own first-draft DTO doc — `resolved` was documented as "the honest denominator for
falsePositives," when `approved` is the correct one, since `reject` can never produce a false
positive and folding rejections into the denominator understates the true proportion — fixed as a
doc-comment correction on `HeldStatementTelemetryDto`, no API shape change; (3) the trust-reason
categories moved from `List<String>` to `List<TrustPredicate.Category>` at the `HoldDecision`
boundary specifically, converted to strings in exactly one place before persistence — adopted
partially rather than fully (the entity/DB layer
stays plain strings), with the reasoning for stopping there recorded in the Decisions table rather
than silently declining the fuller suggestion; (4) `PERCENTILE_CONT`'s query now says explicitly,
in its own doc comment, that it is intentionally live and unsummarized and names the condition
(`held_statements` reaching real scale) under which that choice should be revisited, rather than
leaving a future reader to wonder whether the cost was ever considered; (5) a dedicated test now
proves a `hold_reason_categories IS NULL` hold is excluded from `byCategory` rather than counted as
zero, against real Postgres — the "excluded, not counted as zero" claim had appeared in several
doc comments already but nothing before this test actually verified it.

**Known risks this plan does not close:**
- The brief's own Phase 10 text could not be recovered (see Spec section) — this plan is a
  reconstruction from evidence, not a verified match. If the original brief specified something
  more (a scheduled report, an alert threshold, a specific chart), this plan does not build it,
  because nothing in this repository could confirm it was ever asked for.
- No alerting or threshold-based gating is built here — Task 3's telemetry is read-only and
  on-demand, matching every "cheap live aggregate" precedent in this codebase. If Brief Phase 11
  (future trust signals) eventually wants an automated response to a rising false-positive rate,
  that is new scope, not something this plan's read-only aggregate quietly already does.
- `hold_reason_categories` and `false_positive` are both null for every hold created before this
  plan ships — historical holds are invisible to `byCategory` and undercounted in
  `falsePositives`'s true rate (though not in the `resolved` denominator, which counts by status,
  not by whether a category was ever recorded). This is the same "the column didn't exist yet"
  gap `bank_name`/`root_cause`/`fix_reference` already accepted in Plans 2 and 3 — consistent with
  this series' established practice of snapshotting forward rather than backfilling history that
  was never captured.

**Next:** All four plans in the Held Statement Review System are then complete. Brief Phase 11
(future trust signals) stays explicitly deferred, per the brief's own instruction quoted in Plan 1,
until this plan's own telemetry has accumulated real volume to look at.
