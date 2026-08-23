package com.finora.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    // Bcrypt silently truncates any input past 72 bytes, so two different passwords that only
    // differ after byte 72 would hash identically -- max = 72 makes that impossible rather than
    // letting it happen invisibly. Package-private (not private) so PasswordChangeDtos' own new-
    // password field can share the exact same message instead of a second, driftable copy.
    static final String PASSWORD_SIZE_MESSAGE = "Password must be between 8 and 72 characters";

    // Letters (Unicode-aware, so accented/Indic/etc. names aren't rejected), spaces, hyphens,
    // apostrophes, and periods -- covers "Jean-Luc", "O'Brien", "Md. Rahman". Tolerates
    // leading/trailing whitespace here (AuthService.register() trims before saving) so a name
    // typed with stray surrounding spaces isn't rejected for something the UI already fixes up.
    // Public (not just package-private) so AdminDtos (same package) AND AuthService (a Google
    // sign-in display name isn't a validated request field the way a registration form's fullName
    // is -- see AuthService.sanitizeGoogleDisplayName) both constrain against the SAME rules
    // instead of restating them and drifting. AdminUpdateUserRequest previously declared no
    // constraints at all -- see its own doc comment for what that let through.
    public static final String FULL_NAME_REGEXP = "^\\s*\\p{L}[\\p{L}\\s.'-]{0,98}\\p{L}\\s*$";
    static final String FULL_NAME_MESSAGE = "Enter a valid full name using letters, spaces, hyphens, or apostrophes only";
    static final String PHONE_REGEXP = "^\\+?[0-9]{10,15}$";
    static final String PHONE_MESSAGE = "Enter a valid phone number (10-15 digits, optional + country code)";

    /** @param referralCode D-28 PR4-C. Optional -- absent for the overwhelming majority of
     *        registrations, which arrive with no referral at all. Deliberately unvalidated here:
     *        {@code ReferralService.redeemCode} treats an unrecognized or mistyped code as a silent
     *        no-op rather than a rejected request, so a bad code never turns into a blocked signup
     *        (see that method's own doc comment). Shared by {@code adminCreateUser} too, which
     *        simply never reads it -- support-assisted signup has no organic acquisition to track. */
    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_SIZE_MESSAGE) String password,
            @NotBlank @Pattern(regexp = FULL_NAME_REGEXP, message = FULL_NAME_MESSAGE) String fullName,
            @NotBlank @Pattern(regexp = PHONE_REGEXP, message = PHONE_MESSAGE)
            String phoneNumber,
            String referralCode
    ) {}

    /**
     * `identifier` accepts either an email address or a registered mobile number -- users
     * shouldn't have to remember which one they signed up with. See AuthService.login(), which
     * resolves this to the user's actual email before delegating to Spring Security (the rest of
     * the auth stack -- UserDetailsService, JWT subject -- is still keyed on email underneath).
     */
    /**
     * @param scope which portal is authenticating: {@code "USER"} or {@code "ADMIN"}. Optional --
     *        absent means USER, so a client that has not been updated behaves exactly as before.
     *        Needed because since V52 an email and a phone number identify a user only within a
     *        scope, so the same person can hold one account in each. NOT an authorization input:
     *        it selects which row to check a password against, while what that row may do is
     *        decided entirely by its roles.
     */
    public record LoginRequest(
            @NotBlank String identifier,
            @NotBlank String password,
            String scope
    ) {}

    /**
     * Identifier-first entry step (auth/security review §2.2,
     * docs/proposals/authentication-account-security-review.md): given an email or phone,
     * {@code AuthService.identify} says what the client should show next without a raw
     * account-existence boolean. Always resolves within {@link com.finora.entity.User#SCOPE_USER}
     * -- unlike {@link LoginRequest}, this deliberately has no {@code scope} field, since the
     * admin portal has its own separate sign-in flow and was never meant to reach this endpoint.
     */
    public record IdentifyRequest(@NotBlank String identifier) {}

    /**
     * @param nextAction what the client should present next: {@code "EXISTS"} for an identifier
     *        with an account (regardless of which sign-in method it uses), or {@code "CONTINUE"}
     *        for one with no account -- deliberately not a boolean {@code exists} field, to avoid
     *        handing back a directly machine-readable existence oracle.
     *
     *        <p>Phase 7 hardening (auth/security review, resolved 2026-08-23): this used to be
     *        {@code "PASSWORD"}/{@code "GOOGLE"}/{@code "APPLE"}/{@code "CONTINUE"}, mirroring
     *        {@link com.finora.entity.User#getSignInMethod()} for an existing account -- letting a
     *        caller learn not just THAT an account exists but WHICH method it uses. Collapsed to
     *        two values: the client can no longer distinguish a password account from a Google or
     *        Apple one before ever attempting to sign in, closing that half of the leak. It still
     *        narrows rather than eliminates enumeration risk (EXISTS vs CONTINUE is itself a
     *        signal); the rate limit on this endpoint (see RateLimitFilter) is the other half of
     *        that mitigation, not a substitute for it. The backend's own {@code signInMethod}
     *        refusal at actual login time is unaffected either way -- this only changes what the
     *        pre-login identify step is willing to say.
     */
    public record IdentifyResponse(String nextAction) {}

    /**
     * D-23: {@code idToken} is the raw Google ID token from Google Identity Services (web) or a
     * native Google Sign-In SDK (mobile, Phase 2) -- never the frontend's own parsed claims.
     * {@code AuthService.loginWithGoogle} verifies it server-side via
     * {@code GoogleIdTokenVerifierService} before trusting anything it says, same discipline as
     * {@code ResetPasswordRequest.firebaseIdToken}.
     */
    public record GoogleAuthRequest(@NotBlank String idToken) {}

    /**
     * D-23 Phase 2: {@code idToken} is the raw Apple identity token from native
     * {@code AuthenticationServices} Sign In with Apple ({@code expo-apple-authentication} on
     * mobile) -- never the frontend's own parsed claims, same discipline as
     * {@link GoogleAuthRequest#idToken}. {@code AuthService.loginWithApple} verifies it
     * server-side via {@code AppleIdTokenVerifierService} before trusting anything it says.
     *
     * <p>{@code fullName} is optional and NOT part of the token: Apple's identity token never
     * carries a name claim at all, and hands the display name to the client separately, only on
     * the user's very first authorization for this app -- see {@code AppleIdentity}'s own doc
     * comment. Every subsequent sign-in this will legitimately be {@code null}.
     *
     * <p>Deliberately UNVALIDATED here, unlike {@link RegisterRequest#fullName} -- self-review
     * finding: a {@code @Pattern} at this layer would reject the whole request (a 400, no session
     * issued) on any value that doesn't match, including ones Apple's own name formatter could
     * plausibly hand back (an empty or whitespace-only string, for a components object that's
     * non-null but has every field null). {@code AuthService.sanitizeOAuthDisplayName} already
     * validates and safely falls back to the email address for exactly this case -- the same
     * fallback a missing/invalid Google {@code name} claim gets, which never had a DTO-level gate
     * to trip in the first place. Hard-failing the entire sign-in over a cosmetic display name
     * would make this the ONE thing that turns "just use the email" into "you can't sign in."
     */
    public record AppleAuthRequest(
            @NotBlank String idToken,
            String fullName
    ) {}

    /** token = short-lived access token (15 min default); refreshToken = long-lived (30 days),
     *  used to obtain a new access token via /auth/refresh without re-entering credentials.
     *  phoneVerified tells the frontend right after login/register whether to prompt for OTP
     *  (via Firebase Phone Authentication -- see FirebaseConfig's own doc comment for the full
     *  architecture). maskedPhone (see PhoneMasking) is populated whenever phoneVerified is
     *  false, so VerifyPhone.tsx can show which number a code will go to -- a wrong/missing
     *  country code on the account is visible on screen instead of silently failing to deliver.
     *  The frontend fetches the REAL phone number separately (GET /users/me, once authenticated)
     *  when it actually needs to hand it to Firebase's signInWithPhoneNumber(). */
    public record AuthResponse(
            String token,
            String refreshToken,
            String email,
            String fullName,
            boolean phoneVerified,
            String maskedPhone
    ) {}

    /** @param scope see {@link LoginRequest#scope()} -- a reset link must be issued for the
     *  account in the portal the request came from, not an arbitrary one of the two a person may
     *  hold under one email. */
    public record ForgotPasswordRequest(@Email @NotBlank String email, String scope) {}

    /**
     * devResetLink is only populated because this environment has no email service wired up —
     * in a real deployment this field would be removed and the link would only ever go out
     * via email. Returning it here keeps the flow genuinely testable instead of a dead end.
     */
    public record ForgotPasswordResponse(String message, String devResetLink) {}

    /**
     * firebaseIdToken is the second factor -- the reset token alone (proof of email access) is
     * no longer sufficient to change a password; proof of phone access via Firebase Phone
     * Authentication is required too, same two-factor principle as VerifyPhone already applies
     * elsewhere. AuthService.resetPassword() verifies this token server-side (via
     * PhoneVerificationProvider) and checks the phone number it attests to matches the
     * account's own before proceeding -- never trusts the frontend's own "it worked" signal.
     */
    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank String firebaseIdToken,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_SIZE_MESSAGE) String newPassword
    ) {}

    public record ResetPasswordResponse(String message) {}

    /**
     * BH-015 fix. Used to reveal the account's real phone number for a valid, unused reset link
     * -- inverted so the USER supplies the number instead: the frontend needs SOME phone number
     * to call Firebase Phone Authentication directly (Firebase's own client SDK sends the OTP;
     * this backend never does), and this endpoint confirms whether the one the user just typed
     * belongs to the account BEFORE the client is allowed to hand it to Firebase, rather than the
     * backend handing the real number back to whoever holds a valid link. token here is the SAME
     * raw reset-link token from forgot-password, used to resolve which account without requiring
     * a JWT (the person is, by definition, not logged in at this point). Gated on the exact same
     * reset-token validity check resetPassword() itself uses -- see
     * AuthService.verifyResetPasswordPhone()'s own doc comment for why that's enough to prevent
     * this from becoming an arbitrary phone-number-guessing oracle.
     */
    public record VerifyResetPasswordPhoneRequest(@NotBlank String token, @NotBlank String phoneNumber) {}

    public record VerifyResetPasswordPhoneResponse(String message) {}

    public record RefreshRequest(String refreshToken) {}

    public record RefreshResponse(String token, String refreshToken) {}

    public record LogoutRequest(String refreshToken) {}

    public record LogoutResponse(String message) {}

    /** firebaseIdToken proves phone ownership via Firebase Phone Authentication -- see
     *  PhoneVerificationProvider's own doc comment. Backs POST /api/v1/phone/verify,
     *  called once the frontend's own Firebase client SDK has already sent and confirmed the
     *  OTP; the backend only ever sees the resulting token, never the code itself. */
    public record VerifyPhoneRequest(@NotBlank String firebaseIdToken) {}

    public record VerifyPhoneResponse(String message) {}

    /** token is the raw reactivation token AuthService.login() minted and returned in an
     *  AUTH_ACCOUNT_DEACTIVATED error's details map -- see AuthService.reactivate(). */
    public record ReactivateRequest(@NotBlank String token) {}

    /** D-23. token is the raw verification token from a {@code /verify-email?token=...} link --
     *  see AuthService.mintEmailVerificationToken / verifyEmail. */
    public record VerifyEmailRequest(@NotBlank String token) {}
    public record VerifyEmailResponse(String message) {}

    /** SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). challengeToken is
     *  the raw token AuthService.login() minted and returned in an AUTH_MFA_REQUIRED error's
     *  details map -- see AuthService.completeMfaLogin. code is either a live TOTP code from the
     *  user's authenticator app or one of their unused recovery codes; AdminMfaService.verifyChallenge
     *  tries both. */
    public record MfaVerifyRequest(@NotBlank String challengeToken, @NotBlank String code) {}
}
