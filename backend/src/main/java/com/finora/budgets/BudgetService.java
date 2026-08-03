package com.finora.budgets;

import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public BudgetService(BudgetRepository budgetRepository, CategoryRepository categoryRepository,
                          TransactionRepository transactionRepository, UserRepository userRepository,
                          AuditService auditService) {
        this.budgetRepository = budgetRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<BudgetDto> listForUser(UUID userId) {
        Map<UUID, Category> categoriesById = categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));

        // Bug fix: this used to call the bare YearMonth.now(), which resolves against the
        // server's JVM default timezone rather than the user's own -- same class of bug already
        // fixed in NetWorthService.saveSnapshotForToday() and GoalService's contribution dates.
        // A user meaningfully ahead of or behind the server's zone could have "this month's"
        // spending computed against the wrong month right around either end of the month, from
        // their own point of view.
        YearMonth thisMonth = YearMonth.now(safeZoneId(userId));
        LocalDate from = thisMonth.atDay(1);
        LocalDate to = thisMonth.atEndOfMonth();

        // Bug fix: Transaction.categoryId is nullable (uncategorized expenses have none), but
        // Collectors.groupingBy throws NullPointerException ("element cannot be mapped to a null
        // key") on a null key -- this used to crash the whole budgets list for any user with even
        // one uncategorized expense this month. Safe to filter out: a budget's categoryId is
        // never null, so an uncategorized transaction could never match one of these lookups
        // below anyway (see DashboardService.summarize()'s spendByCategoryId for the same fix).
        Map<UUID, BigDecimal> spendByCategory = transactionRepository
                .findByUserIdAndTxnDateBetween(userId, from, to).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && !t.isTransfer() && t.getIsDuplicateOf() == null
                        && t.getCategoryId() != null)
                .collect(Collectors.groupingBy(Transaction::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        return budgetRepository.findByUserId(userId).stream().map(b -> {
            Category cat = categoriesById.get(b.getCategoryId());
            BigDecimal spent = spendByCategory.getOrDefault(b.getCategoryId(), BigDecimal.ZERO);
            return new BudgetDto(b.getId(), b.getCategoryId(), cat != null ? cat.getName() : "Unknown",
                    b.getMonthlyLimit(), spent);
        }).toList();
    }

    /**
     * Bug fix: this had no @Transactional at all -- category-creation and budget-creation/update
     * are two separate writes that should either both happen or neither; without it, a failure
     * partway through (e.g. the budget save failing after the category save already committed)
     * could leave an orphan category with no budget behind it.
     *
     * Also closes a real, previously unhandled race: findByUserIdAndCategoryId().orElseGet(new)
     * then save() is a check-then-act -- two concurrent upserts for the same category could both
     * see no existing budget and both try to INSERT. budgets(user_id, category_id) already has a
     * UNIQUE constraint (V1__init_schema.sql) preventing the actual duplicate-row corruption, but
     * nothing here ever caught the resulting DataIntegrityViolationException -- the loser of that
     * race got an unhandled 500 instead of the "update the existing budget" behavior upsert()'s
     * own name promises. Same fix shape as NetWorthService.saveSnapshotForToday()'s equivalent
     * bug: catch it and update the concurrent winner's row instead.
     */
    @Transactional
    public BudgetDto upsert(UUID userId, BudgetDto.UpsertRequest req) {
        Category category = categoryRepository.findByUserIdAndName(userId, req.categoryName())
                .orElseGet(() -> {
                    Category c = new Category();
                    c.setUserId(userId);
                    c.setName(req.categoryName());
                    return categoryRepository.save(c);
                });

        Budget budget = budgetRepository.findByUserIdAndCategoryId(userId, category.getId())
                .orElseGet(Budget::new);
        budget.setUserId(userId);
        budget.setCategoryId(category.getId());
        budget.setMonthlyLimit(req.monthlyLimit());
        Budget saved;
        try {
            saved = budgetRepository.save(budget);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            saved = budgetRepository.findByUserIdAndCategoryId(userId, category.getId()).orElseThrow(() -> e);
            saved.setMonthlyLimit(req.monthlyLimit());
            saved = budgetRepository.save(saved);
        }
        // Bug fix: this service never called AuditService at all, unlike every other mutating
        // service in the codebase (see AuditService's own class doc, which names Budget/Goal as
        // the known-remaining gap) -- a budget limit change was invisible in the general activity
        // feed with no way to answer "who/when changed this budget."
        auditService.record(userId, "BUDGET_UPSERTED", "Budget", saved.getId(),
                Map.of("category", category.getName(), "monthlyLimit", req.monthlyLimit()));
        return new BudgetDto(saved.getId(), category.getId(), category.getName(), saved.getMonthlyLimit(), BigDecimal.ZERO);
    }

    /** Same defensive fallback DashboardService.safeZoneId()/NetWorthService's own copy use --
     *  timezone has no format validation on the settings-update path, so this falls back to the
     *  column's own default (V11 migration) rather than an uncaught DateTimeException. */
    private ZoneId safeZoneId(UUID userId) {
        String timezone = userRepository.findById(userId).map(User::getTimezone).orElse(null);
        if (timezone == null) return ZoneId.of("Asia/Kolkata");
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
