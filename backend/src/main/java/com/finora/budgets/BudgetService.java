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
import com.finora.service.RefundNetting;
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
        //
        // Bug fix: this was the one known-remaining copy of the one-sided refund filter
        // RefundNetting replaced everywhere else (see that class's own doc comment, "BudgetService
        // deliberately still does not use this") -- a refunded purchase's expense leg was counted
        // in full here even after ReportService/AnalyticsService/DashboardService had all been
        // fixed to net it. RefundNetting.reportable() drops the income leg (same as the old
        // filter's job); reportableAmount() nets any matched refund/reversal off the expense.
        RefundNetting refunds = refundsFor(userId);
        Map<UUID, BigDecimal> spendByCategory = RefundNetting.reportable(
                        transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && t.getCategoryId() != null)
                .collect(Collectors.groupingBy(Transaction::getCategoryId,
                        Collectors.reducing(BigDecimal.ZERO, refunds::reportableAmount, BigDecimal::add)));

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
     * <p>findByUserIdAndCategoryId().orElseGet(new) then save() is a check-then-act, and
     * budgets(user_id, category_id) has a UNIQUE constraint (V1__init_schema.sql) that stops two
     * concurrent upserts corrupting the table. This method used to wrap the save in a
     * try/catch(DataIntegrityViolationException) claiming to convert that race into an update of
     * the winner's row. <b>That catch was unreachable and has been removed.</b> Budget extends
     * BaseEntity, whose id is {@code @GeneratedValue} on a UUID assigned in memory, so save()
     * routes through merge() and the INSERT is deferred to the flush at commit -- which happens
     * after this method has already returned. The try block completed without exception every
     * time, and the recovery inside the catch could not have worked anyway: by the time a
     * constraint violation exists the transaction is already rollback-only, and re-reading and
     * re-saving inside it fails too (the rule MerchantNormalizationEngine.resolve states as "no
     * handling un-poisons it").
     *
     * <p>What actually happens on a lost race, then and now: the violation surfaces at commit,
     * Spring translates it to DataIntegrityViolationException, and GlobalExceptionHandler answers
     * 409 CONFLICT with "refresh and try again". That is a correct, honest response to a genuine
     * double-submit. Dead code plus a comment asserting a behaviour the code does not have is
     * strictly worse than neither, because it stops the next reader re-investigating.
     */
    @Transactional
    public BudgetDto upsert(UUID userId, BudgetDto.UpsertRequest req) {
        // Bug 16: same case-sensitive lookup CategorizationService.resolveOrCreateCategory had --
        // see CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc's own doc comment for
        // what this does and does not close, including why it returns a list. Without it,
        // budgeting "dining" after already having a "Dining" category from an import creates a
        // second row, and the existing budget attaches to only one of the two -- showing the
        // wrong spend for the category the user thinks they set.
        String categoryName = req.categoryName().trim();
        List<Category> categoryMatches = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, categoryName);
        Category category;
        if (!categoryMatches.isEmpty()) {
            category = categoryMatches.get(0);
        } else {
            category = new Category();
            category.setUserId(userId);
            category.setName(categoryName);
            category = categoryRepository.save(category);
        }

        Budget budget = budgetRepository.findByUserIdAndCategoryId(userId, category.getId())
                .orElseGet(Budget::new);
        budget.setUserId(userId);
        budget.setCategoryId(category.getId());
        budget.setMonthlyLimit(req.monthlyLimit());
        Budget saved = budgetRepository.save(budget);
        // Bug fix: this service never called AuditService at all, unlike every other mutating
        // service in the codebase (see AuditService's own class doc, which names Budget/Goal as
        // the known-remaining gap) -- a budget limit change was invisible in the general activity
        // feed with no way to answer "who/when changed this budget."
        auditService.record(userId, "BUDGET_UPSERTED", "Budget", saved.getId(),
                Map.of("category", category.getName(), "monthlyLimit", req.monthlyLimit()));
        // Bug 35 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). This hardcoded BigDecimal.ZERO
        // regardless of what the category had actually accrued this month -- listForUser computes
        // the real figure, this didn't. A client that updates local state from the mutation
        // response (the standard optimistic-update pattern) showed 0% progress on a category
        // that was already over budget, until an unrelated refetch corrected it -- most visibly on
        // editing an EXISTING budget's limit, the common case.
        BigDecimal spent = spentThisMonth(userId, category.getId());
        return new BudgetDto(saved.getId(), category.getId(), category.getName(), saved.getMonthlyLimit(), spent);
    }

    /** Same query/filter shape as {@link #listForUser}'s spendByCategory map, scoped to one
     *  category -- upsert() only ever needs one, and building the full per-category map here
     *  would be strictly more work for no benefit. */
    private BigDecimal spentThisMonth(UUID userId, UUID categoryId) {
        YearMonth thisMonth = YearMonth.now(safeZoneId(userId));
        LocalDate from = thisMonth.atDay(1);
        LocalDate to = thisMonth.atEndOfMonth();
        RefundNetting refunds = refundsFor(userId);
        return RefundNetting.reportable(transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to)).stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE && categoryId.equals(t.getCategoryId()))
                .map(refunds::reportableAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The refund/reversal offsets for this user, queried across all time rather than the current
     * month's window -- a refund routinely arrives in a later month than the purchase it
     * reverses (BH-005), so netting against only the in-window rows would leave every
     * cross-month refund uncorrected, which is most of them. Same pattern as
     * {@code ReportService}/{@code AnalyticsService}; see {@link RefundNetting}'s own class
     * comment for why this table specifically was the one known-remaining gap.
     */
    private RefundNetting refundsFor(UUID userId) {
        return RefundNetting.from(transactionRepository.findByUserIdAndReconciliationStatusIn(
                userId, List.of(Transaction.ReconciliationStatus.REFUND, Transaction.ReconciliationStatus.REVERSAL)));
    }

    /** Delegates to {@link com.finora.util.UserZone} -- one of four hand-copied implementations,
     *  see that class for why they were consolidated. */
    private ZoneId safeZoneId(UUID userId) {
        return com.finora.util.UserZone.forUser(userRepository, userId);
    }
}
