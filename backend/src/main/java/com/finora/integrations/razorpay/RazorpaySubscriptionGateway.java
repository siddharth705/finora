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
     *  is an immediate stop, used by two Plan 2 callers: the admin support action (spec §6.6), and
     *  {@code RazorpayWebhookDispatcher.handleActivated} stopping the OLD subscription once an
     *  upgrade's new one is confirmed active (spec §6.5 step 4). */
    void cancelSubscription(String razorpaySubscriptionId, boolean cancelAtCycleEnd);

    /** {@code scheduleAtCycleEnd=true} defers the change to the next billing cycle -- the only mode
     *  used today, by Plan 2's downgrade (spec §6.4; {@code BillingCheckoutService.scheduleDowngrade}).
     *  Plan 2's upgrade does NOT call this method at all: it creates a second, independent Razorpay
     *  subscription instead (spec §6.5) and cancels the old one only after the new one activates, so
     *  {@code scheduleAtCycleEnd=false} has no caller yet -- kept for a same-subscription immediate
     *  plan change, should one ever be needed. */
    void updateSubscription(String razorpaySubscriptionId, String newRazorpayPlanId, boolean scheduleAtCycleEnd);
}
