package com.finora.service;

import com.finora.dto.AdminMfaDtos.ConfirmResponse;
import com.finora.dto.AdminMfaDtos.EnrollResponse;
import com.finora.entity.AdminMfaChallenge;
import com.finora.entity.AdminMfaRecoveryCode;
import com.finora.entity.AdminTotpCredential;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AdminMfaChallengeRepository;
import com.finora.repository.AdminMfaRecoveryCodeRepository;
import com.finora.repository.AdminTotpCredentialRepository;
import com.finora.repository.UserRepository;
import com.finora.security.crypto.EncryptionService;
import com.finora.security.mfa.TotpGenerator;
import com.finora.util.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Opt-in TOTP MFA for
 * {@code SCOPE_ADMIN} accounts -- see V98's migration comment for the schema and the three-table
 * split, and {@link TotpGenerator} for the algorithm itself.
 *
 * <h2>Opt-in, deliberately -- not force-enabled for existing sessions</h2>
 *
 * The finding this closes is real (a phished or reused admin password is currently a full,
 * single-factor account takeover), but forcing MFA on for every admin the moment this deploys
 * would risk locking the only admin an installation has out of the admin portal entirely -- a
 * materially worse outcome than the gap it closes, and on a system that has no separate "MFA
 * recovery for a locked-out sole admin" story yet. Self-service enrollment closes the gap for
 * every admin who opts in, with zero risk to anyone who has not yet -- an admin who never enrolls
 * is in exactly the position they are in today, not worse.
 *
 * <h2>Why a challenge-token second step, not a second field on {@code login()}'s own response</h2>
 *
 * The password half of login is unconditionally weaker than intended if the response to a
 * correct-password-wrong-or-missing-code request looks any different (in shape, timing, or
 * detail) from a genuinely wrong password -- so a correct password against an MFA-enabled account
 * gets exactly the same treatment {@code AUTH_ACCOUNT_DEACTIVATED} already established for
 * "password was right, one more step remains": a distinguishable error code carrying a short-lived
 * opaque token in {@code ApiException}'s details map, consumed by a separate endpoint. See
 * {@code AuthService.login()}'s own MFA branch and {@code ErrorCode.AUTH_MFA_REQUIRED}.
 */
@Service
public class AdminMfaService {

    private static final String ISSUER = "Finora Admin";
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int CHALLENGE_TTL_MINUTES = 5;

    /**
     * Off by default. The MFA implementation itself (this class, TotpGenerator, AuthService's
     * gate) is complete and RFC 6238-tested, but the admin portal has no enrollment/verification/
     * recovery UI yet -- an admin (or anyone testing) who called {@link #beginEnrollment}/
     * {@link #confirm} directly today would have MFA required on their very next login with no
     * way to complete it through the web app, a real lockout with no self-service way back in.
     * Set ADMIN_MFA_ENABLED=true only once that UI exists. See {@link #requireFeatureEnabled()}
     * for how every entry point below defers to this, and {@code AuthService.login()}/
     * {@code completeMfaLogin()} for the same gate on the login side.
     */
    @Value("${app.admin-mfa.enabled:false}")
    private boolean featureEnabled;

    private final AdminTotpCredentialRepository credentialRepository;
    private final AdminMfaRecoveryCodeRepository recoveryCodeRepository;
    private final AdminMfaChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final GoogleReauthVerifier googleReauthVerifier;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminMfaService(AdminTotpCredentialRepository credentialRepository,
                            AdminMfaRecoveryCodeRepository recoveryCodeRepository,
                            AdminMfaChallengeRepository challengeRepository,
                            UserRepository userRepository,
                            EncryptionService encryptionService,
                            GoogleReauthVerifier googleReauthVerifier,
                            AuditService auditService) {
        this.credentialRepository = credentialRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.encryptionService = encryptionService;
        this.googleReauthVerifier = googleReauthVerifier;
        this.auditService = auditService;
    }

    /** Single source of truth for whether the feature is reachable at all -- {@code
     *  AuthService.login()}/{@code completeMfaLogin()} defer to this rather than keeping their
     *  own copy of the same {@code app.admin-mfa.enabled} property, so the two can never
     *  disagree about it. */
    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /** Every public entry point below calls this first, including {@link #isEnabled} -- the
     *  point is not just to stop new enrollment while the flag is off, it's that NOTHING about
     *  this feature responds normally, {@code /admin-mfa/status} included, so a direct caller
     *  gets an unambiguous "not available" rather than a status page that quietly looks usable. */
    private void requireFeatureEnabled() {
        if (!featureEnabled) {
            throw new ApiException(ErrorCode.AUTH_MFA_NOT_AVAILABLE);
        }
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        requireFeatureEnabled();
        return credentialRepository.findByUserId(userId).map(AdminTotpCredential::isEnabled).orElse(false);
    }

    /**
     * Starts (or restarts) enrollment. A fresh secret every call, deliberately: re-enrolling
     * (e.g. after losing the authenticator app before ever confirming) must not resume with a
     * secret whose QR code the user already failed to use. Overwrites any existing NOT-YET-enabled
     * row for this user; refuses outright if MFA is already {@link AdminTotpCredential#isEnabled}
     * -- disable() first, so there is never a moment where a fresh, unconfirmed secret has quietly
     * replaced a working one still protecting real logins.
     */
    @Transactional
    public EnrollResponse beginEnrollment(UUID userId) {
        requireFeatureEnabled();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        credentialRepository.findByUserId(userId).ifPresent(existing -> {
            if (existing.isEnabled()) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "MFA is already enabled on this account. Disable it before re-enrolling.");
            }
            credentialRepository.delete(existing);
        });

        String secret = TotpGenerator.generateSecret();
        AdminTotpCredential credential = new AdminTotpCredential();
        credential.setUserId(userId);
        credential.storeSecret(encryptionService.encrypt(secret));
        credentialRepository.save(credential);

        return new EnrollResponse(secret, TotpGenerator.provisioningUri(secret, ISSUER, user.getEmail()));
    }

    /**
     * Proves the enrollment actually works before it starts protecting logins. On success, mints
     * and returns {@link #RECOVERY_CODE_COUNT} single-use backup codes -- this is the only moment
     * they are ever available in the clear (only hashes are persisted, see
     * {@link AdminMfaRecoveryCode}), so losing them means generating a fresh set replaces the old.
     *
     * @param actingAdminId who actually performed the action, written into the audit metadata as
     *        {@code "actorId"} -- same convention {@code AccountService.create()} uses. Always
     *        equal to {@code userId} here: {@code AdminMfaController} is this method's only
     *        caller and is self-service-only (see its own doc comment), with no admin-proxy path
     *        onto another account's MFA. Threaded as a real parameter rather than read from
     *        {@code userId} a second time inside this method, so the caller's identity is visible
     *        at the call site and this stays correct by construction if an admin-proxy path is
     *        ever added later.
     */
    @Transactional
    public ConfirmResponse confirm(UUID userId, String code, UUID actingAdminId) {
        requireFeatureEnabled();
        AdminTotpCredential credential = credentialRepository.findByUserId(userId)
                .filter(c -> !c.isEnabled())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No pending MFA enrollment for this account. Start enrollment first."));

        String secret = encryptionService.decrypt(credential.secret());
        if (!TotpGenerator.verify(secret, code)) {
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE);
        }

        credential.markEnabled();
        credentialRepository.save(credential);

        List<String> rawCodes = generateRecoveryCodes();
        recoveryCodeRepository.deleteByUserId(userId); // clears any leftover set from a prior enrollment
        for (String raw : rawCodes) {
            AdminMfaRecoveryCode entity = new AdminMfaRecoveryCode();
            entity.setUserId(userId);
            entity.setCodeHash(TokenHasher.sha256(raw));
            recoveryCodeRepository.save(entity);
        }

        auditService.record(userId, "ADMIN_MFA_ENABLED", "User", userId,
                Map.of("actorId", actingAdminId.toString()));
        return new ConfirmResponse(rawCodes);
    }

    /** Requires fresh proof of the account's own sign-in credential -- the same bar
     *  {@code UserAccountLifecycleService.deactivate} and password change already hold a
     *  security-downgrading action to, via the same {@link GoogleReauthVerifier} -- AND, when MFA
     *  is currently enabled, a live TOTP code or an unused recovery code. Removing MFA is exactly
     *  the downgrade enrolling it protected against: whoever can do this can turn a two-factor
     *  account back into a one-factor one, so it needs the same proof-of-possession of the second
     *  factor that {@link #confirm} required to turn it on, not password/re-auth alone -- password
     *  re-auth alone would mean a stolen live session plus the account password is enough to strip
     *  MFA with no TOTP secret or recovery code ever proven, undoing the protection MFA is meant to
     *  add against exactly that kind of session compromise. Skipped only when there is no enabled
     *  credential to protect (nothing for a second factor to guard).
     *
     *  @param actingAdminId see {@link #confirm}'s own doc comment -- same convention, same
     *         always-equal-to-userId guarantee, same reason. */
    @Transactional
    public void disable(UUID userId, String currentPassword, String googleIdToken, String appleIdToken, String code, UUID actingAdminId) {
        requireFeatureEnabled();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!googleReauthVerifier.verify(user, currentPassword, googleIdToken, appleIdToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Current credential could not be verified.");
        }

        Optional<AdminTotpCredential> enabledCredential = credentialRepository.findByUserId(userId)
                .filter(AdminTotpCredential::isEnabled);
        if (enabledCredential.isPresent() && !verifyMfaCode(userId, enabledCredential.get(), code)) {
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE);
        }

        credentialRepository.deleteByUserId(userId);
        recoveryCodeRepository.deleteByUserId(userId);
        challengeRepository.deleteByUserId(userId);
        auditService.record(userId, "ADMIN_MFA_DISABLED", "User", userId,
                Map.of("actorId", actingAdminId.toString()));
    }

    /**
     * Called by {@code AuthService.login()} once the password has already checked out for an
     * MFA-enabled account. The raw token travels to the client in {@code ApiException}'s details
     * map (never logged, never persisted) -- only its hash is stored, same convention as every
     * other opaque server-side token in this codebase (password reset, reactivation).
     */
    @Transactional
    public String issueChallenge(UUID userId) {
        String rawToken = generateOpaqueToken();
        AdminMfaChallenge challenge = new AdminMfaChallenge();
        challenge.setUserId(userId);
        challenge.setTokenHash(TokenHasher.sha256(rawToken));
        challenge.setExpiresAt(Instant.now().plusSeconds(CHALLENGE_TTL_MINUTES * 60L));
        challengeRepository.save(challenge);
        return rawToken;
    }

    /**
     * Resolves a challenge token to the user it belongs to, accepting either a live TOTP code or
     * an unused recovery code -- checked in that order, since a TOTP code is the expected path and
     * a recovery code is the fallback for "I don't have my authenticator." Consumes the challenge
     * (and, if that branch matched, the recovery code) on success so neither can be replayed.
     *
     * @return the authenticated user's id
     * @throws ApiException {@code AUTH_MFA_INVALID_CODE} for every failure mode alike (expired,
     *         already-used, or unknown challenge token; wrong TOTP code; wrong or already-used
     *         recovery code) -- distinguishing them would tell a guesser which part of their guess
     *         was closer, the same reasoning {@code AUTH_INVALID_CREDENTIALS} already applies.
     */
    @Transactional
    public UUID verifyChallenge(String rawChallengeToken, String code) {
        AdminMfaChallenge challenge = challengeRepository.findByTokenHash(TokenHasher.sha256(rawChallengeToken))
                .filter(c -> c.getUsedAt() == null)
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE));

        UUID userId = challenge.getUserId();
        AdminTotpCredential credential = credentialRepository.findByUserId(userId)
                .filter(AdminTotpCredential::isEnabled)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE));

        if (!verifyMfaCode(userId, credential, code)) {
            throw new ApiException(ErrorCode.AUTH_MFA_INVALID_CODE);
        }

        challenge.markUsed();
        challengeRepository.save(challenge);
        return userId;
    }

    /** Checked in that order, since a TOTP code is the expected path and a recovery code is the
     *  fallback for "I don't have my authenticator." Consumes the recovery code (marks it used) if
     *  that's the branch that matched, same as {@link #verifyChallenge} already relied on. Shared
     *  by {@link #verifyChallenge} and {@link #disable} -- both are "prove you still hold the
     *  second factor," just gating a different action. */
    private boolean verifyMfaCode(UUID userId, AdminTotpCredential credential, String code) {
        String secret = encryptionService.decrypt(credential.secret());
        if (TotpGenerator.verify(secret, code)) {
            return true;
        }
        return tryConsumeRecoveryCode(userId, code);
    }

    private boolean tryConsumeRecoveryCode(UUID userId, String code) {
        if (code == null || code.isBlank()) return false;
        // Normalized identically to how generateRecoveryCodes() formats what it hands out
        // (uppercase hex, "XXXXX-XXXXX") minus tolerance for a dropped dash or stray whitespace --
        // a human retyping a printed backup code is exactly where that forgiveness is worth it.
        String normalized = code.trim().toUpperCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
        String formatted = normalized.length() == 10 ? normalized.substring(0, 5) + "-" + normalized.substring(5) : code;
        Optional<AdminMfaRecoveryCode> match = recoveryCodeRepository
                .findByUserIdAndCodeHashAndUsedAtIsNull(userId, TokenHasher.sha256(formatted));
        match.ifPresent(rc -> {
            rc.markUsed();
            recoveryCodeRepository.save(rc);
            auditService.record(userId, "ADMIN_MFA_RECOVERY_CODE_USED", "User", userId, Map.of());
        });
        return match.isPresent();
    }

    /** 10 codes, each 10 uppercase hex characters formatted in two groups of 5 -- unambiguous to
     *  read back (hex digits only, no visually similar letter/digit pairs to confuse) and short
     *  enough to type by hand if a QR/copy-paste path isn't available. */
    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] raw = new byte[5];
            secureRandom.nextBytes(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02X", b));
            codes.add(hex.substring(0, 5) + "-" + hex.substring(5, 10));
        }
        return codes;
    }

    private String generateOpaqueToken() {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
