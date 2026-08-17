package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final GoogleReauthVerifier googleReauthVerifier;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final EmailProvider emailProvider;
    private final RequestMetadata requestMetadata;
    private final PasswordChangeService passwordChangeService;
    private final AccountPurgeSweepService accountPurgeSweepService;
    private final TransactionTemplate transactionTemplate;

    public UserAccountLifecycleService(UserRepository userRepository, GoogleReauthVerifier googleReauthVerifier,
                                        RefreshTokenService refreshTokenService, AuditService auditService,
                                        EmailProvider emailProvider, RequestMetadata requestMetadata,
                                        PasswordChangeService passwordChangeService,
                                        AccountPurgeSweepService accountPurgeSweepService,
                                        TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.googleReauthVerifier = googleReauthVerifier;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.emailProvider = emailProvider;
        this.requestMetadata = requestMetadata;
        this.passwordChangeService = passwordChangeService;
        this.accountPurgeSweepService = accountPurgeSweepService;
        this.transactionTemplate = transactionTemplate;
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
    public void deactivate(UUID userId, String currentPassword, String googleIdToken, String reason, String note) {
        User user = requireUser(userId);
        requireUserScope(user);

        // Same reasoning as AuthService.login()'s isPendingDeletion()/isDeleted() checks: the
        // account's real passwordHash is still on the row until AccountPurgeSweepService's last
        // purge step anonymizes it, and requestDeletion() only revokes refresh tokens -- an access
        // token already issued keeps working for up to 15 minutes past the status change. Without
        // this, that window (or simply the same still-known current password, once a fresh login
        // is blocked) would let someone deactivate their way out of a request that requestDeletion
        // ()'s own doc comment and confirmation email both promise has "no way to cancel."
        if (user.isPendingDeletion() || user.isDeleted()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This account is scheduled for deletion and can no longer be modified.");
        }

        if (!User.DEACTIVATION_REASONS.contains(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'" + reason + "' is not a recognized deactivation reason.");
        }

        if (!googleReauthVerifier.verify(user, currentPassword, googleIdToken)) {
            // recordEvenOnRollback, not record: deactivate() is plain @Transactional (no
            // noRollbackFor), so a bare record() here would be rolled back along with everything
            // else the moment ApiException propagates -- see AuditService.recordEvenOnRollback's
            // own doc comment for the DataExportService bug this is the same shape as.
            auditService.recordEvenOnRollback(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, user.isGoogleAccount()
                    ? "We couldn't verify your Google account. Please try again."
                    : "Current password is incorrect.");
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
     * Irreversible AND instant (product decision, changed from the original 48h-delayed purge):
     * current-password+OTP already proven by sessionId (see PasswordChangeService.
     * consumeForAccountDeletion), and by the time this call returns the account and everything
     * {@code AccountPurgeSweepService.purgeOne} purges/anonymizes is already gone. There is
     * deliberately no self-service undo, unlike deactivate() above -- and now, deliberately, no
     * waiting period during which one could exist either. The ONLY recourse for a request that
     * was not really the account owner's own choice (a compromised session, someone else with
     * device access) is contacting support before they act, not after -- see the confirmation
     * email this sends once the purge has actually finished.
     *
     * <p>Two phases, not one {@code @Transactional} method. Phase one -- validate, consume the
     * OTP session, mark {@code PENDING_DELETION}, revoke every session -- runs in its own short
     * transaction via {@code transactionTemplate} and commits before anything else happens.
     * {@link AccountPurgeSweepService#purgeOne} then runs afterward, deliberately NOT nested
     * inside that transaction or any other: {@code purgeOne} manages its own transaction
     * boundaries internally specifically so its outbound Gmail HTTPS call never happens with a
     * pooled DB connection held open (the BH-016/BH-047 failure mode {@code
     * AccountPurgeSweepService}'s own class doc warns about) -- calling it from inside a
     * still-open {@code @Transactional} method here would silently defeat that design, since
     * Spring would just join the caller's already-open transaction rather than honoring {@code
     * purgeOne}'s own boundaries.
     *
     * <p>A failure in phase one leaves the account completely untouched (nothing committed, no
     * email sent). A failure in {@code purgeOne} leaves it at {@code PENDING_DELETION} -- exactly
     * where {@code AccountPurgeSweepService}'s own crash-recovery sweep already knows how to find
     * and safely retry it from scratch, and the confirmation email below never fires for a purge
     * that did not actually finish.
     *
     * @param sessionId a PasswordChangeSession id already at OTP_VERIFIED -- the frontend drives
     *                  the exact same start()/verifyOtp() calls ChangePasswordModal uses. No
     *                  currentPassword parameter: the session itself is that proof, same as
     *                  PasswordChangeService.complete() never re-asks for it either.
     */
    public void requestDeletion(UUID userId, String sessionId) {
        String[] emailHolder = new String[1];
        transactionTemplate.executeWithoutResult(tx -> {
            User user = requireUser(userId);
            requireUserScope(user);

            // A suspension is an admin's call, not something a user should be able to route
            // around via self-service deletion -- they need an admin to reactivate first, then
            // can delete normally. Checked before consuming the OTP session so a blocked attempt
            // doesn't burn it.
            if (user.isSuspended()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This account has been suspended. Contact support for assistance.");
            }
            if (user.isPendingDeletion()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Your account is already scheduled for deletion.");
            }
            if (user.isDeleted()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "User not found");
            }

            // Proves current-password + OTP for THIS request; throws ApiException(BAD_REQUEST) if
            // the session is invalid, expired, or not at OTP_VERIFIED. Also consumes it
            // (DELETION_CONFIRMED) so it can never be replayed into an actual password change.
            passwordChangeService.consumeForAccountDeletion(userId, sessionId);

            Instant now = Instant.now();
            user.setStatus(User.STATUS_PENDING_DELETION);
            user.setDeletionRequestedAt(now);
            user.setUpdatedAt(now);
            userRepository.save(user);

            // Same reasoning as deactivate()'s call below: status alone blocks new logins/
            // refreshes, not an access token already issued (up to 15 minutes) -- revoke every
            // refresh token in this transaction so the session can't outlive the access token
            // it's currently holding, and can't race the purge about to run.
            refreshTokenService.revokeAllForUser(userId);

            Map<String, Object> auditMetadata = requestMetadata.addTo(new HashMap<>(Map.of("method", "self_service")));
            auditService.record(userId, "ACCOUNT_DELETION_REQUESTED", "User", userId, auditMetadata);

            // Captured now, before purgeOne overwrites it with an anonymized placeholder address
            // as its own last write.
            emailHolder[0] = user.getEmail();
        });

        // Outside any transaction, deliberately -- see this method's own doc comment.
        accountPurgeSweepService.purgeOne(userId);

        String email = emailHolder[0];
        AfterCommit.run("account deleted email", () -> {
            EmailResult result = emailProvider.sendAccountDeletedEmail(email, Instant.now());
            auditService.record(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "account_deleted", "provider", result.provider().name(),
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
