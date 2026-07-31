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
        long okCount = 0, duplicateCount = 0, transferCount = 0, refundCount = 0;
        for (Object[] row : transactionRepository.platformReconciliationStatusCounts()) {
            Transaction.ReconciliationStatus status = (Transaction.ReconciliationStatus) row[0];
            long count = (Long) row[1];
            switch (status) {
                case OK -> okCount = count;
                case DUPLICATE -> duplicateCount = count;
                case TRANSFER -> transferCount = count;
                case REFUND -> refundCount = count;
            }
        }
        long recurringCount = transactionRepository.countPlatformRecurring();
        long totalTransactions = okCount + duplicateCount + transferCount + refundCount;

        return new ReconciliationStatsDto(okCount, duplicateCount, transferCount, refundCount, recurringCount, totalTransactions);
    }
}
