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
    void checkoutResumesAnExistingPendingOrderForTheSamePlanAndCycle() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle("MONTHLY");
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));

        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(planId);
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setRazorpaySubscriptionId("sub_already_created");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));

        CheckoutResponseDto response = service.checkout(userId, "PREMIUM", "MONTHLY");

        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_already_created");
        verify(gateway, never()).createSubscription(any(), any(), anyMap());
        verify(subscriptionOrderRepository, never()).save(any());
    }

    @Test
    void checkoutRefusesADifferentPlanWhileAnotherIsPending() {
        BillingPrice price = new BillingPrice();
        price.setPlanId(planId);
        price.setBillingCycle("MONTHLY");
        price.setPrice(new BigDecimal("799.00"));
        price.setRazorpayPlanId("plan_razorpay_123");
        when(billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(eq(planId), eq("MONTHLY")))
                .thenReturn(Optional.of(price));

        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(UUID.randomUUID()); // a DIFFERENT plan than the one being requested
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setRazorpaySubscriptionId("sub_other_plan");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);

        verify(gateway, never()).createSubscription(any(), any(), anyMap());
    }

    @Test
    void cancelPendingOrderMarksItAbandoned() {
        com.finora.entity.SubscriptionOrder order = new com.finora.entity.SubscriptionOrder();
        order.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(order));

        service.cancelPendingOrder(userId);

        assertThat(order.getStatus()).isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_ABANDONED);
        verify(subscriptionOrderRepository).save(order);
        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }

    @Test
    void cancelPendingOrderThrowsWhenNoneExists() {
        assertThatThrownBy(() -> service.cancelPendingOrder(userId))
                .isInstanceOf(ApiException.class);
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

        CheckoutResponseDto response = service.changePlan(userId, "PLUS", "MONTHLY");

        assertThat(response).isNull();
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

        CheckoutResponseDto response = service.changePlan(userId, "PLUS", "MONTHLY");

        assertThat(response).isNull();
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

        CheckoutResponseDto response = service.changePlan(userId, "PREMIUM", "MONTHLY");

        assertThat(response).isNotNull();
        assertThat(response.razorpaySubscriptionId()).isEqualTo("sub_new");
        assertThat(response.keyId()).isEqualTo("rzp_test_123");

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

    @Test
    void changePlanRefusesAnUpgradeWhenAPendingOrderAlreadyExistsForThisUser() {
        UUID plusPlanId = UUID.randomUUID();
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

        com.finora.entity.SubscriptionOrder existingOrder = new com.finora.entity.SubscriptionOrder();
        existingOrder.setPlanId(UUID.randomUUID()); // different plan than PREMIUM, still blocks
        existingOrder.setBillingCycle("MONTHLY");
        existingOrder.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(existingOrder));

        assertThatThrownBy(() -> service.changePlan(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class);

        verify(gateway, never()).createSubscription(any(), any(), anyMap());
        verify(subscriptionOrderRepository, never()).save(any());
    }

    @Test
    void mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber() {
        UUID plusPlanId = planId; // reuse the PREMIUM-labelled fixture id from setUp() -- code doesn't matter here, only that findById resolves it
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        // Real activated Razorpay rows always have both set together (RazorpayWebhookDispatcher.
        // handleActivated) -- this fixture omitted it, which the old hasBillingSubscription check
        // (razorpaySubscriptionId != null) never needed but the generalized payment_provider-based
        // check does.
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(true);
        subscription.setRenewalDate(LocalDate.of(2026, 10, 5));
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());
        // Mockito's default answer for an Optional-returning method is Optional.empty() -- no
        // pending order in this scenario, so subscriptionOrderRepository needs no stub here.

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("PLUS");
        assertThat(dto.planName()).isEqualTo("Plus");
        assertThat(dto.billingCycle()).isEqualTo("MONTHLY");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.renewalDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(dto.autoRenew()).isTrue();
        assertThat(dto.hasBillingSubscription()).isTrue();
        assertThat(dto.pendingChange()).isNull();
        assertThat(dto.pendingOrder()).isNull();
    }

    @Test
    void mySubscriptionSurfacesAPendingScheduledDowngrade() {
        UUID premiumPlanId = planId;
        UUID plusPlanId = UUID.randomUUID();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(premiumPlanId); // still Premium -- the downgrade hasn't applied yet
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange scheduled = new PlanChange();
        scheduled.setSubscriptionId(subscription.getId());
        scheduled.setFromPlanId(premiumPlanId);
        scheduled.setToPlanId(plusPlanId);
        scheduled.setEffectiveAt(java.time.Instant.parse("2026-10-05T00:00:00Z"));
        scheduled.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(scheduled));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNotNull();
        assertThat(dto.pendingChange().toPlanCode()).isEqualTo("PLUS");
        assertThat(dto.pendingChange().toPlanName()).isEqualTo("Plus");
        assertThat(dto.pendingChange().effectiveAt()).isEqualTo(java.time.Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void mySubscriptionOmitsAPendingChangeOnceItHasAlreadyApplied() {
        UUID plusPlanId = planId;
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusPlanId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        when(planRepository.findById(plusPlanId)).thenReturn(Optional.of(plus));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(plusPlanId); // already Plus -- the downgrade already reconciled
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setRazorpaySubscriptionId("sub_existing");
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));

        PlanChange applied = new PlanChange();
        applied.setSubscriptionId(subscription.getId());
        applied.setFromPlanId(UUID.randomUUID());
        applied.setToPlanId(plusPlanId); // matches the subscription's CURRENT plan
        applied.setEffectiveAt(java.time.Instant.now());
        applied.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of(applied));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingChange()).isNull();
    }

    @Test
    void mySubscriptionReflectsFreeWithNoBillingSubscription() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(planId);
        free.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));
        Plan freePlan = new Plan();
        ReflectionTestUtils.setField(freePlan, "id", planId);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        when(planRepository.findById(planId)).thenReturn(Optional.of(freePlan));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(free.getId())).thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.planCode()).isEqualTo("FREE");
        assertThat(dto.hasBillingSubscription()).isFalse();
        assertThat(dto.billingCycle()).isNull();
        assertThat(dto.renewalDate()).isNull();
    }

    @Test
    void mySubscriptionSurfacesAPendingOrderTheUserCanResumeOrCancel() {
        Subscription free = new Subscription();
        ReflectionTestUtils.setField(free, "id", UUID.randomUUID());
        free.setPlanId(planId);
        free.setStatus(Subscription.STATUS_ACTIVE);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(free));
        Plan freePlan = new Plan();
        ReflectionTestUtils.setField(freePlan, "id", planId);
        freePlan.setCode("FREE");
        freePlan.setName("Free");
        when(planRepository.findById(planId)).thenReturn(Optional.of(freePlan));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(free.getId())).thenReturn(List.of());

        UUID premiumPlanId = UUID.randomUUID();
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", premiumPlanId);
        premium.setCode("PREMIUM");
        premium.setName("Premium");
        when(planRepository.findById(premiumPlanId)).thenReturn(Optional.of(premium));

        com.finora.entity.SubscriptionOrder order = new com.finora.entity.SubscriptionOrder();
        order.setPlanId(premiumPlanId);
        order.setBillingCycle("YEARLY");
        order.setRazorpaySubscriptionId("sub_abandoned");
        order.setStatus(com.finora.entity.SubscriptionOrder.STATUS_PENDING);
        when(subscriptionOrderRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                userId, com.finora.entity.SubscriptionOrder.STATUS_PENDING))
                .thenReturn(Optional.of(order));

        var dto = service.mySubscription(userId);

        assertThat(dto.pendingOrder()).isNotNull();
        assertThat(dto.pendingOrder().planCode()).isEqualTo("PREMIUM");
        assertThat(dto.pendingOrder().planName()).isEqualTo("Premium");
        assertThat(dto.pendingOrder().billingCycle()).isEqualTo("YEARLY");
        assertThat(dto.pendingOrder().razorpaySubscriptionId()).isEqualTo("sub_abandoned");
        assertThat(dto.pendingOrder().keyId()).isEqualTo("rzp_test_123");
    }

    @Test
    void mySubscriptionSurfacesTheRevenueCatPaymentProvider() {
        // planRepository.findById(planId) is NOT already stubbed by setUp() -- only findByCode
        // is -- so this needs its own stub, same as the existing
        // mySubscriptionReturnsThePlanAndRenewalDateForAPaidSubscriber test does for "PLUS".
        Plan premium = new Plan();
        ReflectionTestUtils.setField(premium, "id", planId);
        premium.setCode("PREMIUM");
        premium.setName("Premium");
        when(planRepository.findById(planId)).thenReturn(Optional.of(premium));

        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setPlanId(planId);
        subscription.setBillingCycle("MONTHLY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setAutoRenew(true);
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(subscription));
        when(planChangeRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()))
                .thenReturn(List.of());

        var dto = service.mySubscription(userId);

        assertThat(dto.paymentProvider()).isEqualTo("REVENUECAT");
        // Real bug found in bug-hunt review: hasBillingSubscription was computed as
        // razorpaySubscriptionId != null -- a REVENUECAT row never sets that field, so this was
        // silently false for every real paying RevenueCat customer. Mobile's SubscriptionScreen
        // routes on hasBillingSubscription (Paywall vs. MySubscriptionScreen), so this would have
        // shown the Paywall -- offering to buy again -- to a customer who already has a live IAP
        // subscription, and web's "managed through the App Store/Play Store" note is itself gated
        // on hasBillingSubscription too.
        assertThat(dto.hasBillingSubscription()).isTrue();
    }

    @Test
    void checkoutRefusesWhenTheUserAlreadyHasARevenueCatOwnedSubscription() {
        Subscription existing = new Subscription();
        existing.setUserId(userId);
        existing.setPlanId(UUID.randomUUID());
        existing.setPaymentProvider("REVENUECAT");
        when(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.checkout(userId, "PREMIUM", "MONTHLY"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a billing subscription");
    }
}
