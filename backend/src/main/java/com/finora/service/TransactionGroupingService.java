package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups a user's already-persisted needs-review transactions by merchant, so the Ledger can offer
 * "5 Swiggy transactions found" instead of 5 separate one-by-one corrections. Reuses the existing
 * needs-review query and re-groups in Java rather than a GROUP BY query, the same choice
 * AdminPlatformAnalyticsService already made for the identical reason: Transaction has no JPA
 * association to Merchant, only a plain UUID column, so there's no JPQL join path to the name.
 *
 * <p>Groups of exactly one transaction are deliberately excluded — those stay in the existing
 * AskOnceCard one-by-one flow (see docs/proposals/transaction-intelligence-engine-phase0-audit.md),
 * so nothing changes for a user with no repeat-merchant backlog.
 */
@Service
public class TransactionGroupingService {

    private static final int MIN_GROUP_SIZE = 2;

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final AccountRepository accountRepository;

    public TransactionGroupingService(TransactionRepository transactionRepository,
                                       MerchantRepository merchantRepository,
                                       AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.accountRepository = accountRepository;
    }

    public record MerchantGroup(UUID merchantId, String merchantName, List<UUID> transactionIds) {}

    public List<MerchantGroup> groupNeedsReviewByMerchant(UUID userId) {
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so the unscoped finder would
        // keep surfacing them in this grouping forever, not just during
        // StatementImportService's 7-day grace window. This is a separate call site from
        // TransactionService.needsReview -- not called through it -- so it needs its own scoping.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        List<Transaction> candidates = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(
                        userId, liveAccountIds);

        Map<UUID, List<UUID>> idsByMerchant = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            if (t.getMerchantId() == null) continue;
            // A transaction already flagged as a duplicate shouldn't inflate the group's count or be
            // bulk-categorized alongside its canonical counterpart -- it's resolved separately via
            // the duplicate-review flow, not this one.
            if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE) continue;
            idsByMerchant.computeIfAbsent(t.getMerchantId(), k -> new ArrayList<>()).add(t.getId());
        }

        // One query for every merchant name this user could possibly need here, rather than one
        // per distinct merchant with a review backlog -- the same batch-then-look-up-in-memory
        // pattern MerchantNormalizationEngine.indexFor uses for the identical reason. A dangling
        // merchant id (its Merchant row is gone, e.g. discarded via MerchantReviewService) simply
        // has no entry here, matching findByIdAndUserId's empty-Optional "skip it" behavior.
        Map<UUID, String> nameById = new java.util.HashMap<>();
        for (Merchant merchant : merchantRepository.findByUserId(userId)) {
            nameById.put(merchant.getId(), merchant.getCanonicalName());
        }

        List<MerchantGroup> groups = new ArrayList<>();
        for (var entry : idsByMerchant.entrySet()) {
            if (entry.getValue().size() < MIN_GROUP_SIZE) continue;
            String merchantName = nameById.get(entry.getKey());
            if (merchantName == null) continue;
            groups.add(new MerchantGroup(entry.getKey(), merchantName, entry.getValue()));
        }

        groups.sort((a, b) -> b.transactionIds().size() - a.transactionIds().size());
        return groups;
    }
}
