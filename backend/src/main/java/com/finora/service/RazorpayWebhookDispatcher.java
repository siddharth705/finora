package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.SubscriptionOrder;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Subscription billing V1 (design spec §5). One method per Razorpay event type this application
 * acts on. Named for what it does, not {@code *Service}: a single-purpose collaborator used only by
 * {@link com.finora.controller.RazorpayWebhookController}.
 */
@Component
public class RazorpayWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
    }

    /**
     * {@code @Transactional} lives here, not on the individual {@code handle*} methods below —
     * those are called from inside this same class (self-invocation), which bypasses Spring's
     * proxy-based transaction interception entirely. An {@code @Transactional} on a privately
     * self-invoked method is silently a no-op; confirmed the hard way when
     * {@code handleActivated}'s order/subscription/event writes landed outside any transaction and
     * a missing {@code save()} call on the order was masked until the mutated field never persisted.
     */
    @Transactional
    public void dispatch(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            case "subscription.authenticated", "subscription.activated" -> handleActivated(payload);
            default -> log.info("Razorpay webhook event '{}' received but not handled in V1.", eventType);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subscriptionEntity(Map<String, Object> payload) {
        Map<String, Object> subscription = (Map<String, Object>) payload.get("subscription");
        return subscription == null ? Map.of() : (Map<String, Object>) subscription.get("entity");
    }

    /** spec §6.1 step 5 / §5. Completes checkout: marks the matching {@link SubscriptionOrder}
     *  COMPLETED and mutates the user's single {@link Subscription} row in place — the same
     *  mutate-in-place model {@code SubscriptionService.changePlan} already uses, never a second
     *  row (see design spec §6.5's DB-constraint discussion). */
    void handleActivated(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<SubscriptionOrder> maybeOrder = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeOrder.isEmpty()) {
            log.warn("subscription.activated for unknown razorpaySubscriptionId {}, ignoring.", razorpaySubscriptionId);
            return;
        }
        SubscriptionOrder order = maybeOrder.get();
        order.setStatus(SubscriptionOrder.STATUS_COMPLETED);
        order.setCompletedAt(Instant.now());
        subscriptionOrderRepository.save(order);

        Subscription subscription = subscriptionRepository.findActiveOrTrial(order.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User " + order.getUserId() + " has a pending order but no subscription row " +
                        "-- provisionFreeSubscription should have created one at signup."));
        Plan plan = planRepository.findById(order.getPlanId()).orElseThrow();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(order.getBillingCycle());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        Object currentEnd = entity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CREATED);
        event.setMetadata(Map.of("planCode", plan.getCode(), "billingCycle", order.getBillingCycle(),
                "razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);
    }
}
