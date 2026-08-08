package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.AuthDtos.*;
import com.finora.entity.Category;
import com.finora.entity.PasswordResetToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.CategoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.util.PhoneMasking;
import com.finora.util.PhoneNumbers;
import com.finora.util.TokenHasher;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;
    private final PhoneVerificationProvider phoneVerificationProvider;
    private final PlatformSettingsService platformSettingsService;
    private final PasswordHistoryService passwordHistoryService;
    private final IdentityLookup identityLookup;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository, CategoryRepository categoryRepository,
                        PasswordResetTokenRepository resetTokenRepository, PasswordEncoder passwordEncoder,
                        JwtService jwtService, AuthenticationManager authenticationManager,
                        AuditService auditService, RefreshTokenService refreshTokenService,
                        EmailProvider emailProvider, EmailProperties emailProperties,
                        PhoneVerificationProvider phoneVerificationProvider,
                        PlatformSettingsService platformSettingsService,
                        PasswordHistoryService passwordHistoryService,
                        IdentityLookup identityLookup) {
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.auditService = auditService;
        this.refreshTokenService = refreshTokenService;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.platformSettingsService = platformSettingsService;
        this.passwordHistoryService = passwordHistoryService;
        this.identityLookup = identityLookup;
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
        User user = createUserRecord(request, User.SCOPE_USER);
        auditService.record(user.getId(), "USER_REGISTERED", "User", user.getId());
        EmailResult welcomeEmailResult = emailProvider.sendWelcomeEmail(user.getEmail(), user.getFullName());
        auditService.record(user.getId(), "EMAIL_SENT", "User", user.getId(), Map.of(
                "type", "welcome", "provider", welcomeEmailResult.provider().name(), "success", welcomeEmailResult.success()));

        // Refresh token first: it is what mints the session, and the access token has to carry
        // that session's id in its sid claim.
        var issued = refreshTokenService.issue(user.getId());
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(),
                user.getAccountScope());
        String refreshToken = issued.rawToken();
        return new AuthResponse(accessToken, refreshToken, user.getEmail(), user.getFullName(),
                user.isPhoneVerified(), PhoneMasking.mask(user.getPhoneNumber()));
    }

    /**
     * Support-assisted signup -- an admin creating an account on someone's behalf (USER_CREATE,
     * V16__rbac_roles_permissions.sql). Shares createUserRecord() with the self-service register()
     * above (same uniqueness checks, same default-category seeding), but deliberately does NOT
     * mint tokens the way register() does: those exist to get the person who just submitted the
     * form straight into their own session, which isn't the admin's session to have. The new user
     * completes phone verification themselves the first time they actually log in (VerifyPhone.tsx
     * calls Firebase Phone Authentication directly at that point).
     */
    @Transactional
    public User adminCreateUser(RegisterRequest request, UUID actingAdminId) {
        return adminCreateUser(request, actingAdminId, User.SCOPE_USER);
    }

    /**
     * @param accountScope which portal the created account belongs to. The admin portal's
     *        "add a user" creates a {@code USER}-scope account -- it is creating a customer, not a
     *        colleague. Setup creates an {@code ADMIN}-scope one, which is what lets an
     *        administrator hold an admin account under the same email as their personal one.
     */
    @Transactional
    public User adminCreateUser(RegisterRequest request, UUID actingAdminId, String accountScope) {
        User user = createUserRecord(request, accountScope);
        auditService.record(user.getId(), "USER_CREATED_BY_ADMIN", "User", user.getId(),
                Map.of("createdBy", actingAdminId.toString()));
        return user;
    }

    /** The uniqueness checks + row creation + default-category seeding every user-creation path
     *  needs, regardless of what happens after (register() continues into minting tokens;
     *  adminCreateUser() stops here). */
    private User createUserRecord(RegisterRequest request, String accountScope) {
        // Trimmed + lowercased once up front and reused everywhere below -- the duplicate check
        // and the saved value must agree on the exact same string, or "  Jane@Example.com" could
        // dodge the uniqueness check against an existing "jane@example.com" and still get
        // persisted as a distinct-looking row that's really the same mailbox (most providers
        // treat the local part case-insensitively too). existsByEmailIgnoreCase (not the plain,
        // case-sensitive existsByEmail) is what actually catches this against any pre-existing
        // row regardless of what case IT happened to be stored in.
        // Uniqueness is per SCOPE since V52: the same person may hold a USER-scope account and an
        // ADMIN-scope account under one email and one mobile number. Within a scope the rule is
        // unchanged -- one email, one mobile, one account.
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCaseAndAccountScope(email, accountScope)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }
        String phoneNumber = normalizePhoneNumber(request.phoneNumber());
        // Previously unchecked -- two accounts in the SAME scope could share a phone number, which
        // breaks email-or-phone login's assumption that a phone number resolves to at most one
        // account within the scope it is logging into (see resolveEmailForLogin).
        if (userRepository.existsByPhoneNumberAndAccountScope(phoneNumber, accountScope)) {
            throw new ApiException(HttpStatus.CONFLICT, "An account with this mobile number already exists.");
        }

        User user = new User();
        user.setEmail(email);
        user.setAccountScope(accountScope);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        // Trimmed server-side too -- the frontend already trims before sending, but the
        // @Pattern on fullName deliberately tolerates surrounding whitespace (so a stray space
        // isn't rejected outright), which means it can still arrive untrimmed from any other
        // API caller. This is the one place that actually persists, so it's the one place that
        // must not skip it.
        user.setFullName(request.fullName().trim());
        user.setPhoneNumber(phoneNumber);
        user = userRepository.save(user);
        passwordHistoryService.record(user.getId(), user.getPasswordHash());

        seedDefaultCategories(user.getId());
        return user;
    }

    /** Canonicalizes a registration-time phone number to E.164 ("+919999999999") so every stored
     *  number has the same shape going forward, rather than relying on phoneNumbersMatch()'s
     *  digit-only comparison to paper over inconsistent storage everywhere a phone number is
     *  compared.
     *
     *  Bug fix: this used to be the private static method that owned the rule, which meant the
     *  OTHER writer of User.phoneNumber -- AdminUserService.updateProfile -- structurally could
     *  not reuse it and stored whatever an admin typed, verbatim. That produced permanent account
     *  lockout (Firebase always sends E.164, so a raw "9999999999" never matches again) and
     *  defeated phone uniqueness (two spellings of one number are two distinct strings to the DB
     *  index). The rule now lives in {@link PhoneNumbers}, where both writers reach it; this stays
     *  as the local name the rest of this class already reads well with. */
    private static String normalizePhoneNumber(String raw) {
        return PhoneNumbers.normalize(raw);
    }

    /**
     * Bug fix / defensive guard: existsByEmailIgnoreCase() (used at registration) is a COUNT-based
     * query, but findByEmailIgnoreCase() -- used here, at login and forgot-password -- fetches a
     * single row and throws IncorrectResultSizeDataAccessException if more than one matches.
     * Case-insensitive email uniqueness was never enforced before this session (registration only
     * ever checked case-SENSITIVE uniqueness, and the DB's own UNIQUE constraint on email is
     * likewise case-sensitive), so it's possible for two pre-existing accounts to differ only by
     * case (e.g. "Jane@Example.com" and "jane@example.com"). Without this guard, any login or
     * forgot-password attempt touching either account 500s instead of resolving to the previous,
     * still-correct case-sensitive behavior -- failing this closed to "no match" is exactly as
     * safe as the account-not-found path every caller already handles, and never reveals the
     * ambiguity to the caller (no account enumeration).
     */
    private Optional<User> findUserByEmailIgnoreCaseSafely(String email, String scope) {
        try {
            return userRepository.findByEmailIgnoreCaseAndAccountScope(email, scope);
        } catch (IncorrectResultSizeDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Account lockout: after MAX_FAILED_LOGIN_ATTEMPTS consecutive bad passwords, the account
     * is locked for LOCKOUT_DURATION_MINUTES regardless of whether the next attempt would have
     * been correct. Deliberately checked before attempting authentication (not after), so a
     * locked account never even reaches password verification.
     */
    /**
     * Users shouldn't have to remember whether they signed up with their email or their phone
     * number -- a single field should work either way. Registration normalizes new phone numbers
     * to E.164 now (see normalizePhoneNumber()), but accounts created before that change may still
     * have the number stored exactly as originally typed -- so this tries the identifier as given,
     * a couple of raw +/no-+ variants (for those pre-normalization rows), AND the fully normalized
     * form (for rows that already went through normalizePhoneNumber() at registration) before
     * giving up. Bug fix: this used to only try a bare "+" + digits, which never reconstructs a
     * "+91"-prefixed stored number from a bare 10-digit login identifier -- a user who registered
     * with "9876543210" (stored as "+919876543210") and later typed the same bare number to log in
     * could never resolve to their own account. If nothing resolves, the original identifier is
     * returned unchanged so the existing authenticate()-then-catch flow below still fails with the
     * same generic "Invalid credentials" -- this never reveals whether an account exists, matching
     * the email-only behavior this replaces.
     */
    private String resolveEmailForLogin(String identifier, String scope) {
        if (identifier.contains("@")) {
            return identifier;
        }
        // Delegates the stored-format variants to IdentityLookup, so the normalisation used when a
        // number is WRITTEN and the variants tried when one is READ cannot drift apart.
        return identityLookup.byPhoneNumber(identifier, scope)
                .map(User::getEmail)
                .orElse(identifier);
    }

    /**
     * Which portal's account this request is authenticating against.
     *
     * Since V52 an email and a phone number identify a user only within a scope, so login has to
     * know which one it is resolving in. An absent value means USER: that is what every existing
     * client sends today, and it keeps a client that has not been updated behaving exactly as
     * before rather than failing.
     *
     * This is not an authorization input and cannot be used as one. It selects WHICH ROW to check a
     * password against; what that row is then allowed to do is decided entirely by its roles. A
     * caller who asks for ADMIN scope still needs the admin account's own password, and still gets
     * only the authorities that account actually holds -- so sending ADMIN from the user portal is
     * equivalent to visiting the admin portal, not a way to gain anything.
     */
    private static String scopeOf(LoginRequest request) {
        return User.SCOPE_ADMIN.equalsIgnoreCase(request.scope()) ? User.SCOPE_ADMIN : User.SCOPE_USER;
    }

    /**
     * <p><b>{@code noRollbackFor} is load-bearing here, exactly as it is on {@link #refresh}.</b>
     * The bad-password path WRITES and then THROWS: {@link #registerFailedLogin} increments
     * {@code failedLoginAttempts}, sets {@code lockedUntil} once the configured maximum is
     * reached, and records an {@code ACCOUNT_LOCKED} audit entry -- and then this method throws
     * {@code ApiException} to reject the attempt. {@code ApiException} is a RuntimeException, so
     * under the default rollback rule every one of those writes was discarded the instant it was
     * reported. The counter never persisted, never reached
     * {@code settings.getMaxFailedLoginAttempts()}, and per-account lockout therefore did not
     * function at all -- leaving {@code RateLimitFilter}'s per-IP limiter as the only working half
     * of a two-part defence its own comment describes as complementary.
     *
     * <p>Invisible to the unit tests here for the same reason {@code RefreshTokenService.rotate}
     * documents: they mock {@code userRepository}, so {@code save} was called, the verification
     * passed, and no transaction existed to undo it.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse login(LoginRequest request) {
        // Resolve email-or-phone down to the user's actual email up front -- everything below
        // this line (lockout check, Spring Security authentication, JWT subject) is unchanged
        // and still keyed on email exactly as before.
        String scope = scopeOf(request);
        String email = resolveEmailForLogin(request.identifier(), scope);
        User user = findUserByEmailIgnoreCaseSafely(email, scope).orElse(null);

        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.LOCKED,
                    "This account is temporarily locked due to repeated failed login attempts. Try again later.");
        }
        // An EXPIRED lockout clears the counter that produced it. Serving the lockout is the
        // penalty; carrying the count past it turns every subsequent typo into an instant re-lock.
        //
        // This was latent until per-account lockout started working. registerFailedLogin never
        // reset failedLoginAttempts, and only a SUCCESSFUL login did -- but while the counter was
        // being discarded by rollback (the noRollbackFor fix) it never reached the threshold, so
        // the second lock could not happen either. Making lockout function exposed it: an account
        // that served a 15-minute lockout came back with the counter still at the maximum, so one
        // wrong password re-locked it for another 15 minutes, indefinitely, with no way out except
        // remembering the password first time.
        if (user != null && user.getLockedUntil() != null && !user.getLockedUntil().isAfter(Instant.now())) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
        try {
            // Authenticated by the resolved user's ID, not their email: the Spring Security
            // principal is the id (see CurrentUserDetailsService), and an email would be ambiguous
            // across scopes. A null user here means no account matched -- passing a non-UUID
            // through keeps the same generic "Invalid credentials" failure rather than leaking
            // that no such account exists.
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                    user != null ? user.getId().toString() : "no-such-account", request.password()));
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Broadened from BadCredentialsException specifically: DaoAuthenticationProvider can
            // throw other AuthenticationException subtypes too (e.g. if a UserDetailsService
            // implementation is ever changed to throw DisabledException/LockedException itself)
            // — all of them mean "this login attempt failed," and all should count toward
            // lockout and return the same generic message, not leak which failure mode occurred.
            if (user != null) {
                registerFailedLogin(user);
            }
            throw new ApiException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }

        // user is guaranteed non-null here — authenticate() would have thrown otherwise.

        // Suspension, checked AFTER the password. See User.status / V23__user_account_status.sql
        // and AdminUserService.suspend.
        //
        // This used to sit above authenticate(), alongside the lockout check, on the reasoning that
        // "a suspended account shouldn't get a 'correct password' signal at all". That reasoning is
        // inverted, and it cost more than it bought: checking first meant ANY caller, supplying ANY
        // password or none, could POST an email address and read back "This account has been
        // suspended" where an unregistered address returned "Invalid credentials". That is an
        // account-existence oracle on an unauthenticated, public endpoint -- exactly what
        // resolveEmailForLogin, findUserByEmailIgnoreCaseSafely and the "no-such-account" principal
        // below all go out of their way to avoid, and it is worse than the signal it was avoiding,
        // because the person receiving it has proved nothing.
        //
        // Checked here, the message reaches only someone who already knows the password -- for whom
        // the account's existence is not news, and who does need to be told why they cannot get in
        // rather than being sent round the "forgot password" loop for a password that is correct.
        //
        // Deliberately before the counter reset below: a suspended account keeps accumulating
        // failed attempts and can still be locked out, which is the right behaviour for an account
        // under an administrator's sanction. Also before the USER_LOGIN audit entry -- no login
        // happened, and recording one would put a successful-sign-in row in the trail of an account
        // that cannot sign in.
        //
        // The lockout check above stays where it is. It cannot move: its whole purpose is to stop a
        // locked account reaching password verification, so "check it after the password" is not a
        // thing it can do. It leaks the same way and is reported rather than papered over here --
        // closing it means synthesising lockout state for identifiers that do not exist, which is a
        // design, not an edit.
        if (user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "This account has been suspended. Contact support for assistance.");
        }

        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        auditService.record(user.getId(), "USER_LOGIN", "User", user.getId());

        // Refresh token first: it is what mints the session, and the access token has to carry
        // that session's id in its sid claim.
        var issued = refreshTokenService.issue(user.getId());
        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(),
                user.getAccountScope());
        String refreshToken = issued.rawToken();
        return new AuthResponse(accessToken, refreshToken, user.getEmail(), user.getFullName(), user.isPhoneVerified(),
                PhoneMasking.mask(user.getPhoneNumber()));
    }

    /**
     * Exchanges a valid, unused refresh token for a new access token + a rotated refresh token.
     *
     * <p>{@code noRollbackFor} has to be here as well as on
     * {@link RefreshTokenService#rotate}, and this is the copy that actually decides. {@code rotate}
     * joins THIS transaction rather than opening its own, so when its rejection exception
     * propagates out through this method it is this boundary's rollback rule that runs. Marking
     * only the inner method looks correct, changes nothing, and leaves the revocations it writes —
     * including reuse detection signing out every session after a suspected token theft — quietly
     * discarded. See that method's own comment for the full reasoning.
     *
     * <p><b>Worth replacing eventually.</b> This is a rule about a transaction BOUNDARY, not about
     * the operation, so it has to be repeated by every future caller that wraps this method in a
     * transaction of its own — and forgetting reinstates the original bug with no visible symptom.
     * The sturdier shape is to commit the revocation in its own transaction ({@code REQUIRES_NEW},
     * in a separate bean since Spring does not proxy self-invocation — {@code StatementBackfillWorker}
     * used to exist for exactly that reason), so that a security state change cannot be undone by
     * whatever business operation happens to be reporting the failure. Not done here because it is
     * a refactor of a working, tested fix rather than a fix.
     *
     * <p>Until then, {@code RefreshTokenTransportIT.issuanceRotationInvalidationAndReuseDetection…}
     * is the guard: it replays a used cookie and asserts an untouched SECOND session dies, which
     * only a committed account-wide revocation satisfies. Anyone who adds an outer transaction
     * without this rule will see that test go red.
     */
    @Transactional(noRollbackFor = ApiException.class)
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

        String newAccessToken = jwtService.generateToken(user.getId(), user.getEmail(),
                rotation.newToken().sessionId(), user.getAccountScope());
        return new RefreshResponse(newAccessToken, rotation.newToken().rawToken());
    }

    @Transactional
    public LogoutResponse logout(LogoutRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return new LogoutResponse("Signed out.");
    }

    /**
     * Marks the current user's phone verified once Firebase attests it -- the frontend's own
     * Firebase client SDK already sent and confirmed the OTP directly against Firebase; this is
     * the one thing the backend needs to trust that instead of taking the frontend's word for it
     * (see PhoneVerificationProvider's own doc comment). A cryptographically valid token
     * for the WRONG phone number (e.g. stale client state, a different account's number) is
     * rejected just as firmly as an invalid one -- proving control of *some* phone number isn't
     * enough, it has to be the one on this account.
     */
    @Transactional
    public VerifyPhoneResponse verifyPhoneWithFirebase(UUID userId, String firebaseIdToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        String verifiedPhone = phoneVerificationProvider.verifyAndGetPhoneNumber(firebaseIdToken);
        if (!phoneNumbersMatch(verifiedPhone, user.getPhoneNumber())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The verified phone number doesn't match the one on this account.");
        }

        user.setPhoneVerified(true);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(userId, "PHONE_VERIFIED", "User", userId, Map.of("method", "firebase"));
        return new VerifyPhoneResponse("Phone number verified.");
    }

    /** Firebase's phone_number claim is always E.164 ("+919876543210"); User.phoneNumber may or
     *  may not carry the leading "+" depending on how it was typed at registration (see
     *  RegisterRequest's own pattern, which accepts either) -- compares digits only so that
     *  difference alone never causes a false mismatch.
     *
     *  Delegates to {@link PhoneNumbers#sameNumber} so the comparison rule and the normalization
     *  rule sit together. That also fixes the rows an un-normalized admin edit already wrote: a
     *  bare 10-digit stored number now matches Firebase's country-coded claim for the same number,
     *  where strict digit equality left those accounts unable to ever verify again. Normalizing
     *  the write path stops NEW lockouts; only this stops the existing ones being permanent. */
    private boolean phoneNumbersMatch(String a, String b) {
        return PhoneNumbers.sameNumber(a, b);
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
        String genericMessage = emailProvider.isConfigured()
                ? "If an account exists for that email, we've sent a password reset link."
                : "If an account exists for that email, a reset link has been issued.";
        var userOpt = findUserByEmailIgnoreCaseSafely(request.email(),
                User.SCOPE_ADMIN.equalsIgnoreCase(request.scope()) ? User.SCOPE_ADMIN : User.SCOPE_USER);
        if (userOpt.isEmpty()) {
            return new ForgotPasswordResponse(genericMessage, null);
        }

        // One live reset link per account at a time. Issuing a new link used to leave every
        // previously issued one usable for the rest of its own TTL, so "I didn't request this,
        // let me request my own" quietly widened the window instead of closing it.
        resetTokenRepository.markAllUnusedAsUsed(userOpt.get().getId(), Instant.now());

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

        if (emailProvider.isConfigured()) {
            EmailResult resetEmailResult = emailProvider.sendPasswordResetEmail(userOpt.get().getEmail(), resetLink);
            auditService.record(userOpt.get().getId(), "EMAIL_SENT", "User", userOpt.get().getId(), Map.of(
                    "type", "password_reset", "provider", resetEmailResult.provider().name(), "success", resetEmailResult.success()));
            // Real email exists — no reason to also hand the link back in the API response.
            return new ForgotPasswordResponse(genericMessage, null);
        }

        // No email provider configured — same dev-convenience fallback as before.
        return new ForgotPasswordResponse(genericMessage, resetLink);
    }

    /**
     * Reveals the account's real phone number for a valid, unused reset link (see
     * ResolveResetPasswordPhoneRequest's own doc comment for why the frontend needs it and why
     * this reset-token gate is enough to make that safe). Validates the token exactly like
     * resetPassword() does, but deliberately does NOT consume/mark it here -- that only happens
     * once the whole reset actually completes in resetPassword() below, so a user who looks up
     * the phone number but never finishes the flow can still use the same reset link again later
     * within its normal expiry window.
     */
    @Transactional(readOnly = true)
    public ResolveResetPasswordPhoneResponse resolveResetPasswordPhone(ResolveResetPasswordPhoneRequest request) {
        PasswordResetToken prt = validateResetToken(request.token());
        User user = userRepository.findById(prt.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            // Shouldn't be reachable in practice -- phone number is required at both
            // registration and admin-create time -- but User.phoneNumber has no NOT NULL
            // constraint at the DB level (V8), so this is a real, if unlikely, state to guard.
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This account has no phone number on file. Contact an administrator for help resetting your password.");
        }
        // BH-015, KNOWN AND DELIBERATELY STILL OPEN. This returns the account's phone number in
        // full to anyone holding a valid reset link, where register() and login() -- both of which
        // authenticate the caller far more strongly than a link from an inbox -- return
        // PhoneMasking.mask(). The weakest gate in the product hands back the most.
        //
        // <p><b>Masking here does not work, and was tried.</b> All three clients pass this value
        // straight to Firebase to SEND the code -- see ResetPassword.tsx, which calls
        // {@code sendPhoneVerificationCode(res.phoneNumber, ...)} with it. Returning "+•••••••705"
        // makes every password reset fail at the send. The number is not being disclosed
        // decoratively; the client-side Firebase architecture needs it to do the thing this
        // endpoint exists for.
        //
        // <p>Closing it properly means inverting the flow: the USER types their number, the client
        // sends the OTP to what they typed, and resetPassword() rejects the reset unless the
        // Firebase-verified number matches the account -- a check it ALREADY performs, so the
        // server-side half is done. What is missing is the UI change across three clients and the
        // decision to make people type their number. That is a product change, not a bug fix, and
        // doing half of it silently is how a reset flow breaks in production.
        //
        // <p>Until then the exposure is bounded by the reset token: unguessable, single-use,
        // 30-minute TTL, invalidated by any newer link, and now rate-limited (this endpoint was
        // outside every limiter, so a token holder could hammer it). What an attacker who has
        // already compromised the mailbox gains is the account's second factor -- the input to a
        // SIM swap -- handed over before completing the reset.
        return new ResolveResetPasswordPhoneResponse(user.getPhoneNumber());
    }

    @Transactional
    public ResetPasswordResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken prt = validateResetToken(request.token());

        User user = userRepository.findById(prt.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        // Second factor -- the reset token alone (proof of email access) is no longer enough;
        // see ResetPasswordRequest's own doc comment for why. Same defensive check as
        // verifyPhoneWithFirebase(): a valid token for the WRONG phone number is rejected just as
        // firmly as an invalid one.
        String verifiedPhone = phoneVerificationProvider.verifyAndGetPhoneNumber(request.firebaseIdToken());
        if (!phoneNumbersMatch(verifiedPhone, user.getPhoneNumber())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The verified phone number doesn't match the one on this account.");
        }

        // Bug fix: PasswordChangeService.complete() already rejects a new password identical to
        // the CURRENT one directly; this path only relied on passwordHistoryService catching it
        // indirectly (record() runs on every password write, so the current hash is always the
        // newest history row) -- which silently didn't hold for any account that existed before
        // password history started being recorded and hasn't changed its password since (zero
        // history rows). Without this, submitting the same password here returned a false
        // "Password updated" success, wrote a misleading PASSWORD_RESET audit entry, and sent a
        // "your password was changed" email for a password that never actually changed.
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "New password must be different from your current password.");
        }
        passwordHistoryService.rejectIfRecentlyUsed(user.getId(), request.newPassword());

        Instant now = Instant.now();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(now);
        user.setPasswordChangedAt(now);
        userRepository.save(user);
        passwordHistoryService.record(user.getId(), user.getPasswordHash());

        prt.setUsedAt(Instant.now());
        resetTokenRepository.save(prt);
        // Every OTHER live reset link for this account dies with the one just consumed. Marking
        // only the consumed token left an attacker who had triggered their own reset for the
        // victim's address holding a link that still worked for the rest of its 30-minute TTL --
        // so a victim who noticed and reset their own password could be immediately reset again,
        // with no recovery action available to them.
        int alsoInvalidated = resetTokenRepository.markAllUnusedAsUsed(user.getId(), now);

        // Password reset is the canonical response to a suspected compromise, and it has to end
        // the attacker's session, not just change the lock. Without this the attacker's refresh
        // token kept rotating for up to the 7-day absolute cap while the victim received a
        // "your password was changed" email suggesting the incident was resolved.
        // PasswordChangeService.complete -- the authenticated change-password path -- already
        // does this; RefreshTokenService's own class docs name password change as one of the
        // cases warranting account-wide revocation. The forgot-password path was never wired to
        // it. Unconditional here, unlike complete()'s opt-in signOutOtherDevices: whoever
        // completes a reset is not holding a session to preserve.
        refreshTokenService.revokeAllForUser(user.getId());

        auditService.record(user.getId(), "PASSWORD_RESET", "User", user.getId(),
                Map.of("method", "firebase_phone", "sessionsRevoked", true,
                        "otherResetLinksInvalidated", alsoInvalidated));
        EmailResult changedEmailResult = emailProvider.sendPasswordChangedEmail(user.getEmail());
        auditService.record(user.getId(), "EMAIL_SENT", "User", user.getId(), Map.of(
                "type", "password_changed", "provider", changedEmailResult.provider().name(), "success", changedEmailResult.success()));

        return new ResetPasswordResponse("Password updated — you can now sign in with your new password.");
    }

    /** Shared by resolveResetPasswordPhone() and resetPassword() -- both need the exact same
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

    // Bug fix: this issued DEFAULT_CATEGORIES.size() individual INSERTs (one save() call per
    // category) on every registration -- an easily-avoidable per-signup latency cost fixed by
    // building the whole batch in memory and writing it in one saveAll() call.
    private void seedDefaultCategories(java.util.UUID userId) {
        List<Category> categories = new ArrayList<>();
        for (String name : DEFAULT_CATEGORIES) {
            Category c = new Category();
            c.setUserId(userId);
            c.setName(name);
            c.setSystem(true);
            categories.add(c);
        }
        categoryRepository.saveAll(categories);
    }
}
