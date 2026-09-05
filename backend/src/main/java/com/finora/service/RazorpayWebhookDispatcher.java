package com.finora.service;

import com.finora.entity.Payment;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.SubscriptionOrder;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PaymentRepository;
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
    private final BillingPriceRepository billingPriceRepository;
    private final PaymentRepository paymentRepository;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
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
            case "subscription.charged" -> handleCharged(payload);
            case "subscription.pending" -> handlePending(payload);
            case "subscription.halted" -> handleHalted(payload);
            case "subscription.cancelled" -> handleCancelled(payload);
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
     *  row (see design spec §6.5's DB-constraint discussion).
     *
     *  <p>Idempotent across repeated invocations for the same order, not just across repeated
     *  deliveries of the same webhook event id: {@code subscription.authenticated} and
     *  {@code subscription.activated} both fire, sequentially, for one real checkout (confirmed
     *  against Razorpay's own docs — they are lifecycle stages, not mutually-exclusive
     *  alternatives), each as its own event with its own event id, so the {@code webhook_events}
     *  idempotency ledger does not collapse them. Without the early return below, the second
     *  delivery would re-run this whole method and insert a second {@code SUBSCRIPTION_CREATED}
     *  event for one signup — which matters beyond a duplicate audit row, since design spec §5/§6.7
     *  names this exact event as what fires Plan 2's one-time referral trigger. */
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
        if (SubscriptionOrder.STATUS_COMPLETED.equals(order.getStatus())) {
            return;
        }
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

    /** spec §5, §6.4. Renewal is otherwise fully passive — this is also the reconciliation point
     *  that makes a scheduled downgrade (Plan 2) actually take effect: if the charged Razorpay plan
     *  id no longer matches what BillingPrice says the local plan should be billed under, the local
     *  plan_id is corrected to match. */
    @SuppressWarnings("unchecked")
    void handleCharged(Map<String, Object> payload) {
        Map<String, Object> subscriptionEntity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) subscriptionEntity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) {
            log.warn("subscription.charged for unknown razorpaySubscriptionId {}, ignoring.", razorpaySubscriptionId);
            return;
        }
        Subscription subscription = maybeSubscription.get();

        String chargedRazorpayPlanId = (String) subscriptionEntity.get("plan_id");
        if (chargedRazorpayPlanId != null) {
            billingPriceRepository.findAll().stream()
                    .filter(bp -> chargedRazorpayPlanId.equals(bp.getRazorpayPlanId()))
                    .findFirst()
                    .ifPresent(bp -> {
                        subscription.setPlanId(bp.getPlanId());
                        subscription.setBillingCycle(bp.getBillingCycle());
                    });
        }
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        Object currentEnd = subscriptionEntity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        subscriptionRepository.save(subscription);

        Map<String, Object> paymentEntity = (Map<String, Object>) payload.get("payment");
        paymentEntity = paymentEntity == null ? Map.of() : (Map<String, Object>) paymentEntity.get("entity");
        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        Object amountPaise = paymentEntity.get("amount");
        payment.setAmount(amountPaise instanceof Number n
                ? java.math.BigDecimal.valueOf(n.longValue(), 2)
                : java.math.BigDecimal.ZERO);
        payment.setCurrency("INR");
        payment.setProviderTransactionId((String) paymentEntity.get("id"));
        paymentRepository.save(payment);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_RENEWED);
        event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);
    }

    /** spec §5. PAST_DUE, not a revoked state — Razorpay's own retry is in progress and, per its
     *  documented behavior, does not itself affect access (design spec §3). */
    void handlePending(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();
        subscription.setStatus(Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setAmount(java.math.BigDecimal.ZERO); // retry attempt, amount not in this webhook's payload
        payment.setCurrency("INR");
        paymentRepository.save(payment);
    }

    /** spec §5, §9. Retries exhausted — the real access-revoking signal (unlike "pending"). Marks
     *  any outstanding PENDING payment for this subscription FAILED (the retry sequence is over,
     *  it never will succeed now) and downgrades straight to FREE — V1 does not build a "resume a
     *  halted subscription" flow (spec §9); the user re-subscribes via ordinary checkout. */
    void handleHalted(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();

        paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()).stream()
                .filter(p -> Payment.STATUS_PENDING.equals(p.getStatus()))
                .forEach(p -> { p.setStatus(Payment.STATUS_FAILED); paymentRepository.save(p); });

        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        subscription.setPlanId(free.getId());
        subscription.setBillingCycle(null);
        subscription.setRazorpaySubscriptionId(null);
        subscription.setPaymentProvider(null);
        subscription.setAutoRenew(true);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
        event.setMetadata(Map.of("reason", "PAYMENT_FAILURE"));
        subscriptionEventRepository.save(event);
    }

    /** spec §5, §6.3. Does not itself downgrade to Free — that happens at
     * {@code current_period_end}, via {@code SubscriptionReconciliationSweepService} (Task 12), not
     * from this webhook alone (a missed webhook must not leave paid access active forever).
     *
     * <p>Always sets {@code autoRenew=false} here too, not only in {@code BillingCheckoutService
     * .cancel()}: the sweep's own query ({@code findCancelledSubscriptionsPastPeriodEnd}) requires
     * {@code autoRenew=false AND status='CANCELLED'} together. The spec's state-machine table notes
     * "auto_renew already false from the cancel request", true for the only cancellation path V1
     * has today — but this webhook is the one place that hears from Razorpay directly that a
     * subscription is cancelled, regardless of what triggered it (a future admin/Razorpay-dashboard
     * cancellation, or Plan 2's upgrade flow cancelling the old subscription). Without this, any
     * cancellation that didn't go through our own {@code cancel()} first would leave {@code
     * autoRenew=true} forever, and the sweep would never downgrade that user off the paid plan. */
    void handleCancelled(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_CANCELLED);
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
            event.setMetadata(Map.of("reason", "USER_INITIATED"));
            subscriptionEventRepository.save(event);
        });
    }
}
