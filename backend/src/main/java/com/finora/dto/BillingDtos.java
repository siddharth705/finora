package com.finora.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** D-28 PR4-A. Billing/subscription/entitlement DTOs -- both the user-facing entitlement read
 *  and the admin-facing subscription management surface, kept together since both belong to this
 *  one new domain (mirrors AdminDtos' own nested-record convention). */
public class BillingDtos {

    /** GET /api/v1/entitlements -- what PremiumFeatureGate reads. planCode/planName are null for
     *  a user with no active-or-trial subscription (shouldn't happen post-V99 backfill, but fails
     *  closed rather than assuming). features maps every FeatureEntitlement row seeded for the
     *  user's plan; a feature key with no row at all is absent from this map -- the frontend gate
     *  and EntitlementService.hasEntitlement both treat "absent" the same as "false". */
    public record EntitlementsDto(String planCode, String planName, Map<String, Boolean> features) {}

    /** Admin Portal, Subscription Management -- one row per user's current subscription.
     *  paymentProvider (Plan 3) is what the admin UI reads to know whether the plain FREE/PLUS/
     *  PREMIUM dropdown is safe to fire directly, or whether it must go through the
     *  cancel-paid-subscription confirm flow first (design spec §6.6) -- "RAZORPAY" means a live
     *  Razorpay mandate exists, "ADMIN_GRANT" or null means it doesn't. */
    public record SubscriptionSummaryDto(
            UUID subscriptionId, UUID userId, String userEmail, String userFullName,
            String planCode, String planName, String status, String paymentProvider,
            LocalDate startDate, LocalDate endDate, LocalDate renewalDate
    ) {}

    /** Manually change a user's plan -- the only way to grant Plus/Premium today, since no payment
     *  gateway exists yet (e.g. a beta tester, a support gesture, or reverting one of these). */
    public record ChangePlanRequest(
            @NotBlank(message = "Plan code is required") String planCode,
            @NotBlank(message = "A reason is required") String reason
    ) {}

    /** GET /api/v1/billing/history -- the user's own payment records (proposal §3.4). Empty for
     *  every user today: no payment gateway is wired up yet (§10), so nothing has ever inserted a
     *  row. Real once a gateway exists, not a placeholder shape. */
    public record BillingHistoryEntryDto(
            UUID id, BigDecimal amount, String currency, String provider, String status, Instant createdAt
    ) {}

    /** POST /api/v1/billing/checkout (design spec §6.1). */
    public record CheckoutRequest(
            @NotBlank(message = "Plan code is required") String planCode,
            @NotBlank(message = "Billing cycle is required") String billingCycle
    ) {}

    /** What the frontend/mobile Razorpay Checkout widget needs to open. {@code keyId} is
     *  Razorpay's public key -- safe to expose to a client, it authenticates nothing on its own. */
    public record CheckoutResponseDto(String razorpaySubscriptionId, String keyId) {}

    /** POST /api/v1/billing/change-plan (design spec §6.4/§6.5) -- user-initiated upgrade/downgrade,
     *  distinct from the admin-facing {@link ChangePlanRequest} above. */
    public record UserChangePlanRequest(
            @NotBlank(message = "Plan code is required") String planCode,
            @NotBlank(message = "Billing cycle is required") String billingCycle
    ) {}

    /** GET /api/v1/billing/subscription -- what the web/mobile Billing Portal reads. Distinct from
     *  {@link EntitlementsDto} (which only carries plan/features, for gating) and from
     *  {@link SubscriptionSummaryDto} (the admin list row, keyed by userId/email for a table, not
     *  by "the caller's own subscription"). */
    public record MySubscriptionDto(
            String planCode, String planName, String billingCycle, String status,
            LocalDate renewalDate, boolean autoRenew, boolean hasBillingSubscription,
            PendingPlanChangeDto pendingChange, PendingOrderDto pendingOrder, String paymentProvider
    ) {}

    /** Null on {@link MySubscriptionDto} unless a downgrade has been scheduled (design spec §6.4)
     *  and not yet reconciled -- see {@code BillingCheckoutService.mySubscription}'s own doc
     *  comment for how "not yet reconciled" is detected. */
    public record PendingPlanChangeDto(String toPlanCode, String toPlanName, Instant effectiveAt) {}

    /** Non-null on {@link MySubscriptionDto} exactly when a {@code subscription_orders} row is
     *  still {@code PENDING} for this user -- an abandoned or in-flight checkout the Billing
     *  Portal can offer to resume (the same {@code razorpaySubscriptionId}/{@code keyId} Checkout
     *  needs, with no new Razorpay call) or cancel via
     *  {@code POST /api/v1/billing/pending-order/cancel}. Added during Plan 3 review: before this,
     *  nothing gave a user visibility into, or a way to clear, a stuck pending order. */
    public record PendingOrderDto(String planCode, String planName, String billingCycle,
                                   String razorpaySubscriptionId, String keyId) {}

    /** GET /api/v1/admin/subscriptions/health -- platform-wide subscription-state counts for the
     *  admin Subscription Health dashboard (Plan 3 review). Deliberately just these five: Active
     *  (paying/complimentary and current), Past Due (Razorpay mid-retry, access still on),
     *  Payment Failed (retries exhausted, already downgraded), Cancelled (in the grace window
     *  before the reconciliation sweep moves them to Free), and Pending Orders (checkouts started
     *  but not yet activated or abandoned) -- the exact five the review asked for, no revenue or
     *  growth metrics added on top. */
    public record SubscriptionHealthDto(
            long activeCount, long pastDueCount, long paymentFailedCount, long cancelledCount,
            long pendingOrderCount
    ) {}
}
