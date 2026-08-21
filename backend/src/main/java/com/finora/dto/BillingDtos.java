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

    /** Admin Portal, Subscription Management -- one row per user's current subscription. */
    public record SubscriptionSummaryDto(
            UUID subscriptionId, UUID userId, String userEmail, String userFullName,
            String planCode, String planName, String status,
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
}
