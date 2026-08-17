package com.finora.service;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The "prove you're still you" check every sensitive self-service action re-runs before it
 * proceeds -- PasswordChangeService.start (change password, and by extension account deletion,
 * which reuses that same session), UserAccountLifecycleService.deactivate, and
 * DataExportService.buildBundle. Each used to inline its own
 * {@code passwordEncoder.matches(currentPassword, ...)} call directly, which is correct for an
 * ordinary account but can never succeed for one created via Sign in with Google --
 * AuthService#createGoogleUserRecord's passwordHash is a random value nobody, including the
 * user, ever knows. This class is the one place that decides which proof a given account can
 * actually supply, branching on {@link User#getSignInMethod()} -- callers ask "did this
 * credential check out" and keep their own existing throw/audit shape unchanged, since those
 * already differ slightly between the three call sites (some use
 * {@code AuditService.record}, wrapped in {@code noRollbackFor}; others use
 * {@code recordEvenOnRollback} instead).
 */
@Service
public class GoogleReauthVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleReauthVerifier.class);

    private final PasswordEncoder passwordEncoder;
    private final GoogleIdTokenVerifierService googleIdTokenVerifierService;

    public GoogleReauthVerifier(PasswordEncoder passwordEncoder, GoogleIdTokenVerifierService googleIdTokenVerifierService) {
        this.passwordEncoder = passwordEncoder;
        this.googleIdTokenVerifierService = googleIdTokenVerifierService;
    }

    /**
     * @param currentPassword the account's real password, for a {@code PASSWORD}-method account
     *                        -- ignored for a {@code GOOGLE}-method one.
     * @param googleIdToken a fresh Google ID token proving control of the SAME Google identity
     *                      right now, for a {@code GOOGLE}-method account -- ignored for a
     *                      {@code PASSWORD} one. Deliberately re-verified here rather than
     *                      trusted from a prior sign-in: the whole point of this check is proof
     *                      of control at THIS moment (mirroring what re-entering a password
     *                      proves), not of a session established minutes or hours ago.
     * @return true if the credential this account's own sign-in method actually uses was proven,
     *         false otherwise. Never throws for an ordinary verification failure (wrong
     *         password, a Google token that doesn't verify, or one that verifies to a different
     *         email) -- each caller keeps deciding its own audit action and message, exactly as
     *         before this class existed. DOES let a genuine system-configuration problem (Sign
     *         in with Google not configured on this server) propagate as its own ApiException
     *         rather than being swallowed into a misleading "current password is incorrect" for
     *         an account that never had a password to check in the first place.
     */
    public boolean verify(User user, String currentPassword, String googleIdToken) {
        if (user.isGoogleAccount()) {
            if (googleIdToken == null || googleIdToken.isBlank()) {
                return false;
            }
            GoogleIdentity identity;
            try {
                identity = googleIdTokenVerifierService.verify(googleIdToken);
            } catch (ApiException e) {
                if (e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                    throw e;
                }
                log.warn("Google re-auth token verification failed for user {}", user.getId());
                return false;
            }
            return identity.email().trim().equalsIgnoreCase(user.getEmail());
        }
        // Blank, not just null: currentPassword is no longer @NotBlank at the DTO layer (it's
        // conditionally required, depending on this same signInMethod check), so a blank string
        // reaches here now where it never used to. Checked explicitly rather than left to
        // passwordEncoder.matches("", hash) -- BCrypt would still correctly return false, but
        // there's no reason to spend a ~100ms hash comparison on a request that's already known
        // to be empty.
        return currentPassword != null && !currentPassword.isBlank()
                && passwordEncoder.matches(currentPassword, user.getPasswordHash());
    }
}
