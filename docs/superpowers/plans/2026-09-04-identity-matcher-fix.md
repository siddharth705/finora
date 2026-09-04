# Identity-Matcher Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse `RelationshipIdentifier.Type.UPI_ID`/`NAME_PATTERN` into one `COUNTERPARTY_KEY`
type, matched by an indexed join against `transactions.counterparty_key` instead of a
query-time substring scan — the first, independently-shippable subsystem of the Household
Ledger / Money Circle initiative.

**Architecture:** `RelationshipIdentifier` keeps `ACCOUNT_LAST4` untouched (a genuinely distinct
signal `CounterpartyIdentity` deliberately excludes). `UPI_ID`/`NAME_PATTERN` collapse into
`COUNTERPARTY_KEY`, stored verbatim (not run through `CategoryRules.normalize`, which would
strip the `vpa:`/`name:` prefix's colon) and matched by set membership against
`transactions.counterparty_key`. `RelationshipService.transactionsFor()` and the
`ReconciliationService` own-account transfer signal both consume the new matcher; `ACCOUNT_LAST4`
keeps its existing substring-scan path unchanged in both.

**Tech Stack:** Spring Boot, Spring Data JPA, JUnit 5 + Mockito (unit), Testcontainers Postgres
(integration).

**Spec:** `docs/superpowers/specs/2026-09-04-household-ledger-money-circle-design.md` §4.

## Global Constraints

- No DB schema change: `relationship_identifiers.identifier_type` is a plain `VARCHAR(20)` with
  no CHECK constraint (V18) — this is an application-only enum/logic change, no migration.
- Zero production `Relationship` rows — **confirmed**: `SELECT count(*) FROM relationships;`
  returned 0. `relationship_identifiers.relationship_id` is `NOT NULL REFERENCES relationships(id)
  ON DELETE CASCADE` (V18), so `relationship_identifiers` is necessarily 0 too — no row there can
  exist without a parent `relationships` row to reference. Nothing in this plan writes a data
  migration, and that's now verified rather than assumed.
- `ACCOUNT_LAST4` matching logic is out of scope for behavior changes in every task below —
  touch only what routes `COUNTERPARTY_KEY`.

---

### Task 1: Collapse the identifier type enum and fix write-time storage

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/RelationshipIdentifier.java`
- Modify: `backend/src/main/java/com/finora/service/RelationshipService.java:240-251` (`saveIdentifiers`)
- Test: `backend/src/test/java/com/finora/service/RelationshipServiceTest.java`

**Interfaces:**
- Produces: `RelationshipIdentifier.Type` — enum values become `COUNTERPARTY_KEY`,
  `ACCOUNT_LAST4` (removes `UPI_ID`, `NAME_PATTERN`). `saveIdentifiers(UUID relationshipId,
  List<RelationshipDto.IdentifierRequest> requests)` — same signature, new storage rule per type.

- [ ] **Step 1: Write the failing test — `COUNTERPARTY_KEY` is stored verbatim, `ACCOUNT_LAST4` stays normalized**

Add to `RelationshipServiceTest.java` (follow the existing constructor/mock-setup pattern already
in this file's `@BeforeEach`):

```java
@Test
void createStoresCounterpartyKeyIdentifierVerbatim() {
    when(relationshipRepository.save(any(Relationship.class))).thenAnswer(inv -> {
        Relationship r = inv.getArgument(0);
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        return r;
    });
    ArgumentCaptor<RelationshipIdentifier> captor = ArgumentCaptor.forClass(RelationshipIdentifier.class);

    relationshipService.create(userId, new RelationshipDto.CreateRequest(
            "Rahul", "FRIEND", null,
            List.of(new RelationshipDto.IdentifierRequest("COUNTERPARTY_KEY", "vpa:rahul"))),
            actingAdminId);

    verify(identifierRepository).save(captor.capture());
    assertThat(captor.getValue().getIdentifierType()).isEqualTo(RelationshipIdentifier.Type.COUNTERPARTY_KEY);
    // Verbatim -- NOT CategoryRules.normalize("vpa:rahul"), which would strip the colon to "vpa rahul".
    assertThat(captor.getValue().getIdentifierValue()).isEqualTo("vpa:rahul");
}

@Test
void createStillNormalizesAccountLast4Identifier() {
    when(relationshipRepository.save(any(Relationship.class))).thenAnswer(inv -> {
        Relationship r = inv.getArgument(0);
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        return r;
    });
    ArgumentCaptor<RelationshipIdentifier> captor = ArgumentCaptor.forClass(RelationshipIdentifier.class);

    relationshipService.create(userId, new RelationshipDto.CreateRequest(
            "My HDFC", "OWN_ACCOUNT", linkedAccountIdFixture(),
            List.of(new RelationshipDto.IdentifierRequest("ACCOUNT_LAST4", "XXXX-1234"))),
            actingAdminId);

    verify(identifierRepository).save(captor.capture());
    assertThat(captor.getValue().getIdentifierType()).isEqualTo(RelationshipIdentifier.Type.ACCOUNT_LAST4);
    assertThat(captor.getValue().getIdentifierValue()).isEqualTo("xxxx 1234"); // CategoryRules.normalize output
}
```

If this test file has no `linkedAccountIdFixture()` helper yet, check the existing
`OWN_ACCOUNT`-relationship tests in this class for how they stub `accountRepository.findById(...)`
and reuse that exact pattern rather than inventing a new one.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest#createStoresCounterpartyKeyIdentifierVerbatim,RelationshipServiceTest#createStillNormalizesAccountLast4Identifier -pl backend`
Expected: FAIL — `RelationshipIdentifier.Type.valueOf("COUNTERPARTY_KEY")` throws
`IllegalArgumentException` (enum value doesn't exist yet), or `identifierValue` comes back
normalized for both cases (current behavior normalizes everything).

- [ ] **Step 3: Collapse the enum**

In `RelationshipIdentifier.java`, replace:

```java
public enum Type { UPI_ID, ACCOUNT_LAST4, NAME_PATTERN }
```

with:

```java
public enum Type { COUNTERPARTY_KEY, ACCOUNT_LAST4 }
```

- [ ] **Step 4: Fix write-time storage in `saveIdentifiers`**

In `RelationshipService.java`, replace the body of `saveIdentifiers` (currently unconditionally
calling `CategoryRules.normalize(idReq.identifierValue())`) with:

```java
private void saveIdentifiers(UUID relationshipId, List<RelationshipDto.IdentifierRequest> requests) {
    for (var idReq : requests) {
        RelationshipIdentifier identifier = new RelationshipIdentifier();
        identifier.setRelationshipId(relationshipId);
        RelationshipIdentifier.Type type = parseIdentifierType(idReq.identifierType());
        identifier.setIdentifierType(type);
        // COUNTERPARTY_KEY is stored verbatim -- it's matched by equality against
        // transactions.counterparty_key (CounterpartyIdentity.keyOf() output, e.g. "vpa:rahul"),
        // and CategoryRules.normalize would strip the ":" the vpa:/name: prefix depends on.
        // ACCOUNT_LAST4 keeps the original normalize-at-write-time behavior -- it's still matched
        // by substring scan against a normalized transaction description, unchanged by this plan.
        identifier.setIdentifierValue(type == RelationshipIdentifier.Type.COUNTERPARTY_KEY
                ? idReq.identifierValue()
                : CategoryRules.normalize(idReq.identifierValue()));
        identifierRepository.save(identifier);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest -pl backend`
Expected: PASS — all existing tests in this file plus the two new ones.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/entity/RelationshipIdentifier.java \
        backend/src/main/java/com/finora/service/RelationshipService.java \
        backend/src/test/java/com/finora/service/RelationshipServiceTest.java
git commit -m "refactor(relationships): collapse UPI_ID/NAME_PATTERN into COUNTERPARTY_KEY"
```

---

### Task 2: Add a repository query for counterparty-key membership

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java`
- Test: `backend/src/test/java/com/finora/repository/TransactionRepositoryIT.java`

**Interfaces:**
- Produces: `List<Transaction> findByUserIdAndAccountIdInAndCounterpartyKeyIn(UUID userId,
  Collection<UUID> accountIds, Collection<String> counterpartyKeys)` — used by Task 3.
  Account-filtered in SQL rather than in memory: this codebase already has the identical pattern
  for other filter combinations (`findByUserIdAndReconciliationStatusInAndAccountIdIn`,
  `findByUserIdAndMerchantIdAndAccountIdIn` — both in this file), so this follows established
  precedent rather than introducing a new one, and avoids fetching every counterparty-key match
  across a user's full account history (including deleted accounts) only to filter it down after
  the fact.

- [ ] **Step 1: Write the failing test**

Add to `TransactionRepositoryIT.java` (reuse the existing `newTransaction` helper already in this
file — extend it or add a sibling that also sets `counterpartyKey`, matching the existing
`setUp()`/helper pattern in this class):

```java
@Test
@Transactional
void findByUserIdAndAccountIdInAndCounterpartyKeyIn_matchesOnlyListedKeysWithinGivenAccounts() {
    Transaction rahul1 = newTransaction(BigDecimal.valueOf(6000), LocalDate.of(2026, 9, 1), "UPI-RAHUL-vpa rahul@oksbi");
    rahul1.setCounterpartyKey("vpa:rahul");
    transactionRepository.save(rahul1);

    Transaction priya = newTransaction(BigDecimal.valueOf(1000), LocalDate.of(2026, 9, 2), "UPI-PRIYA-vpa priya@okhdfc");
    priya.setCounterpartyKey("vpa:priya");
    transactionRepository.save(priya);

    Transaction unrelated = newTransaction(BigDecimal.valueOf(200), LocalDate.of(2026, 9, 3), "SWIGGY ORDER");
    unrelated.setCounterpartyKey(null);
    transactionRepository.save(unrelated);

    List<Transaction> result = transactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn(
            userId, List.of(accountId), List.of("vpa:rahul"));

    assertThat(result).extracting(Transaction::getId).containsExactly(rahul1.getId());
}

@Test
@Transactional
void findByUserIdAndAccountIdInAndCounterpartyKeyIn_excludesMatchOutsideGivenAccounts() {
    Transaction rahul1 = newTransaction(BigDecimal.valueOf(6000), LocalDate.of(2026, 9, 1), "UPI-RAHUL-vpa rahul@oksbi");
    rahul1.setCounterpartyKey("vpa:rahul");
    transactionRepository.save(rahul1);

    // A second, different account owned by the same user -- the accountId list passed to the
    // query deliberately excludes it, standing in for a deleted account being filtered upstream.
    Account otherAccount = new Account();
    otherAccount.setUserId(userId);
    otherAccount.setName("Other Account");
    otherAccount.setAccountType(Account.Type.SAVINGS);
    otherAccount.setBalance(BigDecimal.ZERO);
    otherAccount = accountRepository.save(otherAccount);

    Transaction rahul2 = new Transaction();
    rahul2.setUserId(userId);
    rahul2.setAccountId(otherAccount.getId());
    rahul2.setCategoryId(categoryId);
    rahul2.setTxnDate(LocalDate.of(2026, 9, 2));
    rahul2.setAmount(BigDecimal.valueOf(500));
    rahul2.setTxnType(Transaction.Type.EXPENSE);
    rahul2.setDescription("UPI-RAHUL-vpa rahul@okhdfc");
    rahul2.setSource(Transaction.Source.MANUAL);
    rahul2.setCounterpartyKey("vpa:rahul");
    transactionRepository.save(rahul2);

    List<Transaction> result = transactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn(
            userId, List.of(accountId), List.of("vpa:rahul"));

    assertThat(result).extracting(Transaction::getId).containsExactly(rahul1.getId());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=TransactionRepositoryIT#findByUserIdAndAccountIdInAndCounterpartyKeyIn_matchesOnlyListedKeysWithinGivenAccounts,TransactionRepositoryIT#findByUserIdAndAccountIdInAndCounterpartyKeyIn_excludesMatchOutsideGivenAccounts -pl backend`
Expected: FAIL — compile error, `findByUserIdAndAccountIdInAndCounterpartyKeyIn` doesn't exist yet.

- [ ] **Step 3: Add the derived query method**

In `TransactionRepository.java`, add near `findByUserIdAndReconciliationStatusInAndAccountIdIn`
(same file, same declaration style — a plain Spring Data derived method, no `@Query` needed):

```java
/** Transactions whose counterparty_key (CounterpartyIdentity.keyOf() output) is one of the
 *  given keys, restricted to the given (live) accounts -- backs Relationship's COUNTERPARTY_KEY
 *  identifier matching (see RelationshipService#transactionsFor). Account-filtered in SQL, same
 *  reason findByUserIdAndReconciliationStatusInAndAccountIdIn is: it excludes a soft-deleted
 *  account's rows at the query level rather than fetching them and filtering in memory. */
List<Transaction> findByUserIdAndAccountIdInAndCounterpartyKeyIn(
        UUID userId, Collection<UUID> accountIds, Collection<String> counterpartyKeys);
```

Add `import java.util.Collection;` to the top of the file if not already present (it is, used by
`findByUserIdAndAccountIdIn`'s own signature at line 145 — confirm before adding a duplicate
import).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=TransactionRepositoryIT#findByUserIdAndAccountIdInAndCounterpartyKeyIn_matchesOnlyListedKeysWithinGivenAccounts,TransactionRepositoryIT#findByUserIdAndAccountIdInAndCounterpartyKeyIn_excludesMatchOutsideGivenAccounts -pl backend`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/repository/TransactionRepository.java \
        backend/src/test/java/com/finora/repository/TransactionRepositoryIT.java
git commit -m "feat(transactions): add account-scoped counterparty-key membership query for Relationship matching"
```

---

### Task 3: Rewrite `transactionsFor()` to route `COUNTERPARTY_KEY` through the new query

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RelationshipService.java:193-220` (`transactionsFor`)
- Test: `backend/src/test/java/com/finora/service/RelationshipServiceTest.java`

**Interfaces:**
- Consumes: `TransactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn` (Task 2).
- Produces: `transactionsFor(UUID userId, UUID relationshipId)` — same signature and return type
  (`List<TransactionDto>`), same caller (`AdminUserRelationshipController`), new matching logic
  internally.

- [ ] **Step 1: Write the failing tests**

Four tests: the combined-match case, a negative case (guards against silently falling back to
substring matching on the wrong person), and a deleted-account case (guards against the
live-account filter being dropped by a future refactor).

```java
@Test
void transactionsFor_matchesCounterpartyKeyByEquality_andAccountLast4BySubstring() {
    UUID relationshipId = UUID.randomUUID();
    Relationship relationship = new Relationship();
    ReflectionTestUtils.setField(relationship, "id", relationshipId);
    relationship.setUserId(userId);
    when(relationshipRepository.findById(relationshipId)).thenReturn(Optional.of(relationship));

    RelationshipIdentifier keyIdentifier = new RelationshipIdentifier();
    keyIdentifier.setIdentifierType(RelationshipIdentifier.Type.COUNTERPARTY_KEY);
    keyIdentifier.setIdentifierValue("vpa:rahul");
    RelationshipIdentifier last4Identifier = new RelationshipIdentifier();
    last4Identifier.setIdentifierType(RelationshipIdentifier.Type.ACCOUNT_LAST4);
    last4Identifier.setIdentifierValue("xxxx 1234");
    when(identifierRepository.findByRelationshipId(relationshipId))
            .thenReturn(List.of(keyIdentifier, last4Identifier));

    when(accountRepository.findByUserId(userId)).thenReturn(List.of(accountFixture()));

    Transaction keyMatch = transactionFixture("UPI-RAHUL-vpa rahul@oksbi");
    keyMatch.setCounterpartyKey("vpa:rahul");
    when(transactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn(eq(userId), any(), eq(List.of("vpa:rahul"))))
            .thenReturn(List.of(keyMatch));

    Transaction last4Match = transactionFixture("NEFT TO ACCT XXXX1234");
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any()))
            .thenReturn(List.of(keyMatch, last4Match));

    List<TransactionDto> result = relationshipService.transactionsFor(userId, relationshipId);

    assertThat(result).extracting(TransactionDto::getId)
            .containsExactlyInAnyOrder(keyMatch.getId(), last4Match.getId());
}

@Test
void transactionsFor_returnsNothing_whenCounterpartyKeyDoesNotMatch() {
    // Guards against an accidental fallback to substring matching: a relationship bound to
    // vpa:rahul must never surface a transaction keyed to a different person just because the
    // description happens to contain overlapping text.
    UUID relationshipId = UUID.randomUUID();
    Relationship relationship = new Relationship();
    ReflectionTestUtils.setField(relationship, "id", relationshipId);
    relationship.setUserId(userId);
    when(relationshipRepository.findById(relationshipId)).thenReturn(Optional.of(relationship));

    RelationshipIdentifier keyIdentifier = new RelationshipIdentifier();
    keyIdentifier.setIdentifierType(RelationshipIdentifier.Type.COUNTERPARTY_KEY);
    keyIdentifier.setIdentifierValue("vpa:rahul");
    when(identifierRepository.findByRelationshipId(relationshipId)).thenReturn(List.of(keyIdentifier));

    when(accountRepository.findByUserId(userId)).thenReturn(List.of(accountFixture()));
    // The repository call itself is the boundary being tested: given a key that doesn't match,
    // it returns nothing -- verifying the service does not additionally consult a substring scan.
    when(transactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn(eq(userId), any(), eq(List.of("vpa:rahul"))))
            .thenReturn(List.of());
    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of());

    List<TransactionDto> result = relationshipService.transactionsFor(userId, relationshipId);

    assertThat(result).isEmpty();
    verifyNoMoreInteractions(transactionRepository);
}

@Test
void transactionsFor_excludesTransactionsOnDeletedAccounts() {
    // The live-account filter used to be applied in memory after an unscoped fetch; Task 2 moved
    // it into the repository query itself. This proves the end-to-end behavior survives that
    // move -- a transaction on an account absent from findByUserId (the same "live accounts"
    // signal DashboardService.summarize uses) must never reach the caller.
    UUID relationshipId = UUID.randomUUID();
    Relationship relationship = new Relationship();
    ReflectionTestUtils.setField(relationship, "id", relationshipId);
    relationship.setUserId(userId);
    when(relationshipRepository.findById(relationshipId)).thenReturn(Optional.of(relationship));

    RelationshipIdentifier keyIdentifier = new RelationshipIdentifier();
    keyIdentifier.setIdentifierType(RelationshipIdentifier.Type.COUNTERPARTY_KEY);
    keyIdentifier.setIdentifierValue("vpa:rahul");
    when(identifierRepository.findByRelationshipId(relationshipId)).thenReturn(List.of(keyIdentifier));

    // No live accounts at all -- the deleted-account case in its most direct form: whatever
    // matched, none of it should reach the caller once every account is gone.
    when(accountRepository.findByUserId(userId)).thenReturn(List.of());

    List<TransactionDto> result = relationshipService.transactionsFor(userId, relationshipId);

    assertThat(result).isEmpty();
    verifyNoInteractions(transactionRepository);
}
```

If this test file has no `accountFixture()`/`transactionFixture(String description)` helpers,
check the existing tests in this class that already build `Account`/`Transaction` fixtures and
extract the shared setup into these two helper methods rather than duplicating it inline — other
tests in this file benefit from the same extraction.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest#transactionsFor_matchesCounterpartyKeyByEquality_andAccountLast4BySubstring,RelationshipServiceTest#transactionsFor_returnsNothing_whenCounterpartyKeyDoesNotMatch,RelationshipServiceTest#transactionsFor_excludesTransactionsOnDeletedAccounts -pl backend`
Expected: FAIL — current implementation (before this task's rewrite) never calls
`findByUserIdAndAccountIdInAndCounterpartyKeyIn` at all; also still does a
substring scan the negative test's `verifyNoMoreInteractions` would catch.

- [ ] **Step 3: Rewrite `transactionsFor`**

Replace the body of `transactionsFor` in `RelationshipService.java`:

```java
public List<TransactionDto> transactionsFor(UUID userId, UUID relationshipId) {
    getOwned(userId, relationshipId);
    List<RelationshipIdentifier> identifiers = identifierRepository.findByRelationshipId(relationshipId);
    if (identifiers.isEmpty()) return List.of();

    List<String> counterpartyKeys = identifiers.stream()
            .filter(i -> i.getIdentifierType() == RelationshipIdentifier.Type.COUNTERPARTY_KEY)
            .map(RelationshipIdentifier::getIdentifierValue)
            .filter(v -> v != null && !v.isBlank())
            .toList();
    List<String> accountLast4Patterns = identifiers.stream()
            .filter(i -> i.getIdentifierType() == RelationshipIdentifier.Type.ACCOUNT_LAST4)
            .map(RelationshipIdentifier::getIdentifierValue)
            .filter(v -> v != null && !v.isBlank())
            .toList();
    if (counterpartyKeys.isEmpty() && accountLast4Patterns.isEmpty()) return List.of();

    Map<UUID, String> categoryNames = new HashMap<>();
    categoryRepository.findByUserId(userId).forEach(c -> categoryNames.put(c.getId(), c.getName()));

    // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so findByUserId alone would
    // keep surfacing them here forever, not just during StatementImportService's 7-day grace
    // window.
    List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
            .map(Account::getId).toList();
    if (liveAccountIds.isEmpty()) return List.of();

    Map<UUID, Transaction> matched = new LinkedHashMap<>();

    // COUNTERPARTY_KEY: exact equality against the persisted key, indexed, precise, account-
    // scoped in SQL (Task 2) rather than filtered in memory after an unscoped fetch.
    if (!counterpartyKeys.isEmpty()) {
        for (Transaction t : transactionRepository.findByUserIdAndAccountIdInAndCounterpartyKeyIn(
                userId, liveAccountIds, counterpartyKeys)) {
            matched.put(t.getId(), t);
        }
    }

    // ACCOUNT_LAST4: unchanged substring scan -- CounterpartyIdentity deliberately excludes
    // account-number-shaped tokens, so there's no key to join against for this signal.
    if (!accountLast4Patterns.isEmpty()) {
        for (Transaction t : transactionRepository.findByUserIdAndAccountIdIn(userId, liveAccountIds)) {
            if (t.getDescription() == null || t.getDescription().isBlank()) continue;
            String normalized = CategoryRules.normalize(t.getDescription());
            if (accountLast4Patterns.stream().anyMatch(normalized::contains)) matched.put(t.getId(), t);
        }
    }

    return matched.values().stream()
            .sorted(Comparator.comparing(Transaction::getTxnDate).reversed())
            .map(t -> TransactionDto.from(t, categoryNames.getOrDefault(t.getCategoryId(), "Uncategorized")))
            .toList();
}
```

Add `import java.util.LinkedHashMap;` if not already present in this file (check the existing
import block first).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest -pl backend`
Expected: PASS — including the pre-existing `transactionsFor`-adjacent tests in this file, which
must continue passing unmodified against `ACCOUNT_LAST4`-only fixtures if any exist. If any
pre-existing test in this file constructs a `RelationshipIdentifier` with `Type.UPI_ID` or
`Type.NAME_PATTERN`, update it to `Type.COUNTERPARTY_KEY` (compile error otherwise, from Task 1).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/RelationshipService.java \
        backend/src/test/java/com/finora/service/RelationshipServiceTest.java
git commit -m "refactor(relationships): match COUNTERPARTY_KEY identifiers by counterparty_key equality"
```

---

### Task 4: Restructure the own-account transfer-detection signal for `ReconciliationService`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/RelationshipService.java:253-270ish` (`ownAccountIdentifierValues`)
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java:356-370`
- Test: `backend/src/test/java/com/finora/service/RelationshipServiceTest.java`
- Test: `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java` (check this file
  exists before writing to it; if it's an IT instead, e.g.
  `backend/src/test/java/com/finora/service/ReconciliationServiceIT.java`, use that one)

**Interfaces:**
- Produces: a new type replacing the flat `List<String> ownAccountIdentifierValues(UUID userId)`
  — `record OwnAccountIdentifiers(Set<String> counterpartyKeys, List<String> accountLast4Patterns)`
  `ownAccountIdentifiers(UUID userId)`. This is a breaking rename of the old method — its only
  caller is `ReconciliationService.reconcileForUser` (confirmed by grep — no other caller exists),
  updated in this same task.

- [ ] **Step 1: Write the failing test for the new `RelationshipService` method**

```java
@Test
void ownAccountIdentifiers_splitsCounterpartyKeysFromAccountLast4Patterns() {
    Relationship ownAccount = new Relationship();
    ReflectionTestUtils.setField(ownAccount, "id", UUID.randomUUID());
    ownAccount.setUserId(userId);
    ownAccount.setRelationshipType(Relationship.Type.OWN_ACCOUNT);
    when(relationshipRepository.findByUserIdAndRelationshipType(userId, Relationship.Type.OWN_ACCOUNT))
            .thenReturn(List.of(ownAccount));

    RelationshipIdentifier keyIdentifier = new RelationshipIdentifier();
    keyIdentifier.setIdentifierType(RelationshipIdentifier.Type.COUNTERPARTY_KEY);
    keyIdentifier.setIdentifierValue("vpa:myhdfcaccount");
    RelationshipIdentifier last4Identifier = new RelationshipIdentifier();
    last4Identifier.setIdentifierType(RelationshipIdentifier.Type.ACCOUNT_LAST4);
    last4Identifier.setIdentifierValue("xxxx 5678");
    when(identifierRepository.findByRelationshipIdIn(List.of(ownAccount.getId())))
            .thenReturn(List.of(keyIdentifier, last4Identifier));

    RelationshipService.OwnAccountIdentifiers result = relationshipService.ownAccountIdentifiers(userId);

    assertThat(result.counterpartyKeys()).containsExactly("vpa:myhdfcaccount");
    assertThat(result.accountLast4Patterns()).containsExactly("xxxx 5678");
}
```

Check whether the existing `ownAccountIdentifierValues` implementation calls
`identifierRepository.findByRelationshipIdIn(...)` (bulk) or `findByRelationshipId(...)` per
relationship in a loop — its own doc comment (already read: "fetched once... rather than each
transaction re-querying") implies bulk. Match whichever it actually does; don't assume.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest#ownAccountIdentifiers_splitsCounterpartyKeysFromAccountLast4Patterns -pl backend`
Expected: FAIL — `ownAccountIdentifiers` and `OwnAccountIdentifiers` don't exist yet.

- [ ] **Step 3: Implement the new method**

In `RelationshipService.java`, add the record (top-level nested type on the class) and replace
`ownAccountIdentifierValues`:

```java
public record OwnAccountIdentifiers(java.util.Set<String> counterpartyKeys, List<String> accountLast4Patterns) {}

@Transactional(readOnly = true)
public OwnAccountIdentifiers ownAccountIdentifiers(UUID userId) {
    List<Relationship> ownAccounts = relationshipRepository.findByUserIdAndRelationshipType(userId, Relationship.Type.OWN_ACCOUNT);
    if (ownAccounts.isEmpty()) return new OwnAccountIdentifiers(java.util.Set.of(), List.of());

    List<UUID> ownAccountIds = ownAccounts.stream().map(Relationship::getId).toList();
    List<RelationshipIdentifier> identifiers = identifierRepository.findByRelationshipIdIn(ownAccountIds);

    java.util.Set<String> counterpartyKeys = identifiers.stream()
            .filter(i -> i.getIdentifierType() == RelationshipIdentifier.Type.COUNTERPARTY_KEY)
            .map(RelationshipIdentifier::getIdentifierValue)
            .filter(v -> v != null && !v.isBlank())
            .collect(java.util.stream.Collectors.toSet());
    List<String> accountLast4Patterns = identifiers.stream()
            .filter(i -> i.getIdentifierType() == RelationshipIdentifier.Type.ACCOUNT_LAST4)
            .map(RelationshipIdentifier::getIdentifierValue)
            .filter(v -> v != null && !v.isBlank())
            .toList();
    return new OwnAccountIdentifiers(counterpartyKeys, accountLast4Patterns);
}
```

- [ ] **Step 3a: Migrate the three existing outcome-level tests that stub the old method**

`ReconciliationServiceTest.java` already has three tests exercising this exact signal end-to-end
via the account-last4-style pattern (`ownAccountIdentifierValues(userId)` returning
`List.of("4802")`, matched by substring against descriptions like `"Payment to XX4802"`) —
`reconcileForUser_widensMatchWindowPastFourDays_whenAnOwnAccountRelationshipMatches`,
`reconcileForUser_stillRespectsTenDayOuterWindow_evenWithARelationshipMatch`, and
`reconcileForUser_relationshipMatchAloneCanTriggerPairing_withoutPaymentWordingInDescription`.
All three will fail to compile once `ownAccountIdentifierValues` is renamed. In each, replace:

```java
when(relationshipService.ownAccountIdentifierValues(userId)).thenReturn(List.of("4802"));
```

with:

```java
when(relationshipService.ownAccountIdentifiers(userId))
        .thenReturn(new RelationshipService.OwnAccountIdentifiers(java.util.Set.of(), List.of("4802")));
```

No other line in any of these three tests changes — they continue to prove the `ACCOUNT_LAST4`
path (empty `counterpartyKeys`, non-empty `accountLast4Patterns`) drives the same outcomes it did
before this plan.

- [ ] **Step 3b: Write a new outcome-level test for the `COUNTERPARTY_KEY` path**

None of the three existing tests exercise a match via `counterpartyKey` equality — add one,
mirroring `reconcileForUser_relationshipMatchAloneCanTriggerPairing_withoutPaymentWordingInDescription`'s
shape exactly, so both signals are proven to reach the same reconciliation outcome, not just the
same data structure:

```java
@Test
void reconcileForUser_relationshipMatchViaCounterpartyKeyAloneCanTriggerPairing_withoutPaymentWordingInDescription() {
    UUID savingsAccount = UUID.randomUUID();
    UUID otherAccount = UUID.randomUUID();

    Transaction debit = txn(UUID.randomUUID(), savingsAccount, LocalDate.of(2026, 7, 10),
            new BigDecimal("5000.00"), Transaction.Type.EXPENSE, "UPI-SELF-vpa myhdfcaccount@oksbi", Instant.now());
    debit.setCounterpartyKey("vpa:myhdfcaccount");
    Transaction credit = txn(UUID.randomUUID(), otherAccount, LocalDate.of(2026, 7, 11),
            new BigDecimal("5000.00"), Transaction.Type.INCOME, "UPI CREDIT RECEIVED", Instant.now());

    when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(debit, credit));
    when(relationshipService.ownAccountIdentifiers(userId))
            .thenReturn(new RelationshipService.OwnAccountIdentifiers(java.util.Set.of("vpa:myhdfcaccount"), List.of()));

    reconciliationService.reconcileForUser(userId);

    assertThat(debit.isTransfer()).isTrue();
    assertThat(credit.isTransfer()).isTrue();
}
```

`credit` deliberately carries no `counterpartyKey` and no matching description text — this proves
the match works from either side of the pair reading `aOwnAccountMatch`/`ownAccountMatch.get(b)`,
the same "matches independently of which transaction the identifier belongs to" property the
existing account-last4 tests already establish, now proven for the key-equality path too.

- [ ] **Step 4: Update `ReconciliationService`'s own-account check**

Locate `backend/src/main/java/com/finora/service/ReconciliationService.java:356-370` (the block
already traced in the architecture review — `List<String> ownAccountIdentifiers =
relationshipService.ownAccountIdentifierValues(userId);` followed by the per-candidate
`ownAccountMatch.put(t.getId(), ownAccountIdentifiers.stream().anyMatch(normalizedDescription::contains));`
loop). Replace with:

```java
RelationshipService.OwnAccountIdentifiers ownAccountIdentifiers = relationshipService.ownAccountIdentifiers(userId);
Map<UUID, Boolean> ownAccountMatch = new HashMap<>();
Map<UUID, Boolean> looksLikeSalary = new HashMap<>();
for (Transaction t : candidates) {
    String normalizedDescription = CategoryRules.normalize(t.getDescription());
    boolean keyMatch = t.getCounterpartyKey() != null
            && ownAccountIdentifiers.counterpartyKeys().contains(t.getCounterpartyKey());
    boolean last4Match = ownAccountIdentifiers.accountLast4Patterns().stream().anyMatch(normalizedDescription::contains);
    ownAccountMatch.put(t.getId(), keyMatch || last4Match);
    looksLikeSalary.put(t.getId(), "Salary".equals(CategoryRules.suggestCategory(t.getDescription())));
}
```

Keep every surrounding line in this method exactly as-is — only the `ownAccountIdentifiers`
fetch and the `ownAccountMatch.put(...)` line inside the loop change.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -o test -Dtest=RelationshipServiceTest,ReconciliationServiceTest -pl backend`
(substitute the correct test class name found in Step 1 if it's an IT instead)
Expected: PASS — the new `RelationshipService` unit test (Step 1), the three migrated
`ReconciliationServiceTest` tests (Step 3a), and the new `COUNTERPARTY_KEY`-path outcome test
(Step 3b) all green.

- [ ] **Step 6: Run the full backend unit suite to catch any other caller broken by the rename**

Run: `./mvnw -o test -pl backend`
Expected: BUILD SUCCESS. `ownAccountIdentifierValues` had exactly one caller per the architecture
review's trace (`ReconciliationService`) — this step exists to catch anything that trace missed,
not because more callers are expected.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/service/RelationshipService.java \
        backend/src/main/java/com/finora/service/ReconciliationService.java \
        backend/src/test/java/com/finora/service/RelationshipServiceTest.java
git commit -m "refactor(reconciliation): split own-account signal into counterparty-key and account-last4 matches"
```

---

## Self-Review

**Spec coverage** (against `docs/superpowers/specs/2026-09-04-household-ledger-money-circle-design.md` §4):
enum collapse — Task 1. Verbatim storage for the new type, unchanged normalization for
`ACCOUNT_LAST4` — Task 1. Indexed-join matching for `COUNTERPARTY_KEY`, account-scoped in SQL —
Tasks 2–3. `ACCOUNT_LAST4` matching stays substring-based, unchanged — Tasks 3–4. The one live
consumer (`ReconciliationService`'s own-account signal) updated without behavior regression for
`ACCOUNT_LAST4`, and now proven for `COUNTERPARTY_KEY` too via an outcome-level test, not just a
data-structure test — Task 4. No migration — confirmed and verified zero-row in Global
Constraints, no task contradicts it.

**Placeholder scan:** no TBD/TODO, no "add validation" without code, no test omitted — every step
has real code or a real `./mvnw` command.

**Type consistency:** `OwnAccountIdentifiers` record and its two accessors
(`counterpartyKeys()`, `accountLast4Patterns()`) are named identically in Task 4's Step 1 test,
Step 3 implementation, Step 3a's migrated tests, and Step 3b's new test.
`findByUserIdAndAccountIdInAndCounterpartyKeyIn`'s signature matches between Task 2's declaration
and Task 3's call site across all three of its tests.

**Review feedback addressed** (against the six items raised on this plan): (1) zero-row assumption
confirmed by direct query plus the `relationship_identifiers` FK-cascade argument — Global
Constraints. (2) `CounterpartyIdentity.keyOf()` re-verified against its actual source, worked
examples appended to the spec's §4 — see the spec document, not repeated here. (3) deleted-account
regression test — Task 3, `transactionsFor_excludesTransactionsOnDeletedAccounts`. (4) SQL-level
account filtering — Task 2, following the `findByUserIdAndReconciliationStatusInAndAccountIdIn`
precedent already in this codebase, confirmed by grep rather than assumed to exist. (5) two
outcome-level reconciliation tests, one per path — Task 4, Steps 3a/3b. (6) negative test — Task 3,
`transactionsFor_returnsNothing_whenCounterpartyKeyDoesNotMatch`.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-04-identity-matcher-fix.md`. Two
execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between
   tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution
   with checkpoints.

Which approach?
