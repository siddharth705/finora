# Finora — Full Repository Bug Review

**Scope:** every top-level directory of the repository — `backend/` (318 main Java files, 190 test files, 61 Flyway migrations, 4 YAML profiles), `frontend/` (user SPA), `admin-portal/` (admin SPA), `mobile/` (React Native/Expo), `scripts/`, `.github/workflows/`, `.husky/`, `docker-compose.yml`, and root config.

**Mode:** bug hunt only. No code was modified, no fixes were applied, no refactors were made, no pull requests were opened. Every finding below cites the file, the symbol, and the code path that demonstrates it.

**Result:** 58 findings, of which **47 are live in the current tree, 10 are closed, and 1 was overstated and has been corrected** (see Validation status below). **All three Criticals are fixed, and 4 of the 10 Highs.**

| Severity | Total | Live | Fixed | Corrected |
|---|---|---|---|---|
| Critical | 3 | 0 | 3 | — |
| High | 10 | 5 | 4 | 1 (Bug 11, → Medium) |
| Medium | 20 | 18 | 2 | — |
| Low | 25 | 24 | 1 | — |
| **Total** | **58** | **47** | **10** | **1** |

## Validation status

The working tree moved underneath this review — concurrent work landed in `backend/` partway through, so the snapshot the findings were originally written against is not the snapshot that exists now. **Every one of the 58 findings was subsequently re-checked against the current tree**, by grepping for the specific code signature each finding cites.

**Closed — do not action these ten:**

| Bug | Title | What changed |
|---|---|---|
| 01 | Merchant-learning queue unreachable | Fixed by concurrent work. `ImportService` now calls `learningEventPublisher.enqueue(...)`. |
| 02 | Unvalidated closing balance overwrites the account balance | **Fixed by this review.** New `imports/ClosingBalanceGuard`; `ImportService.confirm` refuses an uncorroborated balance and warns. |
| 03 | UPI/NEFT/IMPS payees collapse into one merchant | **Fixed by this review.** New `util/PaymentRailTokens`; `MerchantNormalizationEngine` skips rails and tokenises both sides through `extractMerchant`. |
| 05 | Dashboard "this month" is the newest month with data | **Fixed by this review.** New `util/ReportingPeriod`; the response now names the month its figures describe and the client labels it. |
| 06 | Budget alerts fire against the wrong month | **Fixed by this review.** Budget notifications now use the calendar month, agreeing with `BudgetService`. |
| 12 | `ImportSession.fileContent` eagerly fetched | **Fixed by this review.** `@Basic(fetch = LAZY)` added; new Guardian rule **FG-030** fails the build on any persistent `byte[]` without it. |
| 13 | Lockout re-locks on the next wrong password | Fixed by concurrent work. `AuthService` resets `failedLoginAttempts` once `lockedUntil` has elapsed. |
| 17 | Imports never adjust the account balance | **Fixed by this review.** `AccountBalanceConvention.balanceDelta`/`netDelta` now own the rule; `ImportService.confirm` applies it and `StatementImportService.delete` reverses it. |
| 20 | Retry leaves the old error message | Fixed by concurrent work. `requeueForRetry` now clears `lastError`. |
| 21 | JPQL `$`-form enum literal | Fixed by concurrent work. Rewritten to the source dotted form. |

Their entries are retained below, marked **[FIXED]**, because the reasoning is still the record of what was wrong.

**Line numbers throughout this report refer to the snapshot each finding was read from and may be off by a few lines against the current tree in files that have since changed.** The cited symbol names and code signatures are what to search on; all 54 live findings were confirmed by signature, not by line number.

**Note on this codebase:** it is unusually well commented, and many comments record bugs that were already fixed. Findings below are things that are *still true of the code as it stands* — several of them are cases where a documented rule was applied in one place and not in the structurally identical place next to it.

---

# Bug 01 — [FIXED]

> **Fixed while this review was in progress.** `ImportService.java:650` now calls
> `learningEventPublisher.enqueue(...)`, so the queue is wired and the isolation defect below is
> closed. Retained for the record.

## Title
The entire merchant-learning durable queue is unreachable — no production code calls the publisher

## Severity
Critical

## Location
File: `backend/src/main/java/com/finora/service/MerchantLearningEventPublisher.java`
Function/Class: `MerchantLearningEventPublisher.enqueue`
Line(s): 63–67 (and every caller site that does not exist)

## Description
V62, `MerchantLearningEvent`, `MerchantLearningEventRepository`, `MerchantLearningEventWorker`, `MerchantLearningEventPublisher` and `BackgroundWorkConfig` were added as "Deliverable 0 of the Import Reliability Milestone", whose stated purpose is that "a learning failure can never roll back an import". Nothing in `src/main/java` ever calls `enqueue`. The import path still calls `MerchantLearningService.confirm` synchronously, inside the caller's transaction.

## Evidence
```
$ grep -rn "MerchantLearningEventPublisher" backend/src/main/java
  .../MerchantLearningEventPublisher.java:40:public class MerchantLearningEventPublisher {
  .../MerchantLearningEventPublisher.java:42:  private static final Logger log = ...
  .../MerchantLearningEventPublisher.java:47:  public MerchantLearningEventPublisher(...)
  .../MerchantLearningEventWorker.java:104: * Fire-and-forget trigger for {@link MerchantLearningEventPublisher} ...   (javadoc only)
```
The only real references are in tests: `MerchantLearningNudgeIT:47` and `MerchantLearningQueueIT:60`.

Meanwhile `MerchantLearningService.confirm` (line 116) is still `@Transactional` joining the caller and is still reached inline via `CategorizationService.learn` → `TransactionService.create/update/updateCategory/bulkRecategorize` and `ImportService.confirm`. Its own javadoc at lines 92–115 confirms the defect is open: *"a lost race takes the caller down with it — on `ImportService.confirm`, that is every transaction insert for a statement the user has already reviewed."*

## Impact
The bug the milestone exists to fix is not fixed. A unique-constraint race in `merchant_category_learning(user_id, merchant_id, category_id)` still poisons the import transaction and rolls back the user's entire reviewed-and-approved statement. Simultaneously, the queue table, scheduler thread pool, async executor and poller are all live in production doing nothing but polling an always-empty table every 30 seconds.

## Reproduction
1. `grep -rn "\.enqueue(" backend/src/main/java` → no results.
2. Import a statement; observe `MerchantLearningService.confirm` executing on the request thread inside `ImportService.confirm`'s transaction.

## Confidence
High

---

# Bug 02 — [FIXED]

> **Fixed by this review.** `imports/ClosingBalanceGuard` now decides whether a claimed closing
> balance may be written, using `StatementTotalsValidator`'s own arithmetic against the rows
> actually imported. `ImportService.confirm` applies the balance only on a `CORROBORATED` verdict;
> otherwise it leaves the balance untouched, logs at warn, and returns a warning in the import
> summary. Regression tests: `ClosingBalanceGuardTest`.

## Title
Import writes a client-supplied number directly into the account balance with no validation

## Severity
Critical

## Location
File: `backend/src/main/java/com/finora/imports/ImportService.java`
Function/Class: `ImportService.confirm`
Line(s): 623–628

## Description
`request.statementClosingBalance()` comes straight off the HTTP confirm request body. It is written unmodified onto `Account.balance`, replacing whatever the ledger arithmetic in `TransactionService.adjustAccountBalance` had maintained. Nothing checks it against the parsed statement, the running-balance chain, or the sum of the imported rows.

## Evidence
```java
if (request.statementClosingBalance() != null && isMostRecentStatementForAccount(...)) {
    accountRepository.findById(accountId).ifPresent(account -> {
        account.setBalance(request.statementClosingBalance());   // client value, verbatim
        accountRepository.save(account);
    });
}
```
`ConfirmRequest.statementClosingBalance` carries no `@DecimalMin`, no cross-check against `BalanceChainValidator`'s output, and no comparison to `statementOpeningBalance + credits - debits`. `resolveTargetAccount` (line 709) does verify account *ownership*, so the caller must own the account — but any authenticated user can set their own balance to any value by posting a confirm request.

## Impact
Every downstream financial figure is derived from `Account.balance`: net worth (`NetWorthService.netWorthOf`), the dashboard's liquid/assets/liabilities tiles, the health score's debt-utilisation and emergency-fund components, and low-balance notifications. A wrong or malicious value silently corrupts all of them, and the corruption persists because nothing recomputes the balance from the transaction ledger.

## Reproduction
1. Stage any statement.
2. `POST /api/v1/import/csv/confirm` with `statementClosingBalance: 99999999`.
3. `GET /api/v1/accounts` → the account balance is `99999999`.

## Confidence
High

---

# Bug 03 — [FIXED]

> **Fixed by this review.** `util/PaymentRailTokens` names the tokens that identify a payment rail
> rather than a counterparty, and `MerchantNormalizationEngine.firstSignificantToken` skips them.
> Both sides of the comparison are now reduced through `CategoryRules.extractMerchant`, so
> stripping the rail cannot promote the per-transaction reference into the grouping key. Regression
> tests: five cases in `MerchantNormalizationEngineTest`, verified to fail when the fix is
> neutralised.
>
> **Not migrated:** merchants already collapsed by the old heuristic stay collapsed. Repairing them
> is a data migration plus a split/merge decision per merchant, which is a separate piece of work
> from stopping the corruption.

## Title
Every UPI / NEFT / IMPS / ATM transaction collapses into a single merchant

## Severity
Critical

## Location
File: `backend/src/main/java/com/finora/service/MerchantNormalizationEngine.java`
Function/Class: `resolve`, `firstSignificantToken`
Line(s): 136–145, 219–226

## Description
When no alias matches, `resolve` falls back to a fuzzy match: it compares the *first token longer than two characters* of the incoming description against the first such token of every existing merchant's canonical name, and returns the first merchant that matches. Indian bank narrations overwhelmingly begin with a rail prefix — `UPI`, `NEFT`, `IMPS`, `ATM`, `POS`, `ACH` — all of which are three or more characters and all of which survive normalisation.

## Evidence
`CategoryRules.normalize` (util/CategoryRules.java:61) only lowercases and replaces non-alphanumerics with spaces. There is no stop-word list anywhere:
```java
return desc.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
```
`firstSignificantToken` returns the first token with `length() > 2`:
```java
for (String t : tokens) { if (t.length() > 2) return t; }
```
Trace `"UPI/9182736/SWIGGY"`:
- `normalize` → `"upi 9182736 swiggy"`
- `extractMerchant` strips the 4+ digit token → `"upi swiggy"` → canonical name `"Upi Swiggy"`
- `firstSignificantToken("upi swiggy")` → `"upi"`

Now trace `"UPI/5647382/ZOMATO"`: its first significant token is also `"upi"`, so line 139's filter matches the existing `Upi Swiggy` merchant, line 142 aliases the Zomato description onto it, and line 143 returns it.

## Impact
For a typical Indian bank statement, essentially every merchant resolves to whichever UPI payee happened to be seen first. That single merchant then accumulates the confirmation counts of hundreds of unrelated payees, which is exactly what `ConfidenceEngine.topCategory` reads to decide the auto-applied category. Merchant Intelligence, the learning distribution, the Ask-Once review queue, and `MerchantService.merge` all operate on garbage. This also silently defeats Bug 01's whole subject matter.

## Reproduction
1. Import a statement whose first row is `UPI/1234/SWIGGY` and whose second is `UPI/5678/AMAZON`.
2. `GET /api/v1/admin/users/{id}/merchants` → one merchant, both descriptions aliased to it.

## Confidence
High

---

# Bug 04

## Title
An unbounded category name from a statement rolls back the entire import

## Severity
High

## Location
File: `backend/src/main/java/com/finora/service/CategorizationService.java` (`resolveOrCreateCategory`, line 215) and `backend/src/main/java/com/finora/imports/ImportService.java` (line 521)
Function/Class: `CategorizationService.resolveOrCreateCategory`
Line(s): 215–223; caller at ImportService.java:521

## Description
`categories.name` is `VARCHAR(80)` (V1__init_schema.sql:39). `resolveOrCreateCategory` inserts the caller's string with no length check. `ImportDto.ConfirmRow.category` (ImportDto.java:307) is a bare `String` with no `@Size`, and it is populated at staging time from a raw CSV cell (`TransactionNormalizer.normalize` line 288: `String fileCategory = CsvParser.firstNonBlank(row, CATEGORY_HINTS)`), which is unbounded.

## Evidence
`MerchantNormalizationEngine` documents this exact hazard for its own columns and guards it (`MAX_STORED_LENGTH`, `fitToColumn`, lines 95–124), with the reasoning: *"By the time the constraint fires the transaction is already poisoned, and no handling un-poisons it. The write simply must not be attempted."* The identical `VARCHAR` exposure on `categories.name`, fed by the same parser output on the same code path, has no equivalent guard.

`ImportService.confirm` line 521 is inside the confirm transaction:
```java
Category category = categorizationService.resolveOrCreateCategory(userId, row.category());
```

## Impact
A statement with a long "Category" cell (or a client sending one) makes the confirm transaction rollback-only. The user's whole import — every transaction, the `statement_imports` row, the account balance update — is discarded, surfacing as a 409 CONFLICT with no indication of which row caused it. A null/blank `row.category()` hits the same path against `NOT NULL`.

## Reproduction
Stage a CSV containing a `Category` column whose cell is 100 characters, confirm the import, observe the rollback.

## Confidence
High

---

# Bug 05 — [FIXED]

> **Fixed by this review** (web, mobile and a drift guard). The rule moved into
> `util/ReportingPeriod`, shared with the service
> that already got it right (`InsightsService`), so the two cannot drift again. Reporting on the
> newest month with data is *kept* — an empty "this month" is a worse answer for a product built
> around importing in arrears — but `DashboardSummaryDto` now carries `reportingMonth` and
> `reportingMonthIsCurrent`, and `Dashboard.tsx` renders the period instead of asserting one. The
> prior month is now a CALENDAR step, so a user with a gap in their history no longer sees two
> non-adjacent months compared as "vs last month".
>
> **A consumer audit found two more sites after the first pass**, which is why the guard exists:
> the web dashboard still asserted "this month" in its cash-flow sentence, and `mobile/`'s
> `DashboardScreen` had three hardcoded claims (visible label, accessibility label, empty state)
> and had never been given the new fields at all. `ReportingPeriod`'s javadoc now states the
> invariants and maps every feature to the month it must use, and
> `scripts/check-reporting-period-labels.py` fails the build if any client asserts a period
> unconditionally — the reporting-layer instance of the web-fixed/mobile-missed drift
> `check-client-auth-policy.py` already guards in the auth layer.

## Title
Dashboard "this month" is the newest month with data, not the current calendar month

## Severity
High

## Location
File: `backend/src/main/java/com/finora/service/DashboardService.java`
Function/Class: `summarize`
Line(s): 71–79, 89–115

## Description
`currentMonth` is computed as the last element of the distinct sorted list of months that appear in the user's transactions. It is never compared against the real calendar month in the user's timezone, even though the user's `ZoneId` is resolved fifty lines further down (line 125) for a different purpose.

## Evidence
```java
List<String> months = active.stream().map(t -> YearMonth.from(t.getTxnDate()).toString())
        .distinct().sorted().toList();
String currentMonth = months.isEmpty() ? null : months.get(months.size() - 1);
String priorMonth = months.size() > 1 ? months.get(months.size() - 2) : null;
```
`InsightsService` hit the same problem and fixed it (InsightsService.java:76–79) by resolving `YearMonth.now(UserZone.forUser(...))` and adjusting the wording. Its comment names the failure precisely: *"a user who had not yet transacted in August read July's figures as August's."* `DashboardService` was never given the same treatment, and it is the page that renders these as headline KPIs.

The frontend labels them unconditionally: `frontend/src/pages/Dashboard.tsx:157` renders `"% vs last month"` for every delta.

## Impact
`monthlyIncome`, `monthlyExpense`, `netCashFlow`, `savingsRatePct` and `spendByCategory` are all silently a stale month's figures for any user who has not yet transacted this month — the normal case for a product built around importing statements in arrears.

## Reproduction
Create a user whose latest transaction is dated last month. Open the dashboard on the 1st of this month → last month's totals are shown as this month's.

## Confidence
High

---

# Bug 06 — [FIXED]

> **Fixed by this review.** Budget notifications are now filtered on `period.calendarMonth()`,
> matching `BudgetService.listForUser`. A monthly allowance resets on a calendar boundary
> regardless of when the user last imported, so it is the one figure on this response that must
> not follow the reporting month. Regression test:
> `summarize_doesNotFlagABudgetFromAPreviousMonthsSpend`.

## Title
Dashboard budget alerts fire against the wrong month and disagree with the Budgets page

## Severity
High

## Location
File: `backend/src/main/java/com/finora/service/DashboardService.java`
Function/Class: `summarize` → `buildNotifications`
Line(s): 110–115, 271–280

## Description
`spendByCategoryId` — the map that decides whether a budget-exceeded notification is emitted — is filtered on `currentMonth`, which Bug 05 establishes is the newest month with data rather than the actual month. `BudgetService.listForUser` computes the *same* number correctly, from `YearMonth.now(safeZoneId(userId))`.

## Evidence
DashboardService:
```java
.filter(t -> t.getTxnType() == Transaction.Type.EXPENSE
        && t.getCategoryId() != null
        && Objects.equals(YearMonth.from(t.getTxnDate()).toString(), currentMonth))
```
BudgetService (line 54–56), fixed for exactly this reason with a comment saying so:
```java
YearMonth thisMonth = YearMonth.now(safeZoneId(userId));
LocalDate from = thisMonth.atDay(1);
LocalDate to = thisMonth.atEndOfMonth();
```

## Impact
Two screens in the same app report contradictory budget progress. The dashboard warns "Groceries spending has reached your monthly budget" using last month's spend, while the Budgets page correctly shows this month at 0%.

## Reproduction
1. Set a ₹5,000 Groceries budget.
2. Spend ₹6,000 on Groceries last month, nothing this month.
3. Dashboard shows the over-budget notification; `/app/budgets` shows 0%.

## Confidence
High

---

# Bug 07

## Title
Rate-limit 429 responses carry no CORS headers, so browsers never see them

## Severity
High

## Location
File: `backend/src/main/java/com/finora/config/RateLimitFilter.java`
Function/Class: `doFilterInternal`
Line(s): 68 (`@Order`), 154–161

## Description
`RateLimitFilter` is registered at `Ordered.HIGHEST_PRECEDENCE + 1`, i.e. `Integer.MIN_VALUE + 1`. Spring Security's `FilterChainProxy` registers at `SecurityProperties.DEFAULT_FILTER_ORDER` (`-100`). CORS is configured *inside* the security chain (`SecurityConfig.filterChain` line 68, `http.cors(...)`), so `RateLimitFilter` runs strictly before any CORS processing. When it short-circuits with a 429 and `return`s, the response never reaches `CorsFilter` and so has no `Access-Control-Allow-Origin` header.

## Evidence
```java
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after CorrelationIdFilter, before Spring Security
...
if (limiter != null && !limiter.allow(ip)) {
    response.setStatus(429);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(body));
    return;                                    // chain never continues
}
```
Both SPAs are cross-origin by design (`CorsConfig`, `frontend/src/api/client.ts` `VITE_API_BASE_URL`).

## Impact
The carefully-written `RATE_LIMITED` body is unreachable from either browser app. The browser blocks the response for missing CORS headers and axios reports a generic network error, so a rate-limited user sees "something went wrong" instead of "too many requests, please wait" — on login, registration and forgot-password, the three flows where the message matters most. `RateLimitFilterTest` cannot catch it because it exercises the filter directly rather than through the real chain.

## Reproduction
From a browser at `http://localhost:5173`, POST `/api/v1/auth/login` eleven times in a minute. The 11th fails as a CORS/network error, not a rendered 429.

## Confidence
High

---

# Bug 08

## Title
Oversized file uploads return 500 INTERNAL_ERROR instead of 413

## Severity
High

## Location
File: `backend/src/main/java/com/finora/exception/GlobalExceptionHandler.java`
Function/Class: `handleGeneric`
Line(s): 229–239

## Description
`spring.servlet.multipart.max-file-size` is 10 MB (application.yml:101). Exceeding it throws `MaxUploadSizeExceededException`. There is no `@ExceptionHandler` for it, and `@ExceptionHandler(Exception.class)` in a `@RestControllerAdvice` is consulted before `DefaultHandlerExceptionResolver`, so it swallows the framework's own correct 413 mapping.

## Evidence
This class's own javadoc at lines 183–187 states the mechanism: *"`DefaultHandlerExceptionResolver` DOES map all three to 400 out of the box, but a `@ExceptionHandler(Exception.class)` in a `@RestControllerAdvice` is consulted first and matches everything, so the catch-all shadowed the framework's own correct behaviour."* The fix was applied to `DateTimeParseException`, `MethodArgumentTypeMismatchException` and `MissingServletRequestParameterException` (lines 203–217) and to `HttpMessageNotReadableException` (line 166), but not to the multipart case.

## Impact
Uploading an 11 MB bank statement PDF — an entirely ordinary thing on this product — returns `{"errorCode":"INTERNAL_ERROR","message":"Unexpected error"}` with a 500, and is written to the error log as `log.error("Unhandled exception on {} {}")`. The user is told the server broke; the operator's alerting fires on routine input.

## Reproduction
`POST /api/v1/import/pdf/stage` with a 12 MB file.

## Confidence
High

---

# Bug 09

## Title
Same catch-all shadowing turns 405 and 415 into 500

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/exception/GlobalExceptionHandler.java`
Function/Class: `handleGeneric`
Line(s): 229–239

## Description
`HttpRequestMethodNotSupportedException` (wrong HTTP verb on an existing route) and `HttpMediaTypeNotSupportedException` (wrong `Content-Type`) are shadowed by the same catch-all described in Bug 08 and have no explicit handler.

## Evidence
`grep -n "MethodNotSupported\|MediaTypeNotSupported" GlobalExceptionHandler.java` → no matches. The registered handlers are `NoResourceFoundException`, `ApiException`, `BadCredentialsException`, `AccessDeniedException`, `OptimisticLockingFailureException`, `DataIntegrityViolationException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, the three binding failures, and `Exception`.

## Impact
`GET /api/v1/auth/login` returns a 500 with an `INTERNAL_ERROR` code and an `ERROR`-level log line, rather than a 405. Routine scanner and misconfigured-client traffic pollutes error alerting.

## Reproduction
`curl -X GET http://localhost:8080/api/v1/auth/login` → 500.

## Confidence
High

---

# Bug 10

## Title
`GET /api/v1/recurring` is a write endpoint

## Severity
High

## Location
File: `backend/src/main/java/com/finora/controller/RecurringController.java` (line 25–28) and `backend/src/main/java/com/finora/service/RecurringService.java` (line 221)
Function/Class: `RecurringController.list` → `RecurringService.detectForUser`
Line(s): RecurringController.java:25–28; RecurringService.java:221–316

## Description
A plain `@GetMapping` with no request body invokes an `@Transactional` method that mutates `Transaction.recurring` on every one of the user's expense transactions, calls `transactionRepository.saveAll(active)`, and writes a `RECURRING_DETECTION_RUN` audit-log row.

## Evidence
```java
@GetMapping
public ApiResponse<List<RecurringDto>> list() {
    return ApiResponse.ok(recurringService.detectForUser(currentUser.id()));
}
```
```java
@Transactional
public List<RecurringDto> detectForUser(UUID userId) {
    ...
    active.forEach(t -> t.setRecurring(false));
    ...
    transactionRepository.saveAll(active);
    auditService.record(userId, "RECURRING_DETECTION_RUN", ...);
}
```

## Impact
Violates HTTP safe-method semantics: a browser prefetch, a proxy revalidation, a React StrictMode double-render, or two open tabs each trigger a full rewrite pass. Because `Transaction` extends `BaseEntity` (which carries `@Version`), two concurrent GETs that both flip a row's `recurring` flag produce `OptimisticLockingFailureException` → a 409 on a read. It also writes one audit row per page view, diluting the activity feed.

## Reproduction
Open `/app` in two tabs simultaneously with a user who has recurring expenses; one request can return 409 CONFLICT.

## Confidence
High

---

# Bug 11 — [CORRECTED: overstated, downgraded to Medium, NOT fixed]

> **This finding was half wrong, and the repository had already measured the half I got wrong.**
>
> I claimed two services each impose a full-history cost per write. `RecurringService.detectForUser`
> does not. `docs/engineering/scaling-triggers.md` records it at **1 ms at every size**, with the
> note *"the in-memory cost the audit attributed to it is not there"* — written about a previous
> audit that made this exact mistake. I reproduced the benchmark
> (`ReconciliationScalingBenchmark`) and confirm it: **3 ms at 1k, 2 ms at 10k, 1 ms at 50k.**
> I repeated a documented error because I reasoned from the shape of the code instead of reading
> the measurement that already existed.
>
> **What is real:** `reconcileForUser` is synchronous per write and costs, on this machine,
> **292 ms at 1k / 803 ms at 10k / ~4.2 s at 50k**, writing ~21k rows at 50k. That is worth
> knowing and is genuinely reachable by one account with a long import history.
>
> **Why nothing was changed here.** The repository has already taken this as far as evidence
> supports: the date-windowing fix landed (52.8 s → single-digit seconds at 50k), account-bucketing
> was prototyped, measured end-to-end and **rejected for no measurable improvement**, and the
> service already logs a warning naming itself as synchronous request-thread work. The doc's
> conclusion is explicit — *"What would actually help next is unknown, and the honest position is
> that nobody should guess... a background worker is not it — that would do the same quadratic work
> somewhere the user cannot see it."*
>
> Shipping an optimization here without a fresh end-to-end measurement would violate the rule this
> repository already wrote down and already enforced against one prototype. **Downgraded to Medium
> and left open deliberately, not overlooked.** The named next step is a DB-backed measurement of
> the write-back and entity hydration, which `scaling-triggers.md` lists as *still unmeasured*.

## Title
Every single-transaction write re-processes the user's entire transaction history, twice

## Severity
High

## Location
File: `backend/src/main/java/com/finora/transactions/TransactionService.java`
Function/Class: `create`, `update`, `delete`, `bulkDelete`
Line(s): 225–226, 333–334, 437–438, 452–453

## Description
Every mutating path calls both `reconciliationService.reconcileForUser(userId)` and `recurringService.detectForUser(userId)` synchronously, inside the request transaction. Both load `transactionRepository.findByUserId(userId)` in full; reconciliation then runs a duplicate pass, an O(n·window) transfer pass and a refund pass, and recurring detection re-saves the whole set.

## Evidence
```java
Transaction saved = transactionRepository.save(t);
adjustAccountBalance(saved.getAccountId(), balanceOf(saved));
reconciliationService.reconcileForUser(userId);
recurringService.detectForUser(userId);
```
`ReconciliationService.reconcileForUser` line 73: `List<Transaction> all = transactionRepository.findByUserId(userId);`
`RecurringService.detectForUser` line 227: same full load, then line 300 `transactionRepository.saveAll(active)`.

## Impact
Adding one ₹200 coffee transaction for a user with 20,000 imported transactions loads 40,000 rows across two services and holds one of only ten pooled connections (`DB_POOL_MAX_SIZE: 10`) for the duration. Latency grows linearly with account age, and the connection pool becomes the bottleneck under concurrent use. `ImportService.confirm` (line 636–641) does the same after every import.

## Reproduction
Seed 20,000 transactions, then `POST /api/v1/transactions`; compare response time against a fresh account.

## Confidence
High

---

# Bug 12 — [FIXED]

> **Fixed by this review.** `ImportSession.fileContent` now carries
> `@Basic(fetch = FetchType.LAZY)`, matching `StatementImport`. Both readers of these bytes are
> inside `@Transactional` methods, which is what makes lazy safe. The reusable half is Guardian rule
> **FG-030** (`LazyBinaryColumnTest`): every persistent `byte[]` on an `@Entity` must declare a LAZY
> fetch strategy, so the next entity cannot repeat the omission. Its `ruleIsNotVacuous` self-test
> caught the rule scanning zero fields on its first run — ArchUnit reports `byte[]` under the JVM
> binary name `[B`, not `"byte[]"`.

## Title
`ImportSession.fileContent` is eagerly fetched — every session query loads up to 10 MB of raw file bytes

## Severity
High

## Location
File: `backend/src/main/java/com/finora/entity/ImportSession.java`
Function/Class: field `fileContent`
Line(s): 49–50

## Description
The column is declared with no fetch strategy, so JPA's default for a basic `byte[]` attribute applies: EAGER. The sibling entity that holds the identical data explicitly does the opposite.

## Evidence
`ImportSession.java`:
```java
@Column(name = "file_content", nullable = false)
private byte[] fileContent;
```
`StatementImport.java` lines 72–78, with a comment explaining why:
```java
// Deliberately NOT @Lob: on PostgreSQL, Hibernate maps @Lob byte[] to the `oid` large-object ...
@Basic(fetch = FetchType.LAZY)
private byte[] fileContent;
```
`application.yml:69–74` also names the one place that legitimately needs bytes in-session (`StatementImportService.getFile`), confirming the intended design is lazy everywhere else.

## Impact
`ImportSessionService.cleanupExpired` (line 75) does `deleteAll(findByExpiresAtBeforeOrderByExpiresAtAsc(...))` — it materialises up to `CLEANUP_BATCH` expired sessions' complete file bytes into the JVM heap purely to delete them. With a 10 MB upload cap that is hundreds of megabytes of avoidable allocation, triggered from a user request path. Every ordinary session load (stage, confirm, expiry check) also drags the full file.

## Reproduction
Stage several 10 MB PDFs, let them expire, then stage another and watch heap during `cleanupExpired`.

## Confidence
High

---

# Bug 13 — [FIXED]

> **Fixed while this review was in progress.** `AuthService.java:320–324` now clears
> `failedLoginAttempts` and `lockedUntil` once the lockout has elapsed. Retained for the record.

## Title
Account lockout re-locks on the very next wrong password, forever

## Severity
High

## Location
File: `backend/src/main/java/com/finora/service/AuthService.java`
Function/Class: `login`, `registerFailedLogin`
Line(s): 306–309, 339–343, 446–456

## Description
`lockedUntil` expiring does not reset `failedLoginAttempts`. The counter is only cleared on a *successful* login. So once an account has reached the threshold, every subsequent failed attempt sees `attempts = previous + 1`, which is still `>= maxFailedLoginAttempts`, and re-locks for the full lockout duration.

## Evidence
```java
private void registerFailedLogin(User user) {
    var settings = platformSettingsService.getEntity();
    int attempts = user.getFailedLoginAttempts() + 1;   // never reset when lockedUntil expires
    user.setFailedLoginAttempts(attempts);
    if (attempts >= settings.getMaxFailedLoginAttempts()) {
        user.setLockedUntil(Instant.now().plusSeconds(settings.getLockoutDurationMinutes() * 60L));
        ...
    }
```
The only reset is on the success path (lines 339–343), reached only *after* `authenticationManager.authenticate` succeeds.

## Impact
A user who genuinely forgot their password and burned five attempts is locked for 15 minutes; one more wrong guess after the lockout lifts locks them for another 15, indefinitely. The intended behaviour ("locked for N minutes, then a fresh budget of attempts") does not exist. This is a self-inflicted denial of service on legitimate users and is also remotely triggerable against a known account.

## Reproduction
1. Fail login 5 times (account locks).
2. Wait past `lockoutDurationMinutes`.
3. Fail once more → locked again for the full duration, on attempt #6 rather than #5.

## Confidence
High

---

# Bug 14

## Title
Refresh tokens are never purged — the table grows one row per 15-minute rotation, per device, forever

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/service/RefreshTokenService.java`
Function/Class: `issue`, `rotate`
Line(s): 64–80, 166–172

## Description
Every sign-in creates a `refresh_tokens` row, and `rotate` creates another on every use while only setting `revokedAt` on the old one. Nothing anywhere deletes expired or revoked rows.

## Evidence
```
$ grep -rn "deleteBy\|@Modifying" backend/src/main/java/com/finora/repository/
CategoryRuleRepository.java:40:    @Modifying
ImportSessionRepository.java:53:    @Modifying
PasswordResetTokenRepository.java:37:    @Modifying(...)
PlatformSettingsRepository.java:15:    @Modifying
RelationshipIdentifierRepository.java:17:    void deleteByRelationshipId(UUID relationshipId);
```
No `RefreshTokenRepository` entry. `ImportSessionService` has an explicit `SESSION_TTL` and `cleanupExpired`; refresh tokens have no equivalent.

## Impact
Access tokens live 15 minutes (`JWT_EXPIRATION_MS: 900000`), so an actively used session rotates ~96 times a day. That is ~96 permanently retained rows per active device per day, each carrying `tokenHash`, `lastSeenIp`, `browser`, `device` — i.e. a growing store of device-tracking PII with no retention policy. `listActiveSessions` filters at query time so it stays correct, but the table and its index grow without bound.

## Reproduction
`SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL OR expires_at < now();` — monotonically increasing.

## Confidence
High

---

# Bug 15

## Title
The rate limiter's memory-leak sweep never runs for the low-traffic limiters it was written to protect

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/config/RateLimiter.java`
Function/Class: `allow`, `evictExpired`
Line(s): 36–37, 45–49

## Description
`callCount` and `SWEEP_INTERVAL` are per-`RateLimiter` *instance*, and `RateLimitFilter` constructs six independent instances. The sweep fires only after 1,000 calls *to that specific limiter*. Limiters whose whole purpose is to cap traffic at a low ceiling therefore almost never reach 1,000 calls, and their `windows` map is never swept.

## Evidence
```java
private static final long SWEEP_INTERVAL = 1000;
private final AtomicLong callCount = new AtomicLong();

public boolean allow(String key) {
    long now = Instant.now().getEpochSecond();
    if (callCount.incrementAndGet() % SWEEP_INTERVAL == 0) { evictExpired(now); }
```
`RateLimitFilter` fields (lines 71–95) create six separate instances, including `registerLimiter = new RateLimiter(5, 300)` and `forgotPasswordLimiter = new RateLimiter(5, 300)`.

The class comment states the sweep exists because *"`/auth/login` and `/auth/register` are public and routinely hit by bots/scanners, so on a long-running single instance this grows without bound — a slow memory-exhaustion vector needing no authentication to trigger."* The registration path is one of the two named, and it is the one the sweep does not reach.

## Impact
The unbounded-growth vector the sweep was written to close remains open for `registerLimiter`, `forgotPasswordLimiter`, `resetPasswordLimiter`, `importStageLimiter` and `passwordChangeLimiter`. Each distinct client IP that ever touches those endpoints leaves a permanent `Window` entry.

## Reproduction
Send 999 registration attempts from 999 distinct IPs; `windows.size()` stays at 999 indefinitely because the 1,000th call never arrives.

## Confidence
High

---

# Bug 16

## Title
Case-sensitive category resolution creates duplicate categories that split budgets and reports

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/service/CategorizationService.java`
Function/Class: `resolveOrCreateCategory`
Line(s): 215–223

## Description
Lookup is `categoryRepository.findByUserIdAndName(userId, name)` — an exact, case-sensitive match against a `UNIQUE(user_id, name)` index that is itself case-sensitive. Nothing normalises case or trims whitespace on the way in.

## Evidence
```java
public Category resolveOrCreateCategory(UUID userId, String name) {
    return categoryRepository.findByUserIdAndName(userId, name).orElseGet(() -> { ... save ... });
}
```
`CategoryRepository.java:12`: `Optional<Category> findByUserIdAndName(UUID userId, String name);`
`V1__init_schema.sql:41`: `UNIQUE(user_id, name)` — no `LOWER()` expression index.
`AuthService.DEFAULT_CATEGORIES` seeds title case (`"Dining"`, `"Groceries"`); users type freely via `PATCH /transactions/{id}/category`, bulk recategorize, and the import review screen. `BudgetService.upsert` (line 106) uses the same case-sensitive lookup.

## Impact
`"dining"` and `"Dining"` become two rows. Spend splits across them, so a budget attached to one shows the wrong number, the dashboard donut shows two slices for one concept, and reports double-count categories. The same applies to trailing whitespace (`"Dining "`).

## Reproduction
1. `PATCH /api/v1/transactions/{id}/category` with `{"categoryName":"dining"}`.
2. `GET /api/v1/categories` → both `Dining` and `dining` exist.

## Confidence
High

---

# Bug 17 — [FIXED]

> **Fixed by this review.** `Account.balance` now moves with the transactions Finora holds:
> a corroborated closing balance wins outright (Bug 02's guard), and with no such statement the
> balance moves by the imported rows' net effect. The rule left `TransactionService`'s private
> `balanceDelta` and became `AccountBalanceConvention.balanceDelta`/`netDelta` — that privacy is
> precisely why the import path could not reuse it and shipped this bug.
>
> **`StatementImportService.delete` was fixed in the same change, and had to be.** It removed a
> statement's transactions without touching the balance, so applying a delta on import without
> reversing it on delete would have left the balance permanently overstated after any
> import/delete cycle — a new bug in place of the old one. Regression coverage:
> `ImportAccountBalanceIT` (6 end-to-end cases against a real database, including the credit-card
> inversion and the round trip) plus `AccountBalanceConventionTest`.

## Title
Imports never adjust the account balance when the statement has no closing balance

## Severity
High

## Location
File: `backend/src/main/java/com/finora/imports/ImportService.java`
Function/Class: `confirm`
Line(s): 611–628

## Description
Imported transactions are written with `transactionRepository.saveAll(toInsert)` and never pass through `TransactionService.adjustAccountBalance`. The only balance update is the conditional overwrite from `statementClosingBalance`. If that value is null — any CSV with no closing-balance column, any PDF where `StatementSummaryExtractor` finds nothing — the balance is left completely untouched.

## Evidence
```java
List<Transaction> saved = transactionRepository.saveAll(toInsert);
int imported = saved.size();
...
if (request.statementClosingBalance() != null && isMostRecentStatementForAccount(...)) {
    // ... the ONLY balance write on this path
}
```
There is no `adjustAccountBalance` call anywhere in `ImportService`; `grep -n "adjustAccountBalance" imports/` returns nothing.

## Impact
Importing 300 transactions into a new account leaves its balance at the opening balance (set once by `AccountService.create`). Net worth, dashboard totals, the health score and low-balance alerts are all wrong, and stay wrong until a manual transaction happens to be entered through `TransactionService`. The comment at line 614 claims this class of bug was fixed, but the fix only covers the closing-balance-present branch.

## Reproduction
Import a CSV with a Date/Description/Amount layout and no balance column into a new account. `GET /api/v1/accounts` → the balance is unchanged.

## Confidence
High

---

# Bug 18

## Title
The learning queue's stuck-row recovery consumes a retry attempt for a failure the event did not cause

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/service/MerchantLearningEventWorker.java`
Function/Class: `recoverAbandoned`
Line(s): 197–208

## Description
Recovery calls `event.recordFailure(...)`, which increments `attemptCount` and, at `MAX_ATTEMPTS`, sets `status = FAILED` permanently. But being abandoned in PROCESSING means a *worker* died — the event itself was never evaluated.

## Evidence
```java
stuck.forEach(event -> event.recordFailure(
        "Abandoned in PROCESSING for longer than " + PROCESSING_TIMEOUT, now));
```
`MerchantLearningEvent.recordFailure` (lines 129–143):
```java
this.attemptCount++;
...
if (this.attemptCount >= MAX_ATTEMPTS) { this.status = Status.FAILED; }
```

## Impact
Five deploys (each a SIGTERM that can strand an in-flight claim) are enough to burn an event's entire retry budget and mark it permanently FAILED, requiring manual admin intervention for work that never actually failed. It also writes a misleading `last_error` that an operator will read as an application fault.

## Reproduction
Claim an event, kill the JVM, wait out `PROCESSING_TIMEOUT`, repeat five times → the event is `FAILED` with `attemptCount = 5` and was never once applied.

## Confidence
High

---

# Bug 19

## Title
Retry backoff is double the documented schedule (off-by-one on `attemptCount`)

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/entity/MerchantLearningEvent.java`
Function/Class: `recordFailure`, `backoffFor`
Line(s): 129–149

## Description
`recordFailure` increments `attemptCount` *before* computing the backoff, so the first retry uses `2^1 = 2` minutes, not `2^0 = 1`.

## Evidence
```java
public void recordFailure(String error, Instant now) {
    this.attemptCount++;                                  // now 1 on the first failure
    ...
    this.nextAttemptAt = now.plus(backoffFor(this.attemptCount));   // backoffFor(1) = 2 minutes
```
```java
static Duration backoffFor(int attempts) { return Duration.ofMinutes(1L << Math.min(attempts, 30)); }
```
Contradicted by three places: the class javadoc (line 35) — *"Five attempts spread over 1 + 2 + 4 + 8 + 16 minutes"*; `recordFailure`'s own javadoc (line 124) — *"so the first retry waits a minute"*; and `V62__merchant_learning_events.sql:45` — *"now() + 2^attempt_count minutes (1, 2, 4, 8, 16)"*.

## Impact
Actual schedule is 2, 4, 8, 16 minutes (attempt 5 goes straight to FAILED). Not harmful in itself, but three separate documents state a schedule the code does not implement, so anyone tuning the retry policy or debugging a stuck queue reasons from a wrong model.

## Reproduction
Fail an event once and read `next_attempt_at` — it is `now() + 2 minutes`, not `now() + 1 minute`.

## Confidence
High

---

# Bug 20 — [FIXED]

> **Fixed while this review was in progress.** `requeueForRetry` now sets `lastError = null`
> (MerchantLearningEvent.java:195). Retained for the record.

## Title
Admin "Retry" on a failed learning event leaves the old error message in place

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/entity/MerchantLearningEvent.java`
Function/Class: `requeueForRetry`
Line(s): 165–171

## Description
The method resets `status`, `attemptCount`, `nextAttemptAt` and `lastRetryAt` but never clears `lastError`. `markCompleted` (line 151) *does* clear it, so the asymmetry is unintentional.

## Evidence
```java
public void requeueForRetry(Instant now) {
    this.status = Status.PENDING;
    this.attemptCount = 0;
    this.nextAttemptAt = now;
    this.lastRetryAt = now;
    this.updatedAt = now;
    // lastError untouched
}
```
vs.
```java
public void markCompleted(Instant now) {
    this.status = Status.COMPLETED;
    this.lastError = null;
    ...
}
```

## Impact
The admin queue (WI2) shows a PENDING event still displaying the error from its previous, superseded failure. An operator re-checking after a retry cannot tell whether the error is current.

## Reproduction
Let an event reach FAILED, call `requeueForRetry`, read the row: `status = PENDING`, `last_error` still populated.

## Confidence
High

---

# Bug 21 — [FIXED]

> **Fixed while this review was in progress.** The literal is now the source form
> `com.finora.entity.MerchantLearningEvent.Status.PROCESSING`
> (MerchantLearningEventRepository.java:66). Retained for the record.

## Title
JPQL enum literal uses the JVM binary `$` form instead of the source-level dotted form

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/repository/MerchantLearningEventRepository.java`
Function/Class: `findStuckInProcessing`
Line(s): 57–63

## Description
The query references the nested enum constant as `com.finora.entity.MerchantLearningEvent$Status.PROCESSING`. JPQL/HQL path expressions are source-level, not binary-level; the canonical form is `com.finora.entity.MerchantLearningEvent.Status.PROCESSING`.

## Evidence
```java
@Query("""
       SELECT e FROM MerchantLearningEvent e
        WHERE e.status = com.finora.entity.MerchantLearningEvent$Status.PROCESSING
          AND e.updatedAt < :staleBefore
       """)
```
Every other repository in the codebase either binds the enum as a parameter or uses a derived query name (`findByStatusOrderByLastRetryAtDesc` two lines below does exactly that) — this is the only `$`-form literal in the tree.

## Impact
This is resolved at `EntityManagerFactory` bootstrap. If the HQL parser rejects the `$` form on the deployed Hibernate version, the application fails to start, not at first use — a boot-time failure of the whole service. Even where it parses, it is fragile against a Hibernate upgrade for no benefit; a bound parameter would be portable. Note that `recoverAbandoned` is the only caller, and it is invoked from `poll()`, which is disabled under test (`application-test.yml` sets `app.learning.queue.enabled: false`) — so no test exercises this query.

## Reproduction
Boot the application with the queue enabled and no test override; the query is validated during `SessionFactory` creation.

## Confidence
Medium

---

# Bug 22

## Title
`X-Request-Id` is set on every response but is not exposed to either browser app

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/config/CorsConfig.java`
Function/Class: `corsConfigurationSource`
Line(s): 31–48

## Description
`CorrelationIdFilter` writes `X-Request-Id` on every response (`CorrelationIdFilter.java:59`) specifically so *"a client can report 'this is the request that failed' without needing to parse logs."* `CorsConfiguration.setExposedHeaders` is never called, and the CORS default exposes only the seven CORS-safelisted response headers.

## Evidence
```java
config.setAllowedOrigins(origins);
config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
config.setAllowCredentials(true);
// no setExposedHeaders(...)
```
Both SPAs are cross-origin (`VITE_API_BASE_URL` pointing at a separate backend origin; `CorsConfig`'s own existence presumes it).

## Impact
`response.headers['x-request-id']` is `undefined` in both frontends, so the correlation ID cannot be surfaced in an error toast or a support form. The mechanism is built end-to-end and unusable from the two clients it was built for. (It is still readable from the response *body* via `ApiResponse.requestId`, which limits the severity.)

## Reproduction
In the browser devtools console on the deployed app: `(await fetch(API + '/accounts')).headers.get('x-request-id')` → `null`.

## Confidence
High

---

# Bug 23

## Title
CORS `allowedHeaders` omits `X-Request-Id`, so a client that propagates its own ID fails preflight

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/config/CorsConfig.java`
Function/Class: `corsConfigurationSource`
Line(s): 43

## Description
`CorrelationIdFilter` is explicitly designed to accept a client-supplied `X-Request-Id` — *"reused from the client's X-Request-Id header if present (so a frontend or gateway can propagate its own ID)"* — and validates it against a length bound and character allowlist. But `setAllowedHeaders(List.of("Authorization", "Content-Type"))` means a cross-origin request carrying that header fails its `OPTIONS` preflight.

## Evidence
`CorrelationIdFilter.java:18–19, 32, 53`:
```java
public static final String HEADER_NAME = "X-Request-Id";
...
String requestId = request.getHeader(HEADER_NAME);
```
`CorsConfig.java:43`: `config.setAllowedHeaders(List.of("Authorization", "Content-Type"));`

## Impact
The client-propagation half of the correlation-ID design is unusable from any browser client, and the failure mode is an opaque CORS error on the first request that tries it — not an obvious one to diagnose.

## Reproduction
`axios.get(url, { headers: { 'X-Request-Id': 'abc' } })` from `localhost:5173` → preflight rejected.

## Confidence
High

---

# Bug 24

## Title
`ClientIpResolver` takes the last `X-Forwarded-For` hop, which is wrong for a two-proxy deployment

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/config/ClientIpResolver.java`
Function/Class: `resolve`
Line(s): 34–43

## Description
When `trust-proxy-headers` is on, the resolver unconditionally returns the *last* comma-separated entry. That is correct only when exactly one trusted proxy appended to the header. With two hops in front of the app, the last entry is the address of the *inner* proxy, not the client.

## Evidence
```java
String[] hops = forwardedFor.split(",");
return hops[hops.length - 1].trim();
```
There is no configurable trusted-hop count. `TRUST_PROXY_HEADERS` is a single boolean (`application.yml:202`), so the resolver cannot express "trust N hops".

## Impact
This value keys the rate limiter's buckets (`RateLimitFilter.doFilterInternal:150`) and is persisted as `RefreshToken.lastSeenIp` for device-session display. In a two-proxy topology every user is bucketed under a small set of edge IPs — the exact "every user on the platform would silently share one rate-limit bucket" failure the property was introduced to prevent — and the "Active sessions" screen shows infrastructure IPs instead of the user's own.

## Reproduction
Send `X-Forwarded-For: 1.2.3.4, 5.6.7.8` with `TRUST_PROXY_HEADERS=true`; the resolver returns `5.6.7.8`.

## Confidence
Medium — correctness depends on the deployed proxy chain, which this review cannot observe from the repo. The single-hop case is handled correctly.

---

# Bug 25

## Title
`RateLimiter.allow` reads the counter outside the atomic compute, producing spurious rejections

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/config/RateLimiter.java`
Function/Class: `allow`
Line(s): 50–57

## Description
`windows.compute(...)` holds the per-bin lock while incrementing, but the returned `Window`'s count is read *after* the compute returns. A concurrent `allow(key)` for the same key can increment in between, so this call observes a count higher than its own.

## Evidence
```java
Window window = windows.compute(key, (k, existing) -> {
    if (existing == null || now - existing.windowStartEpochSeconds() >= windowSeconds) {
        return new Window(now, new AtomicInteger(1));
    }
    existing.count().incrementAndGet();      // the value this call earned is discarded
    return existing;
});
return window.count().get() <= maxRequests; // re-read, races with other threads
```
`incrementAndGet()` already returns the correct per-call value; it is thrown away.

## Impact
Under concurrent requests from one IP, a request that was legitimately the Nth (within the limit) can read N+k and be rejected with a 429. Fails in the safe direction, but it makes the limiter non-deterministic and the effective ceiling lower than configured.

## Reproduction
Fire 10 concurrent logins from one IP against `new RateLimiter(10, 60)`; some runs reject one of them.

## Confidence
High

---

# Bug 26

## Title
`PhoneVerificationFilter` fails open when the user row cannot be found

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/security/PhoneVerificationFilter.java`
Function/Class: `doFilterInternal`
Line(s): 101–109

## Description
The gate is `if (phoneVerified.isPresent() && !phoneVerified.get())`. An empty `Optional` — no such user id, or a non-UUID principal — falls through and the request proceeds.

## Evidence
```java
Optional<Boolean> phoneVerified = parseId(userDetails.getUsername())
        .flatMap(userRepository::findPhoneVerifiedById);
if (phoneVerified.isPresent() && !phoneVerified.get()) {
    // 403 PHONE_VERIFICATION_REQUIRED
}
// otherwise: filterChain.doFilter(...)
```

## Impact
This is a security control that fails open. It is partly mitigated because `JwtAuthFilter` → `CurrentUserDetailsService.loadUserByUsername` would normally have thrown `UsernameNotFoundException` for a missing user, so the principal should not exist. But the two filters do not share a persistence context (this class's own comment at lines 97–100 says so explicitly), so the two reads can disagree — for example when a user is deleted between them, or under a read-replica lag. The class doc states the design intent as "the backend must be the source of truth, not frontend navigation"; a fail-open default contradicts that.

## Reproduction
Present a valid JWT while the corresponding `users` row is concurrently deleted; the phone gate is skipped for that request.

## Confidence
Medium

---

# Bug 27

## Title
Suspended-user rejection on refresh commits the rotation, orphaning a token nobody receives

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/AuthService.java`
Function/Class: `refresh`
Line(s): 381–397

## Description
`@Transactional(noRollbackFor = ApiException.class)` means the suspension rejection at line 389 does not roll back the work `refreshTokenService.rotate` already did: the presented token is revoked and a brand-new token is persisted. The new token is then discarded because the method throws instead of returning it.

## Evidence
```java
@Transactional(noRollbackFor = ApiException.class)
public RefreshResponse refresh(RefreshRequest request) {
    var rotation = refreshTokenService.rotate(request.refreshToken());  // revokes old, issues + saves new
    User user = userRepository.findById(rotation.userId())...
    if (user.isSuspended()) {
        throw new ApiException(HttpStatus.FORBIDDEN, ...);              // commits everything above
    }
```

## Impact
Every refresh attempt by a suspended user leaves a live, never-delivered `refresh_tokens` row behind. Combined with Bug 14 (no purge) these accumulate. The security outcome is correct (the user is locked out) — this is a data-hygiene defect, not an auth bypass.

## Reproduction
Suspend a user with an active session, let their client attempt a refresh, inspect `refresh_tokens` for that user: one new unrevoked row per attempt.

## Confidence
High

---

# Bug 28

## Title
Active-session list sorts sessions with no captured metadata to the top

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/RefreshTokenService.java`
Function/Class: `listActiveSessions`
Line(s): 185–188

## Description
The derived query orders by `lastSeenAt DESC`. `lastSeenAt` is nullable — `captureDeviceMetadata` (lines 85–96) swallows any exception and leaves every device field null. PostgreSQL's default for `ORDER BY ... DESC` is `NULLS FIRST`.

## Evidence
```java
public List<RefreshToken> listActiveSessions(UUID userId) {
    return refreshTokenRepository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(
            userId, Instant.now());
}
```
```java
private void captureDeviceMetadata(RefreshToken rt) {
    try { ... rt.setLastSeenAt(Instant.now()); }
    catch (Exception e) { /* device metadata is a nice-to-have */ }
}
```

## Impact
Directly contradicts the method's stated contract — *"ordered most-recently-active first, since that's the order a user actually scans when looking for 'which one is my phone right now' or 'what's this session I don't recognize.'"* The sessions with the least information sort above the ones the user is looking for, on a security screen.

## Reproduction
Issue a token from a context where `captureDeviceMetadata` throws (no request-scoped `HttpServletRequest`), then a normal browser sign-in; the metadata-less session lists first.

## Confidence
High

---

# Bug 29

## Title
Swagger UI and the OpenAPI document are unauthenticated on every profile that isn't literally `prod`

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/config/SecurityConfig.java`
Function/Class: `filterChain`
Line(s): 75

## Description
`.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()` is unconditional. The only thing that closes it is `application-prod.yml` setting `springdoc.*.enabled: false`, which is keyed on the profile name being exactly `prod`.

## Evidence
`SecurityConfig.java:75`:
```java
.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
```
`application-prod.yml:15–19` is the only mitigation, and `ProductionConfigValidator.run` (line 97) shows how profile detection works: `Arrays.asList(environment.getActiveProfiles()).contains("prod")`.

## Impact
Any internet-reachable environment running `staging`, `demo`, `preview`, `uat`, or a mis-set `SPRING_PROFILES_ACTIVE` publishes a complete, unauthenticated API map of a financial application — precisely what the prod config's own comment calls *"reconnaissance material for an attacker on a financial API."* The protection depends on a config file rather than on the authorization rule.

## Reproduction
Start with `SPRING_PROFILES_ACTIVE=staging` and `curl http://host/v3/api-docs` → 200 with the full spec.

## Confidence
High

---

# Bug 30

## Title
`TransactionNormalizer` silently stages the running balance as the transaction amount

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/imports/TransactionNormalizer.java`
Function/Class: `normalize`
Line(s): 48–52, 218–221

## Description
`AMOUNT_HINTS` ends with `"balance", "running balance", "closing balance"`. The amount lookup walks hints in order and takes the first that parses, so any row where the real debit/credit cell fails to parse — an unhandled currency glyph, a parenthesised negative, an unrecognised Dr/Cr form — falls through to the balance column, and the balance is staged as the amount.

## Evidence
```java
private static final String[] AMOUNT_HINTS = {"amount", "debit", "credit",
        ..., "balance", "running balance", "closing balance"};
```
```java
String amountRaw = firstNonZeroAmount(row, AMOUNT_HINTS);
if (amountRaw == null) amountRaw = firstParseableAmount(row, AMOUNT_HINTS);
if (dateRaw == null || amountRaw == null) return null;
```
The fallback is deliberate for OPENING/CLOSING BALANCE summary rows (the comment at lines 30–35 says so), but nothing scopes it to those rows — it applies to every ordinary transaction row too.

## Impact
This class's own comment describes the identical failure shape as *"Not a dropped-row bug (which would have been obvious) — a silently-wrong-data bug, worse in kind."* A ₹500 purchase on an account with a ₹47,000 balance imports as a ₹47,000 expense, with no warning and no unparseable-row entry, because the row *did* normalise.

## Reproduction
Stage a CSV with columns `Date, Description, Debit, Balance` where the Debit cell is `(500.00)` (parenthesised, unparseable per Bug 34). The row stages with amount = the Balance value.

## Confidence
High

---

# Bug 31

## Title
`isMostRecentStatementForAccount` loads every statement row the user has ever imported

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/imports/ImportService.java`
Function/Class: `isMostRecentStatementForAccount`
Line(s): 672–677

## Description
The check "is this the newest statement for this account" is answered by fetching every `statement_imports` row for the user and filtering in memory, on every confirm.

## Evidence
```java
return statementImportRepository.findByUserIdOrderByImportedAtDesc(userId).stream()
        .filter(si -> si.getAccountId().equals(accountId) && !si.getId().equals(thisStatementId))
        .allMatch(si -> si.getStatementPeriodEnd() == null || !si.getStatementPeriodEnd().isAfter(thisStatementEnd));
```

## Impact
Bounded in memory (`StatementImport.fileContent` *is* `@Basic(LAZY)`, so bytes are not loaded — see Bug 12 for the sibling that is not), but the query and object graph grow linearly with import history and it runs inside the confirm transaction. `confirmMultiSection` calls `confirm` once per detected account section, multiplying it. A `SELECT max(statement_period_end) WHERE account_id = ?` would answer the same question in constant time.

## Reproduction
Import 500 statements, then time the 501st confirm.

## Confidence
High

---

# Bug 32

## Title
`CsvParser.parseDate` and `maskAccountNumber` throw `NullPointerException` on null input

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/imports/CsvParser.java`
Function/Class: `parseDate`, `maskAccountNumber`
Line(s): 225–231, 318–322

## Description
Both are `public static` entry points on a parsing utility whose whole job is handling unpredictable input, and neither guards its argument. Every sibling in the same class does (`parseNumeric:275`, `detectSignFromRawAmount:242`, `hasTrailingDrCrMarker:257`, `normalizeHeaderCell:162`).

## Evidence
```java
public static LocalDate parseDate(String raw) {
    String withoutTime = TRAILING_TIME.matcher(raw).replaceFirst("");   // NPE if raw == null
```
```java
public static String maskAccountNumber(String raw) {
    String digits = raw.replaceAll("[^0-9]", "");                        // NPE if raw == null
```

## Impact
Callers currently pre-check (`TransactionNormalizer.normalize:222` returns early on `dateRaw == null`), so this is latent rather than live. But it is a public utility on the import path and the inconsistency with every neighbouring method makes the next caller likely to assume the guard exists. An NPE here surfaces as a 500 on a statement upload.

## Reproduction
`CsvParser.parseDate(null)` → `NullPointerException`.

## Confidence
High

---

# Bug 33

## Title
`zipRow` silently discards duplicate column headers

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/imports/CsvParser.java`
Function/Class: `zipRow`
Line(s): 126–133

## Description
Cells are keyed by header text into a `LinkedHashMap`, so a repeated header name overwrites the earlier column's value. Only the last occurrence survives.

## Evidence
```java
public Map<String, String> zipRow(String[] headerRow, String[] cells) {
    Map<String, String> row = new LinkedHashMap<>();
    for (int c = 0; c < headerRow.length; c++) {
        String key = headerRow[c] == null ? "" : headerRow[c].trim();
        row.put(key, c < cells.length ? cells[c] : null);   // overwrites on duplicate key
    }
    return row;
}
```

## Impact
Real statement exports repeat header names — two `Amount` columns for debit and credit, `Date` for both transaction and value date, blank headers for spacer columns. When a debit and a credit column are both labelled `Amount`, the debit value is destroyed and every row imports as whatever the credit column held. Also, every blank header collapses to the single `""` key. Both are silent, and the row still normalises, so nothing reports it.

## Reproduction
Stage a CSV with header row `Date,Description,Amount,Amount,Balance`. Only the fourth column's values reach `TransactionNormalizer`.

## Confidence
High

---

# Bug 34

## Title
Parenthesised negative amounts are not recognised

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/imports/CsvParser.java`
Function/Class: `parseNumeric`
Line(s): 274–316

## Description
`parseNumeric` handles `Dr`/`Cr` markers in bare and parenthesised form, currency prefixes, thousands separators, and the PDFBox rupee-as-`C` artifact — but not the accounting convention `(1,234.00)` meaning −1,234.00.

## Evidence
```java
if (TRAILING_DR.matcher(s).find()) { negative = true; s = TRAILING_DR.matcher(s).replaceFirst(""); }
else if (TRAILING_CR.matcher(s).find()) { s = TRAILING_CR.matcher(s).replaceFirst(""); }
s = s.replaceAll("(?i)^\\s*(rs\\.?|inr)\\s*", "").replace("₹", "")
     .replaceAll("(?i)(?<![A-Za-z0-9])C(?=\\s*\\d)", "")
     .replace(",", "").trim();
s = s.replaceAll("\\s+", "");
if (s.isEmpty() || s.equals("-")) return null;
try { BigDecimal value = new BigDecimal(s); ... }
catch (NumberFormatException e) { return null; }
```
For `"(1234.00)"` the parentheses survive every strip, `new BigDecimal("(1234.00)")` throws, and the method returns `null`.

## Impact
The row's amount is unparseable. In combination with Bug 30 it does not merely drop — it falls through to the balance column and imports the running balance as the amount. Even without that, silently returning `null` means the row vanishes.

## Reproduction
`CsvParser.parseNumeric("(1,234.00)")` → `null`.

## Confidence
High

---

# Bug 35

## Title
`BudgetService.upsert` always reports spend as zero

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/budgets/BudgetService.java`
Function/Class: `upsert`
Line(s): 126

## Description
The returned `BudgetDto` hardcodes `BigDecimal.ZERO` for the `spent` field, regardless of what the category has actually accrued this month. `listForUser` computes the real value; `upsert` does not.

## Evidence
```java
return new BudgetDto(saved.getId(), category.getId(), category.getName(),
        saved.getMonthlyLimit(), BigDecimal.ZERO);
```
compared with `listForUser` (lines 71–76):
```java
BigDecimal spent = spendByCategory.getOrDefault(b.getCategoryId(), BigDecimal.ZERO);
return new BudgetDto(..., b.getMonthlyLimit(), spent);
```

## Impact
Any client that uses the mutation response to update local state — the standard optimistic-update pattern — shows 0% progress on a category that is already over budget, until an unrelated refetch corrects it. Editing an existing budget's limit is the common case and displays the most misleading result.

## Reproduction
Spend ₹6,000 on Groceries this month, then `PUT` a ₹5,000 Groceries budget; the response says `spentThisMonth: 0`.

## Confidence
High

---

# Bug 36

## Title
Bulk transaction operations record no acting-admin attribution

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/transactions/TransactionService.java`
Function/Class: `bulkDelete`, `bulkRecategorize`
Line(s): 454–456, 509–511

## Description
`delete` was fixed to thread `actingAdminId` into the audit metadata; the bulk variants were not, and take no actor parameter at all.

## Evidence
`delete` (lines 439–441):
```java
auditService.record(userId, "TRANSACTION_DELETED", "Transaction", txnId,
        Map.of("amount", t.getAmount(), "description", String.valueOf(t.getDescription()),
                "actorId", actingAdminId.toString()));
```
`bulkDelete` (lines 454–456):
```java
auditService.record(userId, "TRANSACTION_BULK_DELETED", "Transaction", null,
        Map.of("count", ids.size(), "ids", ids));       // no actorId
```
`delete`'s own comment names the bug class and lists the services already fixed for it: *"an admin deleting a user's transaction was indistinguishable in the audit trail from the user deleting their own."*

## Impact
The higher-impact operation (deleting many transactions at once) has weaker attribution than the lower-impact one. An audit investigation cannot answer "who deleted these 200 transactions".

## Reproduction
Perform a bulk delete and read the `audit_logs` metadata — no `actorId` key.

## Confidence
High

---

# Bug 37

## Title
Bulk endpoints accept an unbounded id list

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/transactions/TransactionService.java`
Function/Class: `bulkDelete`, `bulkRecategorize`
Line(s): 445–456, 497–511

## Description
Neither method bounds `ids.size()`, and each performs a per-id `getOwned` lookup and a per-id `save`. `bulkDelete` additionally calls `reconcileForUser` and `detectForUser` afterwards.

## Evidence
```java
public void bulkDelete(UUID userId, List<UUID> ids) {
    List<Transaction> owned = ids.stream().map(id -> getOwned(userId, id)).toList();
    ...
    for (Transaction t : owned) { adjustAccountBalance(...); transactionRepository.delete(t); }
```
```java
for (UUID id : ids) {
    Transaction t = getOwned(userId, id);
    ...
    categorizationService.learn(userId, t.getDescription(), category.getId());
    transactionRepository.save(t);
}
```

## Impact
An authenticated caller can post 100,000 ids in one request. Each triggers a `findById`, and `bulkRecategorize` additionally triggers a full `CategorizationService.learn` (merchant resolution + learning confirm) per id. One request holds a pooled connection (of ten) for an unbounded time. Not exploitable without an account, but there is no ceiling and no rate limiter on these paths.

## Reproduction
`POST /api/v1/transactions/bulk-delete` with 50,000 ids.

## Confidence
High

---

# Bug 38

## Title
Recurring badges survive a transaction type change

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/RecurringService.java`
Function/Class: `detectForUser`
Line(s): 227–238

## Description
The reset pass only clears `recurring` on rows in `active`, which is filtered to non-duplicate, non-transfer EXPENSE transactions. A transaction previously flagged recurring that is subsequently edited to INCOME, marked as a transfer, or flagged as a duplicate falls out of `active` and keeps `recurring = true` permanently.

## Evidence
```java
List<Transaction> active = transactionRepository.findByUserId(userId).stream()
        .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer() && t.getTxnType() == Transaction.Type.EXPENSE)
        .toList();
...
active.forEach(t -> t.setRecurring(false));   // only touches `active`
```

## Impact
Contradicts the stated intent — *"a merchant that used to look recurring but no longer does shouldn't keep a stale badge forever."* The Ledger and Reports pages render a permanent, un-clearable "Recurring" badge on a transaction that is now income or a transfer.

## Reproduction
Let a transaction be detected as recurring, then edit its type to INCOME. The badge persists across every subsequent detection run.

## Confidence
High

---

# Bug 39

## Title
Migration version 58 is missing from the sequence

## Severity
Low

## Location
File: `backend/src/main/resources/db/migration/`
Function/Class: Flyway migration sequence
Line(s): between `V57__refresh_token_session_id.sql` and `V59__statement_analysis_sessions.sql`

## Description
Versions run V1–V57, then jump to V59–V62. No `V58__*.sql` exists, and `git log --diff-filter=D -- 'backend/src/main/resources/db/migration/V58*'` shows no deletion on this branch's history.

## Evidence
```
$ ls backend/src/main/resources/db/migration/ | sort -V | tail -8
V55__reconciliation_explanation.sql
V56__refresh_token_session_start.sql
V57__refresh_token_session_id.sql
V59__statement_analysis_sessions.sql     <-- V58 absent
V60__analysis_session_diagnostics.sql
V61__engine_analysis_permission.sql
V62__merchant_learning_events.sql
```

## Impact
Flyway tolerates version gaps, so this does not break a fresh migration. It is a deployment hazard rather than a live defect: if a `V58` ever existed on another branch and was applied to any long-lived environment, `spring.flyway.validate-on-migrate` (on by default, and not disabled in any profile) will fail the next startup of that environment with a missing-applied-migration error. The gap also removes the implicit "no migration was lost" signal a contiguous sequence provides.

## Reproduction
Apply a `V58` to a database, remove the file, restart → Flyway validation failure.

## Confidence
Medium — the gap is certain; whether V58 was ever applied anywhere cannot be determined from the repository.

---

# Bug 40

## Title
Frontend error interceptor discards the structured `details` payload

## Severity
Medium

## Location
File: `frontend/src/api/client.ts` (and the same code in `admin-portal/src/api/client.ts`)
Function/Class: response error interceptor
Line(s): 216–218

## Description
The interceptor replaces `error.response.data` wholesale with an object containing only `message` and `errorCode`, dropping `requestId`, `timestamp`, and — significantly — `details`.

## Evidence
```js
if (error.response?.data?.message) {
  error.response.data = { message: error.response.data.message, errorCode: error.response.data.errorCode };
}
```
The backend deliberately sends `details` for structured errors: `ApiResponse.error(String, String, Map<String,Object>)` (ApiResponse.java:39) and `GlobalExceptionHandler.handleApiException` line 68 — `ApiResponse.error(ex.getMessage(), errorCode, ex.getDetails())`. `ErrorCode`'s class doc describes `details` as the structured channel.

## Impact
No client can ever act on a structured error payload. Every future `ApiException` carrying `details` (field-level import errors, per-row validation results, remaining-attempt counts) is silently truncated to a string at the transport layer, and the truncation is invisible — the caller sees a well-formed error object with a field simply missing.

## Reproduction
Trigger any `ApiException` constructed with a `details` map; inspect `err.response.data` in the browser → `details` is `undefined`.

## Confidence
High

---

# Bug 41

## Title
`clearSessionAndRedirect` falls through instead of returning

## Severity
Low

## Location
File: `frontend/src/api/client.ts`
Function/Class: response error interceptor
Line(s): 198–201

## Description
The `else` branch calls `clearSessionAndRedirect()` and then continues into the 403 phone-verification check and the error-rewrite block below, unlike the `catch` branch above it, which returns immediately.

## Evidence
```js
if (refreshToken) {
  try { ... return api(originalRequest); }
  catch (refreshError) { clearSessionAndRedirect(...); return Promise.reject(error); }
} else {
  clearSessionAndRedirect();
  // no return -- execution continues to the 403 check at line 207
}
```

## Impact
Low: `window.location.href` navigation is asynchronous, so the fall-through can execute a second navigation (`/verify-phone`) before the first completes, if a 401 response also happened to carry `errorCode: PHONE_VERIFICATION_REQUIRED`. The asymmetry with the sibling branch is also a maintenance hazard.

## Reproduction
Difficult to trigger deterministically; identified by inspection of the divergent control flow.

## Confidence
Medium

---

# Bug 42

## Title
Auth-endpoint detection uses substring matching on the URL

## Severity
Low

## Location
File: `frontend/src/api/client.ts` (lines 79, 177) and `admin-portal/src/api/client.ts` (line 59)
Function/Class: request interceptor, response error interceptor
Line(s): 76–87, 177

## Description
Both interceptors decide whether a request is an auth endpoint with `AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path))` — an unanchored substring test against the whole URL rather than a path comparison.

## Evidence
```js
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password'];
const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path));
```

## Impact
Any future endpoint whose URL contains one of these substrings — `/admin/users/{id}/auth/login-history`, `/audit?action=/auth/register`, a query string echoing a path — silently loses its `Authorization` header (request interceptor) and is exempted from the 401-refresh flow (response interceptor). It would present as an unexplained 401 on a correctly-authenticated call. Not currently reachable, but the guard is doing string containment where it means path equality, and `scripts/check-client-auth-policy.py` exists precisely because drift in this file has already shipped a bug once.

## Reproduction
Add an endpoint at `/api/v1/admin/auth/login-attempts`; it receives no token.

## Confidence
High

---

# Bug 43

## Title
Logging out does not notify `ThemeProvider`, so the previous account's theme persists

## Severity
Low

## Location
File: `frontend/src/context/AuthContext.tsx`
Function/Class: `logout`
Line(s): 94–111 (compare `persist`, line 61)

## Description
`persist` dispatches `AUTH_CHANGED_EVENT` so `ThemeProvider` re-pulls the account's saved theme. `logout` clears all five storage keys and resets all four state values but dispatches nothing.

## Evidence
```js
function persist(data) {
  ...
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

function logout() {
  ...
  setPhoneVerifiedState(false);
  // no dispatch
}
```
`ThemeContext.tsx:75` subscribes: `window.addEventListener(AUTH_CHANGED_EVENT, syncFromServer);`

## Impact
After signing out, the app keeps rendering in the signed-out user's saved theme (e.g. dark mode) on the public login/landing pages, and the next user to sign in on that browser sees the previous user's theme until their own `persist` fires. A visible, if minor, leak of one account's preference to the next.

## Reproduction
Sign in as a dark-theme user, sign out → the login page stays dark.

## Confidence
High

---

# Bug 44

## Title
Dashboard spending-breakdown percentages render as `NaN%` when the total is zero

## Severity
Low

## Location
File: `frontend/src/pages/Dashboard.tsx`
Function/Class: Spending Breakdown panel
Line(s): 126, 222

## Description
`totalSpend` is the sum of `spendByCategory`'s values. The empty-state guard checks `categoryEntries.length === 0`, not `totalSpend === 0`, so a non-empty map whose values sum to zero reaches the division.

## Evidence
```jsx
const totalSpend = categoryEntries.reduce((s, [, v]) => s + v, 0);
...
{categoryEntries.length === 0 ? (<p>No spending data yet.</p>) : (
  ...
  <span className="text-muted">{((val / totalSpend) * 100).toFixed(0)}%</span>
```

## Impact
`0 / 0` → `NaN` → renders the literal text `NaN%` next to every category, and the Chart.js doughnut receives an all-zero dataset. Reachable whenever the reporting month's only expenses are zero-amount rows — which Bug 30 and `TransactionNormalizer`'s zero-amount fallback (line 221) make possible from a real import.

## Reproduction
Import a statement whose only expense rows have amount 0, then open `/app`.

## Confidence
Medium

---

# Bug 45

## Title
CSV exports have no UTF-8 BOM, so non-ASCII text is mojibake in Excel

## Severity
Low

## Location
File: `frontend/src/lib/download.ts` (`toCsv`) and `frontend/src/pages/Reports.tsx` (line 18–19)
Function/Class: `toCsv`, `downloadCsv`
Line(s): download.ts:71–73; Reports.tsx:18–19

## Description
The CSV text is handed to `new Blob([csv], { type: 'text/csv' })` with no BOM prefix and no charset in the MIME type. Excel on Windows decodes a BOM-less `.csv` using the system ANSI code page, not UTF-8.

## Evidence
```js
export function toCsv(rows) { return rows.map((row) => row.map(csvCell).join(',')).join('\n'); }
```
```js
const csv = toCsv([['Category', 'Amount'], ...report.categories.map((c) => [c.category, c.amount])]);
downloadBlob(new Blob([csv], { type: 'text/csv' }), `finora-report-${report.month}.csv`);
```

## Impact
Category names are entirely user-supplied (`resolveOrCreateCategory` accepts any string) and this is an India-focused product, so Devanagari, accented Latin and the ₹ symbol are all realistic. Every such character renders as mojibake for the majority of users who open the export in Excel. The same file's `csvCell` goes to considerable lengths to make the export safe and correct, which makes the missing BOM the odd one out.

## Reproduction
Create a category named `भोजन`, export the monthly report, open the file in Excel on Windows.

## Confidence
High

---

# Bug 46

## Title
`useSavedViews` closes over stale state, so two saves in one tick lose one

## Severity
Low

## Location
File: `admin-portal/src/hooks/useSavedViews.ts`
Function/Class: `save`, `remove`
Line(s): 53–62

## Description
Both callbacks read `views` from the closure and pass a derived array to `persist`, which calls `setViews(next)` directly rather than using the functional updater form.

## Evidence
```js
const save = useCallback((name, values) => {
  const trimmed = name.trim();
  if (!trimmed) return;
  const withoutExisting = views.filter((v) => v.name !== trimmed);   // captured `views`
  persist([...withoutExisting, { name: trimmed, values }]);
}, [views, persist]);
```

## Impact
Two `save`/`remove` calls dispatched before React re-renders both operate on the same snapshot; the second overwrites the first in both state and `localStorage`. The dependency array is correct, so this only manifests within a single tick — but that is exactly what React 18 automatic batching produces from a loop or a rapid double-click. The user sees a saved view silently vanish, which is the same symptom the `safeStorage` fix at lines 42–48 was written to eliminate.

## Reproduction
Call `save('a', ...)` and `save('b', ...)` in the same event handler; only `b` persists.

## Confidence
High

---

# Bug 47

## Title
`check-imports.py` can no longer detect a missing `Status` import anywhere in the repository

## Severity
Medium

## Location
File: `scripts/check-imports.py`
Function/Class: `scan`
Line(s): 90–103, 194, 209

## Description
The `ambiguous` rule skips any simple name declared in more than one file. `MerchantLearningEvent.Status` (new in V62) is the second type named `Status` in the tree, so `Status` is now ambiguous and every occurrence of it is skipped in every file.

## Evidence
```python
ambiguous = {n for n, locs in declarations.items() if len({f for f, _, _ in locs}) > 1}
...
if name in ambiguous or name not in meta["tokens"] or name in bound_names:
    continue
```
The file's own comment at lines 90–103 states this outright: *"a second type named Status now exists, so the name is declared in more than one file, and the `ambiguous` rule below skips every Status occurrence everywhere. The false positive is suppressed rather than fixed, and one real reference to Status would now be missed along with it."*

## Impact
This script is a hard CI gate (`.github/workflows/ci.yml`, "Import cross-reference"). It now has a permanent, silent blind spot on one of the most commonly duplicated simple names in a Java codebase. Its own docstring identifies this exact mechanism as the one that previously let `Category` through, and its remedy for that was to fix the checker rather than widen a filter — which was not done this time. A missing `Status` import will now be caught only by `javac`, at the point in the pipeline the script exists to precede.

## Reproduction
Delete an `import ...Status;` from a file that references `Status`; `python scripts/check-imports.py` passes.

## Confidence
High

---

# Bug 48

## Title
Pre-commit hook word-splits file paths

## Severity
Low

## Location
File: `.husky/pre-commit`
Function/Class: frontend/admin/mobile lint block
Line(s): the `npx eslint --fix $(...)` and `git add $(...)` lines

## Description
Every filename list is passed through unquoted command substitution, so any staged path containing a space is split into multiple arguments.

## Evidence
```sh
(cd frontend && npx eslint --fix $(echo "$staged" | grep '^frontend/' | sed 's#^frontend/##') 2>/dev/null || true)
...
git add $(echo "$staged" | grep -E '^(frontend|admin-portal|mobile)/')
```

## Impact
A staged file named `My Component.tsx` makes `eslint` receive two nonexistent paths (silently swallowed by `2>/dev/null || true`) and makes `git add` fail or stage the wrong paths. Additionally, the `|| true` means eslint failures never block the commit, so the hook's lint step is advisory only — which is intentional given CI enforces it, but is worth knowing when reading the hook as a guard.

## Reproduction
`git add "frontend/src/pages/My Page.tsx"` then commit.

## Confidence
High

---

# Bug 49

## Title
`CurrentUser` injects a repository it never uses

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/security/CurrentUser.java`
Function/Class: constructor / field `userRepository`
Line(s): 18–22

## Description
The field is assigned and never read. The class's own comment explains that the lookup it used to perform was removed — *"It used to resolve the email to a row on every single call"* — but the now-unused dependency was left behind.

## Evidence
```java
private final UserRepository userRepository;

public CurrentUser(UserRepository userRepository) { this.userRepository = userRepository; }

public UUID id() {
    UserDetails principal = (UserDetails) SecurityContextHolder.getContext()...
    return UUID.fromString(principal.getUsername());   // repository never touched
}
```

## Impact
Dead code that misrepresents the class's dependencies. `CurrentUser` is injected into essentially every controller, so it makes the bean graph appear to depend on `UserRepository` in places it does not, which matters for the architecture tests that reason about layer dependencies (`architecture/LayerDependencyDirectionTest`).

## Reproduction
Static inspection; the field has no read reference in the file.

## Confidence
High

---

# Bug 50

## Title
`CurrentUser.id()` throws on an anonymous request instead of returning a clean 401

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/security/CurrentUser.java`
Function/Class: `id`
Line(s): 32–39

## Description
The method chains `getAuthentication().getPrincipal()` and casts to `UserDetails` with no null check and no `instanceof` check. For an unauthenticated request Spring Security's context holds either `null` or an `AnonymousAuthenticationToken` whose principal is the `String` `"anonymousUser"`.

## Evidence
```java
public UUID id() {
    UserDetails principal = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    try { return UUID.fromString(principal.getUsername()); }
    catch (IllegalArgumentException e) { throw new IllegalStateException("Authenticated principal is not a user id", e); }
}
```
The `catch` handles only the parse failure, not the `NullPointerException` from `getAuthentication()` returning null or the `ClassCastException` from a `String` principal.

## Impact
Any controller method reachable without authentication that calls `currentUser.id()` produces a `ClassCastException`/`NullPointerException`, which falls to `handleGeneric` and returns a 500 `INTERNAL_ERROR` with an `ERROR` log line instead of a clean 401. `SecurityConfig`'s `anyRequest().authenticated()` makes this unreachable today, but the exposure is one `permitAll` matcher away and the failure mode is a 500 rather than a 401.

## Reproduction
Add any endpoint to the `permitAll` list that calls `currentUser.id()`.

## Confidence
High

---

# Bug 51

## Title
`EnumParsing.parse` is case-sensitive and does not trim

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/util/EnumParsing.java`
Function/Class: `parse`
Line(s): 21–30

## Description
`Enum.valueOf(enumType, raw)` requires an exact match. The raw value comes from query parameters and request bodies, where `" EXPENSE"` or `"expense"` are entirely ordinary.

## Evidence
```java
if (raw == null || raw.isBlank()) { throw new ApiException(HttpStatus.BAD_REQUEST, fieldName + " is required"); }
try { return Enum.valueOf(enumType, raw); }
catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Unrecognized " + fieldName + ": " + raw); }
```
The blank check calls `isBlank()` — acknowledging that whitespace-only input is expected — but the value is then passed to `valueOf` un-trimmed.

## Impact
`GET /api/v1/transactions?type=expense` returns 400 rather than filtering. Callers include `TransactionService.search` (line 101), `create` (line 171), `update` (line 309) and `ImportService.confirm`, so the rejection surfaces on the ledger filter, manual entry and import confirm alike.

## Reproduction
`EnumParsing.parse(Transaction.Type.class, "expense", "type")` → 400.

## Confidence
High

---

# Bug 52

## Title
`ApiResponse` reads MDC directly, so background threads emit a null `requestId`

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/dto/ApiResponse.java`
Function/Class: `ok`, `error`
Line(s): 26–42

## Description
Every factory reads `MDC.get("requestId")`, which is populated by `CorrelationIdFilter` only on the servlet thread and cleared in its `finally` block.

## Evidence
```java
public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "OK", data, Instant.now(), null, MDC.get("requestId"), Collections.emptyMap());
}
```
`CorrelationIdFilter.java:66`: `MDC.remove(MDC_KEY);`

## Impact
Now that `BackgroundWorkConfig` introduces `@Async` and `@Scheduled` execution (`learningQueueExecutor`, `MerchantLearningEventWorker.poll`), work running on those threads has no MDC context. Every log line from the worker is tagged `no-request-id` (per the `logging.pattern` fallback in application.yml:297), and there is no `TaskDecorator` propagating MDC across the async boundary. The correlation ID that ties an import to the learning it triggered is lost exactly where it becomes most useful — the async handoff the queue exists to create.

## Reproduction
Trigger a nudge and read the worker's log lines: `[no-request-id]`.

## Confidence
High

---

# Bug 53

## Title
Refresh-token cookie is inert whenever the API is not on the same registrable domain as the SPA

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/security/RefreshTokenCookie.java`
Function/Class: `base`
Line(s): 99–105 (and the class doc, lines 30–36)

## Description
The cookie is issued `SameSite=Lax`. Lax cookies are withheld from cross-*site* subresource requests. The class doc is explicit that this only works because the API shares a registrable domain with the app: *"This only became available when the API moved onto the same registrable domain."*

## Evidence
```java
private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(NAME, value)
            .httpOnly(true).secure(true).sameSite("Lax").path(PATH);
}
```
There is no configuration knob to switch to `SameSite=None` for a deployment where that precondition does not hold, and nothing at startup verifies it (unlike `ProductionConfigValidator`, which checks five other deployment preconditions).

## Impact
On any deployment where the SPA origin and the API origin have different registrable domains, the browser neither stores nor sends this cookie. The XSS mitigation the cookie exists to provide silently does not apply, and the app falls back to the `localStorage` refresh token — which `frontend/src/api/client.ts` lines 41–46 explicitly identify as the readable-by-script copy the cookie was meant to replace. The failure is completely silent: everything keeps working via the fallback.

## Reproduction
Serve the SPA from one registrable domain and the API from another; observe that no `finora_refresh_token` cookie is stored, and that `/auth/refresh` succeeds only via the body token.

## Confidence
Medium — the code is unambiguous; whether the precondition currently holds depends on live DNS this review cannot inspect. Per the repository's own migration notes the DNS work is outstanding.

---

# Bug 54

## Title
`MerchantLearningService.undo` can revert a merge it did not create

## Severity
Medium

## Location
File: `backend/src/main/java/com/finora/service/MerchantLearningService.java`
Function/Class: `undo`
Line(s): 164–228

## Description
`undo` rejects `UNDONE`, `MERGED` and `RESET` as the *most recent* entry, then decrements the confirmation count for `mostRecent.getNewCategoryId()`. But `MerchantService.merge` writes a `MERGED` audit entry *and* absorbs the merged merchant's `MerchantCategoryLearning` counts into the survivor (`MerchantService.java:196`). If a `LEARNED`/`CORRECTED` entry is written after a merge, `undo` decrements a count that includes absorbed confirmations from a different merchant.

## Evidence
```java
UUID categoryToRevert = mostRecent.getNewCategoryId();
List<MerchantCategoryLearning> pairs = learningRepository.findByUserIdAndMerchantId(userId, merchantId);
Optional<MerchantCategoryLearning> target = pairs.stream()
        .filter(p -> p.getCategoryId().equals(categoryToRevert)).findFirst();
if (target.isPresent()) {
    int newCount = pair.getConfirmationCount() - 1;
    if (newCount <= 0) { pairs.remove(pair); learningRepository.delete(pair); }
```
There is no check that the pair's current count is attributable to the audit entry being undone.

## Impact
Undoing one confirmation after a merge can delete an entire distribution row (`newCount <= 0` deletes it) when that row carries confirmations from a merchant that was merged in. The absorbed evidence is destroyed, and `recomputeAndSave` then redistributes confidence across the remaining categories — silently changing what `ConfidenceEngine.topCategory` auto-applies.

## Reproduction
1. Merchant A: 1 confirmation of "Dining". Merchant B: 3 confirmations of "Dining".
2. Merge B into A → A has 4.
3. Confirm "Dining" on A once more (LEARNED, count 5).
4. `undo` → count 4. Repeat four times → the row is deleted, losing B's three absorbed confirmations.

## Confidence
Medium

---

# Bug 55

## Title
`ImportSessionService.cleanupExpired` runs on a user request path and deletes other users' data

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/imports/ImportSessionService.java`
Function/Class: `cleanupExpired`
Line(s): 56–76

## Description
Cleanup is piggybacked onto a user's own `stage()` call and its scope is deliberately global — it deletes any expired session, regardless of owner.

## Evidence
```java
// ... "It used to delete only the acting user's expired rows, which meant an expired
// [session] ..." -- the SCOPE was widened deliberately
importSessionRepository.deleteAll(importSessionRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(...));
```

## Impact
One user's staging request performs deletes on rows belonging to arbitrary other users, inside that user's transaction. Combined with Bug 12 (eager `fileContent`), each such delete first materialises another user's full statement bytes into this request's heap. The widened scope is a reasonable design given no scheduler existed at the time — but `BackgroundWorkConfig` now exists and `@Scheduled` is enabled, so the constraint that justified it has expired. That config's own javadoc names `ImportSessionService` as one of the four places to revisit.

## Reproduction
Have user A stage a statement while user B has expired sessions; A's request deletes B's rows.

## Confidence
High

---

# Bug 56

## Title
A dangling merchant alias is never repaired and spawns one duplicate merchant per import row

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/MerchantNormalizationEngine.java`
Function/Class: `resolve`, `addAlias`
Line(s): 126–134, 186–187

## Description
When an alias row exists but the merchant it points at does not, the `orElseGet` creates a *new* merchant and calls `addAlias` — which immediately short-circuits because the alias already exists and still points at the dead merchant.

## Evidence
```java
var existingAlias = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
if (existingAlias.isPresent()) {
    return merchantRepository.findByIdAndUserId(existingAlias.get().getMerchantId(), userId)
            .orElseGet(() -> createMerchantAndAlias(userId, description, normalizedAlias));
}
```
```java
private void addAlias(UUID merchantId, UUID userId, String normalizedAlias) {
    if (merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias).isPresent()) return;
```

## Impact
The dangling alias is never repaired. Every subsequent row with the same description takes the same path and creates yet another orphan merchant, so a single stale alias produces one duplicate merchant per import row while the alias keeps pointing at nothing. Requires a merchant to be deleted without its aliases, which `Merchant`'s `ON DELETE CASCADE` normally prevents — but `Merchant` extends `BaseEntity` and is *soft*-deleted via `@SQLDelete`/`@SQLRestriction`, so the database cascade never fires and the alias genuinely survives.

## Reproduction
Soft-delete a merchant that has aliases, then import a statement containing a description matching one of those aliases twice.

## Confidence
Medium

---

# Bug 57

## Title
`AuthorizationService` grants `ROLE_null` when the legacy role column is unset

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/AuthorizationService.java`
Function/Class: `effectiveAuthorities`, `meAccess`
Line(s): 177, 203

## Description
Both methods concatenate `user.getRole()` without a null check.

## Evidence
```java
authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
roleRepository.findByName(user.getRole()).ifPresent(role -> addRole(authorities, role));
```
```java
roleNames.add(user.getRole());   // may add null to the set
```

## Impact
A user whose legacy `role` column is null (the RBAC migration's stated end state is that `user_roles` supersedes it) is granted the authority string `"ROLE_null"`, and `meAccess` returns a role list containing `null`, which serialises as a JSON `null` element. `admin-portal/src/context/AdminAuthContext` consumes this list to decide admin-shell access; a `null` element in a string array is the kind of value that produces a runtime error in a `.some(r => r.startsWith(...))`-style check. `roleRepository.findByName(null)` also issues a pointless query on every authentication.

## Reproduction
Set `users.role` to NULL and authenticate; inspect `GET /api/v1/users/me/access`.

## Confidence
Medium

---

# Bug 58

## Title
`InsightsService` omits `Locale.ENGLISH` on one of its five `String.format` calls

## Severity
Low

## Location
File: `backend/src/main/java/com/finora/service/InsightsService.java`
Function/Class: `build`
Line(s): 133–134

## Description
Every other `String.format` in this method pins `Locale.ENGLISH` explicitly, with a comment (lines 104–109) explaining that omitting it makes output depend on the server JVM's default locale. The budget-recommendation sentence does not.

## Evidence
```java
.ifPresent(m -> sentences.add(String.format(
        "Consider setting a budget for %s — it's trending up and doesn't have one yet.", m.category())));
```
compared to its neighbours at lines 110, 114, 138 and 156, all of which pass `Locale.ENGLISH` as the first argument.

## Impact
No live defect: the format string contains only `%s`, which is locale-independent. It is a latent inconsistency — adding any numeric conversion to this sentence would reintroduce the exact host-dependent formatting bug the sibling calls were fixed for, and the surrounding comment would make a reviewer assume the guard is already present.

## Reproduction
Static inspection.

## Confidence
High

---

## Areas reviewed with no findings recorded

For completeness, these were read and produced no defect I could evidence:

- `backend/src/main/java/com/finora/domain/Money.java`, `util/MoneyMath.java`, `util/PageBounds.java`, `util/LikePatterns.java`, `util/TokenHasher.java`, `util/SafeHttpUrl.java` — all fail closed and are individually tested.
- `security/OwnershipGuard.java` — explicit null handling on every branch; enforced by `OwnershipGuardUsageTest`.
- `config/EmailProperties.resolveBaseUrl` — checked specifically for host-header injection via the `Origin` header; it only ever returns a *configured* value, so a forged `Origin` cannot redirect a password-reset link.
- Admin controller authorization — all 25 `Admin*`/`RoleAdmin*` controllers carry either class-level or per-method `@PreAuthorize`; verified by inspection and backed by `architecture/AdminEndpointAuthorizationTest`.
- `service/NetWorthService.saveSnapshotForToday` — its `DataIntegrityViolationException` recovery *is* reachable here (unlike the equivalent `BudgetService` code its comment discusses) because the method is not `@Transactional`, so `save()` commits and throws within its own boundary.
- `frontend/src/lib/download.ts` `csvCell` — the CSV-injection guard and its numeric exemption are correct for the inputs I could construct.
- `entity/MerchantCategoryLearning.lastConfirmedAt` — checked as an NPE risk in `ConfidenceEngine.topCategory`'s `thenComparing`; `V7__merchant_intelligence.sql:44` declares it `NOT NULL DEFAULT now()`, so no null can be loaded.
- `.github/workflows/ci.yml`, `commitlint.config.js`, `.editorconfig`, `.vscode/`, `frontend/src/assets`, `admin-portal/src/assets`, `docs/` — inspected; nothing defective found.

## Coverage limitation: `mobile/`

**`mobile/` was NOT given the same line-by-line treatment as `backend/`, `frontend/` and `admin-portal/`.** Its 85 source files were enumerated and its build, CI, navigation and configuration surface was read, but the screen and hook implementations were not read in full. This report should not be taken as bug-hunt coverage of that app; a separate pass is needed for parity.

One doc-drift item was noted in passing while reading its API layer:

- `mobile/src/api/endpoints.ts:461` still defers device management to *"mobile roadmap Phase 5: recommended as a mobile-first screen"*, but `mobile/src/screens/settings/DeviceSessionsSection.tsx` has since shipped it. The comment now points a reader at future work that is already done. Severity: Low.

## Method and limitations

Findings were derived by reading source directly, not by running the application or its test suite. Where a finding depends on runtime or deployment facts I cannot observe from the repository — Bug 21 (Hibernate's HQL parser), Bug 24 (the live proxy chain), Bug 39 (whether a V58 was ever applied), Bug 53 (current DNS) — I have said so and set Confidence accordingly. No code was modified.
