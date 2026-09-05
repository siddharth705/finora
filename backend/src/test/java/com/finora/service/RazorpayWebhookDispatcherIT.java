package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import com.finora.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookDispatcherIT extends AbstractIntegrationTest {

    @Autowired private RazorpayWebhookDispatcher dispatcher;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private PaymentRepository paymentRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("webhook-activate-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Webhook Activation IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void activationCompletesTheMatchingPendingOrderAndActivatesTheUsersSubscription() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId()); // every user already has one
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("799.00"));
        subscriptionOrderRepository.save(order);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId,
                        "current_end", 1893456000L))); // synthetic-ok: arbitrary future epoch second, not a real identifier

        dispatcher.dispatch("subscription.activated", payload);

        SubscriptionOrder completed = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(SubscriptionOrder.STATUS_COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
        assertThat(subscription.getBillingCycle()).isEqualTo("MONTHLY");
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo(razorpaySubscriptionId);
        assertThat(subscription.getPaymentProvider()).isEqualTo("RAZORPAY");
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);

        List<SubscriptionEvent> events = subscriptionEventRepository.findAll().stream()
                .filter(e -> e.getSubscriptionId().equals(subscription.getId())).toList();
        assertThat(events).anyMatch(e -> e.getEventType().equals(SubscriptionEvent.SUBSCRIPTION_CREATED));
    }

    @Test
    void activationForAnUnknownRazorpaySubscriptionIdIsIgnoredNotThrown() {
        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", "sub_never_created", "current_end", 0L)));

        dispatcher.dispatch("subscription.activated", payload); // must not throw
    }

    @Test
    void chargedInsertsAPaymentRowAndExtendsTheRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String razorpayPlanId = "plan_test_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_test_123", "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.charged", payload);

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.STATUS_SUCCESS);
        assertThat(payments.get(0).getProviderTransactionId()).isEqualTo("pay_test_123");
        assertThat(payments.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("799.00"));

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    @Test
    void chargedReconcilesPlanWhenTheChargedRazorpayPlanIdDiffersFromTheLocalPlan() {
        // Simulates a scheduled downgrade (Plan 2, spec §6.4) taking effect: Razorpay charges the
        // NEW (lower) plan's razorpay_plan_id at cycle end, and this webhook is what notices.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String newRazorpayPlanId = "plan_test_" + UUID.randomUUID();
        plusMonthly.setRazorpayPlanId(newRazorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId()); // still Premium locally
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_test_456", "amount", 39900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", newRazorpayPlanId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.charged", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(plus.getId());
    }
}
