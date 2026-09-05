package com.finora.service;

import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.SubscriptionOrder;
import com.finora.exception.ApiException;
import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
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
    private final RazorpaySubscriptionGateway gateway;
    private final RazorpayProperties properties;

    public BillingCheckoutService(PlanRepository planRepository, BillingPriceRepository billingPriceRepository,
                                   SubscriptionOrderRepository subscriptionOrderRepository,
                                   RazorpaySubscriptionGateway gateway, RazorpayProperties properties) {
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Transactional
    public CheckoutResponseDto checkout(UUID userId, String planCode, String billingCycle) {
        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Billing is not available yet.");
        }

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
}
