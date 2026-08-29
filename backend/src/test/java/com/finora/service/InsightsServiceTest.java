package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Core InsightsService.build() coverage -- prior to this file, only the budget-recommendation
 * sentence (see InsightsServiceBudgetRecommendationTest) had a dedicated test. Everything else --
 * the totals sentence, biggest-category, top-merchant, month-over-month category movers, and
 * refund netting -- had none, despite this exact class having a real bug history (BH-005 refund
 * netting, a deleted-account leak). Covers that gap, plus two behavior fixes found in the same
 * review: a brand-new spending category never surfaced anywhere (silently dropped by the
 * pctChange == null filter), and a blank "Unknown" merchant bucket could win "top merchant" by
 * summing together unrelated transactions that merely happened to lack a merchant/description.
 */
class InsightsServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private BudgetRepository budgetRepository;
    private InsightsService insightsService;
    private final UUID userId = UUID.randomUUID();
    private Category dining;
    private Category groceries;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        insightsService = new InsightsService(transactionRepository, accountRepository, categoryRepository, budgetRepository,
                mock(UserRepository.class), transactionGraphService);

        dining = new Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setUserId(userId);
        dining.setName("Dining");
        groceries = new Category();
        ReflectionTestUtils.setField(groceries, "id", UUID.randomUUID());
        groceries.setUserId(userId);
        groceries.setName("Groceries");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining, groceries));
    }

    private Transaction expense(LocalDate date, BigDecimal amount, Category category, String merchant) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setCategoryId(category == null ? null : category.getId());
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setMerchant(merchant);
        t.setDescription(merchant);
        return t;
    }

    private void givenTransactions(List<Transaction> txns) {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(txns);
    }

    // --- Existing, previously-untested behavior ---------------------------------------------

    @Test
    void totalSpendSentence_reportsTotalAcrossAllCategoriesThisMonth() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(600), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 10), BigDecimal.valueOf(400), groceries, "Mart")));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹1,000 across 2 categories"));
    }

    @Test
    void biggestCategorySentence_namesTheHighestSpendCategoryThisMonth() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(600), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 10), BigDecimal.valueOf(400), groceries, "Mart")));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("Dining was your biggest category at ₹600"));
    }

    @Test
    void topMerchantSentence_namesTheHighestSpendMerchantThisMonth() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(300), dining, "Cafe A"),
                expense(LocalDate.of(2026, 7, 6), BigDecimal.valueOf(700), dining, "Cafe B")));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).anyMatch(s -> s.contains("\"Cafe B\" at ₹700"));
    }

    @Test
    void categoryMoverSentence_reportsPercentChangeVersusPriorAverage() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 6, 15), BigDecimal.valueOf(1000), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 15), BigDecimal.valueOf(2000), dining, "Cafe")));

        var result = insightsService.build(userId);

        assertThat(result.movers()).anyMatch(m -> m.category().equals("Dining")
                && m.pctChange() != null && Math.abs(m.pctChange() - 100.0) < 0.01);
        assertThat(result.sentences()).anyMatch(s -> s.contains("Dining spend was 100% more than your recent average"));
    }

    @Test
    void refundedPurchase_isReportedAtWhatItActuallyCost() {
        Transaction purchase = expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(1000), dining, "Cafe");
        ReflectionTestUtils.setField(purchase, "id", UUID.randomUUID());
        Transaction refund = new Transaction();
        refund.setUserId(userId);
        refund.setCategoryId(dining.getId());
        refund.setTxnDate(LocalDate.of(2026, 7, 6));
        refund.setAmount(BigDecimal.valueOf(400));
        refund.setTxnType(Transaction.Type.INCOME);
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        refund.setRefundOfTransactionId(purchase.getId());

        givenTransactions(List.of(purchase, refund));

        var result = insightsService.build(userId);

        // 1000 - 400 refunded = 600 actually spent, not the full 1000.
        assertThat(result.sentences()).anyMatch(s -> s.contains("total spend was ₹600"));
    }

    @Test
    void noTransactions_returnsThePromptSentence_andNoMovers() {
        givenTransactions(List.of());

        var result = insightsService.build(userId);

        assertThat(result.sentences()).containsExactly("Upload or add transactions to see spending insights.");
        assertThat(result.movers()).isEmpty();
    }

    // --- Bug fix: a brand-new spending category never surfaced anywhere ---------------------

    @Test
    void newCategorySentence_surfacesACategoryWithNoPriorHistory() {
        givenTransactions(List.of(
                // Groceries has a prior month, so it is an ordinary (non-new) category.
                expense(LocalDate.of(2026, 6, 15), BigDecimal.valueOf(500), groceries, "Mart"),
                expense(LocalDate.of(2026, 7, 15), BigDecimal.valueOf(500), groceries, "Mart"),
                // Dining appears for the first time this month -- genuinely new, no basis in
                // June to compute a % change from, which is exactly why the old pctChange==null
                // filter silently dropped it from every mover-based sentence.
                expense(LocalDate.of(2026, 7, 16), BigDecimal.valueOf(1200), dining, "Cafe")));

        var result = insightsService.build(userId);

        assertThat(result.sentences())
                .as("a category with real spend but zero prior-month history should be called out")
                .anyMatch(s -> s.contains("Dining") && s.contains("new") && s.contains("₹1,200"));
    }

    @Test
    void newCategorySentence_omitted_whenThereIsNoPriorMonthDataAtAll() {
        // A user's very first month of data: every category is trivially "new" relative to
        // nothing, which is not a meaningful observation -- it is simply the user's first
        // transaction, not a new pattern emerging against an established one.
        givenTransactions(List.of(expense(LocalDate.of(2026, 7, 15), BigDecimal.valueOf(1200), dining, "Cafe")));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).noneMatch(s -> s.contains("new category") || s.contains("new this month"));
    }

    @Test
    void newCategorySentence_omitted_whenEveryCategoryHasPriorHistory() {
        givenTransactions(List.of(
                expense(LocalDate.of(2026, 6, 15), BigDecimal.valueOf(500), dining, "Cafe"),
                expense(LocalDate.of(2026, 7, 15), BigDecimal.valueOf(500), dining, "Cafe")));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).noneMatch(s -> s.contains("new category") || s.contains("new this month"));
    }

    // --- Bug fix: "Unknown" merchant bucket could win top-merchant --------------------------

    @Test
    void topMerchantSentence_excludesUnknownBucket_evenWhenItIsTheLargestTotal() {
        givenTransactions(List.of(
                // Two blank-merchant, blank-description transactions -- unrelated to each other,
                // but both fall into the same literal "Unknown" bucket and together outweigh the
                // one real, identified merchant below.
                expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(5000), dining, null),
                expense(LocalDate.of(2026, 7, 6), BigDecimal.valueOf(5000), dining, null),
                expense(LocalDate.of(2026, 7, 7), BigDecimal.valueOf(300), dining, "Cafe")));

        var result = insightsService.build(userId);

        assertThat(result.sentences())
                .as("the top-merchant sentence must never name the blank-fallback bucket")
                .noneMatch(s -> s.contains("\"Unknown\""));
        assertThat(result.sentences()).anyMatch(s -> s.contains("\"Cafe\" at ₹300"));
    }

    @Test
    void topMerchantSentence_omitted_whenEveryTransactionLacksMerchantAndDescription() {
        givenTransactions(List.of(expense(LocalDate.of(2026, 7, 5), BigDecimal.valueOf(500), dining, null)));

        var result = insightsService.build(userId);

        assertThat(result.sentences()).noneMatch(s -> s.contains("top merchant"));
    }
}
