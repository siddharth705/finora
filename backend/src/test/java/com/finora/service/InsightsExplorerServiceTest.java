package com.finora.service;

import com.finora.dto.InsightsExplorerDto;
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

class InsightsExplorerServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private InsightsExplorerService service;
    private final UUID userId = UUID.randomUUID();
    private Category dining;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        BudgetRepository budgetRepository = mock(BudgetRepository.class);
        userRepository = mock(UserRepository.class);
        TransactionGraphService transactionGraphService = mock(TransactionGraphService.class);
        when(transactionGraphService.ccPaymentFromTransactionIds(any())).thenReturn(Set.of());

        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(userId);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount));

        InsightsService insightsService = new InsightsService(transactionRepository, accountRepository, categoryRepository, budgetRepository,
                userRepository, transactionGraphService);
        service = new InsightsExplorerService(insightsService, userRepository);

        dining = new Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setUserId(userId);
        dining.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(dining));
        when(userRepository.existsById(userId)).thenReturn(true);
    }

    private Transaction expense(BigDecimal amount, String merchant) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setCategoryId(dining.getId());
        t.setTxnDate(LocalDate.of(2026, 7, 15));
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(merchant);
        t.setMerchant(merchant);
        return t;
    }

    @Test
    void trace_isEmpty_whenTheUserDoesNotExist() {
        UUID unknownUser = UUID.randomUUID();
        when(userRepository.existsById(unknownUser)).thenReturn(false);

        assertThat(service.trace(unknownUser)).isEmpty();
    }

    @Test
    void trace_returnsAnAllNullTrace_whenTheUserHasNoReportableExpenses() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of());

        InsightsExplorerDto.Trace trace = service.trace(userId).orElseThrow();

        assertThat(trace.reportingMonth()).isNull();
        assertThat(trace.totalSpend()).isNull();
        assertThat(trace.topCategory()).isNull();
        assertThat(trace.topMerchant()).isNull();
    }

    @Test
    void trace_sumsTotalSpend_acrossAllCurrentMonthExpenses() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(
                expense(new BigDecimal("500"), "Zomato"),
                expense(new BigDecimal("300"), "Swiggy")));

        InsightsExplorerDto.Trace trace = service.trace(userId).orElseThrow();

        assertThat(trace.reportingMonth()).isEqualTo("2026-07");
        assertThat(trace.totalSpend().amount()).isEqualByComparingTo("800");
        assertThat(trace.totalSpend().categoryCount()).isEqualTo(1);
        assertThat(trace.totalSpend().transactions()).hasSize(2);
    }

    @Test
    void trace_namesTheTopCategory_withTheTransactionsThatMadeIt() {
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(
                expense(new BigDecimal("500"), "Zomato")));

        InsightsExplorerDto.Trace trace = service.trace(userId).orElseThrow();

        assertThat(trace.topCategory().category()).isEqualTo("Dining");
        assertThat(trace.topCategory().amount()).isEqualByComparingTo("500");
        assertThat(trace.topCategory().transactions()).hasSize(1);
        assertThat(trace.topCategory().transactions().get(0).reportableAmount()).isEqualByComparingTo("500");
    }

    @Test
    void trace_namesTheTopMerchant_fallingBackToUncategorized_whenNoCategoryIsResolved() {
        Transaction uncategorized = expense(new BigDecimal("500"), "Zomato");
        uncategorized.setCategoryId(null);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(uncategorized));

        InsightsExplorerDto.Trace trace = service.trace(userId).orElseThrow();

        assertThat(trace.topCategory().category()).isEqualTo("Uncategorized");
        assertThat(trace.topMerchant().merchant()).isEqualTo("Zomato");
        assertThat(trace.topMerchant().amount()).isEqualByComparingTo("500");
    }

    @Test
    void trace_reportsARefundNettedAmount_asWhatActuallyCounted_notTheRawAmount() {
        Transaction purchase = expense(new BigDecimal("500"), "Zomato");
        Transaction refund = new Transaction();
        ReflectionTestUtils.setField(refund, "id", UUID.randomUUID());
        refund.setUserId(userId);
        refund.setTxnDate(LocalDate.of(2026, 7, 20));
        refund.setAmount(new BigDecimal("200"));
        refund.setTxnType(Transaction.Type.INCOME);
        refund.setRefundOfTransactionId(purchase.getId());
        refund.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(purchase, refund));

        InsightsExplorerDto.Trace trace = service.trace(userId).orElseThrow();

        assertThat(trace.totalSpend().amount()).isEqualByComparingTo("300");
        InsightsExplorerDto.TracedTransaction traced = trace.totalSpend().transactions().get(0);
        assertThat(traced.rawAmount()).isEqualByComparingTo("500");
        assertThat(traced.reportableAmount()).isEqualByComparingTo("300");
    }
}
