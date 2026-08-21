package com.finora.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** D-28 PR4-C. Referral program DTOs (proposal §4) -- the user-facing "my code / my referrals /
 *  my wallet" surface and the admin-facing referral dashboard, kept together the same way
 *  BillingDtos keeps PR4-A/B's user- and admin-facing records together. */
public class ReferralDtos {

    /** GET /api/v1/referrals/my-code -- lazily generated on first request, see
     *  {@code ReferralService.myCode}. */
    public record MyReferralCodeDto(String code) {}

    /** One row in a user's own "who I referred" list. */
    public record MyReferralDto(
            UUID referralId, String referredUserFullName, String status, BigDecimal reward, Instant createdAt
    ) {}

    /** GET /api/v1/referrals/mine. {@code walletBalance} is the same computed SUM
     *  {@code WalletLedgerRepository.sumAmountByUserId} returns -- never a stored field. */
    public record MyReferralsDto(List<MyReferralDto> referrals, BigDecimal walletBalance) {}

    /** Admin Portal, Referral dashboard -- one row per referral, both parties identified (an admin
     *  reviewing for abuse needs to see who's on each side, unlike the user-facing view above). */
    public record AdminReferralSummaryDto(
            UUID referralId,
            UUID referrerUserId, String referrerEmail, String referrerFullName,
            UUID referredUserId, String referredEmail, String referredFullName,
            String status, BigDecimal reward, Instant createdAt
    ) {}

    /** Admin-only, manual (proposal §10: the actual reward amount is still an open product
     *  decision -- see {@code ReferralService.creditReward}'s own doc comment for why crediting is
     *  an admin action rather than an automatic one, same reasoning as PR4-A's
     *  {@code ChangePlanRequest}). */
    public record CreditReferralRewardRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Reward amount must be greater than zero") BigDecimal amount,
            @NotBlank(message = "A reason is required") String reason
    ) {}
}
