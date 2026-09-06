package com.finora.dto;

/** Refer &amp; Earn MVP DTOs -- a user's own code and how many people they've referred. Nothing
 *  else: no reward, no wallet, no admin dashboard shape (see ReferralService's own doc comment
 *  for the scope this replaced). */
public class ReferralDtos {

    /** GET /api/v1/referrals/my-code -- lazily generated on first request, see
     *  {@code ReferralService.myCode}. */
    public record MyReferralCodeDto(String code) {}

    /** GET /api/v1/referrals/mine. {@code referralCount} is a straight count of
     *  {@code referrals} rows where this user is the referrer -- every row is "successful" by
     *  definition, there's no other status to filter on. */
    public record MyReferralsDto(String code, long referralCount) {}
}
