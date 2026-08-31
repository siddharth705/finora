package com.finora.service;

import com.finora.dto.StatementImportDto.SupersedeResult;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 4 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md: "Import this
 * one as a replacement?" (§0.3/§0.23). {@code StatementImportService.supersede} marks an original
 * statement replaced by a later re-upload of the exact same period, without deleting it -- its
 * transactions stop counting toward Account.balance, coverage, and Insights (§0.6) the same way a
 * TRANSFER-classified transaction already stops counting toward expense totals.
 *
 * <p>The balance-reversal decision is read from {@link StatementImport.BalanceApplicationMode},
 * persisted at the ORIGINAL statement's own confirm time (see {@code ImportService.persistSection}
 * and that field's own doc comment for why this is read rather than recomputed here) -- this is
 * the piece the user's own confirmed design is built around, and the reason every mode gets its own
 * test below rather than one happy path.
 */
class StatementImportServiceSupersedeTest {

    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private ReconciliationService reconciliationService;
    private RecurringService recurringService;
    private AuditService auditService;
    private StatementImportService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID oldId = UUID.randomUUID();
    private final UUID newId = UUID.randomUUID();
    private final LocalDate periodStart = LocalDate.of(2026, 7, 1);
    private final LocalDate periodEnd = LocalDate.of(2026, 7, 31);

    @BeforeEach
    void setUp() {
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        reconciliationService = mock(ReconciliationService.class);
        recurringService = mock(RecurringService.class);
        auditService = mock(AuditService.class);
        service = new StatementImportService(
                statementImportRepository, accountRepository, mock(CategoryRepository.class),
                transactionRepository, reconciliationService, recurringService,
                mock(ImportService.class), auditService, mock(BankManagementService.class),
                new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(),
                        mock(com.finora.security.crypto.EncryptionService.class), "", ""));
    }

    private StatementImport statement(UUID id, StatementImport.BalanceApplicationMode mode) {
        StatementImport s = new StatementImport();
        ReflectionTestUtils.setField(s, "id", id);
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName(id.equals(oldId) ? "original.csv" : "replacement.csv");
        s.setStatementPeriodStart(periodStart);
        s.setStatementPeriodEnd(periodEnd);
        s.setBalanceApplicationMode(mode);
        return s;
    }

    private void stub(StatementImport old, StatementImport replacement) {
        when(statementImportRepository.findById(oldId)).thenReturn(Optional.of(old));
        when(statementImportRepository.findById(newId)).thenReturn(Optional.of(replacement));
    }

    private Transaction transaction(UUID statementImportId, String amount, Transaction.ReconciliationStatus status) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setStatementImportId(statementImportId);
        t.setDescription("Some transaction");
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setReconciliationStatus(status);
        return t;
    }

    private Account account(BigDecimal balance) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", accountId);
        a.setUserId(userId);
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(balance);
        return a;
    }

    // --- balance reversal, one test per mode -----------------------------------------------------

    @Test
    void additive_reversesTheOriginalsNetContribution() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ADDITIVE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ADDITIVE));
        Transaction expense = transaction(oldId, "500.00", Transaction.ReconciliationStatus.OK);
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of(expense));
        Account account = account(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        // SAVINGS is an asset: an EXPENSE lowered the balance by 500, so reversing it raises it back.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
        assertThat(result.balanceReversed()).isTrue();
        assertThat(result.warning()).isNull();
    }

    @Test
    void additive_reversal_excludesATransactionAlreadyFlaggedDuplicate() {
        // A DUPLICATE-flagged row's contribution to Account.balance was already reversed once, at
        // the original statement's own confirm time (ImportService.summarise's BH-003 correction --
        // ReconciliationService always sets isDuplicateOf together with reconciliationStatus
        // DUPLICATE, never leaves a duplicate row at OK). Its CURRENT net contribution is zero, so
        // it must not be summed into the reversal here too -- doing so would move the balance a
        // second time for a row that never really counted.
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ADDITIVE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ADDITIVE));
        Transaction realExpense = transaction(oldId, "500.00", Transaction.ReconciliationStatus.OK);
        Transaction alreadyDuplicate = transaction(oldId, "300.00", Transaction.ReconciliationStatus.DUPLICATE);
        alreadyDuplicate.setIsDuplicateOf(UUID.randomUUID());
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of(realExpense, alreadyDuplicate));
        Account account = account(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        service.supersede(userId, oldId, newId);

        // Reversing only the real 500 expense's contribution -- not 500 + 300.
        assertThat(account.getBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void additive_skipsReversalWhenReplacementIsAbsolute() {
        // Mirror image of rejectsSupersedingWhenOriginalIsAbsoluteButReplacementIsNot, but this
        // direction doesn't need refusing: ABSOLUTE mode does not ADD to Account.balance, it
        // OVERWRITES it with replacement's own stated closing balance (ImportService.persistSection)
        // -- discarding original's still-unreversed ADDITIVE contribution along with everything else
        // that predated it. The overwrite already leaves the balance correct on its own, so there's
        // simply nothing left here to reverse; unlike the ABSOLUTE-original case, there's no
        // unresolvable double-count to refuse against.
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ADDITIVE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId))
                .thenReturn(List.of(transaction(oldId, "500.00", Transaction.ReconciliationStatus.OK)));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNull();
        verify(accountRepository, never()).save(any());
        verify(statementImportRepository).save(old);
    }

    @Test
    void absolute_doesNotReverseTheBalance() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId))
                .thenReturn(List.of(transaction(oldId, "500.00", Transaction.ReconciliationStatus.OK)));
        Account account = account(new BigDecimal("9500.00"));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(account.getBalance()).isEqualByComparingTo("9500.00");
        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNull();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void rejectsSupersedingWhenOriginalIsAbsoluteButReplacementIsNot() {
        // If the replacement's own confirm did not overwrite the balance (its own closing balance
        // was missing, unstated, or did not corroborate against its own rows), then original's
        // ABSOLUTE contribution is still sitting underneath whatever replacement's confirm did --
        // additively or not at all. Superseding without reversing would leave the two double-
        // counted. Refusing is the safe direction, same as UNKNOWN_LEGACY's own warning-instead-of-
        // guessing choice above.
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ADDITIVE));

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);

        verify(transactionRepository, never()).findByStatementImportId(any());
        verify(statementImportRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void none_doesNotReverseTheBalance() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNull();
        verify(accountRepository, never()).save(any());
    }

    @Test
    void unknownLegacy_doesNotReverseTheBalance_andReturnsAnAdminWarning() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.UNKNOWN_LEGACY);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId))
                .thenReturn(List.of(transaction(oldId, "500.00", Transaction.ReconciliationStatus.OK)));

        SupersedeResult result = service.supersede(userId, oldId, newId);

        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNotNull();
        verify(accountRepository, never()).save(any());
    }

    // --- transaction-status bookkeeping -----------------------------------------------------------

    @Test
    void marksOnlyOkStatusTransactionsSuperseded_leavesAnAlreadyClassifiedRowAlone() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        Transaction ok = transaction(oldId, "100.00", Transaction.ReconciliationStatus.OK);
        Transaction alreadyDuplicate = transaction(oldId, "50.00", Transaction.ReconciliationStatus.DUPLICATE);
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of(ok, alreadyDuplicate));

        service.supersede(userId, oldId, newId);

        assertThat(ok.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.SUPERSEDED);
        assertThat(alreadyDuplicate.getReconciliationStatus())
                .as("a row already excluded for its own reason keeps that reason, not overwritten")
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
    }

    @Test
    void setsSupersededByOnTheOriginal() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());

        service.supersede(userId, oldId, newId);

        assertThat(old.getSupersededBy()).isEqualTo(newId);
        verify(statementImportRepository).save(old);
    }

    @Test
    void runsReconciliationAndRecurringDetection_whenTransactionsWereMarkedSuperseded() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId))
                .thenReturn(List.of(transaction(oldId, "100.00", Transaction.ReconciliationStatus.OK)));

        service.supersede(userId, oldId, newId);

        verify(reconciliationService).reconcileForUser(userId);
        verify(recurringService).detectForUser(userId);
    }

    @Test
    void skipsReconciliationAndRecurringDetection_whenThereWereNoTransactions() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());

        service.supersede(userId, oldId, newId);

        verify(reconciliationService, never()).reconcileForUser(any());
        verify(recurringService, never()).detectForUser(any());
    }

    @Test
    void recordsAnAuditEvent() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));
        when(transactionRepository.findByStatementImportId(oldId)).thenReturn(List.of());

        service.supersede(userId, oldId, newId);

        verify(auditService).record(eq(userId), eq("STATEMENT_IMPORT_SUPERSEDED"), eq("StatementImport"),
                eq(oldId), any());
    }

    // --- validation ---------------------------------------------------------------------------

    @Test
    void rejectsSupersedingAcrossDifferentAccounts() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        StatementImport replacement = statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        replacement.setAccountId(UUID.randomUUID());
        stub(old, replacement);

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);
        verify(statementImportRepository, never()).save(any());
    }

    @Test
    void rejectsSupersedingADifferentPeriod() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        StatementImport replacement = statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        replacement.setStatementPeriodEnd(periodEnd.plusDays(1));
        stub(old, replacement);

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);
        verify(statementImportRepository, never()).save(any());
    }

    @Test
    void rejectsSupersedingItself() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        when(statementImportRepository.findById(oldId)).thenReturn(Optional.of(old));

        assertThatThrownBy(() -> service.supersede(userId, oldId, oldId)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsWhenTheOriginalIsAlreadySuperseded() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        old.setSupersededBy(UUID.randomUUID());
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);
        verify(statementImportRepository, never()).save(any());
    }

    @Test
    void rejectsWhenTheReplacementIsItselfAlreadySuperseded() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        StatementImport replacement = statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE);
        replacement.setSupersededBy(UUID.randomUUID());
        stub(old, replacement);

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);
        verify(statementImportRepository, never()).save(any());
    }

    @Test
    void rejectsAStatementNotOwnedByTheCaller() {
        StatementImport old = statement(oldId, StatementImport.BalanceApplicationMode.NONE);
        old.setUserId(UUID.randomUUID());
        stub(old, statement(newId, StatementImport.BalanceApplicationMode.ABSOLUTE));

        assertThatThrownBy(() -> service.supersede(userId, oldId, newId)).isInstanceOf(ApiException.class);
    }
}
