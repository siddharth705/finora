package com.finora.service;

import com.finora.dto.StatementImportDto.AccountGroup;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.entity.Account;
import com.finora.imports.ImportService;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.StatementImportDuplicateCount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Financial Intelligence Workspace, Statement Imports module -- "duplicate count per import"
 * (the one genuine gap the module's gap analysis flagged; "Processing Time" and "Import Logs"
 * were descoped as new schema, see StatementImportDto.Summary's own doc comment). Covers both
 * read paths that expose it: getDetail (single statement) and listGroupedByAccount (bulk, via
 * TransactionRepository.countDuplicatesByStatementImportForUser).
 */
class StatementImportServiceSummaryTest {

    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private StatementImportService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        // listGroupedByAccount() resolves each account's bank via this to build its AccountGroup
        // -- stubbed to fall back through to the real static registry, same as AccountServiceTest,
        // so this suite's assertions (which are about duplicate counts, not bank data) don't have
        // to know anything about custom banks.
        BankManagementService bankManagementService = mock(BankManagementService.class);
        when(bankManagementService.resolve(any())).thenAnswer(invocation ->
                com.finora.accounts.AccountDto.BankDto.from(com.finora.util.BankRegistry.get(invocation.getArgument(0))));
        service = new StatementImportService(
                statementImportRepository, accountRepository, mock(CategoryRepository.class),
                transactionRepository, mock(ReconciliationService.class), mock(RecurringService.class),
                mock(ImportService.class), mock(AuditService.class), bankManagementService);
    }

    private StatementImport statement(UUID id, UUID accountId) {
        StatementImport s = new StatementImport();
        ReflectionTestUtils.setField(s, "id", id);
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName("statement.csv");
        return s;
    }

    private Transaction transaction(Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setReconciliationStatus(status);
        return t;
    }

    @Test
    void getDetail_countsOnlyThisStatementsTransactionsFlaggedDuplicate() {
        UUID statementId = UUID.randomUUID();
        StatementImport si = statement(statementId, UUID.randomUUID());
        when(statementImportRepository.findById(statementId)).thenReturn(Optional.of(si));
        when(transactionRepository.findByStatementImportId(statementId)).thenReturn(List.of(
                transaction(Transaction.ReconciliationStatus.DUPLICATE),
                transaction(Transaction.ReconciliationStatus.DUPLICATE),
                transaction(Transaction.ReconciliationStatus.OK),
                transaction(Transaction.ReconciliationStatus.TRANSFER)));

        Summary summary = service.getDetail(userId, statementId);

        assertThat(summary.duplicateCount()).isEqualTo(2);
    }

    @Test
    void getDetail_noDuplicates_isZero_notNull() {
        UUID statementId = UUID.randomUUID();
        StatementImport si = statement(statementId, UUID.randomUUID());
        when(statementImportRepository.findById(statementId)).thenReturn(Optional.of(si));
        when(transactionRepository.findByStatementImportId(statementId)).thenReturn(List.of());

        Summary summary = service.getDetail(userId, statementId);

        assertThat(summary.duplicateCount()).isZero();
    }

    @Test
    void listGroupedByAccount_appliesTheGroupedDuplicateCount_perStatement_notSummedAcrossAccount() {
        UUID accountId = UUID.randomUUID();
        UUID statementAId = UUID.randomUUID();
        UUID statementBId = UUID.randomUUID();

        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setName("HDFC Savings");
        account.setAccountType(Account.Type.SAVINGS);
        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(account));

        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(
                List.of(statement(statementAId, accountId), statement(statementBId, accountId)));

        StatementImportDuplicateCount rowA = mock(StatementImportDuplicateCount.class);
        when(rowA.getStatementImportId()).thenReturn(statementAId);
        when(rowA.getCount()).thenReturn(3L);
        // statementBId deliberately has no row -- getOrDefault(...,0) must cover the "zero
        // duplicates" case, not just leave it unset.
        when(transactionRepository.countDuplicatesByStatementImportForUser(userId, Transaction.ReconciliationStatus.DUPLICATE))
                .thenReturn(List.of(rowA));

        List<AccountGroup> groups = service.listGroupedByAccount(userId);

        assertThat(groups).hasSize(1);
        var statements = groups.get(0).statements();
        assertThat(statements).extracting(Summary::id, Summary::duplicateCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(statementAId, 3),
                        org.assertj.core.groups.Tuple.tuple(statementBId, 0));
    }
}
