package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionOrder;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing V1 (design spec §6.1). Initiates a Razorpay Subscription checkout — never
 * activates anything itself. Activation happens only from a verified webhook
 * (see {@code RazorpayWebhookDispatcher}, Task 7), never from this call's own return value.
 */
@Service
public class BillingCheckoutService {

    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanChangeRepository planChangeRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   PlanChangeRepository planChangeRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planChangeRepository = planChangeRepository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Transactional
    public CheckoutResponseDto checkout(UUID userId, String planCode, String billingCycle) {
        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing is not available yet.");
        }

        // Guard against a second live Razorpay subscription: this table holds exactly one row per
        // user (see Subscription's class doc / idx_subscriptions_one_active_per_user), so any row
        // already carrying a razorpaySubscriptionId means a Razorpay mandate already exists for this
        // user, in some state (ACTIVE, PAST_DUE mid-retry, or CANCELLED-but-not-yet-swept). Checking
        // out again here would create a SECOND Razorpay subscription that this application would
        // never reference again the moment the next activation webhook overwrites this single row --
        // an orphaned subscription still billing the user on Razorpay's side with nothing in Fynora
        // pointing at it. Upgrading/downgrading an existing paid subscription is Plan 2's
        // change-plan endpoint, which handles the cancel-old/activate-new sequencing safely; this
        // endpoint is for a user's first paid subscription only. findActiveOrTrial() is deliberately
        // NOT used here -- it would miss a PAST_DUE subscription, which still has a live mandate.
        subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().findFirst()
                .filter(s -> s.getRazorpaySubscriptionId() != null)
                .ifPresent(s -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "You already have a billing subscription. Cancel it before starting a new one.");
                });
        ensureNoOrderInFlight(userId);

        Plan plan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode));
        BillingPrice price = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(plan.getId(), billingCycle)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active price for " + planCode + "/" + billingCycle));
        if (price.getRazorpayPlanId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This plan is not yet set up for checkout (missing Razorpay plan id).");
        }

        RazorpaySubscriptionDto razorpaySubscription = gateway.createSubscription(
                price.getRazorpayPlanId(), billingCycle,
                Map.of("fynoraUserId", userId.toString(), "planCode", planCode, "billingCycle", billingCycle));

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscription.id());
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(price.getPrice());
        subscriptionOrderRepository.save(order);

        return new CheckoutResponseDto(razorpaySubscription.id(), properties.getKeyId());
    }

    @Transactional
    public void cancel(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This subscription has no billing to cancel.");
        }
        gateway.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);
        subscription.setAutoRenew(false);
        subscriptionRepository.save(subscription);
    }

    /** design spec §6.4/§6.5. Fixed, hardcoded ordering -- matches Plan's own class doc: the three
     *  plan codes are a fixed, product-approved catalog, not expected to grow without a broader
     *  product decision, so this needs no database column of its own. */
    private static final java.util.List<String> TIER_ORDER = java.util.List.of("FREE", "PLUS", "PREMIUM");

    @Transactional
    public void changePlan(UUID userId, String planCode, String billingCycle) {
        if ("FREE".equals(planCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Use POST /api/v1/billing/cancel to move to the Free plan.");
        }
        int newRank = TIER_ORDER.indexOf(planCode);
        if (newRank < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode);
        }

        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No active subscription."));
        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No billing subscription to change. Use POST /api/v1/billing/checkout first.");
        }
        Plan currentPlan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new IllegalStateException("Subscription references a missing plan."));
        int currentRank = TIER_ORDER.indexOf(currentPlan.getCode());

        if (newRank == currentRank) {
            if (billingCycle.equals(subscription.getBillingCycle())) {
                return;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Changing billing cycle without changing tier is not supported yet -- cancel and re-subscribe.");
        }

        Plan newPlan = planRepository.findByCode(planCode)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown plan code: " + planCode));
        BillingPrice newPrice = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(newPlan.getId(), billingCycle)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No active price for " + planCode + "/" + billingCycle));
        if (newPrice.getRazorpayPlanId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This plan is not yet set up for checkout (missing Razorpay plan id).");
        }

        if (newRank > currentRank) {
            upgradeToNewSubscription(userId, newPlan, newPrice, billingCycle);
        } else {
            scheduleDowngrade(subscription, currentPlan, newPlan, newPrice.getRazorpayPlanId());
        }
    }

    /** design spec §6.5. Creates a NEW, real, external Razorpay subscription and a PENDING
     *  {@code subscription_orders} row carrying its id -- the existing {@code subscriptions} row is
     *  left completely untouched (still on the old plan, still pointing at the old
     *  razorpaySubscriptionId) until the new subscription's own activation webhook confirms real
     *  payment ({@code RazorpayWebhookDispatcher.handleActivated}, extended in Task 2 of this plan
     *  to also stop the old mandate at that point, not before). */
    private void upgradeToNewSubscription(UUID userId, Plan newPlan, BillingPrice newPrice, String billingCycle) {
        ensureNoOrderInFlight(userId);
        RazorpaySubscriptionDto razorpaySubscription = gateway.createSubscription(
                newPrice.getRazorpayPlanId(), billingCycle,
                Map.of("fynoraUserId", userId.toString(), "planCode", newPlan.getCode(), "billingCycle", billingCycle));

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(newPlan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscription.id());
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(newPrice.getPrice());
        subscriptionOrderRepository.save(order);
    }

    /** design spec §6.4. Razorpay's own scheduled-plan-change feature defers the actual switch to
     *  the next billing cycle; {@code subscriptions.plan_id}/{@code billing_cycle} are corrected
     *  later by {@code RazorpayWebhookDispatcher.handleCharged}'s existing plan-id reconciliation
     *  (built in Plan 1, unmodified here) the next time this subscription is actually charged -- no
     *  separate "apply" job. This method only calls Razorpay and records the {@link PlanChange} row
     *  so the billing portal can show "Downgrading to X on <date>." */
    private void scheduleDowngrade(Subscription subscription, Plan currentPlan, Plan newPlan, String newRazorpayPlanId) {
        gateway.updateSubscription(subscription.getRazorpaySubscriptionId(), newRazorpayPlanId, true);

        PlanChange change = new PlanChange();
        change.setSubscriptionId(subscription.getId());
        change.setFromPlanId(currentPlan.getId());
        change.setToPlanId(newPlan.getId());
        change.setEffectiveAt(subscription.getRenewalDate() != null
                ? subscription.getRenewalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : Instant.now());
        change.setReason(PlanChange.REASON_DOWNGRADE_SCHEDULED);
        planChangeRepository.save(change);
    }

    /** Closes the double-submit window between "create the Razorpay subscription" and "the
     *  activation webhook lands": a double-tap, client retry, or two open tabs calling either
     *  {@code checkout()} or an upgrade in quick succession would otherwise each create their own
     *  real, live Razorpay subscription before either one's {@code subscriptions} row reflects it
     *  -- the same failure class {@code checkout()}'s "already has a live subscription" guard
     *  closes only for a repeat call made AFTER activation, not before it. */
    private void ensureNoOrderInFlight(UUID userId) {
        if (subscriptionOrderRepository.existsByUserIdAndStatus(userId, SubscriptionOrder.STATUS_PENDING)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A billing checkout is already in progress. Please wait for it to complete or try again shortly.");
        }
    }
}
