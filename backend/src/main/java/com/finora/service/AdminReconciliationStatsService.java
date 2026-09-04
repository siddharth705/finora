package com.finora.service;

import com.finora.dto.AdminDtos.ReconciliationStatsDto;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-wide Reconciliation Monitor stats for the admin console. Reconciliation itself always
 * runs automatically (ReconciliationService, triggered after every import/create/edit/delete) --
 * there's nothing to manage here, only to observe. See
 * TransactionRepository.platformReconciliationStatusCounts()'s doc comment for why this is one
 * grouped COUNT query rather than the in-memory pattern WorkspaceDashboardService uses per-user.
 */
@Service
public class AdminReconciliationStatsService {

    private final TransactionRepository transactionRepository;

    public AdminReconciliationStatsService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public ReconciliationStatsDto platformStats() {
        // CodeQL (java/missing-case-in-switch), 2026-09-04: this switch used to cover only 4 of
        // ReconciliationStatus's 7 values, and totalTransactions was the sum of those 4 named
        // counters -- so REVERSAL/INVESTMENT_TRANSFER/SUPERSEDED rows (all three genuinely set in
        // production, by ReconciliationService/RefundNetting/StatementImportService) were silently
        // missing from both their own count AND the platform total, with no error and no visible
        // gap. Every status now has an explicit case; totalTransactions sums every row this query
        // returns, not just the ones this switch happens to name, so a future 8th status can never
        // repeat this by omission alone -- it would just be uncounted in its OWN bucket, not in the
        // total.
        long okCount = 0, duplicateCount = 0, transferCount = 0, refundCount = 0;
        long reversalCount = 0, investmentTransferCount = 0, supersededCount = 0;
        long totalTransactions = 0;
        for (Object[] row : transactionRepository.platformReconciliationStatusCounts()) {
            Transaction.ReconciliationStatus status = (Transaction.ReconciliationStatus) row[0];
            long count = (Long) row[1];
            totalTransactions += count;
            switch (status) {
                case OK -> okCount = count;
                case DUPLICATE -> duplicateCount = count;
                case TRANSFER -> transferCount = count;
                case REFUND -> refundCount = count;
                case REVERSAL -> reversalCount = count;
                case INVESTMENT_TRANSFER -> investmentTransferCount = count;
                case SUPERSEDED -> supersededCount = count;
            }
        }
        long recurringCount = transactionRepository.countPlatformRecurring();

        return new ReconciliationStatsDto(okCount, duplicateCount, transferCount, refundCount,
                reversalCount, investmentTransferCount, supersededCount, recurringCount, totalTransactions);
    }
}
