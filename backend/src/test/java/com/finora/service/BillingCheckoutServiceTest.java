package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
    private final PlanChangeRepository planChangeRepository = mock(PlanChangeRepository.class);
    private final RazorpaySubscriptionGateway gateway = mock(RazorpaySubscriptionGateway.class);
    private final RazorpayProperties properties = new RazorpayProperties();
    private BillingCheckoutService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties.setKeyId("rzp_test_123");
        service = new BillingCheckoutService(planRepository, billingPriceRepository,
                subscriptionOrderRepository, subscriptionRepository, planChangeRepository, gateway, properties);

        // Plan.id has no public setter (@GeneratedValue) -- same reflection-based construction
        // EntitlementServiceTest already uses for the same reason.
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", planId);
        premium.setCode("PREMIUM");
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));
        when(gateway.isConfigured()).thenReturn(true);
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
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

    @Test
    void refusesWhenTheUserAlreadyHasALiveRazorpaySubscription() {
        Subscription existing = new Subscription();
        existing.setRazorpaySubscriptionId("sub_already_paying");
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);

        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }

    @Test
    void allowsCheckoutWhenTheExistingSubscriptionIsStillOnFree() {
        Subscription free = new Subscription(); // razorpaySubscriptionId left null, matching FREE
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(free));
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
    }

    @Test
    void changePlanRefusesMovingToFree() {
        assertThatThrownBy(() -> service.changePlan(userId, "FREE", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cancel");
    }

    @Test
    void changePlanRefusesWhenTheUserHasNoBillingSubscriptionYet() {
        Subscription free = new Subscription(); // razorpaySubscriptionId left null
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(UUID.randomUUID());
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));

        assertThatThrownBy(() -> service.changePlan(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("checkout");
    }

    @Test
    void changePlanRefusesACycleOnlyChangeAtTheSameTier() {
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.changePlan(userId, "PLUS", "YEARLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not supported");
    }

    @Test
    void changePlanIsANoOpWhenAlreadyOnTheRequestedPlanAndCycle() {
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        service.changePlan(userId, "PLUS", "MONTHLY");

        verify(gateway, never()).updateSubscription(any(), any(), anyBoolean());
        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }

    @Test
    void changePlanSchedulesADowngradeAndRecordsAPlanChangeRow() {
        UUID premiumPlanId = planId; // reuse the PREMIUM plan already stubbed in setUp()
        UUID plusPlanId = UUID.randomUUID();
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findByCode("PLUS")).thenReturn(Optional.of(plus));
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(premiumPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_existing");
        subscription.setRenewalDate(LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        BillingPrice plusMonthly = new BillingPrice();
        plusMonthly.setPlanId(plusPlanId);
        plusMonthly.setBillingCycle("MONTHLY");
        plusMonthly.setPrice(new BigDecimal("399.00"));
        plusMonthly.setRazorpayPlanId("plan_plus_monthly");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(plusPlanId, "MONTHLY"))
                .thenReturn(Optional.of(plusMonthly));

        service.changePlan(userId, "PLUS", "MONTHLY");

        verify(gateway).updateSubscription("sub_existing", "plan_plus_monthly", true);
        verify(gateway, never()).createSubscription(any(), any(), anyMap());

        ArgumentCaptor<PlanChange> changeCaptor = ArgumentCaptor.forClass(PlanChange.class);
        verify(planChangeRepository).save(changeCaptor.capture());
        PlanChange change = changeCaptor.getValue();
        assertThat(change.getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(change.getFromPlanId()).isEqualTo(premiumPlanId);
        assertThat(change.getToPlanId()).isEqualTo(plusPlanId);
        assertThat(change.getReason()).isEqualTo(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        assertThat(change.getEffectiveAt()).isEqualTo(
                LocalDate.of(2026, 10, 5).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

        // Downgrade never touches the subscriptions row directly -- reconciliation happens later,
        // at the next subscription.charged webhook (Plan 1's existing handleCharged), not here.
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void changePlanInitiatesAnUpgradeByCreatingANewRazorpaySubscription() {
        UUID plusPlanId = UUID.randomUUID(); // deliberately NOT the "planId" field from setUp() --
                                              // this test is the current plan, distinct from setUp()'s premium
        UUID premiumPlanId = UUID.randomUUID();
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", premiumPlanId);
        premium.setCode("PREMIUM");
        when(planRepository.findByCode("PREMIUM")).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_old");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        BillingPrice premiumMonthly = new BillingPrice();
        premiumMonthly.setPlanId(premiumPlanId);
        premiumMonthly.setBillingCycle("MONTHLY");
        premiumMonthly.setPrice(new BigDecimal("799.00"));
        premiumMonthly.setRazorpayPlanId("plan_premium_monthly");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(premiumPlanId, "MONTHLY"))
                .thenReturn(Optional.of(premiumMonthly));
        when(gateway.createSubscription(eq("plan_premium_monthly"), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_new", "created"));

        service.changePlan(userId, "PREMIUM", "MONTHLY");

        verify(gateway, never()).updateSubscription(any(), any(), anyBoolean());
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
        verify(planChangeRepository, never()).save(any());

        ArgumentCaptor<com.finora.entity.SubscriptionOrder> orderCaptor =
                ArgumentCaptor.forClass(com.finora.entity.SubscriptionOrder.class);
        verify(subscriptionOrderRepository).save(orderCaptor.capture());
        com.finora.entity.SubscriptionOrder order = orderCaptor.getValue();
        assertThat(order.getUserId()).isEqualTo(userId);
        assertThat(order.getPlanId()).isEqualTo(premiumPlanId);
        assertThat(order.getRazorpaySubscriptionId()).isEqualTo("sub_new");
        assertThat(order.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_PENDING);

        // The existing subscriptions row is untouched until the new subscription's own activation
        // webhook confirms real payment (design spec §6.5 step 2) -- this call must not mutate it.
        assertThat(subscription.getPlanId()).isEqualTo(plusPlanId);
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo("sub_old");
    }
}
