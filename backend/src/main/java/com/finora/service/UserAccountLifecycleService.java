package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The self-service account lifecycle: deactivate (reversible) today, delete-request + purge
 * (Phase B) to follow. Deliberately its own service rather than folded into AuthService or
 * AdminUserService -- this is the USER-scope, self-service half of account lifecycle management,
 * on a different trust boundary from both: AuthService's login/reactivate flows act on an
 * unauthenticated or just-authenticating caller, and AdminUserService acts on someone else's
 * account under an admin permission grant. This one always acts on the caller's own account.
 */
@Service
public class UserAccountLifecycleService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final EmailProvider emailProvider;
    private final RequestMetadata requestMetadata;
    private final PasswordChangeService passwordChangeService;

    public UserAccountLifecycleService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                        RefreshTokenService refreshTokenService, AuditService auditService,
                                        EmailProvider emailProvider, RequestMetadata requestMetadata,
                                        PasswordChangeService passwordChangeService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.emailProvider = emailProvider;
        this.requestMetadata = requestMetadata;
        this.passwordChangeService = passwordChangeService;
    }

    /**
     * Reversible: blocks login, evicts every active session, and retains all data untouched.
     * AuthService.login() recognizes DEACTIVATED specially and offers a reactivation path rather
     * than a dead end -- see that method's own doc comment.
     *
     * @param reason one of User.DEACTIVATION_REASONS -- required (product decision: the small
     *               extra step is worth the churn-analysis data for an otherwise fully-reversible
     *               action). Validated here, not just at the DTO layer, because the DB CHECK
     *               constraint (V88) is the actual source of truth for the allowed set and a
     *               request-layer @Pattern would be a second place for that list to drift from.
     * @param note optional free text alongside the reason; never validated beyond length (DTO).
     */
    @Transactional
    public void deactivate(UUID userId, String currentPassword, String reason, String note) {
        User user = requireUser(userId);
        requireUserScope(user);

        if (!User.DEACTIVATION_REASONS.contains(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'" + reason + "' is not a recognized deactivation reason.");
        }

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.record(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }

        Instant now = Instant.now();
        user.setStatus(User.STATUS_DEACTIVATED);
        user.setDeactivationReason(reason);
        // Blank note stored as null, not "" -- consistent with every other optional free-text
        // field in this codebase (e.g. RegisterRequest's trimmed fields), and it keeps "no note"
        // one unambiguous value instead of two.
        user.setDeactivationNote((note == null || note.isBlank()) ? null : note);
        user.setDeactivatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        // Status alone blocks new logins/refreshes, but not an access token already issued (up to
        // 15 minutes) -- same gap AdminUserService.suspend's own doc comment describes, closed the
        // same way: revoke every refresh token in this transaction so the session can't outlive
        // the access token it's currently holding.
        refreshTokenService.revokeAllForUser(userId);

        String ip = requestMetadata.ip();
        String device = requestMetadata.device();
        Map<String, Object> auditMetadata = requestMetadata.addTo(new HashMap<>(Map.of(
                "method", "self_service", "reason", reason)));
        auditService.record(userId, "ACCOUNT_DEACTIVATED", "User", userId, auditMetadata);

        String email = user.getEmail();
        AfterCommit.run("account deactivated email", () -> {
            EmailResult result = emailProvider.sendAccountDeactivatedEmail(email, now, device, ip);
            auditService.record(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "account_deactivated", "provider", result.provider().name(),
                    "success", result.success()));
        });
    }

    /**
     * Irreversible: current-password+OTP already proven by sessionId (see PasswordChangeService.
     * consumeForAccountDeletion), sets PENDING_DELETION, revokes every session, sends a
     * no-cancel-link confirmation email. AccountPurgeSweepService purges the account
     * AccountPurgeSweepService.MINIMUM_SAFETY_BUFFER-floored 48h later -- there is deliberately no
     * self-service undo (product decision), unlike deactivate() above.
     *
     * @param sessionId a PasswordChangeSession id already at OTP_VERIFIED -- the frontend drives
     *                  the exact same start()/verifyOtp() calls ChangePasswordModal uses. No
     *                  currentPassword parameter: the session itself is that proof, same as
     *                  PasswordChangeService.complete() never re-asks for it either.
     */
    @Transactional
    public void requestDeletion(UUID userId, String sessionId) {
        User user = requireUser(userId);
        requireUserScope(user);

        // A suspension is an admin's call, not something a user should be able to route around
        // via self-service deletion -- they need an admin to reactivate first, then can delete
        // normally. Checked before consuming the OTP session so a blocked attempt doesn't burn it.
        if (user.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended. Contact support for assistance.");
        }
        if (user.isPendingDeletion()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Your account is already scheduled for deletion.");
        }
        if (user.isDeleted()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
        }

        // Proves current-password + OTP for THIS request; throws ApiException(BAD_REQUEST) if the
        // session is invalid, expired, or not at OTP_VERIFIED. Also consumes it (DELETION_CONFIRMED)
        // so it can never be replayed into an actual password change.
        passwordChangeService.consumeForAccountDeletion(userId, sessionId);

        Instant now = Instant.now();
        user.setStatus(User.STATUS_PENDING_DELETION);
        user.setDeletionRequestedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        // Same reasoning as deactivate()'s call below: status alone blocks new logins/refreshes,
        // not an access token already issued (up to 15 minutes) -- revoke every refresh token in
        // this transaction so the session can't outlive the access token it's currently holding.
        refreshTokenService.revokeAllForUser(userId);

        Map<String, Object> auditMetadata = requestMetadata.addTo(new HashMap<>(Map.of("method", "self_service")));
        auditService.record(userId, "ACCOUNT_DELETION_REQUESTED", "User", userId, auditMetadata);

        String email = user.getEmail();
        AfterCommit.run("account deletion requested email", () -> {
            EmailResult result = emailProvider.sendAccountDeletionRequestedEmail(email, now);
            auditService.record(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "account_deletion_requested", "provider", result.provider().name(),
                    "success", result.success()));
        });
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    /** Admin/support account lifecycle stays an admin-portal operation, not something reachable
     *  through the same shared /users/me endpoints a regular user token can call -- see this
     *  class's own doc comment on the trust boundary these endpoints sit on. */
    private void requireUserScope(User user) {
        if (User.SCOPE_ADMIN.equals(user.getAccountScope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This action is not available for admin accounts.");
        }
    }
}
