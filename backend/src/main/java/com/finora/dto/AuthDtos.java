package com.finora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    // Bcrypt silently truncates any input past 72 bytes, so two different passwords that only
    // differ after byte 72 would hash identically -- max = 72 makes that impossible rather than
    // letting it happen invisibly.
    private static final String PASSWORD_SIZE_MESSAGE = "Password must be between 8 and 72 characters";

    // Letters (Unicode-aware, so accented/Indic/etc. names aren't rejected), spaces, hyphens,
    // apostrophes, and periods -- covers "Jean-Luc", "O'Brien", "Md. Rahman". Tolerates
    // leading/trailing whitespace here (AuthService.register() trims before saving) so a name
    // typed with stray surrounding spaces isn't rejected for something the UI already fixes up.
    private static final String FULL_NAME_REGEXP = "^\\s*\\p{L}[\\p{L}\\s.'-]{0,98}\\p{L}\\s*$";
    private static final String FULL_NAME_MESSAGE = "Enter a valid full name using letters, spaces, hyphens, or apostrophes only";

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_SIZE_MESSAGE) String password,
            @NotBlank @Pattern(regexp = FULL_NAME_REGEXP, message = FULL_NAME_MESSAGE) String fullName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "Enter a valid phone number (10-15 digits, optional + country code)")
            String phoneNumber
    ) {}

    /**
     * `identifier` accepts either an email address or a registered mobile number -- users
     * shouldn't have to remember which one they signed up with. See AuthService.login(), which
     * resolves this to the user's actual email before delegating to Spring Security (the rest of
     * the auth stack -- UserDetailsService, JWT subject -- is still keyed on email underneath).
     */
    public record LoginRequest(
            @NotBlank String identifier,
            @NotBlank String password
    ) {}

    /** token = short-lived access token (15 min default); refreshToken = long-lived (30 days),
     *  used to obtain a new access token via /auth/refresh without re-entering credentials.
     *  phoneVerified tells the frontend right after login/register whether to prompt for OTP.
     *  devOtp is only populated by register() when no SMS provider is configured — mirrors
     *  devResetLink's reasoning, and keeps the flow testable without a real Twilio account
     *  instead of the OTP being visible only in server logs. Always null from login().
     *  maskedPhone (see PhoneMasking) is populated whenever phoneVerified is false, regardless of
     *  whether an OTP was actually issued at this exact call -- VerifyPhone.tsx uses it to show
     *  which number a code was (or is about to be) sent to, so a wrong/missing country code is
     *  visible on screen instead of silently failing to deliver. */
    public record AuthResponse(
            String token,
            String refreshToken,
            String email,
            String fullName,
            boolean phoneVerified,
            String devOtp,
            String maskedPhone
    ) {}

    public record ForgotPasswordRequest(@Email @NotBlank String email) {}

    /**
     * devResetLink is only populated because this environment has no email service wired up —
     * in a real deployment this field would be removed and the link would only ever go out
     * via email. Returning it here keeps the flow genuinely testable instead of a dead end.
     */
    public record ForgotPasswordResponse(String message, String devResetLink) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank String otp,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_SIZE_MESSAGE) String newPassword
    ) {}

    public record ResetPasswordResponse(String message) {}

    /**
     * Second factor for password reset -- the reset token alone (proof of email access) is no
     * longer sufficient to change a password; a phone OTP (proof of phone access) is required
     * too, same two-factor principle as VerifyPhone already applies elsewhere. token here is the
     * SAME raw reset-link token from forgot-password, used to resolve which account to send the
     * OTP to without requiring a JWT (the person is, by definition, not logged in at this point).
     */
    public record RequestPasswordResetOtpRequest(@NotBlank String token) {}

    /** devOtp mirrors SendOtpResponse.devOtp -- only populated when no SMS provider is
     *  configured, same convention as everywhere else an OTP gets issued in this codebase. */
    public record RequestPasswordResetOtpResponse(String message, String devOtp) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record RefreshResponse(String token, String refreshToken) {}

    public record LogoutRequest(@NotBlank String refreshToken) {}

    public record LogoutResponse(String message) {}

    /**
     * devOtp is only populated because this environment has no SMS provider wired up — same
     * reasoning as devResetLink above. Remove it once a real Twilio (or similar) account is
     * configured; with one configured, this field is always null and the code only ever goes
     * out via real SMS. maskedPhone (see PhoneMasking) is always populated, real provider or not.
     */
    public record SendOtpResponse(String message, String devOtp, String maskedPhone) {}

    public record VerifyOtpRequest(@NotBlank String otp) {}

    public record VerifyOtpResponse(boolean verified, String message) {}
}
