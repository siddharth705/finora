# Repository Guardian — rule registry

Every rule the Guardian enforces, what it prevents, and where its authority comes from.

**This file is verified, not maintained by hand.** `GuardianRegistryTest` reads the
`@GuardianRule` annotations on the rules themselves and fails the build if this document and the
code disagree in either direction — a rule added without a row, a row left behind by a retired
rule, or an intent reworded in one place and not the other. So the list below is true as of the
commit you are reading, not as of the last time somebody remembered to update it.

**Read this instead of the tests.** The tests carry the reasoning in their Javadoc, which is where
the detail belongs; this is the index. A rule's `Source` column is the thing to argue with if you
disagree with a rule — the Guardian enforces standards, it does not author them
([repository-guardian.md §3.3](../engineering/repository-guardian.md)).

---

## Summary

**29 enforced rules**, all currently passing.

| Category | Rules |
|---|---|
| BOUNDARY | 3 |
| CORRECTNESS | 1 |
| DEPENDENCY | 7 |
| HYGIENE | 5 |
| MIGRATION | 3 |
| NAMING | 5 |
| SECURITY | 5 |

Rule ids are permanent. They are never reused and never renumbered — `FG-007` means the same thing
in a two-year-old commit message as it does in today's CI log. Ids run contiguously from `FG-001`;
a new rule takes the next free number regardless of category, the same way a CVE id says nothing
about what kind of bug it is.

### How to read `Verification`

| Value | Means |
|---|---|
| `SELF_TEST` | The rule ships with its own tests proving it fires on a deliberate violation and that its subject set is non-empty. Re-verified on every run. |
| `MANUAL_FALSIFICATION` | Verified once, by hand, by introducing a violation and observing the failure. It says the rule worked the day it was written, not that it still does. |

That distinction is not bookkeeping. A rule whose predicate matches nothing passes forever and
protects nothing, and green is exactly what a working rule looks like too. One of the rules below
shipped in that state: `FG-023` was originally written as
`noClasses().should().callMethod(Throwable.class, "printStackTrace")`, which never matches, because
javac emits the call against the receiver's *static* type — so `catch (RuntimeException e)` produces
a call owned by `RuntimeException`. It was green, caught nothing, and only trying to break it
revealed that.

**Known gap:** the 23 rules introduced on 2026-08-05 are `MANUAL_FALSIFICATION`; the six older ones
are `SELF_TEST`. Upgrading them is tracked in
[repository-guardian.md](../engineering/repository-guardian.md).

---

## Rules

### Dependency rules

Which layer may depend on which. Direction, not placement -- these hold regardless of whether a class has been migrated to its feature package yet.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-001` | DEPENDENCY | A controller must not depend on a repository; the query belongs behind a service. | CODING_STANDARDS.md > Backend > Controllers | 2026-08-05 | architecture | MANUAL_FALSIFICATION | LEGACY_CONTROLLER_REPOSITORY_ACCESS -- 4 pre-existing controllers, frozen |
| `FG-002` | DEPENDENCY | No controller method returns a JPA entity, at any depth of the generic return type. | CODING_STANDARDS.md > Backend > DTO mapping | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-003` | DEPENDENCY | No @Transactional on a controller; the unit of work is owned by a service method. | CODING_STANDARDS.md > Backend > Controllers | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-004` | DEPENDENCY | An entity must not depend on a service, repository, controller or DTO. | CODING_STANDARDS.md > Backend > Package structure | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-005` | DEPENDENCY | A DTO must not depend on a service or repository; its caller populates it. | CODING_STANDARDS.md > Backend > DTO mapping | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-006` | DEPENDENCY | A repository must not depend on a service or controller. | CODING_STANDARDS.md > Backend > Package structure | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-007` | DEPENDENCY | A controller must not call another controller; shared behaviour belongs in a service. | CODING_STANDARDS.md > Backend > Controllers | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |

### Boundary rules

What a module may reach into, and what may reach into it.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-008` | BOUNDARY | The sub-packages of com.finora.imports stay free of dependency cycles. | CODING_STANDARDS.md > Backend > Package structure | 2026-08-05 | architecture | MANUAL_FALSIFICATION | Scoped to com.finora.imports; top-level slices still cycle mid-migration |
| `FG-009` | BOUNDARY | Only com.finora.imports.storage may name a concrete StatementStorage implementation. | statement-storage-migration.md > Provider replaceability | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-010` | BOUNDARY | No production class depends on a test fixture package. | repository-guardian.md 3.1 | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |

### Migration rules

Keeps the layer-based to feature-based migration converging, and keeps the accept-lists honest as debt is paid down.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-011` | MIGRATION | A migrated feature module must not depend on the legacy com.finora.controller package. | CODING_STANDARDS.md > Migrating existing modules | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-012` | MIGRATION | Every entry in the FG-001 accept-list still describes a real violation. | repository-guardian.md 3.2 -- check-dependency-advisories.py template | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-013` | MIGRATION | Every entry in the FG-019 accept-list still describes a real violation. | repository-guardian.md 3.2 -- check-dependency-advisories.py template | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |

### Naming rules

A class's name and its Spring stereotype agree, because the name is all a reader has at the call site.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-014` | NAMING | A class named *Controller is annotated @RestController. | CODING_STANDARDS.md > Backend > Naming | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-015` | NAMING | A class annotated @RestController is named *Controller. | CODING_STANDARDS.md > Backend > Naming | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-016` | NAMING | A class named *Service is a Spring bean (@Service or @Component). | CODING_STANDARDS.md > Backend > Naming | 2026-08-05 | architecture | MANUAL_FALSIFICATION | @Component accepted: ImportSessionService, ImportRuleLearningService, BootstrapService |
| `FG-017` | NAMING | A type named *Repository is an interface extending Spring Data Repository. | CODING_STANDARDS.md > Backend > Naming | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-018` | NAMING | A class named *Config is annotated @Configuration. | CODING_STANDARDS.md > Backend > Naming | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |

### Hygiene rules

House conventions that arrive by autocomplete rather than by decision -- the category a human reviewer skims past.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-019` | HYGIENE | Time uses java.time, not java.util.Date, Calendar, SimpleDateFormat or sql.Timestamp. | CODING_STANDARDS.md > Backend | 2026-08-05 | architecture | MANUAL_FALSIFICATION | DATE_API_BOUNDARY -- JwtService, forced by JJWT's issuedAt(Date) |
| `FG-020` | HYGIENE | No field injection; dependencies arrive through the constructor. | CODING_STANDARDS.md > Backend | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-021` | HYGIENE | A public static field must be final. | CODING_STANDARDS.md > Backend | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-022` | HYGIENE | No System.out or System.err; use LoggerFactory. | CODING_STANDARDS.md > Backend > Logging | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |
| `FG-023` | HYGIENE | No Throwable.printStackTrace(). | CODING_STANDARDS.md > Backend > Logging | 2026-08-05 | architecture | MANUAL_FALSIFICATION | None |

### Security rules

Authorization, actor attribution, tenant scoping and input validation. Every one of these was written after a real defect.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-024` | SECURITY | Every admin endpoint carries an authorization annotation. | Incident: unguarded admin endpoint | 2026-08-01 | architecture | SELF_TEST | None |
| `FG-025` | SECURITY | Every admin-reachable audit write records the acting actor. | Incident: unattributed admin audit write | 2026-08-05 | architecture | SELF_TEST | None |
| `FG-026` | SECURITY | A filter reading the raw request URI must parse it, never string-compare it. | Incident: rate-limit bypass via percent-encoding | 2026-08-04 | architecture | MANUAL_FALSIFICATION | None |
| `FG-027` | SECURITY | Every identity lookup on UserRepository is tenant-scoped. | Incident: unscoped identity lookup | 2026-08-03 | architecture | SELF_TEST | None |
| `FG-028` | SECURITY | A @RequestBody whose type carries constraints is annotated @Valid. | CODING_STANDARDS.md > Backend > Validation | 2026-08-05 | architecture | SELF_TEST | None |

### Correctness rules

Framework misuse that is silently wrong rather than merely untidy.

| ID | Category | Intent | Source | Introduced | Owner | Verification | Exceptions |
|---|---|---|---|---|---|---|---|
| `FG-029` | CORRECTNESS | No @Bean method returns Optional<...>; Spring resolves it to empty at every injection. | Incident: FirebaseConfig.firebaseApp() silently disabled phone verification | 2026-08-02 | architecture | SELF_TEST | None |

---

## Accepted exceptions

Two rules ship with frozen accept-lists rather than being weakened or dropped. Both follow the
`check-dependency-advisories.py` template, for the property that matters most: **they fail on a
stale entry too**, so paying the debt down tightens the rule automatically instead of waiting for
someone to remember. `FG-012` and `FG-013` are those staleness checks.

| Rule | Accept-list | Contents | Why |
|---|---|---|---|
| `FG-001` | `LayerDependencyDirectionTest.LEGACY_CONTROLLER_REPOSITORY_ACCESS` | `AdminController`, `AdminBankController` → `AuditLogRepository`; `CategoryController` → `CategoryRepository`; `UserController` → `UserRepository` | Predates the rule. Frozen — nothing may be added. |
| `FG-019` | `ProductionCodeHygieneTest.DATE_API_BOUNDARY` | `JwtService` | JJWT's `issuedAt(Date)` and `Claims::getExpiration` leave no alternative at that boundary. |

`FG-016` accepts `@Component` alongside `@Service` for three classes. That is not debt: the two
stereotypes are functionally identical to Spring, and insisting on one spelling would be a rename
with no behaviour behind it.

---

## Adding a rule

1. Write it. Prefer ArchUnit — it runs in the existing suite and needs no new mechanism.
2. **Run it against `main` before believing it.** A rule that fails on existing code either gets
   the code fixed in the same change or is not ready. A permanently-red rule teaches the team to
   skip the suite.
3. **Prove it can fail.** Introduce a deliberate violation and watch it go red. Better, ship a
   `@GuardianSelfTest` so that proof re-runs forever.
4. Annotate it `@GuardianRule` with the next free id and complete lifecycle metadata.
5. Run `mvn -f backend/pom.xml test`. `GuardianRegistryTest` will tell you the exact row to paste
   into this file.

## Retiring a rule

Remove the code and its row in the same commit. Do not renumber anything and do not reuse the id —
`GuardianRegistryTest` enforces contiguity from `FG-001`, so a retirement means the replacement
takes the next free number and the retirement is noted here.

---

## Report

```bash
python3 scripts/guardian-report.py
```

Joins this registry against the surefire results from the last backend run and prints pass/fail per
category. It reports a **rule pass rate**, not a "repository health" percentage — see
[repository-guardian.md §4.3](../engineering/repository-guardian.md) for why that distinction is
deliberate.
