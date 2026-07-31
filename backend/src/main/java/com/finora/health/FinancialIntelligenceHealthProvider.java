package com.finora.health;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Financial Intelligence Engine's real integrity signal: a bounded sample of the most recent
 * platform-wide transactions, checked for dangling reconciliation pointers -- the exact same
 * check WorkspaceDashboardService.reconciliationHealthy() runs per-user (see that method's doc
 * comment on the dangling-pointer bug class this guards against), just sampled rather than
 * loading every transaction on the platform. Bounded to SAMPLE_SIZE for the same "cheap live
 * check, not a full-table scan" discipline as AdminStatsService. A pointer target outside the
 * sample window that's still a real, non-deleted row is NOT a false positive: this checks
 * existence via findAllById() against the whole table (soft-deleted rows excluded by
 * @SQLRestriction), not just the sample itself.
 */
@Component
public class FinancialIntelligenceHealthProvider implements HealthProvider {

    private static final int SAMPLE_SIZE = 500;

    private final TransactionRepository transactionRepository;

    public FinancialIntelligenceHealthProvider(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    public String name() {
        return "Financial Intelligence Engine";
    }

    @Override
    public String category() {
        return "Financial Intelligence";
    }

    @Override
    public HealthCheckResult check() {
        List<Transaction> sample = transactionRepository
                .findAll(PageRequest.of(0, SAMPLE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();

        if (sample.isEmpty()) {
            return HealthCheckResult.up("No transactions on the platform yet");
        }

        Set<UUID> referenced = new HashSet<>();
        for (Transaction t : sample) {
            if (t.getIsDuplicateOf() != null) referenced.add(t.getIsDuplicateOf());
            if (t.getTransferPairId() != null) referenced.add(t.getTransferPairId());
            if (t.getRefundOfTransactionId() != null) referenced.add(t.getRefundOfTransactionId());
        }

        if (referenced.isEmpty()) {
            return HealthCheckResult.up("Sampled " + sample.size() + " recent transactions, no reconciliation pointers to check");
        }

        long found = transactionRepository.findAllById(referenced).size();
        if (found < referenced.size()) {
            long dangling = referenced.size() - found;
            return HealthCheckResult.down(dangling + " dangling reconciliation pointer(s) found in a sample of "
                    + sample.size() + " recent transactions");
        }

        return HealthCheckResult.up("Sampled " + sample.size() + " recent transactions, "
                + referenced.size() + " reconciliation pointer(s) all resolve cleanly");
    }
}
