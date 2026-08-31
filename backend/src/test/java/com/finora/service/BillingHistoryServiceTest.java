package com.finora.service;

import com.finora.dto.BillingDtos.BillingHistoryEntryDto;
import com.finora.entity.Payment;
import com.finora.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-28 PR4-B. {@link BillingHistoryService} has exactly one real caller today (no payment gateway
 * exists yet, §10) -- an empty repository, which must resolve to an empty list, not an error.
 */
class BillingHistoryServiceTest {

    private PaymentRepository paymentRepository;
    private BillingHistoryService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        service = new BillingHistoryService(paymentRepository);
    }

    private Payment payment(BigDecimal amount, String status) {
        Payment p = new Payment();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setAmount(amount);
        p.setCurrency("INR");
        p.setProvider("RAZORPAY");
        p.setStatus(status);
        ReflectionTestUtils.setField(p, "createdAt", Instant.now());
        return p;
    }

    @Test
    void history_returnsAnEmptyList_whenTheUserHasNoPayments() {
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertThat(service.history(userId)).isEmpty();
    }

    @Test
    void history_mapsEveryPaymentRowToItsDto() {
        Payment p = payment(BigDecimal.valueOf(499), Payment.STATUS_SUCCESS);
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(p));

        List<BillingHistoryEntryDto> result = service.history(userId);

        assertThat(result).hasSize(1);
        BillingHistoryEntryDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(p.getId());
        assertThat(dto.amount()).isEqualByComparingTo(BigDecimal.valueOf(499));
        assertThat(dto.currency()).isEqualTo("INR");
        assertThat(dto.provider()).isEqualTo("RAZORPAY");
        assertThat(dto.status()).isEqualTo(Payment.STATUS_SUCCESS);
        assertThat(dto.createdAt()).isEqualTo(p.getCreatedAt());
    }
}
