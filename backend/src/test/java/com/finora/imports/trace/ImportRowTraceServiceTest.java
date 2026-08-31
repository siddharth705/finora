package com.finora.imports.trace;

import com.finora.dto.ImportRowTraceDto;
import com.finora.entity.Transaction;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportRowTraceServiceTest {

    private StatementImportRepository statementImportRepository;
    private TransactionRepository transactionRepository;
    private ImportRowTraceService service;
    private final UUID statementImportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        statementImportRepository = mock(StatementImportRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        service = new ImportRowTraceService(statementImportRepository, transactionRepository);
        when(statementImportRepository.existsById(statementImportId)).thenReturn(true);
    }

    private Transaction txn(Integer sourceRowPosition) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setSourceRowPosition(sourceRowPosition);
        t.setDescription("ZOMATO ORDER");
        t.setAmount(new BigDecimal("340.00"));
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        return t;
    }

    @Test
    void trace_isEmpty_whenTheStatementImportDoesNotExist() {
        UUID unknownImport = UUID.randomUUID();
        when(statementImportRepository.existsById(unknownImport)).thenReturn(false);

        assertThat(service.trace(unknownImport)).isEmpty();
    }

    @Test
    void trace_omitsTransactionsWithNoKnownSourceRowPosition() {
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(txn(3), txn(null)));

        ImportRowTraceDto.Trace trace = service.trace(statementImportId).orElseThrow();

        assertThat(trace.rows()).hasSize(1);
        assertThat(trace.rows().get(0).rowPosition()).isEqualTo(3);
    }

    @Test
    void trace_sortsRowsByPosition_regardlessOfRepositoryOrder() {
        Transaction fifth = txn(5);
        Transaction second = txn(2);
        Transaction ninth = txn(9);
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(fifth, ninth, second));

        List<ImportRowTraceDto.RowOutcome> rows = service.trace(statementImportId).orElseThrow().rows();

        assertThat(rows).extracting(ImportRowTraceDto.RowOutcome::rowPosition).containsExactly(2, 5, 9);
        assertThat(rows).extracting(ImportRowTraceDto.RowOutcome::transactionId)
                .containsExactly(second.getId(), fifth.getId(), ninth.getId());
    }

    @Test
    void trace_returnsAnEmptyRowList_whenNoTransactionHasAKnownPosition() {
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(txn(null), txn(null)));

        assertThat(service.trace(statementImportId).orElseThrow().rows()).isEmpty();
    }

    @Test
    void trace_carriesDescriptionAmountAndDate_throughUnchanged() {
        when(transactionRepository.findByStatementImportId(statementImportId))
                .thenReturn(List.of(txn(1)));

        ImportRowTraceDto.RowOutcome row = service.trace(statementImportId).orElseThrow().rows().get(0);

        assertThat(row.description()).isEqualTo("ZOMATO ORDER");
        assertThat(row.amount()).isEqualByComparingTo("340.00");
        assertThat(row.txnDate()).isEqualTo(LocalDate.of(2026, 7, 10));
    }
}
