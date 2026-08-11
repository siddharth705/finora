# Finora Coding Standards (v56)

Reference doc for Phase 1 of the v56 roadmap. New code should follow this; existing code moves
toward it incrementally (see "Migrating existing modules" at the bottom) rather than in one pass.

## Backend (Java / Spring Boot)

### Package structure — feature-based, not layer-based
Target shape per feature module (see `com.finora.imports` for the first migrated example):

```
com.finora.<feature>/
  <Feature>Controller.java
  <Feature>Service.java        // orchestration — the only class Spring wires as the public entry point
  <Feature>Repository.java     // if the feature owns its own entity
  <Feature>Dto.java
  <Feature>Mapper.java         // MapStruct, once introduced (see below)
  (single-responsibility collaborators, e.g. Parser/Validator/Normalizer — see imports/)
```

Entities and repositories for concepts shared across many features (`Transaction`, `Account`,
`Category`) stay in `entity/` and `repository/` until their owning module is migrated — don't
move an entity as a side effect of migrating an unrelated feature.

### Naming
- Controllers: `<Noun>Controller`, one REST resource family per class (see `AdminController`
  fragmentation into `AdminUserController`/`AdminRuleController`/etc. as the pattern to follow —
  don't add new endpoints to a general-purpose `AdminController`).
- Services: `<Noun>Service` for the public orchestration entry point Spring wires into
  controllers. Single-responsibility collaborators used only by one service get a name
  describing what they *do*, not `-Service` — e.g. `CsvParser`, `DuplicateDetector`, not
  `CsvParsingService`.
- DTOs: `<Noun>Dto` as an outer class with nested records for request/response shapes
  (`TransactionDto.CreateRequest`, `TransactionDto.FilterRequest`) — keeps the import list at
  call sites short and groups a resource's whole request/response contract in one file.

### Service size
A service class mixing more than one of {parsing, validation, persistence orchestration,
external-format normalization} is a signal to split — see `com.finora.imports` for the reference
decomposition (CsvImportService → CsvParser + TransactionNormalizer + StatementValidator +
DuplicateDetector + PreviewGenerator + ImportRuleLearningService + ImportService). As a rough
guide, a service file crossing ~400 lines is worth a second look, not an automatic split — some
services are long because the domain is genuinely that detailed (see `AuthService`), not because
responsibilities are tangled.

### Controllers
- Thin: parse request, call one service method, wrap the result. No business logic, no direct
  repository access.
- One `@RequestMapping` base path per resource family.

### DTO mapping
Never expose entities directly from a controller. Today this is done by hand (`TransactionDto.from(t, categoryName)`
static factories); MapStruct is the target for new/migrated modules once introduced, to cut down
on hand-written mapping boilerplate as modules grow. Introduce it module-by-module rather than
as a big-bang rewrite of existing `.from(...)` factories.

### Validation
Bean Validation annotations (`@NotNull`, `@Size`, etc.) on request DTOs; anything that needs
cross-field or DB-dependent validation belongs in the service, raising `ApiException` with a
clear message (see Global Error Codes below for the structured version of this).

### Logging
- `LoggerFactory.getLogger(ThisClass.class)`, never `System.out`.
- Log at the point a decision is made (a rule matched, a fallback was used), not at every method
  entry/exit.
- Never log a raw exception's message into a user-facing `ApiResponse` — that's what
  `GlobalExceptionHandler` is for.

### Exception handling
- Domain/validation failures: throw `ApiException(HttpStatus, message)` (or the structured
  `ErrorCode` variant — see below) from services; never catch-and-swallow in a controller.
- Let `GlobalExceptionHandler` be the only place that turns an exception into an HTTP response.

### API naming
- `/api/v1/<resource>` (plural noun), sub-resources nested (`/api/v1/transactions/{id}/category`).
- Admin endpoints under `/api/v1/admin/<resource>`, matching the `Admin*Controller` split.
- One route tree for every client — web, admin, and mobile all call the same `/api/v1/*` routes;
  no `/mobile/*` or `/web/*` namespace. See
  [ADR-001: One Backend, One Database, Three Clients](../../architecture/adr/adr-001-client-architecture.md)
  for why, and [api-compatibility-policy.md](api-compatibility-policy.md) for what counts as a
  breaking change to a route already in use.

## Frontend (React / TypeScript)

- Feature-first folders (`transactions/`, `budgets/`, ...), each with its own `components/`,
  `hooks/`, `api/`, `types/` — see Phase 3 of the roadmap. `frontend/src/pages/*.tsx` files stay
  as route entry points that compose feature components, not as monolithic implementations.
- No inline business logic in components — categorization, budget math, forecasting are backend
  concerns; a component reads a computed value, it doesn't derive one.
- Forms: React Hook Form + Zod (target — see Phase 3); no hand-rolled validation state.
- API calls go through a domain-specific client (`transactionsApi.ts`, not a shared `api.ts`
  grab-bag) once a module migrates to feature-based structure.

## What "done" looks like for a migrated module
1. Feature package exists under `com.finora.<feature>` (backend) and `src/<feature>/` (frontend).
2. Each backend class has one clear responsibility (see Service size above).
3. No entity is exposed directly through a controller.
4. Tests live alongside the code they cover, in the mirrored `src/test/java/com/finora/<feature>`
   package — see `com.finora.imports`'s test package for the pattern.

## Migrating existing modules
Do NOT do a big-bang move of all ~45 controllers / ~50 services at once — that's a huge diff with
no incremental review point and a high chance of a silent regression. Migrate one feature module
per PR, in this order (highest business-criticality / most tangled first, since those benefit
most and get the most scrutiny while the codebase is still small enough to review by hand):

1. ~~`imports`~~ — done: `CsvImportService` → 7 focused classes.
2. ~~`transactions`~~ — done: Controller/Service/Dto moved; entity/repository stay shared
   (still ~25 files deep across the rest of the codebase).
3. ~~`accounts`~~ — done: Controller/Service/Dto moved; entity/repository stay shared.
4. ~~`budgets`~~ — done: Controller/Service/Dto moved; entity/repository stay shared
   (Dashboard/Insights read them directly).
5. ~~`goals`~~ — done, fully: nothing else in the codebase referenced `Goal`/
   `GoalContribution` directly, so the entity and both repositories moved too, not just the
   controller/service/dto trio.
6. ~~`rules`~~ — done: `RuleController`/`RuleService`/`RuleDto` moved. `RuleEngineService`
   (the matching engine, used directly by CategorizationService/RecurringService/imports) and
   `CategoryRule`/`CategoryRuleRepository` deliberately stay in `service`/`entity`/`repository` —
   see `com.finora.rules`'s package-info for the reasoning.
7. `analytics`/`reports`/`dashboard` — depend on most other modules; migrate after their
   dependencies have moved, so the new package's imports are stable.
8. `admin/*` — largest controller count (14 `Admin*Controller` classes) but each is already
   narrowly scoped; mostly a mechanical move once the pattern is proven.

**A recurring trap worth calling out explicitly**: a class in the *same* old package as a type
being moved (e.g. another class in `com.finora.dto` using `RuleDto`, or `Goal.java` itself
extending `BaseEntity`) never needed an import before, because same-package references don't
require one. Moving the referenced type breaks that silently — `grep` for the type's *fully-
qualified* name only catches files that already had an explicit import. Every module migration
in this codebase so far has caught at least one real instance of this (`ImportDto`/
`StatementImportDto` implicitly using `AccountDto`; `Goal` implicitly extending `BaseEntity`;
`RuleService` implicitly using `AuditService`). The reliable check is a bare-word grep
(`\bTypeName\b`) across the *old* package directories, followed by manually confirming each hit
is real code and not a comment — see the git history of this file's own migrations for the
pattern, or just budget the extra grep pass every time.

For each: `git mv` the files into the new package, update the package declaration and all
importing files' import statements, then run the full test suite before merging — don't rely on
manual review alone for a cross-cutting rename.
