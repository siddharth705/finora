package com.finora.imports.trace;

import com.finora.dto.ImportRowTraceDto;
import com.finora.entity.Transaction;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles the Import Row Trace's per-row view (docs/proposals/reconciliation-evolution-
 * roadmap-proposal.md Part 9) for one statement import -- every successfully-imported row's
 * position, alongside the transaction it became. See {@link ImportRowTraceDto} for why a
 * dropped/excluded row is deliberately absent rather than listed with a null transaction.
 */
@Service
public class ImportRowTraceService {

    private final StatementImportRepository statementImportRepository;
    private final TransactionRepository transactionRepository;

    public ImportRowTraceService(StatementImportRepository statementImportRepository,
                                  TransactionRepository transactionRepository) {
        this.statementImportRepository = statementImportRepository;
        this.transactionRepository = transactionRepository;
    }

    public Optional<ImportRowTraceDto.Trace> trace(UUID statementImportId) {
        if (!statementImportRepository.existsById(statementImportId)) {
            return Optional.empty();
        }

        List<ImportRowTraceDto.RowOutcome> rows = transactionRepository.findByStatementImportId(statementImportId)
                .stream()
                .filter(t -> t.getSourceRowPosition() != null)
                .sorted(Comparator.comparingInt(Transaction::getSourceRowPosition))
                .map(t -> new ImportRowTraceDto.RowOutcome(
                        t.getSourceRowPosition(), t.getId(), t.getDescription(), t.getAmount(), t.getTxnDate()))
                .toList();

        return Optional.of(new ImportRowTraceDto.Trace(statementImportId, rows));
    }
}
