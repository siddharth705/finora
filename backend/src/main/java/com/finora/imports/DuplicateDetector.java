package com.finora.imports;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Duplicate detection for the import pipeline. Two distinct checks live here:
 *  - staging-time: "does this row already look like something in the ledger" (flags a StagedRow
 *    for review, doesn't block anything)
 *  - confirm-time: "of what was just imported, how much did full reconciliation flag" (feeds the
 *    ConfirmResponse summary)
 *
 * Both are intentionally read-only from this class's point of view — actually marking a
 * transaction as a duplicate happens in ReconciliationService, which runs after import as its
 * own pass. This class only answers "does a duplicate-shaped row already exist," it never writes.
 */
@Component
public class DuplicateDetector {

    private final TransactionRepository transactionRepository;

    public DuplicateDetector(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Duplicate check at staging time can't be scoped to a target account yet — the account is
     * chosen/created after staging (see ImportDto.ConfirmRequest) — so this checks across all of
     * the user's transactions rather than one account. That's a feature, not a limitation: it
     * also catches "you already logged this under a different account by mistake."
     */
    public boolean isLikelyDuplicate(UUID userId, LocalDate date, BigDecimal amount, String description) {
        return !transactionRepository.findPotentialDuplicatesByUser(userId, date, amount, description).isEmpty();
    }

    /** Counts, among a just-imported batch, how many rows reconciliation flagged as duplicates
     *  vs. internal transfers. Re-fetches by ID rather than trusting in-memory copies, since
     *  reconciliation mutates rows via its own repository calls. */
    public ReconciliationTally tally(List<Transaction> savedBatch) {
        List<Transaction> reconciled = transactionRepository.findAllById(
                savedBatch.stream().map(Transaction::getId).toList());
        int duplicatesDetected = (int) reconciled.stream().filter(t -> t.getIsDuplicateOf() != null).count();
        int transfersIdentified = (int) reconciled.stream().filter(Transaction::isTransfer).count();
        return new ReconciliationTally(duplicatesDetected, transfersIdentified);
    }

    public record ReconciliationTally(int duplicatesDetected, int transfersIdentified) {}
}
