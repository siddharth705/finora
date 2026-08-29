package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionGroupingServiceTest {

    private final UUID userId = UUID.randomUUID();

    private Transaction txnFor(UUID merchantId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setMerchantId(merchantId);
        t.setTxnDate(LocalDate.of(2026, 1, 1));
        t.setAmount(BigDecimal.TEN);
        return t;
    }

    private Merchant merchantOf(UUID id, String canonicalName) {
        Merchant merchant = new Merchant();
        org.springframework.test.util.ReflectionTestUtils.setField(merchant, "id", id);
        merchant.setCanonicalName(canonicalName);
        return merchant;
    }

    @Test
    void groupsTransactionsByMerchant_excludingGroupsOfOne() {
        UUID swiggyId = UUID.randomUUID();
        UUID uniqueShopId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uniqueShopId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    /**
     * Regression test: this used to call {@code merchantRepository.findByIdAndUserId} once PER
     * DISTINCT MERCHANT with a review backlog, instead of loading the user's merchants once and
     * looking them up in memory -- the same batch-index pattern
     * {@code MerchantNormalizationEngine.indexFor} already uses. A user with a large needs-review
     * backlog spanning many merchants paid one query per merchant instead of one query total.
     */
    @Test
    void resolvesMerchantNamesFromASingleBatchLoad_regardlessOfDistinctMerchantCount() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();
        UUID zomatoId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uberId), txnFor(uberId),
                        txnFor(zomatoId), txnFor(zomatoId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER"), merchantOf(zomatoId, "ZOMATO")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(3);
        verify(merchantRepository, org.mockito.Mockito.times(1)).findByUserId(userId);
        verify(merchantRepository, never()).findByIdAndUserId(any(), any());
    }

    /**
     * A dangling merchant id (its Merchant row is gone, e.g. discarded via MerchantReviewService)
     * must not surface as a group with no name -- same "skip, don't guess" contract the old
     * per-merchant lookup had via {@code findByIdAndUserId}'s empty Optional.
     */
    @Test
    void excludesAGroupWhoseMerchantNoLongerExists() {
        UUID danglingId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(danglingId), txnFor(danglingId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesTransactionsWithNoMerchantIdentity() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        Transaction noMerchant = txnFor(null);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(noMerchant, noMerchant));
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
    }

    /**
     * Regression test: a transaction already flagged DUPLICATE must not inflate the group's count
     * or be offered for bulk categorization -- it's resolved through the duplicate-review flow, not
     * this one.
     */
    @Test
    void excludesTransactionsAlreadyFlaggedAsDuplicate() {
        UUID swiggyId = UUID.randomUUID();

        Transaction original = txnFor(swiggyId);
        Transaction duplicate = txnFor(swiggyId);
        duplicate.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        Transaction another = txnFor(swiggyId);

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(original, duplicate, another));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void sortsLargestGroupFirst() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(uberId), txnFor(uberId), txnFor(swiggyId), txnFor(swiggyId), txnFor(swiggyId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(1).merchantName()).isEqualTo("UBER");
    }
}
