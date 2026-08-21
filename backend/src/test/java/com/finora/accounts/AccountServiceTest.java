package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Locks in a real bug found during a full-application review: update() previously called
 * a.setBalance(req.balance()) unconditionally. Setup.tsx's "Rename Account" action sends a
 * partial payload ({name, accountType} only), so req.balance() would be null -- and since
 * `balance` is NOT NULL at the DB level (V1__init_schema.sql), accountRepository.save() would
 * have thrown a constraint violation on every rename. Same reasoning applies to creditLimit/
 * dueDate, which is why all three are asserted here.
 */
class AccountServiceTest {

    private AccountRepository accountRepository;
    private StatementImportRepository statementImportRepository;
    private TransactionRepository transactionRepository;
    private AuditService auditService;
    private AccountService accountService;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID actingAdminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        // Default: no transactions for anyone, unless a specific test overrides this. Mockito
        // returns null (not an empty list) for an unstubbed method returning List<T>, which
        // would NPE inside listForUser's Collectors.toMap(...) -- every test that reaches
        // listForUser needs this stubbed, so it's set here rather than repeated per-test.
        when(transactionRepository.countByAccountForUser(any())).thenReturn(List.of());
        // bankManagementService.resolve(...) is what listForUser/create/update use to attach each
        // account's BankDto -- stubbed to fall back through to the real static registry (the same
        // resolution BankManagementService.resolve() itself does for a non-custom bankId) so
        // existing bank-name assertions in this suite (e.g. "Punjab National Bank") keep working
        // without this test needing to know about custom banks at all.
        BankManagementService bankManagementService = mock(BankManagementService.class);
        when(bankManagementService.resolve(any())).thenAnswer(invocation ->
                AccountDto.BankDto.from(com.finora.util.BankRegistry.get(invocation.getArgument(0))));

        auditService = mock(AuditService.class);
        accountService = new AccountService(accountRepository, statementImportRepository,
                transactionRepository, auditService, bankManagementService);
    }

    private Account existingAccount() {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", accountId);
        a.setUserId(userId);
        a.setName("Punjab National Bank");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(15000));
        a.setCreditLimit(BigDecimal.valueOf(50000));
        a.setDueDate(LocalDate.of(2026, 8, 5));
        a.setBankId("PNB");
        return a;
    }

    @Test
    void update_withOnlyNameAndAccountType_doesNotWipeOutBalanceCreditLimitOrDueDate() {
        // Exactly the payload Setup.tsx's rename action sends.
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(existingAccount()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountDto.CreateRequest renameOnly = new AccountDto.CreateRequest(
                "Salary Account", "SAVINGS", null, null, null, null, null, null, null, null, null);

        AccountDto result = accountService.update(userId, accountId, renameOnly, actingAdminId);

        assertThat(result.name()).isEqualTo("Salary Account");
        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.valueOf(15000));
        assertThat(result.creditLimit()).isEqualByComparingTo(BigDecimal.valueOf(50000));
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2026, 8, 5));
        // bankId isn't part of AccountDto directly -- resolved bank metadata is -- but confirms
        // the identity wasn't reset to OTHER by a rename that never mentioned it.
        assertThat(result.bank().id()).isEqualTo("PNB");
    }

    @Test
    void update_withAnExplicitNewBalance_actuallyUpdatesIt() {
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(existingAccount()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountDto.CreateRequest balanceEdit = new AccountDto.CreateRequest(
                "Punjab National Bank", "SAVINGS", BigDecimal.valueOf(20000), null, null, null, null, null, null, null, null);

        AccountDto result = accountService.update(userId, accountId, balanceEdit, actingAdminId);

        assertThat(result.balance()).isEqualByComparingTo(BigDecimal.valueOf(20000));
    }

    @Test
    void create_withAnUnrecognizedBankId_fallsBackToOtherRatherThanThrowing() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        });

        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "My Wallet", "WALLET", BigDecimal.ZERO, null, null, null, null, null, "NOT_A_REAL_BANK", null, null);

        AccountDto result = accountService.create(userId, req, actingAdminId);

        assertThat(result.bank().id()).isEqualTo("OTHER");
    }

    @Test
    void create_withBranchAndIfsc_persistsBothOnTheAccount() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        });

        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "Salary Account", "SAVINGS", BigDecimal.ZERO, null, null, null, null, null, "PNB",
                "MG Road Branch", "PUNB0123456");

        AccountDto result = accountService.create(userId, req, actingAdminId);

        assertThat(result.branchName()).isEqualTo("MG Road Branch");
        assertThat(result.ifscCode()).isEqualTo("PUNB0123456");
    }

    /**
     * Bug fix regression tests: create() used to call Account.Type.valueOf(req.accountType())
     * directly. A missing or unrecognized accountType threw NullPointerException/
     * IllegalArgumentException, which GlobalExceptionHandler has no specific handler for -- both
     * fell through to its generic Exception handler and came back as an opaque 500 instead of a
     * real 400 explaining what was wrong.
     */
    @Test
    void create_withAnUnrecognizedAccountType_throwsABadRequestApiException() {
        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "My Wallet", "NOT_A_REAL_TYPE", BigDecimal.ZERO, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> accountService.create(userId, req, actingAdminId))
                .isInstanceOf(com.finora.exception.ApiException.class)
                .hasMessageContaining("accountType")
                .extracting(ex -> ((com.finora.exception.ApiException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);

        verifyNoInteractions(accountRepository);
    }

    @Test
    void create_withAMissingAccountType_throwsABadRequestApiException() {
        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "My Wallet", null, BigDecimal.ZERO, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> accountService.create(userId, req, actingAdminId))
                .isInstanceOf(com.finora.exception.ApiException.class)
                .extracting(ex -> ((com.finora.exception.ApiException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);

        verifyNoInteractions(accountRepository);
    }

    @Test
    void listForUser_attachesEachAccountsMostRecentStatementImport() {
        Account acct = existingAccount();
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(acct));

        StatementImportRepository.StatementMetadata older = statementImportMetadata(accountId, Instant.parse("2026-06-01T00:00:00Z"));
        StatementImportRepository.StatementMetadata newer = statementImportMetadata(accountId, Instant.parse("2026-07-01T00:00:00Z"));
        // Returned newest-first, matching the real repository method's contract.
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(newer, older));

        List<AccountDto> result = accountService.listForUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lastImportedAt()).isEqualTo(newer.getImportedAt());
        // Two statements on file for this account -- both older and newer belong to it.
        assertThat(result.get(0).statementsCount()).isEqualTo(2);
    }

    @Test
    void listForUser_withNoStatementImportsAtAll_leavesLastImportedFieldsNull() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existingAccount()));
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of());

        List<AccountDto> result = accountService.listForUser(userId);

        assertThat(result.get(0).lastImportedAt()).isNull();
        assertThat(result.get(0).lastStatementPeriodStart()).isNull();
        assertThat(result.get(0).statementsCount()).isEqualTo(0);
    }

    @Test
    void listForUser_attachesTheAccountsTransactionCount() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existingAccount()));
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of());

        TransactionRepository.AccountTransactionCount countRow = mock(TransactionRepository.AccountTransactionCount.class);
        when(countRow.getAccountId()).thenReturn(accountId);
        when(countRow.getCount()).thenReturn(42L);
        when(transactionRepository.countByAccountForUser(userId)).thenReturn(List.of(countRow));

        List<AccountDto> result = accountService.listForUser(userId);

        assertThat(result.get(0).transactionsCount()).isEqualTo(42L);
    }

    private StatementImportRepository.StatementMetadata statementImportMetadata(UUID accountId, Instant importedAt) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getAccountId()).thenReturn(accountId);
        when(m.getImportedAt()).thenReturn(importedAt);
        return m;
    }

    // Bug fix: create()/update()/delete() each do the account write plus an AuditService.record()
    // call that must commit atomically with it -- AuditService.record() doesn't swallow its own
    // exceptions, so without @Transactional a failed audit write leaves the account mutation
    // already committed while the client still sees a 500. A Mockito unit test can't exercise
    // real transactional rollback (needs a live Spring/DB context), so -- matching
    // BudgetServiceTest.upsert_isTransactional()'s established pattern -- these assert the
    // annotation is actually present.
    @Test
    void create_isTransactional() throws NoSuchMethodException {
        assertThat(AccountService.class.getMethod("create", UUID.class, AccountDto.CreateRequest.class, UUID.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    void update_isTransactional() throws NoSuchMethodException {
        assertThat(AccountService.class.getMethod("update", UUID.class, UUID.class, AccountDto.CreateRequest.class, UUID.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    @Test
    void delete_isTransactional() throws NoSuchMethodException {
        assertThat(AccountService.class.getMethod("delete", UUID.class, UUID.class, UUID.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    // Bug fix: create()/update()/delete() used to record their audit entry with no actingAdminId
    // at all, so an admin acting on a user's account via AdminAccountController (support-assisted
    // account management) was indistinguishable in the audit trail from the user acting on their
    // own account. Same "actorId" convention as RelationshipService/MerchantService/RuleService.
    @Test
    void create_recordsActingAdminIdInAuditMetadata() {
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", accountId);
            return a;
        });
        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "My Wallet", "WALLET", BigDecimal.ZERO, null, null, null, null, null, null, null, null);

        accountService.create(userId, req, actingAdminId);

        verify(auditService).record(eq(userId), eq("ACCOUNT_CREATED"), eq("Account"), eq(accountId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void update_recordsActingAdminIdInAuditMetadata() {
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(existingAccount()));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        AccountDto.CreateRequest req = new AccountDto.CreateRequest(
                "Salary Account", "SAVINGS", null, null, null, null, null, null, null, null, null);

        accountService.update(userId, accountId, req, actingAdminId);

        verify(auditService).record(eq(userId), eq("ACCOUNT_UPDATED"), eq("Account"), eq(accountId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void delete_recordsActingAdminIdInAuditMetadata() {
        when(accountRepository.findById(accountId)).thenReturn(java.util.Optional.of(existingAccount()));

        accountService.delete(userId, accountId, actingAdminId);

        verify(auditService).record(eq(userId), eq("ACCOUNT_DELETED"), eq("Account"), eq(accountId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }
}
