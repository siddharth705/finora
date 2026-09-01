# CPU profile — `ReconciliationService.reconcileForUser`

**Date:** 2026-08-05 · **Purpose:** establish a permanent performance baseline, not to justify a
change. No code was modified to produce this.

---

## How to reproduce

JDK 21 ships Flight Recorder, so this needs no tooling that is not already on any machine that can
build the project.

```bash
cd backend
./mvnw -o test -Dtest='ReconciliationScalingBenchmark#measureReconcileAndDetectAcrossHistorySizes' \
  -Dfinora.benchmark=true \
  -DfailIfNoTests=false \
  -DargLine="-XX:StartFlightRecording=filename=recon.jfr,settings=profile,dumponexit=true"

"$JAVA_HOME/bin/jfr" print --events jdk.ExecutionSample --stack-depth 128 recon.jfr > samples.txt
```

Then aggregate `samples.txt`, keeping only samples whose stack contains `reconcileForUser` and
trimming each stack at that frame. **325 of 382 samples** landed inside `reconcileForUser`; the rest
are JVM startup and class loading.

Benchmark methodology **v2** (see `scaling-triggers.md`). The run covers 1k, 10k and 50k in
sequence; the 50k pass dominates wall time, so the profile is effectively of that case.

---

## Result

Percentages are of samples inside `reconcileForUser`, not of the whole JVM.

| exclusive | inclusive | method |
|---:|---:|---|
| 21.5% | 100.0% | `ReconciliationService.reconcileForUser` |
| 20.0% | 20.0% | `java.util.regex.Pattern$Slice.match` |
| 10.5% | 10.5% | `java.util.HashMap.getNode` |
| 8.6% | 8.6% | `java.util.regex.Matcher.reset` |
| 5.5% | 27.1% | `java.util.regex.Pattern$Start.match` |
| 4.0% | 4.9% | `java.util.regex.Pattern$StartS.match` |
| 2.8% | 2.8% | `java.util.UUID.equals` |
| 2.8% | 2.8% | `java.time.LocalDate.until` |
| 2.5% | 47.1% | `com.finora.util.CategoryRules.suggestCategory` |
| 2.2% | 10.8% | `java.util.regex.Pattern.matcher` |
| 2.2% | 4.0% | `ImmutableCollections$ListItr.next` |
| 1.5% | 1.5% | `java.util.Objects.checkIndex` |

Application frames by inclusive share:

| inclusive | exclusive | method |
|---:|---:|---|
| 100.0% | 21.5% | `ReconciliationService.reconcileForUser` |
| **47.1%** | 2.5% | `CategoryRules.suggestCategory` |
| 7.7% | 0.0% | `CategoryRules.normalize` |

---

## What this says, and it is not what was expected

**Roughly half the remaining time is regular-expression matching, and none of it is in the
quadratic pair-matching.**

`suggestCategory` accounts for **47.1% inclusive**, and the regex internals beneath it —
`Pattern$Slice.match`, `Matcher.reset`, `Pattern$Start.match`, `Pattern.matcher` — are the top
exclusive entries. Its implementation explains why:

```java
public static String suggestCategory(String description) {
    String norm = normalize(description);
    for (var entry : RULE_PATTERNS.entrySet()) {
        for (Pattern pattern : entry.getValue()) {
            if (pattern.matcher(norm).find()) return entry.getKey();   // walks every pattern
        }
    }
    return "Other";
}
```

It scans the whole compiled keyword table until something matches, and returns `"Other"` only after
trying all of them — so the *cheapest* case is a match on the first rule and the *most common* case
(no match) is the most expensive.

The call site is a **linear** pre-pass, not the quadratic loop:

```java
for (Transaction t : candidates) {                       // O(n)
    String normalizedDescription = CategoryRules.normalize(t.getDescription());
    ownAccountMatch.put(t.getId(), ...);
    looksLikeSalary.put(t.getId(), "Salary".equals(CategoryRules.suggestCategory(...)));
}
```

That pre-pass exists precisely *because* someone already recognised `suggestCategory` was expensive
and hoisted it out of the O(n²) loop — the comment above it says so. The hoist worked. What it left
behind is that the hoisted work is still the single largest cost in the method, and it is now
being paid once per candidate to answer one question: *is this row a salary credit?*

**This also explains why account bucketing measured as no improvement.** Bucketing narrows the
refund pass's candidate iteration — `UUID.equals` at 2.8% and `LocalDate.until` at 2.8%. Removing
two thirds of a 2.8% line item is invisible next to a 47% one. The optimisation was aimed at a part
of the method that is not hot.

`HashMap.getNode` at 10.5% is the second-order cost of the same design: `ownAccountMatch` and
`looksLikeSalary` are keyed by `UUID`, and every pair-check inside the matching loops does a hashed
lookup to read a boolean.

---

## Deliberately not acted on

This is a baseline, and the reconciliation performance investigation is closed (see
`reconciliation-investigation-closure.md`). Recording the observation is the point; acting on it is
a separate decision needing its own evidence.

For whoever picks it up, the profile points somewhere specific rather than at "make it faster":

- **The question asked is narrower than the tool used.** The pre-pass wants "is this salary", and
  calls a general category classifier that tries every rule for every category to answer it. A
  direct salary check would not need the other rules at all.
- **The answer is computed for every candidate, but only read for some.** The transfer loop reads
  `looksLikeSalary` after several cheaper guards; candidates rejected by those never needed it.
- **`Matcher.reset` at 8.6%** suggests per-call `Matcher` allocation is itself material, separate
  from the matching.

Each of those is a hypothesis. This investigation's own record shows what happens when a hypothesis
of that shape is merged without an end-to-end measurement: account bucketing looked like a 66%
reduction and delivered nothing. Any of the above needs the same treatment — prototype, measure
end-to-end against a baseline taken in the same session, and reject it if the numbers do not move.

---

## Caveats

- **382 samples is a modest population.** Percentages are indicative, not precise; treat a 2%
  difference as noise and the 47% figure as "roughly half".
- **Machine variance on this host is around 1.5× run-to-run**, so this is one recording, not a
  distribution.
- **Synthetic descriptions.** The fixture draws from eight description templates
  (`ReconciliationScalingBenchmark.syntheticHistory`). Real narrations are longer and more varied,
  which would make `suggestCategory` *more* expensive, not less — so the 47% is more likely an
  under-estimate than an over-estimate for real data.
- **Repository is mocked.** No query time, no connection acquisition, no Hibernate hydration. This
  profiles the in-memory algorithm only; a production profile would show database time this cannot.
