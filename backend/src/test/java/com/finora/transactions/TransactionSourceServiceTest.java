package com.finora.transactions;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Where did this number come from?" (Track C/C7) -- every branch reads fields the import
 * pipeline already wrote onto the transaction/statement import; nothing here computes a new
 * answer.
 */
class TransactionSourceServiceTest {

    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private AccountRepository accountRepository;
    private TransactionSourceService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID txnId = UUID.randomUUID();
    private final UUID statementImportId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new TransactionSourceService(transactionRepository, statementImportRepository, accountRepository);
    }

    @Test
    void csvImportedRowWithFullDataIsExplained() {
        Transaction t = transaction(Transaction.Source.CSV_IMPORT, statementImportId, 14);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", statementImportId);
        si.setAccountId(accountId);
        si.setFileName("march-statement.pdf");
        si.setImportedAt(Instant.parse("2026-08-15T10:00:00Z"));
        si.setStatementPeriodStart(LocalDate.of(2026, 3, 1));
        si.setStatementPeriodEnd(LocalDate.of(2026, 3, 31));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(si));

        Account account = new Account();
        account.setName("HDFC Savings");
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isTrue();
        assertThat(result.sourceLabel()).isEqualTo("CSV_IMPORT");
        assertThat(result.statementDeleted()).isFalse();
        assertThat(result.statementImportId()).isEqualTo(statementImportId);
        assertThat(result.fileName()).isEqualTo("march-statement.pdf");
        assertThat(result.rowPosition()).isEqualTo(14);
        assertThat(result.importedAt()).isEqualTo(Instant.parse("2026-08-15T10:00:00Z"));
        assertThat(result.accountName()).isEqualTo("HDFC Savings");
        assertThat(result.statementPeriodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(result.statementPeriodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void manualTransactionHasNoSourceRow() {
        Transaction t = transaction(Transaction.Source.MANUAL, null, null);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isFalse();
        assertThat(result.sourceLabel()).isEqualTo("MANUAL");
        assertThat(result.statementDeleted()).isFalse();
        assertThat(result.fileName()).isNull();
        assertThat(result.rowPosition()).isNull();
    }

    @Test
    void gmailImportedTransactionHasNoStatementRow() {
        Transaction t = transaction(Transaction.Source.GMAIL_IMPORT, null, null);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isFalse();
        assertThat(result.sourceLabel()).isEqualTo("GMAIL_IMPORT");
        assertThat(result.statementDeleted()).isFalse();
    }

    @Test
    void csvImportPredatingSourceRowPositionDegradesGracefully() {
        // A row imported before Transaction.sourceRowPosition existed carries a
        // statementImportId but no row position -- see that field's own doc comment. The
        // statement import itself DOES exist and IS stubbed to resolve -- proving this comes back
        // unavailable because of the missing row position, not merely because nothing was mocked.
        Transaction t = transaction(Transaction.Source.CSV_IMPORT, statementImportId, null);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", statementImportId);
        si.setAccountId(accountId);
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(si));

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isFalse();
        assertThat(result.sourceLabel()).isEqualTo("CSV_IMPORT");
        // Never had a row -- a genuinely different fact from "had one, lost it" below.
        assertThat(result.statementDeleted()).isFalse();
    }

    @Test
    void deletedStatementImportDegradesRatherThanErroring() {
        Transaction t = transaction(Transaction.Source.CSV_IMPORT, statementImportId, 3);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.empty());

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isFalse();
        assertThat(result.sourceLabel()).isEqualTo("CSV_IMPORT");
        // The row WAS tracked -- statementDeleted distinguishes this from "never had a row"
        // above, so the client doesn't tell the user their transaction predates tracking when it
        // doesn't.
        assertThat(result.statementDeleted()).isTrue();
    }

    @Test
    void missingAccountStillReturnsEverythingElse() {
        Transaction t = transaction(Transaction.Source.CSV_IMPORT, statementImportId, 7);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", statementImportId);
        si.setAccountId(accountId);
        si.setFileName("stmt.csv");
        when(statementImportRepository.findById(statementImportId)).thenReturn(Optional.of(si));
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        TransactionSourceDto result = service.explainSource(userId, txnId);

        assertThat(result.available()).isTrue();
        assertThat(result.statementDeleted()).isFalse();
        assertThat(result.accountName()).isNull();
        assertThat(result.fileName()).isEqualTo("stmt.csv");
    }

    @Test
    void someoneElsesTransactionIsRejected() {
        Transaction t = transaction(Transaction.Source.MANUAL, null, null);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.explainSource(UUID.randomUUID(), txnId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aNonexistentTransactionIsNotFound() {
        when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.explainSource(userId, txnId))
                .isInstanceOf(ApiException.class);
    }

    private Transaction transaction(Transaction.Source source, UUID statementImportId, Integer rowPosition) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", txnId);
        t.setUserId(userId);
        t.setSource(source);
        t.setStatementImportId(statementImportId);
        t.setSourceRowPosition(rowPosition);
        t.setAmount(BigDecimal.TEN);
        return t;
    }
}
