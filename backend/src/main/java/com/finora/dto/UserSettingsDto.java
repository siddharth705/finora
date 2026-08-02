package com.finora.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Backs GET/PUT /api/v1/users/me — the Settings page's Profile/Preferences/Security/Account
 * sections. phoneNumber/phoneVerified/createdAt are read-only (no setter path in UpdateRequest):
 * phone editing isn't built (it's the OTP-verified registration number — see PhoneMaskingTest/
 * VerifyPhone.tsx), and createdAt is a fact about the account, not a preference.
 */
public record UserSettingsDto(
        String email, String fullName, BigDecimal lowBalanceThreshold, String theme, String timezone,
        String phoneNumber, boolean phoneVerified, Instant createdAt,
        // Null until the account's password has been changed at least once (via Change Password
        // or the forgot-password flow) — see User.passwordChangedAt's own doc comment. Security's
        // "Last changed" only renders when this is non-null, never a guessed fallback date.
        Instant passwordChangedAt
) {
    public record UpdateRequest(BigDecimal lowBalanceThreshold, String theme, String timezone, String fullName) {}
}
