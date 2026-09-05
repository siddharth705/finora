package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionOrder;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   SubscriptionRepository subscriptionRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionRepository = subscriptionRepository;
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
}
