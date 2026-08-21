package com.finora.service;

import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private MerchantRepository merchantRepository;
    private MerchantCategoryLearningRepository learningRepository;
    private MerchantLearningAuditRepository learningAuditRepository;
    private CategoryRepository categoryRepository;
    private StatementImportRepository statementImportRepository;
    private AnalyticsService analyticsService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        learningAuditRepository = mock(MerchantLearningAuditRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        // AnalyticsService now takes a UserRepository so it can resolve the USER's timezone rather
        // than computing months in the server's zone (merchantTrend) and hardcoded UTC
        // (learningGrowth). An empty findById is exactly the "no such user" case, which UserZone
        // resolves to its documented default -- so these tests keep the deterministic zone they
        // already assumed, without having to seed a user row.
        userRepository = mock(UserRepository.class);
        when(userRepository.findById(any())).thenReturn(Optional.empty());
        analyticsService = new AnalyticsService(transactionRepository, merchantRepository,
                learningRepository, learningAuditRepository, categoryRepository, statementImportRepository,
                new ConfidenceEngine(), userRepository);
    }

    private Transaction expense(UUID merchantId, LocalDate date, BigDecimal amount) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setMerchantId(merchantId);
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    private Merchant merchant(UUID id, String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", id);
        m.setUserId(userId);
        m.setCanonicalName(name);
        return m;
    }

    // --- topMerchants ---

    @Test
    void topMerchants_ranksByTotalSpendDescending() {
        UUID amazon = UUID.randomUUID();
        UUID swiggy = UUID.randomUUID();
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(
                expense(amazon, LocalDate.of(2026, 7, 1), new BigDecimal("5000")),
                expense(swiggy, LocalDate.of(2026, 7, 2), new BigDecimal("400")),
                expense(swiggy, LocalDate.of(2026, 7, 3), new BigDecimal("300"))
        ));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(
                merchant(amazon, "Amazon"), merchant(swiggy, "Swiggy")));

        var result = analyticsService.topMerchants(userId, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).merchantName()).isEqualTo("Amazon");
        assertThat(result.get(0).totalSpend()).isEqualByComparingTo("5000");
        assertThat(result.get(1).merchantName()).isEqualTo("Swiggy");
        assertThat(result.get(1).totalSpend()).isEqualByComparingTo("700");
        assertThat(result.get(1).transactionCount()).isEqualTo(2);
    }

    @Test
    void topMerchants_excludesTransfersDuplicatesAndRefunds() {
        UUID amazon = UUID.randomUUID();
        Transaction real = expense(amazon, LocalDate.of(2026, 7, 1), new BigDecimal("1000"));
        Transaction transfer = expense(amazon, LocalDate.of(2026, 7, 2), new BigDecimal("9000"));
        transfer.setTransfer(true);
        Transaction duplicate = expense(amazon, LocalDate.of(2026, 7, 3), new BigDecimal("9000"));
        duplicate.setIsDuplicateOf(UUID.randomUUID());

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(real, transfer, duplicate));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant(amazon, "Amazon")));

        var result = analyticsService.topMerchants(userId, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalSpend()).isEqualByComparingTo("1000");
    }

    @Test
    void topMerchants_filtersToTheGivenMonth() {
        // BH-042 follow-up: a specific month is now itself the bounded query
        // (findByUserIdAndTxnDateBetween), not a filter over the all-time findByUserId load -- see
        // topMerchants_givenASpecificMonth_queriesOnlyThatMonth below for the test that proves the
        // bound is what's actually requested. The June row here would never even be fetched now,
        // but keeping it in the stub still proves the July-only assertion holds either way.
        UUID amazon = UUID.randomUUID();
        when(transactionRepository.findByUserIdAndTxnDateBetween(eq(userId), any(), any())).thenReturn(List.of(
                expense(amazon, LocalDate.of(2026, 7, 15), new BigDecimal("500"))
        ));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant(amazon, "Amazon")));

        var result = analyticsService.topMerchants(userId, YearMonth.of(2026, 7));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).totalSpend()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("BH-042 follow-up: topMerchants given a specific month queries only that month, not the entire history")
    void topMerchants_givenASpecificMonth_queriesOnlyThatMonth() {
        // No stub for findByUserId at all -- if this ever regresses back to the all-time
        // activeExpenseTransactions(userId, month) filtering in memory, this test's own verify()
        // below would fail to see any invocation of findByUserIdAndTxnDateBetween, since Mockito
        // never routes one method's stub to another.
        analyticsService.topMerchants(userId, YearMonth.of(2026, 7));

        org.mockito.ArgumentCaptor<LocalDate> fromCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.ArgumentCaptor<LocalDate> toCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository).findByUserIdAndTxnDateBetween(eq(userId), fromCaptor.capture(), toCaptor.capture());

        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    @DisplayName("BH-042 follow-up: the all-time case (month == null) is unaffected, still queries the whole history")
    void topMerchants_givenNoMonth_stillQueriesTheEntireHistory() {
        analyticsService.topMerchants(userId, null);

        verify(transactionRepository).findByUserId(userId);
        verify(transactionRepository, org.mockito.Mockito.never())
                .findByUserIdAndTxnDateBetween(any(), any(), any());
    }

    @Test
    void topMerchants_ignoresTransactionsWithNoResolvedMerchant() {
        Transaction noMerchant = expense(null, LocalDate.of(2026, 7, 1), new BigDecimal("1000"));
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(noMerchant));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(analyticsService.topMerchants(userId, null)).isEmpty();
    }

    // --- merchantTrend ---

    @Test
    void merchantTrend_returnsSixTrailingMonths_oldestFirst_includingZeroSpendMonths() {
        UUID amazon = UUID.randomUUID();
        // BH-042: merchantTrend now fetches only its own [start, end] window via
        // findByUserIdAndTxnDateBetween instead of loading the user's whole history, so the stub
        // moves to that method -- see merchantTrend_queriesOnlyItsOwnWindow_notTheEntireHistory
        // below for the test that actually proves the window is what's requested.
        when(transactionRepository.findByUserIdAndTxnDateBetween(eq(userId), any(), any())).thenReturn(List.of(
                expense(amazon, LocalDate.of(2026, 7, 10), new BigDecimal("500"))
        ));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant(amazon, "Amazon")));

        var result = analyticsService.merchantTrend(userId, YearMonth.of(2026, 7));

        assertThat(result).hasSize(6);
        assertThat(result.get(0).month()).isEqualTo("2026-02"); // 6 months back from July, inclusive
        assertThat(result.get(5).month()).isEqualTo("2026-07");
        assertThat(result.get(5).totalSpend()).isEqualByComparingTo("500");
        assertThat(result.get(0).totalSpend()).isEqualByComparingTo("0"); // no spend that month, not omitted
    }

    @Test
    void merchantTrend_sumsMultipleMerchantsWithinTheSameMonth() {
        UUID amazon = UUID.randomUUID();
        UUID swiggy = UUID.randomUUID();
        when(transactionRepository.findByUserIdAndTxnDateBetween(eq(userId), any(), any())).thenReturn(List.of(
                expense(amazon, LocalDate.of(2026, 7, 5), new BigDecimal("500")),
                expense(swiggy, LocalDate.of(2026, 7, 20), new BigDecimal("300"))
        ));
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());

        var result = analyticsService.merchantTrend(userId, YearMonth.of(2026, 7));

        assertThat(result.get(5).totalSpend()).isEqualByComparingTo("800");
    }

    @Test
    @DisplayName("BH-042: merchantTrend queries only its own 6-month window, not the entire history")
    void merchantTrend_queriesOnlyItsOwnWindow_notTheEntireHistory() {
        // No stub for findByUserId at all -- if merchantTrend ever regresses back to calling the
        // all-time activeExpenseTransactions(userId, null) overload (which is backed by
        // findByUserId), this test's own verify() below would fail to see any invocation of
        // findByUserIdAndTxnDateBetween, since Mockito never routes one method's stub to another.
        analyticsService.merchantTrend(userId, YearMonth.of(2026, 7));

        org.mockito.ArgumentCaptor<LocalDate> fromCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.ArgumentCaptor<LocalDate> toCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository).findByUserIdAndTxnDateBetween(eq(userId), fromCaptor.capture(), toCaptor.capture());

        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2026, 2, 1)); // 6 months back from July, inclusive
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    // --- categoryConfidence ---

    @Test
    void categoryConfidence_averagesAcrossMerchantsSharingTheSameTopCategory() {
        UUID shoppingId = UUID.randomUUID();
        UUID amazon = UUID.randomUUID();
        UUID flipkart = UUID.randomUUID();

        Category shopping = new Category();
        ReflectionTestUtils.setField(shopping, "id", shoppingId);
        shopping.setUserId(userId);
        shopping.setName("Shopping");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(shopping));

        when(learningRepository.findByUserId(userId)).thenReturn(List.of(
                pair(amazon, shoppingId, 80),
                pair(flipkart, shoppingId, 60)
        ));

        var result = analyticsService.categoryConfidence(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("Shopping");
        assertThat(result.get(0).avgConfidence()).isEqualTo(70); // (80 + 60) / 2
        assertThat(result.get(0).merchantCount()).isEqualTo(2);
    }

    @Test
    void categoryConfidence_onlyCountsEachMerchantsTopCategory_notEveryPair() {
        UUID shoppingId = UUID.randomUUID();
        UUID electronicsId = UUID.randomUUID();
        UUID amazon = UUID.randomUUID();

        Category shopping = new Category();
        ReflectionTestUtils.setField(shopping, "id", shoppingId);
        shopping.setName("Shopping");
        Category electronics = new Category();
        ReflectionTestUtils.setField(electronics, "id", electronicsId);
        electronics.setName("Electronics");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(shopping, electronics));

        // Amazon: Shopping confirmed more often (higher confirmationCount) than Electronics --
        // only Shopping (the top pick) should count toward the aggregate below.
        MerchantCategoryLearning shoppingPair = pairWithCount(amazon, shoppingId, 10, 70);
        MerchantCategoryLearning electronicsPair = pairWithCount(amazon, electronicsId, 3, 30);
        when(learningRepository.findByUserId(userId)).thenReturn(List.of(shoppingPair, electronicsPair));

        var result = analyticsService.categoryConfidence(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("Shopping");
        assertThat(result.get(0).merchantCount()).isEqualTo(1);
    }

    @Test
    void categoryConfidence_noLearningData_returnsEmptyList() {
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(learningRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(analyticsService.categoryConfidence(userId)).isEmpty();
    }

    // --- topCategories (Financial Intelligence Workspace, Analytics module) ---

    @Test
    void topCategories_ranksByTotalSpendDescending_sameExclusionsAsTopMerchants() {
        UUID shoppingId = UUID.randomUUID();
        UUID diningId = UUID.randomUUID();
        Transaction shoppingTxn = expense(null, LocalDate.of(2026, 7, 1), new BigDecimal("2000"));
        shoppingTxn.setCategoryId(shoppingId);
        Transaction diningTxn = expense(null, LocalDate.of(2026, 7, 2), new BigDecimal("500"));
        diningTxn.setCategoryId(diningId);
        Transaction excludedTransfer = expense(null, LocalDate.of(2026, 7, 3), new BigDecimal("9000"));
        excludedTransfer.setCategoryId(shoppingId);
        excludedTransfer.setTransfer(true);

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(shoppingTxn, diningTxn, excludedTransfer));

        Category shopping = new Category();
        ReflectionTestUtils.setField(shopping, "id", shoppingId);
        shopping.setName("Shopping");
        Category dining = new Category();
        ReflectionTestUtils.setField(dining, "id", diningId);
        dining.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(shopping, dining));

        var result = analyticsService.topCategories(userId, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).categoryName()).isEqualTo("Shopping");
        assertThat(result.get(0).totalSpend()).isEqualByComparingTo("2000"); // transfer excluded
        assertThat(result.get(1).categoryName()).isEqualTo("Dining");
    }

    // --- importStatistics ---

    @Test
    void importStatistics_sumsAcrossAllImports_lastImportedAtFromTheMostRecent() {
        StatementImportRepository.StatementMetadata older = statementImport(20, 2, Instant.parse("2026-01-01T00:00:00Z"));
        StatementImportRepository.StatementMetadata newer = statementImport(35, 1, Instant.parse("2026-06-01T00:00:00Z"));
        // findMetadataByUserIdOrderByImportedAtDesc -- newest first, same as the real query's contract
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(newer, older));

        var result = analyticsService.importStatistics(userId);

        assertThat(result.totalStatements()).isEqualTo(2);
        assertThat(result.totalTransactionsImported()).isEqualTo(55);
        assertThat(result.totalTransactionsSkipped()).isEqualTo(3);
        assertThat(result.lastImportedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void importStatistics_noImportsYet_lastImportedAtIsNull_notEpochZero() {
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of());

        var result = analyticsService.importStatistics(userId);

        assertThat(result.totalStatements()).isZero();
        assertThat(result.lastImportedAt()).isNull();
    }

    // --- learningGrowth ---

    @Test
    void learningGrowth_groupsLearnedAndCorrectedSeparately_perMonth_oldestFirst() {
        when(learningAuditRepository.findByUserId(userId)).thenReturn(List.of(
                auditEntry(MerchantLearningAudit.Action.LEARNED, Instant.parse("2026-05-15T00:00:00Z")),
                auditEntry(MerchantLearningAudit.Action.LEARNED, Instant.parse("2026-05-20T00:00:00Z")),
                auditEntry(MerchantLearningAudit.Action.CORRECTED, Instant.parse("2026-06-10T00:00:00Z"))));

        var result = analyticsService.learningGrowth(userId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).month()).isEqualTo("2026-05");
        assertThat(result.get(0).learnedCount()).isEqualTo(2);
        assertThat(result.get(0).correctedCount()).isZero();
        assertThat(result.get(1).month()).isEqualTo("2026-06");
        assertThat(result.get(1).correctedCount()).isEqualTo(1);
    }

    @Test
    void learningGrowth_ignoresUndoneAndMergedActions() {
        when(learningAuditRepository.findByUserId(userId)).thenReturn(List.of(
                auditEntry(MerchantLearningAudit.Action.UNDONE, Instant.parse("2026-05-15T00:00:00Z")),
                auditEntry(MerchantLearningAudit.Action.MERGED, Instant.parse("2026-05-15T00:00:00Z"))));

        assertThat(analyticsService.learningGrowth(userId)).isEmpty();
    }

    @Test
    void learningGrowth_noHistory_returnsEmptyList() {
        when(learningAuditRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(analyticsService.learningGrowth(userId)).isEmpty();
    }

    private StatementImportRepository.StatementMetadata statementImport(int imported, int skipped, Instant importedAt) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getTransactionsImported()).thenReturn(imported);
        when(m.getTransactionsSkipped()).thenReturn(skipped);
        when(m.getImportedAt()).thenReturn(importedAt);
        return m;
    }

    private MerchantLearningAudit auditEntry(MerchantLearningAudit.Action action, Instant createdAt) {
        MerchantLearningAudit a = new MerchantLearningAudit();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        a.setMerchantId(UUID.randomUUID());
        a.setAction(action);
        ReflectionTestUtils.setField(a, "createdAt", createdAt);
        return a;
    }

    private MerchantCategoryLearning pair(UUID merchantId, UUID categoryId, int confidence) {
        return pairWithCount(merchantId, categoryId, 1, confidence);
    }

    private MerchantCategoryLearning pairWithCount(UUID merchantId, UUID categoryId, int confirmationCount, int confidence) {
        MerchantCategoryLearning p = new MerchantCategoryLearning();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setMerchantId(merchantId);
        p.setCategoryId(categoryId);
        p.setConfirmationCount(confirmationCount);
        p.setConfidence(confidence);
        return p;
    }
}
