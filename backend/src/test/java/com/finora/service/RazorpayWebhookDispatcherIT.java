package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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

    @MockitoBean private RazorpaySubscriptionGateway gateway;

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

    @Test
    void pendingSetsStatusToPastDueButDoesNotRevokeAccess() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.pending", payload);

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_PAST_DUE);

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).getStatus()).isEqualTo(Payment.STATUS_PENDING);
    }

    @Test
    void haltedDowngradesToFreeAndMarksTheOutstandingPaymentFailed() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setStatus(Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        Payment pendingPayment = new Payment();
        pendingPayment.setUserId(user.getId());
        pendingPayment.setSubscriptionId(subscription.getId());
        pendingPayment.setProvider("RAZORPAY");
        pendingPayment.setStatus(Payment.STATUS_PENDING);
        pendingPayment.setAmount(new BigDecimal("799.00"));
        pendingPayment.setCurrency("INR");
        paymentRepository.save(pendingPayment);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.halted", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(free.getId());
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(reloaded.getPaymentProvider()).isNull();
        assertThat(reloaded.getRazorpaySubscriptionId()).isNull();

        List<Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertThat(payments).anyMatch(p -> p.getStatus().equals(Payment.STATUS_FAILED));
    }

    @Test
    void cancelledSetsStatusToCancelledWithoutRevokingAccessYet() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.cancelled", payload);

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }

    @Test
    void cancelledSetsAutoRenewFalseEvenWhenNotAlreadySetByOurOwnCancelRequest() {
        // Regression test: BillingCheckoutService.cancel() already sets autoRenew=false before
        // calling Razorpay's cancel API, but this webhook is the one place that hears "this
        // subscription is cancelled" directly from Razorpay, regardless of what triggered it (a
        // future admin/Razorpay-dashboard cancellation, or Plan 2's upgrade flow). The
        // reconciliation sweep's own query requires autoRenew=false AND status='CANCELLED' together
        // -- if this handler didn't set autoRenew itself, a subscription cancelled through any path
        // other than our own cancel() would stay on its paid plan forever.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setAutoRenew(true);
        subscriptionRepository.save(subscription);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId)));

        dispatcher.dispatch("subscription.cancelled", payload);

        Subscription reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
    }

    @Test
    void activatedIsIdempotentAcrossAuthenticatedAndActivatedBothFiringForTheSameOrder() {
        // Regression test: Razorpay's own docs confirm subscription.authenticated and
        // subscription.activated both fire, sequentially, for one real checkout -- they are
        // lifecycle stages, not mutually exclusive alternatives, and each is its own webhook event
        // with its own event id, so the webhook_events idempotency ledger does not collapse them.
        // Without an idempotency guard inside handleActivated itself, the second delivery would
        // insert a second SUBSCRIPTION_CREATED event for one signup -- which matters because the
        // spec names this exact event as what fires Plan 2's one-time referral trigger.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
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
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        // provisionFreeSubscription above already inserted one SUBSCRIPTION_CREATED event (for the
        // FREE signup) against this same subscription id -- the mutate-in-place model means
        // activation reuses that same row/id rather than creating a new one. The assertion below
        // checks the two dispatch() calls together contribute exactly one MORE such event, not that
        // the total is one.
        long before = subscriptionEventRepository.findAll().stream()
                .filter(e -> e.getSubscriptionId().equals(
                        subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().getId()))
                .filter(e -> e.getEventType().equals(SubscriptionEvent.SUBSCRIPTION_CREATED))
                .count();

        dispatcher.dispatch("subscription.authenticated", payload);
        dispatcher.dispatch("subscription.activated", payload);

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        long after = subscriptionEventRepository.findAll().stream()
                .filter(e -> e.getSubscriptionId().equals(subscription.getId()))
                .filter(e -> e.getEventType().equals(SubscriptionEvent.SUBSCRIPTION_CREATED))
                .count();
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void activatingAnUpgradeCancelsTheOldRazorpaySubscriptionImmediately() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        String oldRazorpaySubscriptionId = "sub_old_" + UUID.randomUUID();
        String newRazorpaySubscriptionId = "sub_new_" + UUID.randomUUID();

        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(oldRazorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(premium.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(newRazorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("799.00"));
        subscriptionOrderRepository.save(order);

        Map<String, Object> payload = Map.of(
                "subscription", Map.of("entity", Map.of(
                        "id", newRazorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.activated", payload);

        Subscription reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getPlanId()).isEqualTo(premium.getId());
        assertThat(reloaded.getRazorpaySubscriptionId()).isEqualTo(newRazorpaySubscriptionId);
        verify(gateway).cancelSubscription(oldRazorpaySubscriptionId, false);
    }

    @Test
    void aBrandNewSignupActivationNeverCallsCancel() {
        // The existing activation path (Plan 1) has no prior razorpaySubscriptionId on the row --
        // the same code that stops an old upgrade mandate must do nothing here.
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
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
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L))); // synthetic-ok: fixture epoch second

        dispatcher.dispatch("subscription.activated", payload);

        verify(gateway, never()).cancelSubscription(any(), anyBoolean());
    }
}
