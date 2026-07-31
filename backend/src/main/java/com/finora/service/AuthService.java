package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.*;
import com.finora.entity.Category;
import com.finora.entity.PasswordResetToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.util.TokenHasher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    // Default categories seeded for every new user — mirrors the prototype's starter category
    // list, expanded (see V11 migration, which backfills the same additions for existing users)
    // beyond the original 13 to cover common real-life cases the first pass didn't: repaying a
    // friend, EMIs, insurance premiums, and so on, so users aren't stuck recategorizing
    // everything as "Other" or hand-creating categories one at a time.
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "Salary", "Rent", "Groceries", "Dining", "Transport", "Utilities", "Shopping",
            "Health", "Entertainment", "Investments", "Fees/Interest", "Transfer",
            "Friend Repayment", "Loan EMI", "Insurance", "Education", "Subscriptions", "Travel",
            "Gifts & Donations", "Pets", "Home & Furnishing", "Taxes", "Cash Withdrawal",
            "Business Expenses", "Other"
    );
    private static final long RESET_TOKEN_TTL_MINUTES = 30;
    // MAX_FAILED_LOGIN_ATTEMPTS / LOCKOUT_DURATION_MINUTES used to be hardcoded here -- now read
    // live from PlatformSettingsService on every call (see registerFailedLogin() and login()'s
    // lockout check) so an admin's change on the System page takes effect immediately, not just
    // for accounts created after a redeploy. V27__platform_settings.sql seeds the same defaults
    // (5 / 15) these constants used to have, so existing behavior is unchanged until an admin
    // actually edits the setting.

    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AuditService auditService;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;
    private final EmailProperties emailProperties;
    private final OtpService otpService;
    private final PlatformSettingsService platformSettingsService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository, CategoryRepository categoryRepository,
                        PasswordResetTokenRepository resetTokenRepository, PasswordEncoder passwordEncoder,
                        JwtService jwtService, AuthenticationManager authenticationManager,
                        AuditService auditService, RefreshTokenService refreshTokenService,
                        EmailService emailService, EmailProperties emailProperties, OtpService otpService,
                        PlatformSettingsService platformSettingsService) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.emailService = emailService;
        this.emailProperties = emailProperties;
        this.otpService = otpService;
        this.platformSettingsService = platformSettingsService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Public self-service signup only -- checked here, not in createUserRecord(), so
        // adminCreateUser() (support-assisted signup) still works while registrations are toggled
        // off. An admin closing the front door to new public signups shouldn't also lock
        // themselves out of creating accounts for people they're helping directly.
        if (!platformSettingsService.getEntity().isRegistrationsEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "New registrations are currently disabled.");
        }
        User user = createUserRecord(request);
        auditService.record(user.getId(), "USER_REGISTERED", "User", user.getId());

        // Send the first OTP automatically — the user shouldn't have to take a separate action
        // just to trigger it right after signing up.
        var otpResult = otpService.issueOtp(user.getId(), user.getPhoneNumber());

        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());
        String refreshToken = refreshTokenService.issue(user.getId()).rawToken();
        return new AuthResponse(accessToken, refreshToken, user.getEmail(), user.getFullName(),
                user.isPhoneVerified(), otpResult.delivered() ? null : otpResult.otp());
    }

    /**
     * Support-assisted signup -- an admin creating an account on someone's behalf (USER_CREATE,
     * V16__rbac_roles_permissions.sql). Shares createUserRecord() with the self-service register()
     * above (same uniqueness checks, same default-category seeding), but deliberately does NOT
     * issue an OTP or mint tokens the way register() does: those exist to get the person who just
     * submitted the form straight into their own session, which isn't the admin's session to have.
     * The new user completes phone verification themselves the first time they actually log in
     * (VerifyPhone.tsx already triggers a fresh OTP send at that point regardless of whether one
     * was ever issued before).
     */
    @Transactional
    public User adminCreateUser(RegisterRequest request, UUID actingAdminId) {
        User user = createUserRecord(request);
        auditService.record(user.getId(), "USER_CREATED_BY_ADMIN", "User", user.getId(),
                Map.of("createdBy", actingAdminId.toString()));
        return user;
    }

    /** The uniqueness checks + row creation + default-category seeding every user-creation path
     *  needs, regardless of what happens after (register() continues into OTP + tokens;
     *  adminCreateUser() stops here). */
    private User createUserRecord(RegisterRequest request) {
        // Trimmed once up front and reused everywhere below -- the duplicate check and the
        // saved value must agree on the exact same string, or "  jane@example.com" could dodge
        // the uniqueness check against an existing "jane@example.com" and still get persisted
        // as a distinct-looking row.
        String email = request.email().trim();
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        // Previously unchecked -- two different accounts could share a phone number, which also
        // breaks email-or-phone login's assumption that a phone number resolves to at most one
        // account (see AuthService.resolveEmailForLogin).
        if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this mobile number already exists.");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Trimmed server-side too -- the frontend already trims before sending, but the
        // @Pattern on fullName deliberately tolerates surrounding whitespace (so a stray space
        // isn't rejected outright), which means it can still arrive untrimmed from any other
        // API caller. This is the one place that actually persists, so it's the one place that
        // must not skip it.
        user.setFullName(request.fullName().trim());
        user.setPhoneNumber(request.phoneNumber());
        user = userRepository.save(user);

        seedDefaultCategories(user.getId());
        return user;
    }

    /**
     * Account lockout: after MAX_FAILED_LOGIN_ATTEMPTS consecutive bad passwords, the account
     * is locked for LOCKOUT_DURATION_MINUTES regardless of whether the next attempt would have
     * been correct. Deliberately checked before attempting authentication (not after), so a
     * locked account never even reaches password verification.
     */
    /**
     * Users shouldn't have to remember whether they signed up with their email or their phone
     * number -- a single field should work either way. Registration never normalizes the phone
     * number (it's stored exactly as typed, "+" prefix and all), so this tries the identifier
     * as given, then with/without a leading "+", before giving up. If nothing resolves, the
     * original identifier is returned unchanged so the existing authenticate()-then-catch flow
     * below still fails with the same generic "Invalid credentials" -- this never reveals
     * whether an account exists, matching the email-only behavior this replaces.
     */
    private String resolveEmailForLogin(String identifier) {
        if (identifier.contains("@")) {
            return identifier;
        }
        String digitsOnly = identifier.replaceAll("[^0-9]", "");
        return userRepository.findByPhoneNumber(identifier)
                .or(() -> userRepository.findByPhoneNumber("+" + digitsOnly))
                .or(() -> userRepository.findByPhoneNumber(digitsOnly))
                .map(User::getEmail)
                .orElse(identifier);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Resolve email-or-phone down to the user's actual email up front -- everything below
        // this line (lockout check, Spring Security authentication, JWT subject) is unchanged
        // and still keyed on email exactly as before.
        String email = resolveEmailForLogin(request.identifier());
        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.LOCKED,
                    "This account is temporarily locked due to repeated failed login attempts. Try again later.");
        }
        // Checked before authenticate() (like the lockout check above), not after -- a suspended
        // account shouldn't get a "correct password" signal at all, just a uniform rejection.
        // See User.status / V23__user_account_status.sql and AdminUserService.suspend.
        if (user != null && user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This account has been suspended. Contact support for assistance.");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Broadened from BadCredentialsException specifically: DaoAuthenticationProvider can
            // throw other AuthenticationException subtypes too (e.g. if a UserDetailsService
            // implementation is ever changed to throw DisabledException/LockedException itself)
            // — all of them mean "this login attempt failed," and all should count toward
            // lockout and return the same generic message, not leak which failure mode occurred.
            if (user != null) {
                registerFailedLogin(user);
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        // user is guaranteed non-null here — authenticate() would have thrown otherwise.
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        auditService.record(user.getId(), "USER_LOGIN", "User", user.getId());

        String accessToken = jwtService.generateToken(user.getId(), user.getEmail());
        String refreshToken = refreshTokenService.issue(user.getId()).rawToken();
        return new AuthResponse(accessToken, refreshToken, user.getEmail(), user.getFullName(), user.isPhoneVerified(), null);
    }

    /** Exchanges a valid, unused refresh token for a new access token + a rotated refresh token. */
    @Transactional
    public RefreshResponse refresh(RefreshRequest request) {
        var rotation = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "User no longer exists"));
        // A suspension that happens mid-session must actually take effect, not just block future
        // logins -- without this check, a suspended user with an unexpired refresh token could
        // keep minting new 15-minute access tokens indefinitely. See login()'s matching check.
        if (user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This account has been suspended. Contact support for assistance.");
        }

        String newAccessToken = jwtService.generateToken(user.getId(), user.getEmail());
        return new RefreshResponse(newAccessToken, rotation.newToken().rawToken());
    }

    @Transactional
    public LogoutResponse logout(LogoutRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return new LogoutResponse("Signed out.");
    }

    /** Resends an OTP to the current user's stored phone number. */
    @Transactional
    public SendOtpResponse sendPhoneOtp(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No phone number on file for this account.");
        }

        var result = otpService.issueOtp(userId, user.getPhoneNumber());
        String message = result.delivered()
                ? "A verification code has been sent to your phone."
                : "A verification code has been issued.";
        // Only exposed when there's no real SMS provider configured — see OtpService.OtpIssueResult.
        return new SendOtpResponse(message, result.delivered() ? null : result.otp());
    }

    @Transactional
    public VerifyOtpResponse verifyPhoneOtp(UUID userId, String otp) {
        boolean verified = otpService.verifyOtp(userId, otp);
        return verified
                ? new VerifyOtpResponse(true, "Phone number verified.")
                : new VerifyOtpResponse(false, "That code doesn't match — check and try again.");
    }

    private void registerFailedLogin(User user) {
        var settings = platformSettingsService.getEntity();
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        if (attempts >= settings.getMaxFailedLoginAttempts()) {
            user.setLockedUntil(Instant.now().plusSeconds(settings.getLockoutDurationMinutes() * 60L));
            auditService.record(user.getId(), "ACCOUNT_LOCKED", "User", user.getId(),
                    java.util.Map.of("failedAttempts", attempts));
        }
        userRepository.save(user);
    }

    /**
     * Issues a reset token regardless of whether the email exists (so the response can't be used
     * to enumerate registered emails). There's no email service wired up in this environment, so
     * the raw link is returned directly in devResetLink — remove that field once real email
     * delivery is in place, and this becomes a normal "check your email" response.
     */
    @Transactional
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request, String requestOrigin) {
        String genericMessage = emailService.isConfigured()
                ? "If an account exists for that email, we've sent a password reset link."
                : "If an account exists for that email, a reset link has been issued.";
        var userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            return new ForgotPasswordResponse(genericMessage, null);
        }

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        PasswordResetToken prt = new PasswordResetToken();
        prt.setUserId(userOpt.get().getId());
        prt.setTokenHash(TokenHasher.sha256(rawToken));
        prt.setExpiresAt(Instant.now().plusSeconds(RESET_TOKEN_TTL_MINUTES * 60));
        resetTokenRepository.save(prt);

        // Bug fix: this used to always build the link from the single user-frontend base URL,
        // regardless of which app the request actually came from -- the user frontend and admin
        // portal are two separate deployed apps, each with its own /reset-password page, and
        // there's no separate admin auth service (admin accounts go through this exact same
        // shared method). An admin using "Forgot Password" got an email linking to the wrong
        // app's reset-password page. resolveBaseUrl() picks the right one from the request's own
        // Origin header -- see EmailProperties' own doc comment for the full story.
        String base = emailProperties.resolveBaseUrl(requestOrigin);
        String resetLink = base + "/reset-password?token=" + rawToken;

        if (emailService.isConfigured()) {
            emailService.sendPasswordResetEmail(userOpt.get().getEmail(), resetLink);
            // Real email exists — no reason to also hand the link back in the API response.
            return new ForgotPasswordResponse(genericMessage, null);
        }

        // No email provider configured — same dev-convenience fallback as before.
        return new ForgotPasswordResponse(genericMessage, resetLink);
    }

    /**
     * Second factor for password reset (see RequestPasswordResetOtpRequest's own doc comment):
     * validates the reset token exactly like resetPassword() does, then sends an OTP to the
     * account's phone. Deliberately does NOT consume/mark the token here -- that only happens
     * once the whole reset actually completes in resetPassword() below, so a user who requests
     * an OTP but never finishes the flow can still use the same reset link again later within
     * its normal expiry window.
     */
    @Transactional
    public RequestPasswordResetOtpResponse requestPasswordResetOtp(RequestPasswordResetOtpRequest request) {
        PasswordResetToken prt = validateResetToken(request.token());
        User user = userRepository.findById(prt.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            // Shouldn't be reachable in practice -- phone number is required at both
            // registration and admin-create time -- but User.phoneNumber has no NOT NULL
            // constraint at the DB level (V8), so this is a real, if unlikely, state to guard
            // rather than let a null flow into SmsService.sendOtp().
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This account has no phone number on file. Contact an administrator for help resetting your password.");
        }
        var otpResult = otpService.issueOtp(user.getId(), user.getPhoneNumber());
        String message = otpResult.delivered()
                ? "A verification code has been sent to the phone number on file."
                : "A verification code has been issued.";
        return new RequestPasswordResetOtpResponse(message, otpResult.delivered() ? null : otpResult.otp());
    }

    @Transactional
    public ResetPasswordResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken prt = validateResetToken(request.token());

        User user = userRepository.findById(prt.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        // Second factor -- the reset token alone (proof of email access) is no longer enough;
        // see RequestPasswordResetOtpRequest's doc comment for why. otpService.verifyOtp()
        // itself throws for "no OTP requested yet"/expired/too-many-attempts, and returns false
        // (rather than throwing) specifically for a wrong code -- that boolean is what's turned
        // into a clear error here rather than silently proceeding.
        if (!otpService.verifyOtp(user.getId(), request.otp())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Incorrect verification code.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        prt.setUsedAt(Instant.now());
        resetTokenRepository.save(prt);

        auditService.record(user.getId(), "PASSWORD_RESET", "User", user.getId());

        return new ResetPasswordResponse("Password updated — you can now sign in with your new password.");
    }

    /** Shared by requestPasswordResetOtp() and resetPassword() -- both need the exact same
     *  "is this reset link still good" checks, and drifting between two separate copies of this
     *  logic is exactly how one of them ends up silently more/less strict than the other. */
    private PasswordResetToken validateResetToken(String rawToken) {
        PasswordResetToken prt = resetTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has already been used."));

        if (prt.getUsedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This reset link has already been used.");
        }
        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This reset link has expired — request a new one.");
        }
        return prt;
    }

    private void seedDefaultCategories(java.util.UUID userId) {
        for (String name : DEFAULT_CATEGORIES) {
            Category c = new Category();
            c.setUserId(userId);
            c.setName(name);
            c.setSystem(true);
            categoryRepository.save(c);
        }
    }
}
