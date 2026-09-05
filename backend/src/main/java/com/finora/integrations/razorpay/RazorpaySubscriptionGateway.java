package com.finora.integrations.razorpay;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §6). The only Razorpay-facing seam every billing service
 * depends on — checkout (Task 6), cancellation (Task 11), and upgrade/downgrade (Plan 2) all
 * program against this interface, never the SDK's {@code RazorpayClient} directly, so they can be
 * unit-tested with a plain mock.
 */
public interface RazorpaySubscriptionGateway {

    boolean isConfigured();

    /** Creates a new Razorpay Subscription against an already-provisioned Razorpay Plan (see
     *  {@code billing_prices.razorpayPlanId}). {@code notes} is stored on the Razorpay side for
     *  support/debugging correlation, not read back by this application. */
    RazorpaySubscriptionDto createSubscription(String razorpayPlanId, String billingCycle, Map<String, String> notes);

    RazorpaySubscriptionDto fetchSubscription(String razorpaySubscriptionId);

    /** {@code cancelAtCycleEnd=true} for every user-initiated cancellation (spec §6.3); {@code false}
     *  is reserved for the admin support action in Plan 2 (spec §6.6), which needs an immediate stop. */
    void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd);

    /** {@code scheduleAtCycleEnd=true} defers the change to the next billing cycle (spec §6.4,
     *  downgrade); {@code false} applies it now (spec §6.5, upgrade — used by Plan 2, not this plan). */
    void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd);
}
