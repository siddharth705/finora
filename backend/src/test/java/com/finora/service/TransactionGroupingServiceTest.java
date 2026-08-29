package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionGroupingServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final List<UUID> liveAccountIds = List.of(accountId);

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

    private Account liveAccount() {
        Account account = new Account();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", accountId);
        return account;
    }

    /** Stubs {@code accountRepository.findByUserId} to return the one live account every other
     *  test in this file assumes, so each test only has to stub the transaction/merchant query it
     *  actually cares about. */
    private AccountRepository accountRepositoryWithOneLiveAccount() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount()));
        return accountRepository;
    }

    @Test
    void groupsTransactionsByMerchant_excludingGroupsOfOne() {
        UUID swiggyId = UUID.randomUUID();
        UUID uniqueShopId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uniqueShopId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
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
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uberId), txnFor(uberId),
                        txnFor(zomatoId), txnFor(zomatoId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER"), merchantOf(zomatoId, "ZOMATO")));

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
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
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(txnFor(danglingId), txnFor(danglingId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesTransactionsWithNoMerchantIdentity() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        Transaction noMerchant = txnFor(null);
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(noMerchant, noMerchant));
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
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
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(original, duplicate, another));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void sortsLargestGroupFirst() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndAccountIdInAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId, liveAccountIds))
                .thenReturn(List.of(txnFor(uberId), txnFor(uberId), txnFor(swiggyId), txnFor(swiggyId), txnFor(swiggyId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER")));

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(1).merchantName()).isEqualTo("UBER");
    }

    /**
     * Regression test for the deleted-account leak: once a user's last account is deleted,
     * {@code accountRepository.findByUserId} returns nothing live, and this must short-circuit to
     * an empty result without ever querying transactions -- not keep surfacing a deleted account's
     * needs-review backlog forever. Same fix DashboardService got in PR #529, applied here.
     */
    @Test
    void returnsNoGroupsAndSkipsTheTransactionQueryOnceTheUserHasNoLiveAccounts() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(
                transactionRepository, merchantRepository, accountRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
        verifyNoInteractions(transactionRepository);
    }
}
