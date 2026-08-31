package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.dto.EmailChangeDtos.*;
import com.finora.entity.EmailChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.EmailChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import com.finora.util.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 (docs/proposals/authentication-account-security-review.md §2.7). The step-up-gated
 * Change Email flow: verify current identity -> a verification link is sent to and confirmed
 * against the NEW address -> commit. Mirrors {@link PhoneChangeService}'s session-based state
 * machine structurally, with the one substantive difference {@link EmailChangeDtos}'s own doc
 * comment names: this flow DOES have a "prove you still are who you say you are" first step
 * (via {@link GoogleReauthVerifier}, same as {@link PasswordChangeService#start}) -- email is the
 * account's password-reset delivery channel, so authorizing a change to it on a lower bar than
 * phone-change accepts would be worse, not better.
 *
 * Deliberately its own service rather than folded into PasswordChangeService/PhoneChangeService --
 * same reasoning both of those give: a focused, self-contained slice with its own narrow
 * dependency set.
 */
@Service
public class EmailChangeService {

    // Configurable rather than hardcoded, same reasoning as PhoneChangeService/
    // PasswordChangeService's identically-shaped properties.
    @Value("${app.security.email-change-session-expiry-minutes:15}")
    private long sessionTtlMinutes;

    private final UserRepository userRepository;
    private final EmailChangeSessionRepository sessionRepository;
    private final GoogleReauthVerifier googleReauthVerifier;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailChangeService(UserRepository userRepository, EmailChangeSessionRepository sessionRepository,
                               GoogleReauthVerifier googleReauthVerifier, RefreshTokenService refreshTokenService,
                               AuditService auditService, EmailProvider emailProvider, EmailProperties emailProperties) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.googleReauthVerifier = googleReauthVerifier;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /** Step 1: verify current identity, validate the requested address, open a session, and mail
     *  a verification link to it. Rejects an address identical to the one already on file
     *  (case-insensitively, matching the V52 scoped unique index this check is a pre-flight for)
     *  and one already claimed by another account in this scope
     *  (existsByEmailIgnoreCaseAndAccountScope) -- necessarily check-then-act, same as
     *  PhoneChangeService.start()'s identical comment explains; the DB's own uq_users_email_scope
     *  index is the authoritative backstop at complete()'s user save, turned into a clean 409 by
     *  GlobalExceptionHandler.handleDataIntegrityViolation. */
    @Transactional(noRollbackFor = ApiException.class)
    public StartResponse start(UUID userId, StartRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        requireActiveAccount(user);

        if (!googleReauthVerifier.verify(user, request.currentPassword(), request.googleIdToken(), request.appleIdToken())) {
            auditService.record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, user.isGoogleAccount()
                    ? "We couldn't verify your Google account. Please try again."
                    : user.isAppleAccount()
                            ? "We couldn't verify your Apple account. Please try again."
                            : "Current password is incorrect.");
        }

        // Bug fix (self-review): matches AuthService.register()'s own normalization
        // (request.email().trim().toLowerCase()) -- storing the case the caller happened to type
        // would still compare correctly everywhere (the unique index and every existsByEmailIgnoreCase*
        // lookup are already case-insensitive), but would let two different-looking stored values
        // represent the same account, which is worse for anything that ever compares them exactly.
        String newEmail = request.newEmail().trim().toLowerCase();
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That's already the email on your account.");
        }
        if (userRepository.existsByEmailIgnoreCaseAndAccountScope(newEmail, user.getAccountScope())) {
            auditService.record(userId, "EMAIL_CHANGE_REJECTED_DUPLICATE", "User", userId);
            throw new ApiException(HttpStatus.CONFLICT, "An account with this email already exists.");
        }

        Instant now = Instant.now();
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        EmailChangeSession session = new EmailChangeSession();
        session.setUserId(userId);
        session.setStatus(EmailChangeSession.Status.STARTED);
        session.setCurrentEmail(user.getEmail());
        session.setRequestedEmail(newEmail);
        session.setVerificationTokenHash(TokenHasher.sha256(rawToken));
        session.setExpiresAt(now.plusSeconds(sessionTtlMinutes * 60));
        session = sessionRepository.save(session);

        auditService.record(userId, "EMAIL_CHANGE_STARTED", "User", userId);

        // Bug fix: verify() needs BOTH sessionId and token (VerifyRequest's shape) -- a link
        // carrying only the token cannot actually be completed by whatever page consumes it.
        // Base64 URL-safe encoding (no padding) already produces query-string-safe characters for
        // rawToken; session.getId() is a UUID, equally safe unescaped.
        String verifyLink = emailProperties.resolveBaseUrl(null) + "/email-change-verify?sessionId="
                + session.getId() + "&token=" + rawToken;
        AfterCommit.run("email change verification email", () -> {
            EmailResult result = emailProvider.sendEmailChangeVerificationEmail(newEmail, verifyLink);
            auditService.record(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "email_change_verification", "provider", result.provider().name(),
                    "success", result.success()));
        });

        return new StartResponse(session.getId().toString(),
                emailProvider.isConfigured() ? null : verifyLink);
    }

    /** Step 2: verify the token from the link proves control of THIS session's requested address,
     *  not the account's existing one -- the token is checked against this session's own stored
     *  hash (see EmailChangeSession), not a global lookup, the same session-scoping
     *  PhoneChangeService.verifyOtp() applies to its Firebase token. */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyResponse verify(UUID userId, VerifyRequest request) {
        EmailChangeSession session = loadActiveSession(userId, request.sessionId(), EmailChangeSession.Status.STARTED,
                "This step has already been completed, or the session is no longer valid. Please start again.");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        requireActiveAccount(user);

        if (!TokenHasher.sha256(request.token()).equals(session.getVerificationTokenHash())) {
            auditService.record(userId, "INVALID_EMAIL_CHANGE_TOKEN", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "This verification link is invalid.");
        }

        session.setEmailVerifiedAt(Instant.now());
        session.setStatus(EmailChangeSession.Status.EMAIL_VERIFIED);
        sessionRepository.save(session);

        auditService.record(userId, "EMAIL_CHANGE_TOKEN_VERIFIED", "User", userId);
        return new VerifyResponse("Verified.");
    }

    /** Step 3: commit the new address. Only reachable once the session itself shows
     *  EMAIL_VERIFIED -- server-side state, not a client-supplied flag. Also marks the account
     *  emailVerified -- the link that was just clicked proves control of this exact address, the
     *  same fact AuthService.verifyEmail records that flag for.
     *
     *  Idempotency: a session that's already COMPLETED returns the same outcome again rather than
     *  re-running the side effects -- same pattern as PhoneChangeService.complete()/
     *  PasswordChangeService.complete().
     *
     *  Session revocation is unconditional (no signOutOtherDevices toggle, unlike
     *  PasswordChangeService): there is no frontend UI for this flow yet to carry such a flag, and
     *  the safer default absent one -- established by the Phase 3.5 session-invalidation audit's
     *  identical fix to PhoneChangeService -- is to revoke rather than leave every other session
     *  alive by default, especially for the field a password reset is delivered through. */
    @Transactional(noRollbackFor = ApiException.class)
    public CompleteResponse complete(UUID userId, CompleteRequest request, UUID currentSessionId) {
        EmailChangeSession session = resolveSession(userId, request.sessionId());

        if (session.getStatus() == EmailChangeSession.Status.COMPLETED) {
            return new CompleteResponse(COMPLETE_MESSAGE, session.getRequestedEmail());
        }
        if (session.getStatus() != EmailChangeSession.Status.EMAIL_VERIFIED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Confirm the link sent to your new email before completing this change.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        // Deliberately after the COMPLETED-session idempotency check above, not before -- same
        // ordering PhoneChangeService.complete()/PasswordChangeService.complete() use, for the
        // same reason: a session that already succeeded must keep returning its original outcome
        // even if the account's status changed afterward.
        requireActiveAccount(user);

        Instant now = Instant.now();
        user.setEmail(session.getRequestedEmail());
        user.setEmailVerified(true);
        user.setUpdatedAt(now);
        userRepository.save(user);

        session.setStatus(EmailChangeSession.Status.COMPLETED);
        session.setCompletedAt(now);
        sessionRepository.save(session);

        refreshTokenService.revokeAllOtherSessionsForUser(userId, currentSessionId);

        auditService.record(userId, "EMAIL_CHANGED", "User", userId, Map.of("method", "authenticated_settings_link_verified"));

        return new CompleteResponse(COMPLETE_MESSAGE, session.getRequestedEmail());
    }

    private static final String COMPLETE_MESSAGE = "Your email address has been updated.";

    /** Same guard PasswordChangeService/PhoneChangeService apply at every step, for the identical
     *  reason: a suspended/deactivated/pending-deletion account's own still-valid JWT must not be
     *  usable to change the email out from under an account that is locked out or that its own
     *  owner just stepped away from -- and a hijacked email is a worse outcome here than most
     *  fields this guard already protects, since it's the account's own password-reset channel. */
    private void requireActiveAccount(User user) {
        if (user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended.");
        }
        if (user.isDeactivated()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account is deactivated.");
        }
        if (user.isPendingDeletion() || user.isDeleted()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account is scheduled for deletion.");
        }
    }

    /** Shared by verify() and complete() -- both need the same "does this session belong to this
     *  user, is it in the right state, and has it expired" checks. Mirrors
     *  PhoneChangeService/PasswordChangeService's identically-named helper. */
    private EmailChangeSession loadActiveSession(UUID userId, String rawSessionId,
                                                  EmailChangeSession.Status requiredStatus, String wrongStateMessage) {
        EmailChangeSession session = resolveSession(userId, rawSessionId);
        if (session.getStatus() != requiredStatus) {
            throw new ApiException(HttpStatus.BAD_REQUEST, wrongStateMessage);
        }
        return session;
    }

    /** Looks up the session and lazily transitions it to EXPIRED if its TTL has passed. Mirrors
     *  PhoneChangeService/PasswordChangeService's identically-named helper, including the same
     *  reasoning for never re-deriving a COMPLETED session into EXPIRED. */
    private EmailChangeSession resolveSession(UUID userId, String rawSessionId) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid email change session.");
        }
        EmailChangeSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid email change session."));

        boolean stillInProgress = session.getStatus() == EmailChangeSession.Status.STARTED
                || session.getStatus() == EmailChangeSession.Status.EMAIL_VERIFIED;
        if (stillInProgress && session.isExpired()) {
            session.setStatus(EmailChangeSession.Status.EXPIRED);
            sessionRepository.save(session);
        }
        if (session.getStatus() == EmailChangeSession.Status.EXPIRED) {
            auditService.record(userId, "SESSION_EXPIRED", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "This email change session has expired. Please start again.");
        }
        return session;
    }
}
