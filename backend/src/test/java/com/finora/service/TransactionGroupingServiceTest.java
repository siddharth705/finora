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
import static org.mockito.ArgumentMatchers.eq;
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
        // txn_type is NOT NULL in the schema (V1) -- every real Transaction has one, so
        // TransactionSummary.from() (see MerchantGroup's own doc comment) reads it unguarded,
        // the same trust-the-DB-constraint posture ReconciliationStatus's non-null default
        // already gets away with on this same entity.
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Test transaction");
        return t;
    }

    private Merchant merchantOf(UUID id, String canonicalName) {
        Merchant merchant = new Merchant();
        org.springframework.test.util.ReflectionTestUtils.setField(merchant, "id", id);
        merchant.setCanonicalName(canonicalName);
        return merchant;
    }

    /** Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
     *  account's transactions deliberately keep deleted_at unset, so groupNeedsReviewByMerchant
     *  must scope its transaction fetch to exactly the user's live account ids. Every test below
     *  stubs an AccountRepository with one live account so the existing (pre-fix) test behavior is
     *  preserved; the dedicated tests further down assert the scoping itself. */
    private AccountRepository accountRepositoryWithOneLiveAccount() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));
        return accountRepository;
    }

    @Test
    void groupsTransactionsByMerchant_excludingGroupsOfOne() {
        UUID swiggyId = UUID.randomUUID();
        UUID uniqueShopId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uniqueShopId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    /**
     * Backs the Ledger's "preview before applying" expansion on this card: {@code transactions}
     * must carry the SAME rows as {@code transactionIds}, in the same order, not a separately
     * derived list that could drift out of sync with what "Apply to N transactions" actually acts
     * on.
     */
    @Test
    void groupTransactionsCarryTheSameIdsAndOrderAsTransactionIds() {
        UUID swiggyId = UUID.randomUUID();
        Transaction first = txnFor(swiggyId);
        org.springframework.test.util.ReflectionTestUtils.setField(first, "id", UUID.randomUUID());
        first.setDescription("SWIGGY*ORDER1");
        first.setAmount(BigDecimal.valueOf(250));
        Transaction second = txnFor(swiggyId);
        org.springframework.test.util.ReflectionTestUtils.setField(second, "id", UUID.randomUUID());
        second.setDescription("SWIGGY*ORDER2");
        second.setAmount(BigDecimal.valueOf(480));

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(first, second));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        TransactionGroupingService.MerchantGroup group = service.groupNeedsReviewByMerchant(userId).get(0);

        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::id)
                .containsExactlyElementsOf(group.transactionIds());
        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::description)
                .containsExactly("SWIGGY*ORDER1", "SWIGGY*ORDER2");
        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::amount)
                .containsExactly(BigDecimal.valueOf(250), BigDecimal.valueOf(480));
        assertThat(group.transactions()).extracting(TransactionGroupingService.TransactionSummary::type)
                .containsExactly("EXPENSE", "EXPENSE");
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
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(txnFor(swiggyId), txnFor(swiggyId), txnFor(uberId), txnFor(uberId),
                        txnFor(zomatoId), txnFor(zomatoId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER"), merchantOf(zomatoId, "ZOMATO")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
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
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(txnFor(danglingId), txnFor(danglingId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
    }

    @Test
    void excludesTransactionsWithNoMerchantIdentity() {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        Transaction noMerchant = txnFor(null);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(noMerchant, noMerchant));
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
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
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(original, duplicate, another));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchantOf(swiggyId, "SWIGGY")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).transactionIds()).hasSize(2);
    }

    @Test
    void sortsLargestGroupFirst() {
        UUID swiggyId = UUID.randomUUID();
        UUID uberId = UUID.randomUUID();

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of(txnFor(uberId), txnFor(uberId), txnFor(swiggyId), txnFor(swiggyId), txnFor(swiggyId)));

        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchantOf(swiggyId, "SWIGGY"), merchantOf(uberId, "UBER")));

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepositoryWithOneLiveAccount());
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).merchantName()).isEqualTo("SWIGGY");
        assertThat(groups.get(1).merchantName()).isEqualTo("UBER");
    }

    // --- Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so groupNeedsReviewByMerchant
    // must scope its transaction fetch to exactly the user's live account ids, not just their
    // userId. This is a separate call site from TransactionService.needsReview -- not called
    // through it -- so it needs its own coverage. ---

    @Test
    void groupNeedsReviewByMerchant_scopesTransactionFetch_toExactlyTheLiveAccountIds() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account account = new Account();
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(account));

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(eq(userId), any()))
                .thenReturn(List.of());
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepository);
        service.groupNeedsReviewByMerchant(userId);

        verify(transactionRepository).findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(
                userId, List.of(account.getId()));
    }

    @Test
    void groupNeedsReviewByMerchant_withNoLiveAccounts_shortCircuits_withoutQueryingTransactions() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);

        TransactionGroupingService service = new TransactionGroupingService(transactionRepository, merchantRepository, accountRepository);
        List<TransactionGroupingService.MerchantGroup> groups = service.groupNeedsReviewByMerchant(userId);

        assertThat(groups).isEmpty();
        verify(transactionRepository, never())
                .findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(any(), any());
    }
}
