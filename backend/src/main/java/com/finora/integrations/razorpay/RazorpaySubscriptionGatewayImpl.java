package com.finora.integrations.razorpay;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscription billing V1. Thin, mostly-untested-at-the-unit-level adapter over the Razorpay SDK —
 * the interface it implements ({@link RazorpaySubscriptionGateway}) is what everything else in this
 * codebase depends on and mocks; this class is exercised for real only against a live Razorpay test
 * account, matching how {@code GoogleOAuthClient} and {@code GmailApiClient} are thin real-network
 * wrappers verified at the IT/stub level rather than heavily unit tested internally.
 *
 * <p>{@code totalCount}: Razorpay's create-subscription API requires a finite cycle count even for
 * what is conceptually an indefinitely-recurring plan. 120 monthly cycles (10 years) / 20 yearly
 * cycles (20 years) are engineering defaults with no product-visible effect — {@code
 * subscription.completed} is explicitly not expected to fire in V1 (design spec §5), and Razorpay
 * subscriptions auto-renew on their own schedule regardless of this number until cancelled.
 */
@Component
public class RazorpaySubscriptionGatewayImpl implements RazorpaySubscriptionGateway {

    private static final int MONTHLY_TOTAL_COUNT = 120;
    private static final int YEARLY_TOTAL_COUNT = 20;

    private final RazorpayProperties properties;

    public RazorpaySubscriptionGatewayImpl(RazorpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    private RazorpayClient client() {
        if (!isConfigured()) {
            throw new IllegalStateException("Razorpay is not configured (RAZORPAY_KEY_ID/KEY_SECRET/WEBHOOK_SECRET unset).");
        }
        try {
            return new RazorpayClient(properties.getKeyId(), properties.getKeySecret());
        } catch (RazorpayException e) {
            throw new IllegalStateException("Failed to initialize Razorpay client.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", razorpayPlanId);
            request.put("total_count", "YEARLY".equals(billingCycle) ? YEARLY_TOTAL_COUNT : MONTHLY_TOTAL_COUNT);
            request.put("quantity", 1);
            request.put("customer_notify", 1);
            JSONObject notesJson = new JSONObject();
            notes.forEach(notesJson::put);
            request.put("notes", notesJson);

            com.razorpay.Subscription subscription = client().subscriptions.create(request);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay createSubscription failed.", e);
        }
    }

    @Override
    public RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId) {
        try {
            com.razorpay.Subscription subscription = client().subscriptions.fetch(razorpaySubscriptionId);
            return new RazorpaySubscriptionDto(subscription.get("id"), subscription.get("status"));
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay fetchSubscription failed.", e);
        }
    }

    @Override
    public void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("cancel_at_cycle_end", cancelAtCycleEnd);
            client().subscriptions.cancel(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay cancelSubscription failed.", e);
        }
    }

    @Override
    public void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd) {
        try {
            JSONObject request = new JSONObject();
            request.put("plan_id", newRazorpayPlanId);
            request.put("schedule_change_at", scheduleAtCycleEnd ? "cycle_end" : "now");
            client().subscriptions.update(razorpaySubscriptionId, request);
        } catch (RazorpayException e) {
            throw new IllegalStateException("Razorpay updateSubscription failed.", e);
        }
    }
}
