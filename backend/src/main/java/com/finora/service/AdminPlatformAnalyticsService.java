package com.finora.service;

import com.finora.dto.AdminDtos.PlatformAnalyticsDto;
import com.finora.dto.AdminDtos.PlatformCategorySpendDto;
import com.finora.dto.AdminDtos.PlatformMerchantSpendDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-wide spend analytics for the admin console -- the aggregate view AnalyticsController's
 * per-user topCategories/topMerchants have no equivalent of (spec §5.7 scopes that controller to
 * CurrentUser only, by design).
 *
 * Two-step aggregation rather than a single grouped query: Transaction.categoryId/merchantId are
 * plain UUID FK columns, not mapped @ManyToOne associations (see Category/Merchant entities), so
 * there's no JPQL join path from Transaction straight to a name. Step one groups by id in SQL
 * (TransactionRepository.platformCategorySpendTotals/platformMerchantSpendTotals -- cheap,
 * indexed); step two batch-resolves those ids to names via findAllById and re-groups by NAME in
 * Java, since categories/merchants are commonly seeded with the same names across different
 * users' own private rows (isSystem categories, same pattern AdminMerchantStatsService's
 * canonicalName grouping already relies on for merchants) -- without this second grouping pass,
 * "platform-wide top categories" would just be whichever single user's private "Groceries" row
 * happened to have the most spend, not a real aggregate across everyone's Groceries.
 */
@Service
public class AdminPlatformAnalyticsService {

    private static final int TOP_LIMIT = 10;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MerchantRepository merchantRepository;

    public AdminPlatformAnalyticsService(TransactionRepository transactionRepository,
                                          CategoryRepository categoryRepository,
                                          MerchantRepository merchantRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.merchantRepository = merchantRepository;
    }

    @Transactional(readOnly = true)
    public PlatformAnalyticsDto platformAnalytics() {
        return new PlatformAnalyticsDto(topCategories(), topMerchants());
    }

    private List<PlatformCategorySpendDto> topCategories() {
        List<Object[]> rows = transactionRepository.platformCategorySpendTotals(
                Transaction.ReconciliationStatus.REFUND, Transaction.Type.EXPENSE);
        if (rows.isEmpty()) return List.of();

        List<UUID> categoryIds = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, String> names = new HashMap<>();
        for (Category c : categoryRepository.findAllById(categoryIds)) names.put(c.getId(), c.getName());

        // Re-group by name -- see class comment for why id-level grouping alone isn't the right
        // platform-wide answer.
        Map<String, long[]> countByName = new LinkedHashMap<>();
        Map<String, BigDecimal> spendByName = new HashMap<>();
        for (Object[] row : rows) {
            String name = names.getOrDefault((UUID) row[0], "Unknown category");
            long count = (Long) row[1];
            BigDecimal spend = (BigDecimal) row[2];
            countByName.merge(name, new long[]{count}, (a, b) -> new long[]{a[0] + b[0]});
            spendByName.merge(name, spend, BigDecimal::add);
        }

        return countByName.keySet().stream()
                .map(name -> new PlatformCategorySpendDto(name, spendByName.get(name), countByName.get(name)[0]))
                .sorted(Comparator.comparing(PlatformCategorySpendDto::totalSpend).reversed())
                .limit(TOP_LIMIT)
                .toList();
    }

    private List<PlatformMerchantSpendDto> topMerchants() {
        List<Object[]> rows = transactionRepository.platformMerchantSpendTotals(
                Transaction.ReconciliationStatus.REFUND, Transaction.Type.EXPENSE);
        if (rows.isEmpty()) return List.of();

        List<UUID> merchantIds = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, String> names = new HashMap<>();
        for (Merchant m : merchantRepository.findAllById(merchantIds)) names.put(m.getId(), m.getCanonicalName());

        Map<String, long[]> countByName = new LinkedHashMap<>();
        Map<String, BigDecimal> spendByName = new HashMap<>();
        for (Object[] row : rows) {
            String name = names.getOrDefault((UUID) row[0], "Unknown merchant");
            long count = (Long) row[1];
            BigDecimal spend = (BigDecimal) row[2];
            countByName.merge(name, new long[]{count}, (a, b) -> new long[]{a[0] + b[0]});
            spendByName.merge(name, spend, BigDecimal::add);
        }

        return countByName.keySet().stream()
                .map(name -> new PlatformMerchantSpendDto(name, spendByName.get(name), countByName.get(name)[0]))
                .sorted(Comparator.comparing(PlatformMerchantSpendDto::totalSpend).reversed())
                .limit(TOP_LIMIT)
                .toList();
    }
}
