package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * StatementImportService.delete() regression coverage -- two bugs found during the today's-work
 * bug audit, both fixed alongside these tests:
 *
 * 1. Reconciliation pointer cleanup only handled isDuplicateOf/transferPairId, never
 *    refundOfTransactionId, so deleting a statement containing the EXPENSE side of a matched
 *    refund pair left a surviving INCOME row dangling and permanently stuck at
 *    ReconciliationStatus.REFUND (silently excluded from DashboardService's totals forever).
 * 2. recurringService.detectForUser() was never called here at all, unlike every other write
 *    path that changes a user's transaction set (TransactionService.delete/bulkDelete/create/
 *    update) -- see docs/team-message-financial-intelligence-v1-closeout.md.
 */
class StatementImportServiceDeleteTest {

    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private ReconciliationService reconciliationService;
    private RecurringService recurringService;
    private StatementImportService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID statementImportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        reconciliationService = mock(ReconciliationService.class);
        recurringService = mock(RecurringService.class);
        service = new StatementImportService(
                statementImportRepository, accountRepository, mock(CategoryRepository.class),
                transactionRepository, reconciliationService, recurringService,
                mock(ImportService.class), mock(AuditService.class), mock(BankManagementService.class), new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), mock(com.finora.security.crypto.EncryptionService.class), "", ""));

        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));
    }

    private Transaction transaction(UUID id) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", id);
        t.setUserId(userId);
        t.setDescription("Some transaction");
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    @Test
    void delete_clearsRefundPointer_onASurvivingTransactionOutsideTheStatement() {
        UUID expenseInStatementId = UUID.randomUUID();
        Transaction expenseInStatement = transaction(expenseInStatementId);
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(expenseInStatement));

        UUID refundIncomeId = UUID.randomUUID();
        Transaction refundIncome = transaction(refundIncomeId);
        refundIncome.setTxnType(Transaction.Type.INCOME);
        refundIncome.setRefundOfTransactionId(expenseInStatementId);
        refundIncome.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        when(transactionRepository.findByRefundOfTransactionIdIn(List.of(expenseInStatementId)))
                .thenReturn(List.of(refundIncome));

        service.delete(userId, statementImportId);

        assertThat(refundIncome.getRefundOfTransactionId()).isNull();
        assertThat(refundIncome.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void delete_runsRecurringDetection_afterRemovingTheStatementsTransactions() {
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of(transaction(txnId)));

        service.delete(userId, statementImportId);

        verify(recurringService).detectForUser(userId);
        verify(reconciliationService).reconcileForUser(userId);
    }

    @Test
    void delete_withNoTransactions_skipsReconciliationAndRecurringDetection() {
        when(transactionRepository.findByStatementImportId(statementImportId)).thenReturn(List.of());

        service.delete(userId, statementImportId);

        verify(recurringService, never()).detectForUser(any());
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    @Test
    void delete_reversal_excludesATransactionAlreadyFlaggedDuplicate() {
        // A DUPLICATE-flagged row's contribution to Account.balance was already reversed once, at
        // the original statement's own confirm time (ImportService.summarise's BH-003 correction --
        // ReconciliationService always sets isDuplicateOf together with reconciliationStatus
        // DUPLICATE, never leaves a duplicate row at OK). Its CURRENT net contribution is zero, so
        // it must not be summed into the reversal here too -- doing so would move the balance a
        // second time for a row that never really counted. Same fix as StatementImportService's
        // supersede() reversal.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        Transaction realExpense = transaction(UUID.randomUUID());
        realExpense.setAmount(new BigDecimal("500.00"));

        Transaction alreadyDuplicate = transaction(UUID.randomUUID());
        alreadyDuplicate.setAmount(new BigDecimal("300.00"));
        alreadyDuplicate.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        alreadyDuplicate.setIsDuplicateOf(UUID.randomUUID());

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(realExpense, alreadyDuplicate));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        // Reversing only the real 500 expense's contribution -- not 500 + 300.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void delete_reversal_excludesATransactionAlreadyFlaggedSuperseded() {
        // Same bug, a second trigger (#631 only excluded isDuplicateOf, not this):
        // StatementImportService.supersede() marks an ADDITIVE-mode original's rows SUPERSEDED
        // and, in the same call, reverses their contribution to Account.balance -- so a SUPERSEDED
        // row's CURRENT net contribution is zero, exactly like an already-DUPLICATE-flagged row's.
        // Deleting an already-superseded statement must not sum that row into the reversal again.
        UUID accountId = UUID.randomUUID();
        StatementImport statementImport = new StatementImport();
        ReflectionTestUtils.setField(statementImport, "id", statementImportId);
        statementImport.setUserId(userId);
        statementImport.setFileName("statement.csv");
        statementImport.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(statementImport));

        Transaction realExpense = transaction(UUID.randomUUID());
        realExpense.setAmount(new BigDecimal("500.00"));

        Transaction alreadySuperseded = transaction(UUID.randomUUID());
        alreadySuperseded.setAmount(new BigDecimal("300.00"));
        alreadySuperseded.setReconciliationStatus(Transaction.ReconciliationStatus.SUPERSEDED);

        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(realExpense, alreadySuperseded));

        Account account = new Account();
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.delete(userId, statementImportId);

        // Reversing only the real 500 expense's contribution -- not 500 + 300.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }
}
