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
}
