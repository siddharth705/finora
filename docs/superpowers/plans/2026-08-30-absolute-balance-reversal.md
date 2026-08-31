# Reversible ABSOLUTE-Mode Balance Contributions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an `ABSOLUTE`-mode statement's contribution to `Account.balance` actually reversible in `StatementImportService.supersede()` and `delete()`, instead of refusing (supersede) or silently mis-reversing (delete) it.

**Architecture:** Persist a live snapshot (`Account.balance` immediately before the SET) on `StatementImport` at confirm time, plus a pointer on `Account` recording which statement most recently performed a SET. A single shared reversal helper checks the pointer to tell "this SET is still live" apart from "something else already overwrote it," and applies the reversal only in the first case. Every other write path to `Account.balance` (manual edits, ordinary transaction deltas, `ADDITIVE`-mode confirms) either updates or deliberately never touches this pointer, per the write-path audit in the spec.

**Tech Stack:** Spring Boot / Java 21, JPA/Hibernate, PostgreSQL, Flyway, JUnit 5 + Mockito + AssertJ.

**Spec:** [docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md](../specs/2026-08-30-absolute-balance-reversal-design.md)

## Global Constraints

- Never trust a replacement statement's stated-but-uncorroborated closing balance as authoritative, at supersede time or anywhere else — same invariant `ClosingBalanceGuard` already enforces on first import (confirmed with Sid 2026-08-30).
- Never backfill `balanceBeforeAbsoluteSet` or `lastAbsoluteSetStatementId` for pre-migration rows — not reconstructible; the legacy/no-snapshot case must warn, never guess.
- A manual `Account.balance` edit (`AccountService.update`) always clears `lastAbsoluteSetStatementId` — automatic balance lineage is intentionally abandoned once a manual edit occurs (explicit product decision, not just a technical consequence).
- `StatementImportService.delete()` stays `void` — surface the legacy/no-snapshot case via `log.warn`, not a response-contract change (it's called from both `StatementImportController` and the unattended `AccountPurgeSweepService`).
- Before adding the Flyway migration, re-run `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5` to confirm `V120` is still unclaimed — this repo has had 3 real migration-version collisions from concurrent sessions.
- Before starting Task 4, check whether [PR #638](https://github.com/finora/finora/pull/638) (branch `Finora/friendly-diffie-a6833d`) has merged into `main` since this plan was written: `git log origin/main --oneline | grep -i "refuses an ABSOLUTE original"`. If it has, `supersede()` will contain a guard clause this plan's Task 4 doesn't currently show — Task 4's steps note exactly what to delete in that case.

---

## Task 1: Schema + entity fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V120__absolute_balance_reversal_tracking.sql`
- Modify: `backend/src/main/java/com/finora/entity/StatementImport.java:259-261` (field), `:332-333` (getter/setter)
- Modify: `backend/src/main/java/com/finora/entity/Account.java:116-117` (field), `:159-160` (getter/setter)
- Test: `backend/src/test/java/com/finora/repository/AbsoluteBalanceReversalFieldsIT.java`

**Interfaces:**
- Produces: `StatementImport.getBalanceBeforeAbsoluteSet()/setBalanceBeforeAbsoluteSet(BigDecimal)`, `Account.getLastAbsoluteSetStatementId()/setLastAbsoluteSetStatementId(UUID)` — both used by every later task.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/repository/AbsoluteBalanceReversalFieldsIT.java`:

```java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres round-trip for the two new columns backing the "absolute balance reversal"
 * design (docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md) -- proves the
 * migration and entity mappings agree before any service code depends on them.
 */
class AbsoluteBalanceReversalFieldsIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;

    @Test
    void statementImport_balanceBeforeAbsoluteSet_roundTripsAndDefaultsToNull() {
        User user = new User();
        user.setEmail("absolute-balance-fields-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Balance Fields IT User");
        UUID userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(account).getId();

        StatementImport withoutSnapshot = new StatementImport();
        withoutSnapshot.setUserId(userId);
        withoutSnapshot.setAccountId(accountId);
        withoutSnapshot.setFileName("no-snapshot.csv");
        withoutSnapshot.setContentHash("no-snapshot-hash-" + UUID.randomUUID());
        UUID noSnapshotId = statementImportRepository.save(withoutSnapshot).getId();
        assertThat(statementImportRepository.findById(noSnapshotId).orElseThrow()
                .getBalanceBeforeAbsoluteSet()).isNull();

        StatementImport withSnapshot = new StatementImport();
        withSnapshot.setUserId(userId);
        withSnapshot.setAccountId(accountId);
        withSnapshot.setFileName("with-snapshot.csv");
        withSnapshot.setContentHash("with-snapshot-hash-" + UUID.randomUUID());
        withSnapshot.setBalanceBeforeAbsoluteSet(new BigDecimal("1234.56"));
        UUID withSnapshotId = statementImportRepository.save(withSnapshot).getId();
        assertThat(statementImportRepository.findById(withSnapshotId).orElseThrow()
                .getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("1234.56");
    }

    @Test
    void account_lastAbsoluteSetStatementId_roundTripsAndDefaultsToNull() {
        User user = new User();
        user.setEmail("absolute-balance-fields-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Balance Fields IT User");
        UUID userId = userRepository.save(user).getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(account).getId();
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isNull();

        UUID pointerTarget = UUID.randomUUID();
        Account toUpdate = accountRepository.findById(accountId).orElseThrow();
        toUpdate.setLastAbsoluteSetStatementId(pointerTarget);
        accountRepository.save(toUpdate);
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isEqualTo(pointerTarget);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceReversalFieldsIT -q`
Expected: FAIL — compile error, `StatementImport` has no `getBalanceBeforeAbsoluteSet`/`setBalanceBeforeAbsoluteSet` and `Account` has no `getLastAbsoluteSetStatementId`/`setLastAbsoluteSetStatementId`.

- [ ] **Step 3: Check for a Flyway version collision, then create the migration**

```bash
git fetch origin
ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Confirm `V120` is not already taken (by `main` or any sibling branch you know is in flight). If it is, use the next free number and update every reference to `V120` in this plan accordingly.

Create `backend/src/main/resources/db/migration/V120__absolute_balance_reversal_tracking.sql`:

```sql
-- Design: docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md
-- Makes an ABSOLUTE-mode statement's contribution to Account.balance actually reversible.
-- StatementImportService.supersede/delete previously could not safely reverse a statement that
-- had SET Account.balance directly (as opposed to moving it by a delta) -- nothing about that
-- SET is reconstructible after the fact (see BalanceApplicationMode's own doc comment on why
-- recomputing from totalCredits/totalDebits or from opening/closing arithmetic is unsafe).

-- Account.balance immediately before this statement's own confirm overwrote it, captured once at
-- confirm time by ImportService.persistSection's ABSOLUTE branch. NULL for every row that isn't
-- ABSOLUTE mode, and for every ABSOLUTE row confirmed before this migration -- never backfilled,
-- same "never guess, never reconstruct" stance V119 already took for balance_application_mode
-- itself. StatementImportService.reverseAbsoluteContribution is the sole reader.
ALTER TABLE statement_imports ADD COLUMN balance_before_absolute_set NUMERIC(14, 2) NULL;

-- Which statement most recently SET (not added to) this account's balance -- NULL means either
-- nothing ever has, or a manual balance edit (AccountService.update) invalidated the previous
-- claim. Lets a later reversal tell "this statement's SET is still the account's live anchor"
-- apart from "something else already overwrote it" (a later-period ABSOLUTE statement, or a
-- manual edit) without reconstructing history -- see the design spec's "live anchor" section.
-- No FOREIGN KEY, same reasoning as statement_imports.superseded_by (V119): the referenced row
-- can be soft-deleted later, and a hard FK's ON DELETE behavior doesn't fit a soft-delete model.
ALTER TABLE accounts ADD COLUMN last_absolute_set_statement_id UUID NULL;
```

- [ ] **Step 4: Add the field to `StatementImport.java`**

In `backend/src/main/java/com/finora/entity/StatementImport.java`, immediately after the existing `balanceApplicationMode` field (currently lines 259-261):

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "balance_application_mode", nullable = false, length = 20)
    private BalanceApplicationMode balanceApplicationMode = BalanceApplicationMode.UNKNOWN_LEGACY;

    /** {@code Account.balance} the instant before this statement's own confirm overwrote it (only
     *  when {@link #balanceApplicationMode} is {@code ABSOLUTE} -- null otherwise, including for
     *  every row confirmed before this field existed). This is the only safe source for reversing
     *  that SET later: {@code effectiveOpeningBalance} at confirm time comes from the account's
     *  PRIOR statement's stated closing balance (see {@code OpeningBalanceCarryForward}), not from
     *  live {@code Account.balance}, so it can diverge from what the balance actually was if a
     *  manual edit or transaction landed in between -- the arithmetic identity {@code opening +
     *  net == closing} does not reconstruct this value after the fact. Read by {@code
     *  StatementImportService.reverseAbsoluteContribution}, together with {@link
     *  Account#getLastAbsoluteSetStatementId()}, which tells whether this SET is still the
     *  account's live anchor or has already been overwritten by something else. */
    @Column(name = "balance_before_absolute_set")
    private java.math.BigDecimal balanceBeforeAbsoluteSet;
```

Then, immediately after the existing `getBalanceApplicationMode`/`setBalanceApplicationMode` accessors (currently lines 332-333):

```java
    public BalanceApplicationMode getBalanceApplicationMode() { return balanceApplicationMode; }
    public void setBalanceApplicationMode(BalanceApplicationMode balanceApplicationMode) { this.balanceApplicationMode = balanceApplicationMode; }
    public java.math.BigDecimal getBalanceBeforeAbsoluteSet() { return balanceBeforeAbsoluteSet; }
    public void setBalanceBeforeAbsoluteSet(java.math.BigDecimal balanceBeforeAbsoluteSet) { this.balanceBeforeAbsoluteSet = balanceBeforeAbsoluteSet; }
```

- [ ] **Step 5: Add the field to `Account.java`**

In `backend/src/main/java/com/finora/entity/Account.java`, immediately after the existing `ifscCode` field (currently lines 116-117):

```java
    @Column(name = "ifsc_code")
    private String ifscCode;

    /** Which {@code StatementImport} most recently SET (not added to) this account's balance --
     *  null means either nothing ever has, or a manual balance edit ({@code AccountService
     *  .update}) invalidated the previous claim. See the "absolute balance reversal" design spec's
     *  "live anchor" section: this is what lets a later reversal tell "that statement's SET is
     *  still live under the current balance" apart from "something else has already overwritten
     *  it," without reconstructing history. Written by {@code ImportService.persistSection}'s
     *  ABSOLUTE branch, {@code AccountService.update} (cleared on a manual edit), and {@code
     *  StatementImportService.reverseAbsoluteContribution} (cleared after a successful reversal). */
    @Column(name = "last_absolute_set_statement_id")
    private UUID lastAbsoluteSetStatementId;
```

Then, immediately after the existing `getIfscCode`/`setIfscCode` accessors (currently lines 159-160, the last two lines before the closing brace):

```java
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public UUID getLastAbsoluteSetStatementId() { return lastAbsoluteSetStatementId; }
    public void setLastAbsoluteSetStatementId(UUID lastAbsoluteSetStatementId) { this.lastAbsoluteSetStatementId = lastAbsoluteSetStatementId; }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceReversalFieldsIT -q`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V120__absolute_balance_reversal_tracking.sql \
        backend/src/main/java/com/finora/entity/StatementImport.java \
        backend/src/main/java/com/finora/entity/Account.java \
        backend/src/test/java/com/finora/repository/AbsoluteBalanceReversalFieldsIT.java
git commit -m "feat(backend): add balanceBeforeAbsoluteSet/lastAbsoluteSetStatementId columns"
```

---

## Task 2: Capture the snapshot and set the pointer at confirm time

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java:1187-1193`
- Test: `backend/src/test/java/com/finora/imports/AbsoluteBalanceSnapshotIT.java`

**Interfaces:**
- Consumes: `StatementImport.setBalanceBeforeAbsoluteSet(BigDecimal)`, `Account.setLastAbsoluteSetStatementId(UUID)` (Task 1).
- Produces: every `ABSOLUTE`-mode confirm now leaves `StatementImport.balanceBeforeAbsoluteSet` populated and `Account.lastAbsoluteSetStatementId` pointing at it — depended on by Tasks 4-6.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/imports/AbsoluteBalanceSnapshotIT.java`:

```java
package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ImportService.persistSection's ABSOLUTE branch must capture Account.balance immediately before
 * the overwrite -- see StatementImport.balanceBeforeAbsoluteSet's own doc comment for why this is
 * the only safe source for reversing the SET later.
 */
class AbsoluteBalanceSnapshotIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    @Test
    void absoluteConfirm_capturesPriorBalanceAndSetsTheAccountPointer() throws Exception {
        User user = new User();
        user.setEmail("absolute-snapshot-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Snapshot IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("500.00"));
        UUID accountId = accountRepository.save(account).getId();

        // Opening 500.00, one 150.00 expense, stated closing 350.00 -- corroborates -> ABSOLUTE.
        var response = importService.confirm(savedUser.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null,
                        List.of(new ConfirmedRow(LocalDate.of(2026, 7, 10), "COFFEE SHOP",
                                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null,
                                false, null, null, false)),
                        accountId, null, new BigDecimal("500.00"), new BigDecimal("350.00"), null,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null));

        StatementImport saved = statementImportRepository.findById(response.statementImportId())
                .orElseThrow();
        assertThat(saved.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(saved.getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("500.00");

        Account afterConfirm = accountRepository.findById(accountId).orElseThrow();
        assertThat(afterConfirm.getBalance()).isEqualByComparingTo("350.00");
        assertThat(afterConfirm.getLastAbsoluteSetStatementId()).isEqualTo(saved.getId());
    }

    @Test
    void additiveConfirm_leavesTheSnapshotNullAndDoesNotTouchThePointer() throws Exception {
        User user = new User();
        user.setEmail("absolute-snapshot-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Snapshot IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("500.00"));
        UUID accountId = accountRepository.save(account).getId();

        // No stated closing balance -> ADDITIVE, not ABSOLUTE.
        var response = importService.confirm(savedUser.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null,
                        List.of(new ConfirmedRow(LocalDate.of(2026, 7, 10), "COFFEE SHOP",
                                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null,
                                false, null, null, false)),
                        accountId, null, new BigDecimal("500.00"), null, null,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null));

        StatementImport saved = statementImportRepository.findById(response.statementImportId())
                .orElseThrow();
        assertThat(saved.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);
        assertThat(saved.getBalanceBeforeAbsoluteSet()).isNull();
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceSnapshotIT -q`
Expected: FAIL — first test's `getBalanceBeforeAbsoluteSet()`/`getLastAbsoluteSetStatementId()` assertions fail (both null/not-set today).

- [ ] **Step 3: Implement**

In `backend/src/main/java/com/finora/imports/ImportService.java`, replace lines 1187-1193:

```java
        boolean closingBalanceIsAuthoritative = balanceDecision.mayOverwriteAccountBalance()
                && isMostRecentStatementForAccount(userId, accountId, maxDate, savedImport.getId());
        if (closingBalanceIsAuthoritative) {
            accountRepository.findById(accountId).ifPresent(account -> {
                account.setBalance(request.statementClosingBalance());
                accountRepository.save(account);
            });
        } else if (!toInsert.isEmpty()) {
```

with:

```java
        boolean closingBalanceIsAuthoritative = balanceDecision.mayOverwriteAccountBalance()
                && isMostRecentStatementForAccount(userId, accountId, maxDate, savedImport.getId());
        if (closingBalanceIsAuthoritative) {
            accountRepository.findById(accountId).ifPresent(account -> {
                // Captured before the overwrite -- the only safe source for reversing this SET
                // later (see StatementImport.balanceBeforeAbsoluteSet's own doc comment). Nothing
                // about this statement's own rows or opening/closing arithmetic can reconstruct it
                // after the fact.
                java.math.BigDecimal priorBalance = account.getBalance();
                account.setBalance(request.statementClosingBalance());
                account.setLastAbsoluteSetStatementId(savedImport.getId());
                accountRepository.save(account);
                savedImport.setBalanceBeforeAbsoluteSet(priorBalance);
            });
        } else if (!toInsert.isEmpty()) {
```

`savedImport` is already the managed, dirty-checked instance this method's later `balanceApplicationMode` write relies on (see that write's own comment a few lines below) — no extra `save()` call needed here either.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceSnapshotIT -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Run the full existing ImportService test suite to check for regressions**

Run: `cd backend && ./mvnw test -Dtest="com.finora.imports.**" -q`
Expected: PASS, no regressions.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/AbsoluteBalanceSnapshotIT.java
git commit -m "feat(backend): persistSection captures the pre-SET balance and sets the account's absolute-set pointer"
```

---

## Task 3: Manual balance edits clear the pointer

**Files:**
- Modify: `backend/src/main/java/com/finora/accounts/AccountService.java:146`
- Test: `backend/src/test/java/com/finora/accounts/AccountServiceTest.java` (create if it doesn't exist — check first)

**Interfaces:**
- Consumes: `Account.setLastAbsoluteSetStatementId(UUID)` (Task 1).

- [ ] **Step 1: Check whether an existing unit test file covers `AccountService.update`**

Run: `find backend/src/test -iname "AccountServiceTest.java"`

If it exists, read it fully first to match its existing mocking/fixture conventions before adding a test. If it doesn't exist, create it following the plain-Mockito style used by `StatementImportServiceSupersedeTest`/`StatementImportServiceDeleteTest` (mock every repository/collaborator constructor argument, no Spring context).

- [ ] **Step 2: Write the failing test**

Add to `AccountServiceTest.java` (creating the file with a minimal fixture if it doesn't exist — mock `AccountRepository`, `BankManagementService`, `AuditService`, and whatever else `AccountService`'s constructor requires; stub `getOwned`'s underlying `accountRepository.findById`):

```java
@Test
void update_withANewBalance_clearsTheAbsoluteSetPointer() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID actingAdminId = userId;
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", accountId);
    account.setUserId(userId);
    account.setName("Savings");
    account.setAccountType(Account.Type.SAVINGS);
    account.setBalance(new BigDecimal("100.00"));
    account.setLastAbsoluteSetStatementId(UUID.randomUUID());
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(bankManagementService.resolve(any())).thenReturn(new BankRegistry.Bank("OTHER", "Other", null, null));

    service.update(userId, accountId,
            new AccountDto.CreateRequest("Savings", "SAVINGS", new BigDecimal("999.00"),
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null),
            actingAdminId);

    assertThat(account.getLastAbsoluteSetStatementId()).isNull();
}

@Test
void update_withNoBalanceInRequest_leavesTheAbsoluteSetPointerUntouched() {
    UUID userId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID actingAdminId = userId;
    UUID existingPointer = UUID.randomUUID();
    Account account = new Account();
    ReflectionTestUtils.setField(account, "id", accountId);
    account.setUserId(userId);
    account.setName("Savings");
    account.setAccountType(Account.Type.SAVINGS);
    account.setBalance(new BigDecimal("100.00"));
    account.setLastAbsoluteSetStatementId(existingPointer);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    service.update(userId, accountId,
            new AccountDto.CreateRequest("Savings Renamed", "SAVINGS", null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null),
            actingAdminId);

    assertThat(account.getLastAbsoluteSetStatementId()).isEqualTo(existingPointer);
}
```

(Adjust the `AccountDto.CreateRequest` constructor call's parameter list to match its actual record definition — read `backend/src/main/java/com/finora/accounts/AccountDto.java` first if the field order/count above doesn't compile.)

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountServiceTest#update_withANewBalance_clearsTheAbsoluteSetPointer -q`
Expected: FAIL — pointer is still set (nothing clears it today).

- [ ] **Step 4: Implement**

In `backend/src/main/java/com/finora/accounts/AccountService.java`, replace line 146:

```java
        if (req.balance() != null) a.setBalance(req.balance());
```

with:

```java
        if (req.balance() != null) {
            a.setBalance(req.balance());
            // A manual balance edit is a fresh, fully-trusted baseline -- any statement's claim to
            // being this account's live absolute-SET anchor is invalidated by it, the same way a
            // later ABSOLUTE-mode statement confirm would invalidate an earlier one. See the
            // "absolute balance reversal" design spec's Case D / product-decision note: automatic
            // balance lineage is intentionally abandoned once a manual edit occurs.
            a.setLastAbsoluteSetStatementId(null);
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountServiceTest -q`
Expected: PASS.

- [ ] **Step 6: Run the full existing AccountService/AccountController test suites to check for regressions**

Run: `cd backend && ./mvnw test -Dtest="com.finora.accounts.**" -q`
Expected: PASS, no regressions.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/accounts/AccountService.java \
        backend/src/test/java/com/finora/accounts/AccountServiceTest.java
git commit -m "fix(backend): manual account balance edits clear the absolute-set pointer"
```

---

## Task 4: The reversal primitive, wired into `supersede()`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/StatementImportService.java` (add imports/logger near the top, add the helper near `getOwned` at line 408-411, rewire the `supersede()` switch at lines 471-502)
- Modify: `backend/src/test/java/com/finora/service/StatementImportServiceSupersedeTest.java`

**Interfaces:**
- Consumes: `StatementImport.getBalanceBeforeAbsoluteSet()`, `Account.getLastAbsoluteSetStatementId()/setLastAbsoluteSetStatementId(UUID)` (Task 1).
- Produces: `private enum ReversalOutcome { REVERSED, MOOT, NO_SNAPSHOT }`; `private ReversalOutcome reverseAbsoluteContribution(StatementImport original, Account account)` — also consumed by Task 5 (`delete()`).

- [ ] **Step 1: If PR #638 has already merged into `main`, note its guard clause for removal in Step 4**

Run: `git log origin/main --oneline | grep -i "refuses an ABSOLUTE original"`

If this prints a commit, `StatementImportService.java`'s `supersede()` doc comment and body currently contain the guard clause shown in the spec's "Why the obvious approaches don't work" section (a `<p><b>ABSOLUTE original requires ABSOLUTE replacement.</b>...` doc paragraph, and an `if (original.getBalanceApplicationMode() == ABSOLUTE && replacement.getBalanceApplicationMode() != ABSOLUTE) throw ...` block right after the "already superseded" checks). Delete both as part of Step 4 below. If this prints nothing (the situation as of this plan's writing), there is no guard clause in the file yet — skip straight to Step 4's switch rewrite.

- [ ] **Step 2: Write the failing tests**

In `backend/src/test/java/com/finora/service/StatementImportServiceSupersedeTest.java`, replace the existing `absolute_doesNotReverseTheBalance` test (lines 155-170) with:

```java
    @Test
    void absolute_reversesToThePreSetBalance_whenStillTheLiveAnchor() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        old.setClosingBalance(new BigDecimal("9500.00"));
        old.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ADDITIVE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());
        // Replacement's own ADDITIVE confirm already added its -300.00 net delta on top of old's
        // SET (9500.00 - 300.00 = 9200.00) before supersede() is ever called -- this is the
        // balance supersede() must correct, not the 9500.00 old's own SET produced.
        Account account = account(new BigDecimal("9200.00"));
        account.setLastAbsoluteSetStatementId(oldId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        // 9200.00 - (9500.00 - 10000.00) = 9200.00 + 500.00 = 9700.00, i.e. old's pre-SET baseline
        // (10000.00) plus replacement's real net delta (-300.00).
        assertThat(account.getBalance()).isEqualByComparingTo("9700.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isNull();
        assertThat(result.balanceReversed()).isTrue();
        assertThat(result.warning()).isNull();
    }

    @Test
    void absolute_doesNothing_whenReplacementItselfWasAlreadyTheLiveAnchor() {
        // Replacement's own confirm was ALSO ABSOLUTE -- its own confirm already moved the pointer
        // to itself before supersede() runs, so old's SET has already been fully overwritten.
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        old.setClosingBalance(new BigDecimal("9500.00"));
        old.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());
        Account account = account(new BigDecimal("9700.00"));
        account.setLastAbsoluteSetStatementId(newId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(account.getBalance()).isEqualByComparingTo("9700.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isEqualTo(newId);
        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNull();
    }

    @Test
    void absolute_doesNothing_andWarns_whenTheOriginalPredatesTheSnapshotField() {
        // BalanceApplicationMode says ABSOLUTE, but balanceBeforeAbsoluteSet is null -- a row
        // confirmed before this fix shipped. Never guess; same stance as UNKNOWN_LEGACY.
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        old.setClosingBalance(new BigDecimal("9500.00"));
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ADDITIVE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());
        Account account = account(new BigDecimal("9200.00"));
        account.setLastAbsoluteSetStatementId(oldId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(account.getBalance()).isEqualByComparingTo("9200.00");
        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNotNull();
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=StatementImportServiceSupersedeTest -q`
Expected: FAIL — `StatementImport` has no `setBalanceBeforeAbsoluteSet` mismatch is already fixed by Task 1, but the `ABSOLUTE` case in `supersede()` is still a no-op, so `absolute_reversesToThePreSetBalance_whenStillTheLiveAnchor` fails on the balance assertion (stays `9200.00`, not `9700.00`), and `absolute_doesNothing_andWarns_whenTheOriginalPredatesTheSnapshotField` fails on `result.warning()` being null.

- [ ] **Step 4: Implement**

In `backend/src/main/java/com/finora/service/StatementImportService.java`, add the SLF4J imports near the top of the file, immediately after the existing `import java.util.stream.Collectors;` line:

```java
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

(Merge this alphabetically/logically with the existing import block rather than literally duplicating `import java.util.stream.Collectors;` — the point is `org.slf4j.Logger`/`org.slf4j.LoggerFactory` need to be added.)

Add the logger field immediately after the class declaration, alongside the existing repository/service fields:

```java
public class StatementImportService {

    private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);

    private final StatementImportRepository statementImportRepository;
```

Add the enum and helper method immediately after `getOwned` (currently lines 408-411):

```java
    private StatementImport getOwned(UUID userId, UUID statementImportId) {
        return OwnershipGuard.requireOwned(statementImportRepository.findById(statementImportId),
                StatementImport::getUserId, userId, "Statement import");
    }

    private enum ReversalOutcome { REVERSED, MOOT, NO_SNAPSHOT }

    /**
     * Reverses an ABSOLUTE-mode statement's contribution to {@code Account.balance} -- the SET
     * {@code ImportService.persistSection} performed at this statement's own confirm time. Shared
     * by {@code supersede} and {@code delete}, the only two callers that ever need to undo one.
     *
     * <p>A SET is only safely reversible while it is still the account's live anchor: {@link
     * Account#getLastAbsoluteSetStatementId()} tells whether some OTHER SET (a later-period
     * ABSOLUTE statement, or a manual {@code AccountService.update} balance edit) has already
     * overwritten it, in which case {@code original}'s contribution is already fully gone and
     * there is nothing to reverse -- correct, not a gap. See the "absolute balance reversal"
     * design spec's "live anchor" section.
     *
     * <p>{@code original.getBalanceBeforeAbsoluteSet()} is null for any row confirmed before that
     * field existed -- {@code BalanceApplicationMode} says ABSOLUTE, but nothing captured what the
     * balance was before the SET. Guessing risks the exact corruption this exists to prevent, so
     * this is treated the same conservative way {@code UNKNOWN_LEGACY} already is: no reversal,
     * caller surfaces a warning instead.
     */
    private ReversalOutcome reverseAbsoluteContribution(StatementImport original, Account account) {
        if (original.getBalanceBeforeAbsoluteSet() == null) {
            return ReversalOutcome.NO_SNAPSHOT;
        }
        if (!original.getId().equals(account.getLastAbsoluteSetStatementId())) {
            return ReversalOutcome.MOOT;
        }
        BigDecimal delta = original.getBalanceBeforeAbsoluteSet().subtract(original.getClosingBalance());
        if (delta.signum() != 0) {
            account.setBalance(account.getBalance().add(delta));
        }
        account.setLastAbsoluteSetStatementId(null);
        accountRepository.save(account);
        return ReversalOutcome.REVERSED;
    }
```

If Step 1 found PR #638 already merged, delete its guard-clause doc paragraph and `if` block now (the block immediately following the "already superseded" validation checks, right before the "Only OK-status rows" comment).

Replace the `switch` block in `supersede()` (currently lines 471-502):

```java
        boolean balanceReversed = false;
        String warning = null;
        switch (original.getBalanceApplicationMode()) {
            case ADDITIVE -> {
                // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                // was already reversed once, at the original statement's own confirm time
                // (ImportService.summarise's BH-003 correction) -- summing it again here would
                // move the balance a second time for a row that currently contributes nothing.
                // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                List<Transaction> stillContributing = originalTransactions.stream()
                        .filter(t -> t.getIsDuplicateOf() == null)
                        .toList();
                if (!stillContributing.isEmpty()) {
                    Optional<Account> account = accountRepository.findById(original.getAccountId());
                    if (account.isPresent()) {
                        BigDecimal reversal = AccountBalanceConvention
                                .netDelta(account.get().getAccountType(), stillContributing).negate();
                        if (reversal.signum() != 0) {
                            account.get().setBalance(account.get().getBalance().add(reversal));
                            accountRepository.save(account.get());
                            balanceReversed = true;
                        }
                    }
                }
            }
            case UNKNOWN_LEGACY -> warning = "This statement predates balance-application tracking, so its "
                    + "contribution to the account balance could not be automatically reversed. An "
                    + "administrator should verify this account's balance.";
            case ABSOLUTE, NONE -> { /* no reversal -- see BalanceApplicationMode's own doc comment */ }
        }
```

with:

```java
        boolean balanceReversed = false;
        String warning = null;
        switch (original.getBalanceApplicationMode()) {
            case ADDITIVE -> {
                // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                // was already reversed once, at the original statement's own confirm time
                // (ImportService.summarise's BH-003 correction) -- summing it again here would
                // move the balance a second time for a row that currently contributes nothing.
                // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                List<Transaction> stillContributing = originalTransactions.stream()
                        .filter(t -> t.getIsDuplicateOf() == null)
                        .toList();
                if (!stillContributing.isEmpty()) {
                    Optional<Account> account = accountRepository.findById(original.getAccountId());
                    if (account.isPresent()) {
                        BigDecimal reversal = AccountBalanceConvention
                                .netDelta(account.get().getAccountType(), stillContributing).negate();
                        if (reversal.signum() != 0) {
                            account.get().setBalance(account.get().getBalance().add(reversal));
                            accountRepository.save(account.get());
                            balanceReversed = true;
                        }
                    }
                }
            }
            case ABSOLUTE -> {
                Optional<Account> account = accountRepository.findById(original.getAccountId());
                if (account.isPresent()) {
                    ReversalOutcome outcome = reverseAbsoluteContribution(original, account.get());
                    balanceReversed = outcome == ReversalOutcome.REVERSED;
                    if (outcome == ReversalOutcome.NO_SNAPSHOT) {
                        warning = "This statement predates automatic balance-reversal tracking, so its "
                                + "contribution to the account balance could not be automatically reversed. "
                                + "An administrator should verify this account's balance.";
                    }
                }
            }
            case UNKNOWN_LEGACY -> warning = "This statement predates balance-application tracking, so its "
                    + "contribution to the account balance could not be automatically reversed. An "
                    + "administrator should verify this account's balance.";
            case NONE -> { /* no reversal -- nothing was ever moved, see BalanceApplicationMode's own doc comment */ }
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=StatementImportServiceSupersedeTest -q`
Expected: PASS (all tests, including the 3 new/replaced ones and every existing one — `ADDITIVE`/`NONE`/`UNKNOWN_LEGACY`/validation tests are untouched by this change).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/StatementImportService.java \
        backend/src/test/java/com/finora/service/StatementImportServiceSupersedeTest.java
git commit -m "fix(backend): supersede() correctly reverses an ABSOLUTE original's balance contribution"
```

---

## Task 5: Wire `delete()` to the same reversal primitive

**Files:**
- Modify: `backend/src/main/java/com/finora/service/StatementImportService.java:350-386`
- Modify: `backend/src/test/java/com/finora/service/StatementImportServiceDeleteTest.java`

**Interfaces:**
- Consumes: `reverseAbsoluteContribution`, `ReversalOutcome` (Task 4).

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/service/StatementImportServiceDeleteTest.java`:

```java
    @Test
    void delete_reversesAnAbsoluteStatement_toItsPreSetBalance_whenStillTheLiveAnchor() {
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isNull();
    }

    @Test
    void delete_doesNotDoubleReverseAnAbsoluteStatement_whenALaterSetAlreadyOverwroteIt() {
        UUID accountId = UUID.randomUUID();
        UUID laterStatementId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("10000.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("12000.00"));
        account.setLastAbsoluteSetStatementId(laterStatementId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        assertThat(account.getBalance()).isEqualByComparingTo("12000.00");
        assertThat(account.getLastAbsoluteSetStatementId()).isEqualTo(laterStatementId);
    }

    @Test
    void delete_doesNotReverse_andLogsAWarning_whenTheStatementPredatesTheSnapshotField() {
        // BalanceApplicationMode says ABSOLUTE, but balanceBeforeAbsoluteSet is null -- a row
        // confirmed before this fix shipped. Never guess; same stance as UNKNOWN_LEGACY, and the
        // same case supersede() handles via its NO_SNAPSHOT outcome (Task 4's equivalent test).
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("9500.00"));
        // balanceBeforeAbsoluteSet deliberately left null.
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(transaction(UUID.randomUUID())));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        assertThat(account.getBalance()).isEqualByComparingTo("9500.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void delete_reversesAnAbsoluteStatement_evenWithZeroTransactions() {
        // ABSOLUTE mode can fire with zero rows (opening == closing trivially corroborates) -- the
        // reversal must not be gated on whether this statement had any transactions, unlike the
        // ADDITIVE/NONE row-based reversal below it.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        statementImport.setBalanceApplicationMode(StatementImport.BalanceApplicationMode.ABSOLUTE);
        statementImport.setClosingBalance(new BigDecimal("100.00"));
        statementImport.setBalanceBeforeAbsoluteSet(new BigDecimal("500.00"));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of());

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("100.00"));
        account.setLastAbsoluteSetStatementId(statementImportId);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=StatementImportServiceDeleteTest -q`
Expected: FAIL — all four new tests fail (delete's current reversal ignores `BalanceApplicationMode` entirely and always uses the row-`netDelta` approach, which doesn't match these expectations; the legacy/no-snapshot test fails because a balance change happens today when it shouldn't; and the zero-transaction case does nothing at all today since the whole reversal block is gated on `!toRemove.isEmpty()`).

- [ ] **Step 3: Implement**

In `backend/src/main/java/com/finora/service/StatementImportService.java`, replace the reversal block in `delete()` (currently lines 361-386):

```java
        if (!toRemove.isEmpty()) {
            accountRepository.findById(statementImport.getAccountId()).ifPresent(account -> {
                // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                // was already reversed once, at the original statement's own confirm time
                // (ImportService.summarise's BH-003 correction) -- summing it again here would
                // move the balance a second time for a row that currently contributes nothing.
                // Also excludes SUPERSEDED (#631 missed this second trigger of the same bug):
                // StatementImportService.supersede() marks an ADDITIVE-mode original's rows
                // SUPERSEDED and reverses their contribution in that same call, so a SUPERSEDED
                // row's current net contribution is zero too -- deleting an already-superseded
                // statement must not reverse it a second time here.
                // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                List<Transaction> stillContributing = toRemove.stream()
                        .filter(t -> t.getIsDuplicateOf() == null
                                && t.getReconciliationStatus() != Transaction.ReconciliationStatus.SUPERSEDED)
                        .toList();
                BigDecimal reversal = AccountBalanceConvention
                        .netDelta(account.getAccountType(), stillContributing).negate();
                if (reversal.signum() != 0) {
                    account.setBalance(account.getBalance().add(reversal));
                    accountRepository.save(account);
                }
            });
        }
```

with:

```java
        // ABSOLUTE-mode rows are reversed separately from ADDITIVE/NONE/UNKNOWN_LEGACY, and
        // unconditionally (not gated on whether this statement had any transactions): an ABSOLUTE
        // confirm's SET can move the balance even for a zero-row statement, if its stated closing
        // balance corroborated against a carried-forward opening figure that differed from live
        // Account.balance at that moment (see OpeningBalanceCarryForward) -- reverseAbsoluteContribution
        // reads the persisted snapshot and live pointer, not this statement's rows, so row count is
        // irrelevant to it. The row-based reversal below it is unchanged: negating a still-live
        // ADDITIVE-mode row's current net effect (or an UNKNOWN_LEGACY row's, unfixed here --
        // deliberately out of scope, see the design spec) is only correct when there are rows to
        // sum, unlike ABSOLUTE's snapshot-based approach.
        if (statementImport.getBalanceApplicationMode() == StatementImport.BalanceApplicationMode.ABSOLUTE
                || !toRemove.isEmpty()) {
            accountRepository.findById(statementImport.getAccountId()).ifPresent(account -> {
                if (statementImport.getBalanceApplicationMode() == StatementImport.BalanceApplicationMode.ABSOLUTE) {
                    ReversalOutcome outcome = reverseAbsoluteContribution(statementImport, account);
                    if (outcome == ReversalOutcome.NO_SNAPSHOT) {
                        log.warn("Cannot reverse ABSOLUTE contribution for statement {}: no pre-SET "
                                + "snapshot (row predates automatic reversal tracking). Balance not "
                                + "adjusted; verify manually if needed.", statementImport.getId());
                    }
                    return;
                }
                if (toRemove.isEmpty()) return;
                // Excludes an already-DUPLICATE-flagged row: its contribution to Account.balance
                // was already reversed once, at the original statement's own confirm time
                // (ImportService.summarise's BH-003 correction) -- summing it again here would
                // move the balance a second time for a row that currently contributes nothing.
                // Also excludes SUPERSEDED (#631 missed this second trigger of the same bug):
                // StatementImportService.supersede() marks an ADDITIVE-mode original's rows
                // SUPERSEDED and reverses their contribution in that same call, so a SUPERSEDED
                // row's current net contribution is zero too -- deleting an already-superseded
                // statement must not reverse it a second time here.
                // TRANSFER/REFUND/REVERSAL/INVESTMENT_TRANSFER rows stay included: those
                // classifications only affect expense/income REPORTING (RefundNetting.reportable),
                // not Account.balance -- the cash genuinely moved, so the balance still reflects it.
                List<Transaction> stillContributing = toRemove.stream()
                        .filter(t -> t.getIsDuplicateOf() == null
                                && t.getReconciliationStatus() != Transaction.ReconciliationStatus.SUPERSEDED)
                        .toList();
                BigDecimal reversal = AccountBalanceConvention
                        .netDelta(account.getAccountType(), stillContributing).negate();
                if (reversal.signum() != 0) {
                    account.setBalance(account.getBalance().add(reversal));
                    accountRepository.save(account);
                }
            });
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=StatementImportServiceDeleteTest -q`
Expected: PASS (all tests, including the 4 new ones — the existing `delete_reversal_excludesATransactionAlreadyFlaggedDuplicate`/`delete_reversal_excludesATransactionAlreadyFlaggedSuperseded` tests use the default `UNKNOWN_LEGACY` mode from `new StatementImport()`, so they still route through the unchanged row-based branch and should pass unmodified).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/StatementImportService.java \
        backend/src/test/java/com/finora/service/StatementImportServiceDeleteTest.java
git commit -m "fix(backend): delete() correctly reverses an ABSOLUTE statement's balance contribution"
```

---

## Task 6: End-to-end integration coverage against real `confirm()`/`supersede()`/`delete()` calls

**Files:**
- Create: `backend/src/test/java/com/finora/imports/AbsoluteBalanceReversalIT.java`
- If it exists on this branch (per Task 4 Step 1's check): Modify or delete `backend/src/test/java/com/finora/imports/SupersedeRefusesMismatchedAbsoluteModeIT.java`

**Interfaces:**
- Consumes: `ImportService.confirm`, `StatementImportService.supersede`, `StatementImportService.delete` — no new interfaces, this task only proves the unit-level guarantees from Tasks 2-5 hold end-to-end against a real database.

- [ ] **Step 1: Handle `SupersedeRefusesMismatchedAbsoluteModeIT` if it exists**

Run: `find backend/src/test -iname "SupersedeRefusesMismatchedAbsoluteModeIT.java"`

If found (i.e. PR #638 merged into `main` before this plan reached Task 6), delete it — it asserts the old refuse-with-400 behavior, which this design replaces with a correct reversal. Its fixture helpers (`fixture`, `statementFile`, `row`, `balanceOf`) are superseded by the ones written fresh in Step 2 below. If not found, there's nothing to remove; proceed to Step 2.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/finora/imports/AbsoluteBalanceReversalIT.java`:

```java
package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.StatementImportDto.SupersedeResult;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the "absolute balance reversal" design
 * (docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md), against a real
 * database and real confirm()/supersede()/delete() calls -- a mocked-repository unit test
 * (StatementImportServiceSupersedeTest/StatementImportServiceDeleteTest) proves the reversal
 * primitive's own logic; this proves the whole pipeline (two real confirms, then a real
 * supersede/delete) produces the numbers the spec's worked examples predict.
 */
class AbsoluteBalanceReversalIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture(String openingBalance) {
        User user = new User();
        user.setEmail("absolute-reversal-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Reversal IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal(openingBalance));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private MockMultipartFile statementFile(String name) {
        return new MockMultipartFile("file", name, "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row(String description, String amount) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private ConfirmedRow incomeRow(LocalDate date, String description, String amount) {
        return new ConfirmedRow(date, description, new BigDecimal(amount),
                "INCOME", "Other", true, "rule", null, false, null, null, false);
    }

    private ConfirmedRow expenseRow(LocalDate date, String description, String amount) {
        return new ConfirmedRow(date, description, new BigDecimal(amount),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    private StatementImport findByFileName(Fixture f, String fileName) {
        return statementImportRepository
                .findAllByOrderByImportedAtDesc(PageRequest.of(0, 50)).stream()
                .filter(si -> si.getUserId().equals(f.user().getId()) && si.getFileName().equals(fileName))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Case A/B: replacement lands ADDITIVE (no closing balance, or an uncorroborated "
            + "one) -- supersede restores original's pre-SET baseline plus replacement's real net delta")
    void supersede_reversesAnAbsoluteOriginal_whenReplacementLandsAdditive() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: states a closing balance that corroborates (10000 - 150 = 9850) -> ABSOLUTE.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Replacement: corrects the row (200.00, not 150.00), states a closing balance (5000.00)
        // that does NOT corroborate against its own row (9850 - 200 = 9650, not 5000) -> ADDITIVE.
        // Its own confirm only ADDS its real -200.00 delta on top of 9850.00.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("5000.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9650.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");
        assertThat(original.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        // 10000.00 (original's pre-SET baseline) - 200.00 (replacement's real net) = 9800.00.
        // NEVER 5000.00 -- replacement's uncorroborated stated figure is never trusted.
        assertThat(balanceOf(f)).isEqualByComparingTo("9800.00");
        assertThat(result.balanceReversed()).isTrue();
        StatementImport originalAfter = statementImportRepository.findById(original.getId()).orElseThrow();
        assertThat(originalAfter.getSupersededBy()).isEqualTo(replacement.getId());
    }

    @Test
    @DisplayName("Case C: replacement itself corroborates and lands ABSOLUTE -- supersede is a "
            + "no-op, the replacement's own stated figure is already authoritative")
    void supersede_isANoOp_whenReplacementItselfLandedAbsolute() throws Exception {
        Fixture f = fixture("100.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: opening 100, one 900.00 expense, states closing 1000.00 -- wait, that doesn't
        // corroborate for an expense (100 - 900 = -800). Use an INCOME row so opening + net ==
        // closing: 100 + 900 = 1000.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null,
                        List.of(incomeRow(LocalDate.of(2026, 7, 10), "SALARY", "900.00")),
                        f.account().getId(), null, new BigDecimal("100.00"), new BigDecimal("1000.00"),
                        null, periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1000.00");

        // Replacement: same period, its own rows net +1100.00 with opening 100.00, and it states a
        // closing balance of 1200.00 that DOES corroborate (100 + 1100 = 1200) -> ABSOLUTE. Its own
        // confirm SETS the balance directly, moving the account's live-anchor pointer to it.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null,
                        List.of(incomeRow(LocalDate.of(2026, 7, 10), "SALARY", "1100.00")),
                        f.account().getId(), null, new BigDecimal("100.00"), new BigDecimal("1200.00"),
                        null, periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1200.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        assertThat(balanceOf(f)).isEqualByComparingTo("1200.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("Case D: a manual balance edit intervenes between original's confirm and "
            + "supersede -- automatic reversal is abandoned, not guessed")
    void supersede_doesNotReverse_afterAManualBalanceEditIntervened() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Manual edit, simulating AccountService.update -- directly on the entity, then saved,
        // exactly what that service does, to avoid depending on AccountController's full request
        // wiring in this test.
        Account manuallyEdited = accountRepository.findById(f.account().getId()).orElseThrow();
        manuallyEdited.setBalance(new BigDecimal("20000.00"));
        manuallyEdited.setLastAbsoluteSetStatementId(null);
        accountRepository.save(manuallyEdited);

        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), null, null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("19800.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        assertThat(balanceOf(f)).isEqualByComparingTo("19800.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("delete() regression: an ABSOLUTE statement's reversal no longer depends on live "
            + "transaction amounts matching what the original confirm's own arithmetic assumed")
    void delete_reversesAnAbsoluteStatement_correctlyEvenAfterATransactionAmountWasEdited() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        StatementImport original = findByFileName(f, "original.csv");

        statementImportService.delete(f.user().getId(), original.getId());

        // The old row-netDelta reversal would have subtracted the (possibly-edited) row's current
        // amount; the new snapshot-based reversal restores the exact pre-SET baseline (10000.00)
        // regardless of what the row's live amount says.
        assertThat(balanceOf(f)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("supersede is a no-op when an intervening statement for a LATER period already "
            + "landed ABSOLUTE, fully overwriting the earlier original's contribution")
    void supersede_isANoOp_whenALaterPeriodStatementAlreadyOverwroteTheBalance() throws Exception {
        Fixture f = fixture("1000.00");
        LocalDate juneStart = LocalDate.of(2026, 6, 1);
        LocalDate juneEnd = LocalDate.of(2026, 6, 30);
        LocalDate julyStart = LocalDate.of(2026, 7, 1);
        LocalDate julyEnd = LocalDate.of(2026, 7, 31);

        // A: June, corroborates (1000 - 100 = 900) -> ABSOLUTE. Pointer -> A.
        importService.confirm(f.user().getId(), statementFile("a.csv"),
                new ConfirmRequest(null, List.of(expenseRow(juneEnd, "GROCERIES", "100.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), new BigDecimal("900.00"),
                        null, juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("900.00");

        // B: July -- a LATER period, unrelated to A. Corroborates (900 - 50 = 850) -> ABSOLUTE.
        // Its own confirm SETS the balance directly, moving the pointer to B and fully overwriting
        // A's earlier SET, independent of anything supersede() does later.
        importService.confirm(f.user().getId(), statementFile("b.csv"),
                new ConfirmRequest(null, List.of(expenseRow(julyEnd, "UTILITIES", "50.00")),
                        f.account().getId(), null, new BigDecimal("900.00"), new BigDecimal("850.00"),
                        null, julyStart, julyEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("850.00");

        // Replacement for A: same period as A (June), states no closing balance -> ADDITIVE. Its
        // own confirm only ADDS its real -130.00 delta on top of whatever balance already exists
        // (850.00, set by B), landing on 720.00.
        importService.confirm(f.user().getId(), statementFile("replacement-a.csv"),
                new ConfirmRequest(null, List.of(expenseRow(juneEnd, "GROCERIES", "130.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), null, null,
                        juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("720.00");

        StatementImport a = findByFileName(f, "a.csv");
        StatementImport replacementA = findByFileName(f, "replacement-a.csv");

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), a.getId(), replacementA.getId());

        // A's contribution was already fully gone once B set the balance -- nothing to reverse.
        assertThat(balanceOf(f)).isEqualByComparingTo("720.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("delete() regression: reversal is correct even when a manual, non-statement "
            + "balance change happened between an earlier statement's carried-forward closing "
            + "balance and this statement's own ABSOLUTE confirm")
    void delete_reversesAnAbsoluteStatement_correctlyDespiteOpeningBalanceCarryForwardDivergence() throws Exception {
        Fixture f = fixture("1000.00");
        LocalDate juneStart = LocalDate.of(2026, 6, 1);
        LocalDate juneEnd = LocalDate.of(2026, 6, 30);
        LocalDate julyStart = LocalDate.of(2026, 7, 1);
        LocalDate julyEnd = LocalDate.of(2026, 7, 31);

        // Prior statement: June, corroborates (1000 + 500 = 1500) -> ABSOLUTE. Its closing balance
        // (1500.00) becomes what OpeningBalanceCarryForward will hand to the next statement as ITS
        // opening balance, if that statement doesn't state a matching one of its own.
        importService.confirm(f.user().getId(), statementFile("prior.csv"),
                new ConfirmRequest(null, List.of(incomeRow(juneEnd, "SALARY", "500.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), new BigDecimal("1500.00"),
                        null, juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1500.00");

        // A manual, non-statement change to the balance (e.g. a manually-entered transaction) --
        // additive-shaped, so it does NOT touch the account's absolute-set pointer, same as
        // TransactionService.adjustAccountBalance never does. Live balance is now 1800.00, but no
        // statement's own arithmetic knows that -- OpeningBalanceCarryForward only ever sees the
        // PRIOR STATEMENT's closing balance (1500.00), not live Account.balance.
        Account afterManualChange = accountRepository.findById(f.account().getId()).orElseThrow();
        afterManualChange.setBalance(new BigDecimal("1800.00"));
        accountRepository.save(afterManualChange);

        // The statement under test: July, states an opening balance that does NOT match the prior
        // statement's closing (forcing OpeningBalanceCarryForward to substitute 1500.00), one
        // -200.00 expense, and a closing balance of 1300.00 that corroborates against the CARRIED-
        // FORWARD opening (1500 - 200 = 1300) -> ABSOLUTE. Its own confirm SETS the balance to
        // 1300.00, discarding the live 1800.00 -- exactly the divergence this design exists for.
        importService.confirm(f.user().getId(), statementFile("statement.csv"),
                new ConfirmRequest(null, List.of(expenseRow(julyEnd, "RENT", "200.00")),
                        f.account().getId(), null, new BigDecimal("999999.99"), new BigDecimal("1300.00"),
                        null, julyStart, julyEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1300.00");

        StatementImport statement = findByFileName(f, "statement.csv");
        assertThat(statement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        // The snapshot captured the true LIVE balance before the SET (1800.00), not the carried-
        // forward figure (1500.00) the confirm's own arithmetic used.
        assertThat(statement.getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("1800.00");

        statementImportService.delete(f.user().getId(), statement.getId());

        // Correct: restores 1800.00. The OLD row-netDelta reversal would have taken CURRENT balance
        // (1300.00) and subtracted this statement's own -200.00 net (i.e. added back 200.00),
        // landing on 1500.00 -- silently losing the 300.00 manual change forever.
        assertThat(balanceOf(f)).isEqualByComparingTo("1800.00");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceReversalIT -q`
Expected: If Tasks 2-5 are already implemented and committed, these should already PASS — this task is primarily an end-to-end regression net, not new production code. If any assertion fails, that's a real discrepancy between the spec's worked examples and the actual implementation from Tasks 2-5; treat it as a bug to fix in this task rather than adjusting the test's expected numbers, unless careful re-derivation shows the test's arithmetic itself was wrong.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AbsoluteBalanceReversalIT -q`
Expected: PASS (6 tests).

- [ ] **Step 5: Run the full backend test suite**

Run: `cd backend && ./mvnw test -q`
Expected: PASS, no regressions anywhere in the suite.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/com/finora/imports/AbsoluteBalanceReversalIT.java
# If Step 1 found and removed SupersedeRefusesMismatchedAbsoluteModeIT.java, stage that removal too:
# git add backend/src/test/java/com/finora/imports/SupersedeRefusesMismatchedAbsoluteModeIT.java
git commit -m "test(backend): end-to-end coverage for ABSOLUTE-mode balance reversal (Cases A-D)"
```
