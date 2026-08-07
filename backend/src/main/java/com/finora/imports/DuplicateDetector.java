package com.finora.imports;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.finora.dto.ImportDto;
import java.util.Optional;

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
     * An index for one staging pass, replacing the per-row query with one query per distinct date.
     *
     * <p>Recommendation 2 of the import pipeline profile: findMatch below cost 1.00 statements per
     * row, measured. A statement covers ~31 dates whatever its row count, so this is the difference
     * between 5,000 queries and 31 for a large statement.
     */
    public DuplicateIndex indexFor(UUID userId) {
        return new DuplicateIndex(transactionRepository, userId);
    }

    /**
     * Duplicate check at staging time can't be scoped to a target account yet — the account is
     * chosen/created after staging (see ImportDto.ConfirmRequest) — so this checks across all of
     * the user's transactions rather than one account. That's a feature, not a limitation: it
     * also catches "you already logged this under a different account by mistake."
     */
    public boolean isLikelyDuplicate(UUID userId, LocalDate date, BigDecimal amount, String description) {
        return findMatch(userId, date, amount, description).isPresent();
    }

    /**
     * The same check, returning the EVIDENCE rather than a boolean (WI5).
     *
     * <p>A boolean is enough to filter and not enough to decide. Duplicate detection is decision
     * support now: the user is shown what the staged row appears to repeat -- the existing
     * transaction, its account, when it was imported -- and chooses. Returning only "yes it looks
     * like a duplicate" is what allowed a row to be dropped without anyone ever seeing what it was
     * supposedly a duplicate of.
     *
     * <p>Reports the FIRST match plus a count of how many there were. More than one is a
     * meaningful signal in itself: it usually means the user genuinely transacts this amount on
     * this date repeatedly (a daily commute fare, a split bill), which is precisely the case where
     * skipping is the wrong default.
     */
    public Optional<ImportDto.DuplicateMatch> findMatch(UUID userId, LocalDate date,
                                                          BigDecimal amount, String description) {
        return describe(transactionRepository.findPotentialDuplicatesByUser(userId, date, amount, description));
    }

    /**
     * The same check against a {@link DuplicateIndex} the caller built once for the whole
     * statement, instead of a query per row.
     *
     * <p>Both overloads end in {@link #describe}, deliberately. The evidence a review screen shows
     * -- which transaction, which account, when it was imported, how many matches -- must not
     * depend on which path found it, and the surest way to guarantee that is for there to be only
     * one place that builds it.
     */
    public Optional<ImportDto.DuplicateMatch> findMatch(DuplicateIndex index, LocalDate date,
                                                          BigDecimal amount, String description) {
        return describe(index.matches(date, amount, description));
    }

    /** Turns matching rows into the evidence WI5 reports. One implementation for both paths. */
    private Optional<ImportDto.DuplicateMatch> describe(List<Transaction> matches) {
        if (matches.isEmpty()) return Optional.empty();

        Transaction first = matches.get(0);
        return Optional.of(new ImportDto.DuplicateMatch(
                first.getId(),
                first.getAccountId(),
                first.getTxnDate(),
                first.getDescription(),
                first.getAmount(),
                first.getTxnType() == null ? null : first.getTxnType().name(),
                first.getCreatedAt(),
                matches.size(),
                // One level, honestly named. The query requires date, amount AND description to be
                // identical, so there is no weaker tier to report -- see DuplicateMatch's own doc.
                "EXACT",
                "Same date, amount and description as a transaction already in your ledger."));
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
