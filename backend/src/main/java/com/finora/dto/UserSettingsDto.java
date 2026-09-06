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
        Instant passwordChangedAt,
        // "PASSWORD" or "GOOGLE" -- see User.signInMethod's own doc comment. Lets the frontend
        // decide, before the user even opens a modal, whether Change Password/Delete Account/
        // Export Data should ask for a current password or offer a fresh Sign in with Google
        // button instead -- see GoogleReauthVerifier, which is what the backend actually checks
        // against regardless of what this field says.
        String signInMethod,
        // Same channel phoneVerified already rides -- see docs/superpowers/specs/
        // 2026-09-06-first-login-onboarding-tour-design.md §7. This is what a returning user's
        // silent-refresh bootstrap (AuthContext, web and mobile) reads onboarding state from,
        // since /auth/refresh itself returns no profile fields.
        boolean onboardingCompleted
) {
    /**
     * <p>Bug fix: this record declared NO constraints at all, and UserController.update() applied
     * no {@code @Valid} either -- so the self-service path accepted an unbounded {@code fullName}
     * and an arbitrary {@code theme}, while the two other writers to the same columns
     * ({@code RegisterRequest} and {@code AdminUpdateUserRequest}) both constrain them. Two
     * writers to one field, one validated and one not, is the same asymmetry that produced the
     * un-normalized phone numbers {@code AdminUserService.updateProfile} documents having had to
     * repair. Here it meant an over-long value reached the database and came back as a confusing
     * 409 from the constraint rather than a message naming the field.
     *
     * <p>{@code theme} is bounded but deliberately not enumerated here -- {@code UserSettingsService}
     * validates it against the real option list, next to the timezone check, because both are
     * questions about a value's meaning rather than its shape.
     */
    public record UpdateRequest(
            @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "Low balance threshold can't be negative")
            @jakarta.validation.constraints.Digits(integer = 12, fraction = 2, message = "Low balance threshold must be a money amount")
            BigDecimal lowBalanceThreshold,
            @jakarta.validation.constraints.Size(max = 20, message = "Theme is not a valid option")
            String theme,
            @jakarta.validation.constraints.Size(max = 64, message = "Timezone is too long to be a valid zone id")
            String timezone,
            @jakarta.validation.constraints.Pattern(regexp = AuthDtos.FULL_NAME_REGEXP, message = AuthDtos.FULL_NAME_MESSAGE)
            String fullName) {}
}
