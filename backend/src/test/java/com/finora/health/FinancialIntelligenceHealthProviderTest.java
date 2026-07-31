package com.finora.health;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinancialIntelligenceHealthProviderTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final FinancialIntelligenceHealthProvider provider = new FinancialIntelligenceHealthProvider(transactionRepository);

    private Transaction transactionWithId() {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        return t;
    }

    @Test
    void check_reportsUp_whenThePlatformHasNoTransactionsYet() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsUp_whenEveryReconciliationPointerResolves() {
        Transaction original = transactionWithId();
        Transaction duplicate = transactionWithId();
        duplicate.setIsDuplicateOf(original.getId());

        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(duplicate)));
        when(transactionRepository.findAllById(any())).thenReturn(List.of(original));

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDown_whenAReconciliationPointerIsDangling() {
        Transaction danglingRefundPointer = transactionWithId();
        danglingRefundPointer.setRefundOfTransactionId(UUID.randomUUID()); // never resolved below

        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(danglingRefundPointer)));
        // The referenced expense no longer exists -- findAllById() comes back short.
        when(transactionRepository.findAllById(any())).thenReturn(List.of());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.detail()).contains("dangling");
    }
}
