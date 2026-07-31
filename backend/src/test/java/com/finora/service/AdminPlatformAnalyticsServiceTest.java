package com.finora.service;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Platform-wide spend analytics -- mocked-repository unit test. The interesting behavior here
 *  isn't the SQL (that's TransactionRepository's job) but the Java-side re-grouping: two
 *  different users' own private Category rows that happen to share a name must be combined into
 *  one platform-wide total, not shown as two separate "Groceries" entries -- see
 *  AdminPlatformAnalyticsService's class comment for why. */
class AdminPlatformAnalyticsServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private MerchantRepository merchantRepository;
    private AdminPlatformAnalyticsService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        service = new AdminPlatformAnalyticsService(transactionRepository, categoryRepository, merchantRepository);
    }

    private Category category(String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setName(name);
        return c;
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setCanonicalName(name);
        return m;
    }

    @Test
    void topCategories_combinesDifferentUsersPrivateCategoryRowsThatShareAName() {
        Category groceriesUserA = category("Groceries");
        Category groceriesUserB = category("Groceries");
        Category dining = category("Dining");

        when(transactionRepository.platformCategorySpendTotals(any(), any())).thenReturn(List.of(
                new Object[]{groceriesUserA.getId(), 3L, new BigDecimal("100.00")},
                new Object[]{groceriesUserB.getId(), 2L, new BigDecimal("50.00")},
                new Object[]{dining.getId(), 1L, new BigDecimal("20.00")}
        ));
        when(categoryRepository.findAllById(any())).thenReturn(List.of(groceriesUserA, groceriesUserB, dining));
        when(transactionRepository.platformMerchantSpendTotals(any(), any())).thenReturn(List.of());
        when(merchantRepository.findAllById(any())).thenReturn(List.of());

        PlatformAnalyticsDto result = service.platformAnalytics();

        assertThat(result.topCategories()).hasSize(2);
        var groceries = result.topCategories().stream().filter(c -> c.categoryName().equals("Groceries")).findFirst().orElseThrow();
        assertThat(groceries.totalSpend()).isEqualByComparingTo("150.00");
        assertThat(groceries.transactionCount()).isEqualTo(5L);
        // Sorted by spend descending -- Groceries (150) ahead of Dining (20).
        assertThat(result.topCategories().get(0).categoryName()).isEqualTo("Groceries");
    }

    @Test
    void topMerchants_combinesDifferentUsersPrivateMerchantRowsThatShareAName() {
        Merchant amazonUserA = merchant("Amazon");
        Merchant amazonUserB = merchant("Amazon");

        when(transactionRepository.platformMerchantSpendTotals(any(), any())).thenReturn(List.of(
                new Object[]{amazonUserA.getId(), 4L, new BigDecimal("200.00")},
                new Object[]{amazonUserB.getId(), 1L, new BigDecimal("40.00")}
        ));
        when(merchantRepository.findAllById(any())).thenReturn(List.of(amazonUserA, amazonUserB));
        when(transactionRepository.platformCategorySpendTotals(any(), any())).thenReturn(List.of());
        when(categoryRepository.findAllById(any())).thenReturn(List.of());

        PlatformAnalyticsDto result = service.platformAnalytics();

        assertThat(result.topMerchants()).hasSize(1);
        assertThat(result.topMerchants().get(0).merchantName()).isEqualTo("Amazon");
        assertThat(result.topMerchants().get(0).totalSpend()).isEqualByComparingTo("240.00");
        assertThat(result.topMerchants().get(0).transactionCount()).isEqualTo(5L);
    }

    @Test
    void platformAnalytics_toleratesNoSpendAtAll() {
        when(transactionRepository.platformCategorySpendTotals(any(), any())).thenReturn(List.of());
        when(transactionRepository.platformMerchantSpendTotals(any(), any())).thenReturn(List.of());
        when(categoryRepository.findAllById(any())).thenReturn(List.of());
        when(merchantRepository.findAllById(any())).thenReturn(List.of());

        PlatformAnalyticsDto result = service.platformAnalytics();

        assertThat(result.topCategories()).isEmpty();
        assertThat(result.topMerchants()).isEmpty();
    }
}
