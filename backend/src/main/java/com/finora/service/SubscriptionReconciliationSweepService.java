package com.finora.service;

import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Subscription billing V1 (design spec §6.3). Safety net, not the primary mechanism — a cancelled
 * subscription is normally downgraded to Free by {@code RazorpayWebhookDispatcher.handleCancelled}'s
 * webhook path reaching {@code current_period_end} naturally (no further {@code subscription.charged}
 * arrives). This sweep exists because "we stopped hearing an event" is not itself a reliable signal:
 * Razorpay disables a webhook endpoint after 24h of failed deliveries, so a missed webhook could
 * otherwise leave paid access active indefinitely with nothing to notice. Same shape as
 * {@code AccountPurgeSweepService}/{@code NetWorthSnapshotSweepService}: {@code fixedDelay}, gated by
 * a flag {@code application-test.yml} turns off, tests call {@link #sweep()} directly.
 */
@Service
public class SubscriptionReconciliationSweepService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionReconciliationSweepService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;

    @Value("${app.subscription-reconciliation.sweep.enabled:true}")
    private boolean sweepEnabled;

    public SubscriptionReconciliationSweepService(SubscriptionRepository subscriptionRepository,
                                                   SubscriptionEventRepository subscriptionEventRepository,
                                                   PlanRepository planRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
    }

    @Scheduled(fixedDelayString = "${app.subscription-reconciliation.sweep.interval-ms:3600000}",
            initialDelayString = "${app.subscription-reconciliation.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        int downgraded = sweep();
        if (downgraded > 0) {
            log.info("Subscription reconciliation sweep: {} subscription(s) downgraded to Free.", downgraded);
        }
    }

    @Transactional
    public int sweep() {
        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        List<Subscription> overdue = subscriptionRepository.findCancelledSubscriptionsPastPeriodEnd(LocalDate.now());

        for (Subscription subscription : overdue) {
            subscription.setPlanId(free.getId());
            subscription.setBillingCycle(null);
            subscription.setRazorpaySubscriptionId(null);
            subscription.setPaymentProvider(null);
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            subscription.setAutoRenew(true);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.PLAN_CHANGED);
            event.setMetadata(Map.of("reason", "CANCELLATION_PERIOD_ENDED"));
            subscriptionEventRepository.save(event);
        }
        return overdue.size();
    }
}
