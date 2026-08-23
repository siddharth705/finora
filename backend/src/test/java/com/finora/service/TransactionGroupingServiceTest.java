package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void groupsTransactionsByMerchant_excludingGroupsOfOne() {
        UUID swiggyId = UUID.randomUUID();
        UUID uniqueShopId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uniqueShopId)));

        Merchant swiggy = new Merchant();
        swiggy.setCanonicalName("SWIGGY");
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByIdAndUserId(swiggyId, userId)).thenReturn(Optional.of(swiggy));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(0).transactionIds()).hasSize(2);
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

    @Test
    void sortsLargestGroupFirst() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId))
                .thenReturn(List.of(txnFor(uberId), txnFor(uberId), txnFor(swiggyId), txnFor(swiggyId), txnFor(swiggyId)));

        Merchant swiggy = new Merchant();
        swiggy.setCanonicalName("SWIGGY");
        Merchant uber = new Merchant();
        uber.setCanonicalName("UBER");
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByIdAndUserId(swiggyId, userId)).thenReturn(Optional.of(swiggy));
        when(merchantRepository.findByIdAndUserId(uberId, userId)).thenReturn(Optional.of(uber));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(1).merchantName()).isEqualTo("UBER");
    }
}
