package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingCheckoutServiceTest {

    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final BillingPriceRepository billingPriceRepository = mock(BillingPriceRepository.class);
    private final SubscriptionOrderRepository subscriptionOrderRepository = mock(SubscriptionOrderRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final RazorpaySubscriptionGateway gateway = mock(RazorpaySubscriptionGateway.class);
    private final RazorpayProperties properties = new RazorpayProperties();
    private BillingCheckoutService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties.setKeyId("rzp_test_123");
        service = new BillingCheckoutService(planRepository, billingPriceRepository,
                subscriptionOrderRepository, subscriptionRepository, gateway, properties);

        // Plan.id has no public setter (@GeneratedValue) -- same reflection-based construction
        // EntitlementServiceTest already uses for the same reason.
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", planId);
        premium.setCode("PREMIUM");
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));
        when(gateway.isConfigured()).thenReturn(true);
    }

    @Test
    void createsRazorpaySubscriptionAndWritesAPendingOrder() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle(BillingPrice.CYCLE_MONTHLY);
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));
        when(gateway.createSubscription(eq("plan_razorpay_123"), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_razorpay_123", "created"));

        CheckoutResponseDto response = service.checkout(userId, "PREMIUM", "MONTHLY");

        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_razorpay_123");
        assertThat(response.keyId()).isEqualTo("rzp_test_123");

        ArgumentCaptor<com.finora.entity.SubscriptionOrder> orderCaptor =
                ArgumentCaptor.forClass(com.finora.entity.SubscriptionOrder.class);
        verify(subscriptionOrderRepository).save(orderCaptor.capture());
        com.finora.entity.SubscriptionOrder saved = orderCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        assertThat(saved.getRazorpaySubscriptionId()).isEqualTo("sub_razorpay_123");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("799.00"));
    }

    @Test
    void refusesWhenNoBillingPriceExistsForThePlanAndCycle() {
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void refusesWhenRazorpayIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);
    }
}
