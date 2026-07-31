package com.finora.budgets;

import com.finora.entity.Budget;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Previously had zero coverage at all -- which is exactly how the bug fixed below (a
 * Collectors.groupingBy NullPointerException on an uncategorized expense's null categoryId,
 * the same root cause DashboardServiceTest independently caught in DashboardService.summarize())
 * went unnoticed here for as long as it did. See BudgetService.listForUser()'s doc comment.
 *
 * Also covers two further bugs found in a later pass: listForUser() used to resolve "this month"
 * against the server's own timezone rather than the user's (same class of bug already fixed in
 * NetWorthService/GoalService), and upsert() never handled the budgets(user_id, category_id)
 * UNIQUE constraint being violated by a concurrent request, surfacing a raw 500 instead of
 * updating the existing budget the way its own name promises.
 */
class BudgetServiceTest {

    private BudgetRepository budgetRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private BudgetService budgetService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        budgetRepository = mock(BudgetRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        budgetService = new BudgetService(budgetRepository, categoryRepository, transactionRepository, userRepository);
        when(userRepository.findById(any())).thenReturn(Optional.empty());
    }

    private Category category(String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setUserId(userId);
        c.setName(name);
        return c;
    }

    private Budget budget(UUID categoryId, BigDecimal monthlyLimit) {
        Budget b = new Budget();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setUserId(userId);
        b.setCategoryId(categoryId);
        b.setMonthlyLimit(monthlyLimit);
        return b;
    }

    private Transaction expense(BigDecimal amount, UUID categoryId) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(LocalDate.now());
        t.setCategoryId(categoryId);
        return t;
    }

    @Test
    void listForUser_matchesSpendToTheRightBudgetByCategory() {
        Category dining = category("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining));
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget(dining.getId(), new BigDecimal("5000.00"))));
        when(transactionRepository.findByUserIdAndTxnDateBetween(any(), any(), any()))
                .thenReturn(List.of(expense(new BigDecimal("1200.00"), dining.getId())));

        List<BudgetDto> result = budgetService.listForUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryName()).isEqualTo("Dining");
        assertThat(result.get(0).spentThisMonth()).isEqualByComparingTo("1200.00");
    }

    @Test
    void listForUser_doesNotCrash_whenAnExpenseHasNoCategory() {
        // Regression test: Transaction.categoryId is nullable (an uncategorized expense has none),
        // but Collectors.groupingBy(Transaction::getCategoryId, ...) throws "element cannot be
        // mapped to a null key" the moment it sees one -- this used to take down the whole
        // budgets list for any user with even one uncategorized expense this month.
        Category dining = category("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining));
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of(budget(dining.getId(), new BigDecimal("5000.00"))));
        when(transactionRepository.findByUserIdAndTxnDateBetween(any(), any(), any()))
                .thenReturn(List.of(
                        expense(new BigDecimal("1200.00"), dining.getId()),
                        expense(new BigDecimal("300.00"), null) // uncategorized -- categoryId is null
                ));

        assertThatCode(() -> budgetService.listForUser(userId)).doesNotThrowAnyException();

        List<BudgetDto> result = budgetService.listForUser(userId);
        assertThat(result).hasSize(1);
        // The uncategorized 300.00 can't match any budget (a Budget always has a real
        // categoryId) -- Dining's spend must reflect only its own transaction.
        assertThat(result.get(0).spentThisMonth()).isEqualByComparingTo("1200.00");
    }

    @Test
    void listForUser_resolvesThisMonth_inTheUsersOwnTimezone_notTheServersDefault() {
        User user = new User();
        // UTC+14 -- as far ahead of UTC as any real IANA zone gets, so its "this month" is
        // essentially guaranteed to potentially differ from the system default zone (almost
        // certainly UTC in CI) right around a month boundary -- chosen so this test is
        // meaningful rather than coincidentally passing regardless of the fix.
        user.setTimezone("Pacific/Kiritimati");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(userId)).thenReturn(List.of());

        budgetService.listForUser(userId);

        YearMonth expected = YearMonth.now(ZoneId.of("Pacific/Kiritimati"));
        verify(transactionRepository).findByUserIdAndTxnDateBetween(
                userId, expected.atDay(1), expected.atEndOfMonth());
    }

    @Test
    void upsert_isTransactional() throws NoSuchMethodException {
        var transactional = BudgetService.class.getMethod("upsert", UUID.class, BudgetDto.UpsertRequest.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transactional).isNotNull();
    }

    @Test
    void upsert_recoversGracefully_whenAConcurrentUpsertWinsTheUniqueConstraintRace() {
        // budgets(user_id, category_id) already has a UNIQUE constraint (V1__init_schema.sql) --
        // this simulates losing that race (a concurrent upsert for the same category created the
        // row first) and proves upsert() now updates that row instead of surfacing the raw
        // DataIntegrityViolationException.
        Category dining = category("Dining");
        when(categoryRepository.findByUserIdAndName(userId, "Dining")).thenReturn(Optional.of(dining));

        Budget winnersRow = budget(dining.getId(), new BigDecimal("3000.00"));
        when(budgetRepository.findByUserIdAndCategoryId(userId, dining.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        when(budgetRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        BudgetDto result = budgetService.upsert(userId, new BudgetDto.UpsertRequest("Dining", new BigDecimal("6000.00")));

        assertThat(result.monthlyLimit()).isEqualByComparingTo("6000.00");
        assertThat(winnersRow.getMonthlyLimit()).isEqualByComparingTo("6000.00");
        verify(budgetRepository, times(2)).save(any());
    }
}
