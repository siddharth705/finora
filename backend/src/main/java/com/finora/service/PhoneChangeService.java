package com.finora.service;

import com.finora.dto.PhoneChangeDtos.*;
import com.finora.entity.PhoneChangeSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PhoneChangeSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PhoneMasking;
import com.finora.util.PhoneNumbers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The authenticated, OTP-gated Change (or, for an account with none yet, Set) Phone Number flow:
 * enter a number -> Firebase OTP sent to and verified against THAT number -> commit. Reached from
 * VerifyPhone.tsx two ways: the OTP-failure screen's escape hatch, for a user whose account phone
 * number is wrong or no longer reachable; and automatically, on load, for an account with no
 * phone number on file at all -- a Google Sign-In account (see AuthService.createGoogleUserRecord)
 * has none until it goes through exactly this flow once. Either way there is no other self-service
 * path (the only other writer of User.phoneNumber is AdminUserService.updateProfile, an
 * admin-only path).
 *
 * Deliberately its own service mirroring {@link PasswordChangeService}'s session-based state
 * machine rather than folded into it or into {@link AuthService} -- same reasoning
 * PasswordChangeService's own doc comment gives: a focused, self-contained slice with its own
 * narrow dependency set. The one structural difference from PasswordChangeService: there, the OTP
 * re-proves control of the number already on the account, to authorize changing something else
 * (the password). Here, the OTP proves control of the number the account is MOVING TO -- that
 * proof, on its own, is the entire authorization this flow needs, which is why there is no
 * "verify current credential" first step the way PasswordChangeService's start() has one.
 */
@Service
public class PhoneChangeService {

    // Configurable rather than hardcoded, same reasoning as PasswordChangeService's identically-
    // shaped property -- see app.security.phone-change-session-expiry-minutes in application.yml's
    // own comment.
    @Value("${app.security.phone-change-session-expiry-minutes:15}")
    private long sessionTtlMinutes;

    private final UserRepository userRepository;
    private final PhoneChangeSessionRepository sessionRepository;
    private final PhoneVerificationProvider phoneVerificationProvider;
    private final AuditService auditService;

    public PhoneChangeService(UserRepository userRepository, PhoneChangeSessionRepository sessionRepository,
                               PhoneVerificationProvider phoneVerificationProvider, AuditService auditService) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.phoneVerificationProvider = phoneVerificationProvider;
        this.auditService = auditService;
    }

    /** Step 1: validate the requested number and open a session. Rejects a number identical to the
     *  one already on file (nothing to change) and one already claimed by another account in this
     *  scope (existsByPhoneNumberAndAccountScope -- the same check AuthService.createUserRecord
     *  applies at registration). That check is necessarily check-then-act: the authoritative
     *  backstop against a genuine race (another account claiming this exact number between start()
     *  and complete()) is the DB's own uq_users_phone_scope index, enforced when complete() saves
     *  the user row, and GlobalExceptionHandler.handleDataIntegrityViolation already turns that
     *  into a clean 409 -- its own doc comment names "the V52 scoped email/phone indexes" as a
     *  case it covers. No bespoke re-check is needed at verifyOtp() or complete() for the same
     *  reason none exists at registration beyond the DB constraint itself. */
    @Transactional(noRollbackFor = ApiException.class)
    public StartResponse start(UUID userId, StartRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        requireActiveAccount(user);
        // Bug fix (review): this used to unconditionally reject a null/blank phoneNumber as
        // "shouldn't be reachable in practice" -- true for the original registration flow (phone
        // number is required there), false since AuthService.createGoogleUserRecord shipped: a
        // Google Sign-In account starts with NO phone number at all, by design, and VerifyPhone.tsx
        // routes exactly that account here to set one for the first time. This method now doubles
        // as "set" as well as "change" -- the two are the same operation from this service's own
        // point of view (open a session, prove control of the requested number, commit it).
        boolean hasNoNumberYet = user.getPhoneNumber() == null || user.getPhoneNumber().isBlank();

        String newPhoneNumber = PhoneNumbers.normalize(request.newPhoneNumber());
        // No separate hasNoNumberYet check needed here: PhoneNumbers.sameNumber() already returns
        // false whenever either side is null or blank (see its own doc comment), so a first-time
        // number is never mistaken for "the same as" a phone number that doesn't exist yet.
        if (PhoneNumbers.sameNumber(newPhoneNumber, user.getPhoneNumber())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That's already the number on your account.");
        }
        if (userRepository.existsByPhoneNumberAndAccountScope(newPhoneNumber, user.getAccountScope())) {
            auditService.record(userId, "PHONE_CHANGE_REJECTED_DUPLICATE", "User", userId);
            throw new ApiException(HttpStatus.CONFLICT, "An account with this mobile number already exists.");
        }

        Instant now = Instant.now();
        PhoneChangeSession session = new PhoneChangeSession();
        session.setUserId(userId);
        session.setStatus(PhoneChangeSession.Status.STARTED);
        // "" not null: current_phone_number is NOT NULL at the DB level (see this table's own
        // migration), and there genuinely is no prior number to record for a first-time set --
        // an honest empty value for "there wasn't one," not a fabricated placeholder. Never read
        // back anywhere in this codebase beyond this entity's own getter, so an empty string here
        // carries no risk of ever being displayed or compared as if it were a real number.
        session.setCurrentPhoneNumber(hasNoNumberYet ? "" : user.getPhoneNumber());
        session.setRequestedPhoneNumber(newPhoneNumber);
        session.setExpiresAt(now.plusSeconds(sessionTtlMinutes * 60));
        session = sessionRepository.save(session);

        auditService.record(userId, "PHONE_CHANGE_STARTED", "User", userId);

        return new StartResponse(session.getId().toString(), PhoneMasking.mask(newPhoneNumber));
    }

    /** Step 2: verify the Firebase ID token proves control of THIS session's requested number, not
     *  the account's existing one -- the one point this flow actually differs from
     *  PasswordChangeService.verifyOtp() in substance, not just naming. By the time a token exists
     *  at all, Firebase has already confirmed the code client-side; a wrong code never produces one
     *  to send here in the first place. */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyOtpResponse verifyOtp(UUID userId, VerifyOtpRequest request) {
        PhoneChangeSession session = loadActiveSession(userId, request.sessionId(), PhoneChangeSession.Status.STARTED,
                "This step has already been completed, or the session is no longer valid. Please start again.");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        requireActiveAccount(user);

        String verifiedPhone = phoneVerificationProvider.verifyAndGetPhoneNumber(request.firebaseIdToken());
        if (!PhoneNumbers.sameNumber(verifiedPhone, session.getRequestedPhoneNumber())) {
            auditService.record(userId, "INVALID_OTP", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "The verified phone number doesn't match the number you're trying to change to.");
        }

        session.setOtpVerifiedAt(Instant.now());
        session.setStatus(PhoneChangeSession.Status.OTP_VERIFIED);
        session.setVerificationProvider(ProviderType.FIREBASE);
        session.setVerifiedPhoneNumber(verifiedPhone);
        sessionRepository.save(session);

        auditService.record(userId, "PHONE_CHANGE_OTP_VERIFIED", "User", userId);
        return new VerifyOtpResponse("Verified.");
    }

    /** Step 3: commit the new number. Only reachable once the session itself shows OTP_VERIFIED --
     *  server-side state, not a client-supplied flag. Also marks the account phoneVerified -- the
     *  OTP that just ran proved control of this exact number, which is the same fact
     *  AuthService.verifyPhoneWithFirebase records that flag for.
     *
     *  Idempotency: a session that's already COMPLETED (the frontend retried after a timeout or
     *  network hiccup without ever seeing the first response) returns the same outcome again
     *  rather than re-running the side effects -- a second PHONE_NUMBER_CHANGED audit entry for one
     *  user action, or a second (redundant but harmless) write. Same pattern as
     *  PasswordChangeService.complete(). */
    @Transactional(noRollbackFor = ApiException.class)
    public CompleteResponse complete(UUID userId, CompleteRequest request) {
        PhoneChangeSession session = resolveSession(userId, request.sessionId());

        if (session.getStatus() == PhoneChangeSession.Status.COMPLETED) {
            return new CompleteResponse(COMPLETE_MESSAGE, session.getRequestedPhoneNumber());
        }
        if (session.getStatus() != PhoneChangeSession.Status.OTP_VERIFIED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Verify the code sent to your new number before completing this change.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        // Deliberately after the COMPLETED-session idempotency check above, not before -- a
        // session that already succeeded must keep returning its original outcome even if the
        // account's status changed afterward. Same ordering PasswordChangeService.complete() uses,
        // for the same reason.
        requireActiveAccount(user);

        Instant now = Instant.now();
        user.setPhoneNumber(session.getRequestedPhoneNumber());
        user.setPhoneVerified(true);
        user.setUpdatedAt(now);
        userRepository.save(user);

        session.setStatus(PhoneChangeSession.Status.COMPLETED);
        session.setCompletedAt(now);
        sessionRepository.save(session);

        auditService.record(userId, "PHONE_NUMBER_CHANGED", "User", userId, Map.of("method", "firebase"));

        return new CompleteResponse(COMPLETE_MESSAGE, session.getRequestedPhoneNumber());
    }

    private static final String COMPLETE_MESSAGE = "Your phone number has been updated.";

    /** Same guard PasswordChangeService applies at every step, for the identical reason: a
     *  suspended/deactivated/pending-deletion account's own still-valid JWT (issued before the
     *  status change; JWTs aren't revoked, only the refresh token that would renew them) must not
     *  be usable to change the phone number out from under an account that is locked out or that
     *  its own owner just stepped away from -- and a hijacked phone number is a worse outcome here
     *  than most fields this guard already protects, since it can be used to intercept a future
     *  password reset. */
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

    /** Shared by verifyOtp() and complete() -- both need the same "does this session belong to
     *  this user, is it in the right state, and has it expired" checks. Mirrors
     *  PasswordChangeService's identically-named helper. */
    private PhoneChangeSession loadActiveSession(UUID userId, String rawSessionId,
                                                  PhoneChangeSession.Status requiredStatus, String wrongStateMessage) {
        PhoneChangeSession session = resolveSession(userId, rawSessionId);
        if (session.getStatus() != requiredStatus) {
            throw new ApiException(HttpStatus.BAD_REQUEST, wrongStateMessage);
        }
        return session;
    }

    /** Looks up the session and lazily transitions it to EXPIRED if its TTL has passed. Mirrors
     *  PasswordChangeService's identically-named helper, including the same reasoning for never
     *  re-deriving a COMPLETED session into EXPIRED. */
    private PhoneChangeSession resolveSession(UUID userId, String rawSessionId) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(rawSessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid phone number change session.");
        }
        PhoneChangeSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Invalid phone number change session."));

        boolean stillInProgress = session.getStatus() == PhoneChangeSession.Status.STARTED
                || session.getStatus() == PhoneChangeSession.Status.OTP_VERIFIED;
        if (stillInProgress && session.isExpired()) {
            session.setStatus(PhoneChangeSession.Status.EXPIRED);
            sessionRepository.save(session);
        }
        if (session.getStatus() == PhoneChangeSession.Status.EXPIRED) {
            auditService.record(userId, "SESSION_EXPIRED", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "This phone number change session has expired. Please start again.");
        }
        return session;
    }
}
