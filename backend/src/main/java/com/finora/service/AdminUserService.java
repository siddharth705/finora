package com.finora.service;

import com.finora.dto.AdminDtos.AdminUpdateUserRequest;
import com.finora.dto.PagedResponse;
import com.finora.dto.AdminDtos.UserDetailDto;
import com.finora.dto.AdminDtos.UserSummaryDto;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PhoneNumbers;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only user directory + account status management, backing frontend-admin/'s Users page.
 * Deliberately a separate service from UserSettingsService (self-service "my own settings") and
 * RoleService (RBAC role/permission grants) -- this one operates cross-user, on a different
 * permission gate (USER_VIEW / USER_DELETE, not ROLE_MANAGE), matching this codebase's convention
 * of one focused service per distinct capability rather than one large "UserAdminService" that
 * does everything admin-related.
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AuditService auditService;
    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    public AdminUserService(UserRepository userRepository, AccountRepository accountRepository,
                             TransactionRepository transactionRepository, AuditService auditService,
                             AuthService authService, RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
    }

    /** Support-assisted signup (USER_CREATE) -- delegates the actual user-creation work to
     *  AuthService.adminCreateUser, which shares the uniqueness checks and default-category
     *  seeding the self-service register() flow uses, minus the OTP/token issuance that flow
     *  needs and this one doesn't (see that method's own doc comment). */
    @Transactional
    public UserSummaryDto createUser(UUID actingAdminId, RegisterRequest request) {
        User user = authService.adminCreateUser(request, actingAdminId);
        return toSummary(user);
    }

    /** Support-assisted profile edit (USER_UPDATE) -- every field optional, only supplied ones
     *  change (see AdminUpdateUserRequest's own doc comment for why email/password aren't here).
     *  A blank string is treated the same as "not supplied" for fullName/phoneNumber, not as "the
     *  admin meant to clear it" -- neither field makes sense empty (a blank fullName is invalid
     *  everywhere else in this codebase, and a blank phoneNumber would silently break this
     *  account's phone-based login/OTP), and AdminUpdateUserRequest carries no @NotBlank of its
     *  own to catch this at the validation layer the way RegisterRequest does. */
    @Transactional
    public UserSummaryDto updateProfile(UUID actingAdminId, UUID userId, AdminUpdateUserRequest req) {
        User user = requireUser(userId);
        if (req.fullName() != null && !req.fullName().isBlank()) user.setFullName(req.fullName());
        if (req.phoneNumber() != null && !req.phoneNumber().isBlank()) {
            // Bug fix: this used to store req.phoneNumber() verbatim, while registration stored
            // PhoneNumbers.normalize()'s E.164 form -- two writers to one field, one normalized
            // and one not. An admin typing "9999999999" locked the account out permanently
            // (Firebase's claim is always E.164, so verification could never match again and
            // PhoneVerificationFilter 403s everything with no self-service recovery), and the
            // uniqueness check below compared raw strings against a DB index on the literal
            // column, so "9999999999" and "+919999999999" could both exist. Normalizing FIRST
            // fixes both: the comparison, the conflict check and the stored value are now the
            // same canonical form registration uses. See PhoneNumbers' own doc comment.
            String normalized = PhoneNumbers.normalize(req.phoneNumber());
            if (!normalized.equals(user.getPhoneNumber())) {
                // Scoped to the account's own portal: the same person may legitimately use one
                // mobile number for their USER account and their ADMIN account, so a clash only
                // matters within a scope.
                if (userRepository.existsByPhoneNumberAndAccountScope(normalized, user.getAccountScope())) {
                    throw new ApiException(HttpStatus.CONFLICT, "Another account already uses this phone number.");
                }
                user.setPhoneNumber(normalized);
                // A number nobody has proved control of is not a verified number. Changing the
                // number without clearing this left phoneVerified asserting something that was
                // never true of the new value -- and phoneVerified is not decoration, it is a
                // security control: AuthService.resetPassword and verifyPhoneWithFirebase both
                // accept a Firebase token only if its phone_number matches THIS field, which is
                // the entire reason the reset flow has a second factor at all ("the reset token
                // alone -- proof of email access -- is no longer enough"). Without this line an
                // admin holding only USER_UPDATE could point any account's phone, a SUPER_ADMIN's
                // included, at a handset they control and inherit that second factor.
                //
                // The comment above is about an admin edit locking a user OUT because Firebase
                // could never match again. Normalizing fixed that; this is the converse question
                // it left unasked -- what happens once it does match.
                //
                // setPhoneVerified(false) had no call site anywhere in the backend before this.
                user.setPhoneVerified(false);
                // Same reasoning as suspend(): a security-state change that leaves existing
                // sessions running has not taken effect yet. PhoneVerificationFilter will now 403
                // this account's requests until it re-verifies, but only on the NEXT request --
                // revoking refresh tokens stops the session renewing indefinitely in the meantime.
                refreshTokenService.revokeAllForUser(userId);
                auditService.record(userId, "PHONE_VERIFICATION_RESET", "User", userId,
                        Map.of("reason", "phone_number_changed_by_admin", "changedBy", actingAdminId.toString()));
            }
        }
        if (req.lowBalanceThreshold() != null) user.setLowBalanceThreshold(req.lowBalanceThreshold());
        if (req.timezone() != null) {
            // Same check UserSettingsService.update() already applies on the user-facing path.
            // A zone id's validity is a runtime question no annotation can answer, so the DTO's
            // @Size only bounds the string and this decides whether it names a real zone. Without
            // it the admin path could write a value every downstream safeZoneId() then has to
            // silently fall back from -- a user's dashboard quietly running in the wrong timezone
            // because an admin typed "IST" instead of "Asia/Kolkata".
            try {
                ZoneId.of(req.timezone());
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "'" + req.timezone() + "' is not a recognized timezone.");
            }
            user.setTimezone(req.timezone());
        }
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        auditService.record(userId, "USER_PROFILE_UPDATED_BY_ADMIN", "User", userId,
                Map.of("updatedBy", actingAdminId.toString()));
        return toSummary(user);
    }

    @Transactional(readOnly = true)
    public PagedResponse<UserSummaryDto> list(String q, String status, int page, int size) {
        // Clamped rather than rejected -- an admin typing a huge page size shouldn't 400, it
        // should just get a sane upper bound (mirrors how most paginated admin UIs behave).
        int safeSize = com.finora.util.PageBounds.safeSize(size, MAX_PAGE_SIZE);
        int safePage = com.finora.util.PageBounds.safePage(page);
        // Blank search text is the same as "no filter" -- an admin clearing the search box
        // shouldn't have to also know that empty string vs. null behaves differently server-side.
        String normalizedQ = (q == null || q.isBlank()) ? null : q.trim();
        String normalizedStatus = (status == null || status.isBlank()) ? null : status.trim();

        var pageResult = userRepository.search(normalizedQ, normalizedStatus,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.of(pageResult.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public UserDetailDto getUser(UUID userId) {
        User user = requireUser(userId);
        long accountCount = accountRepository.countByUserId(userId);
        long transactionCount = transactionRepository.countByUserId(userId);
        return new UserDetailDto(user.getId(), user.getEmail(), user.getFullName(), user.getPhoneNumber(),
                user.isPhoneVerified(), user.getStatus(), roleNames(user), user.getCreatedAt(),
                user.getUpdatedAt(), accountCount, transactionCount);
    }

    /**
     * Freezes login for this account (AuthService.login/refresh both check User.status). Idempotent
     * -- suspending an already-suspended account is a no-op that just returns the current state,
     * rather than erroring or writing a duplicate audit entry, since a double-click or a stale
     * admin UI retrying the same action shouldn't be treated as a failure.
     */
    @Transactional
    public UserSummaryDto suspend(UUID userId, UUID actingAdminId) {
        if (userId.equals(actingAdminId)) {
            // An admin locking themselves out has no recovery path short of another admin (or
            // direct DB access) reversing it -- worth blocking outright rather than trusting
            // every caller of this UI to never misclick on their own row.
            throw new ApiException(HttpStatus.BAD_REQUEST, "You cannot suspend your own account.");
        }
        User user = requireUser(userId);
        if (!user.isSuspended()) {
            user.setStatus("SUSPENDED");
            userRepository.save(user);
            // Suspension has to EVICT, not just bar the door. Status alone blocks new logins and
            // refreshes, but it does nothing about an access token already issued: JwtAuthFilter
            // authenticates it normally for the rest of its 15-minute life, because
            // CurrentUserDetailsService builds the principal without .disabled() and
            // AuthorizationService.effectiveAuthorities never reads user.status. SetupService
            // already documents that gap and works around it for the bootstrap account alone
            // ("Explicitly revoking BOOTSTRAP_ADMIN means SYSTEM_INITIALIZE is gone immediately,
            // not just until this token's 15-minute expiry"); ordinary suspension had no
            // equivalent. Revoking every refresh token closes the renewal path in the same
            // transaction as the status change, so the suspended session cannot outlive the
            // access token it is currently holding.
            refreshTokenService.revokeAllForUser(userId);
            auditService.record(userId, "ACCOUNT_SUSPENDED", "User", userId, Map.of("suspendedBy", actingAdminId.toString()));
        }
        return toSummary(user);
    }

    @Transactional
    public UserSummaryDto reactivate(UUID userId, UUID actingAdminId) {
        User user = requireUser(userId);
        if (user.isSuspended()) {
            user.setStatus("ACTIVE");
            userRepository.save(user);
            auditService.record(userId, "ACCOUNT_REACTIVATED", "User", userId, Map.of("reactivatedBy", actingAdminId.toString()));
        }
        return toSummary(user);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private UserSummaryDto toSummary(User user) {
        return new UserSummaryDto(user.getId(), user.getEmail(), user.getFullName(), user.getPhoneNumber(),
                user.isPhoneVerified(), user.getStatus(), roleNames(user), user.getCreatedAt());
    }

    /** Legacy User.role string plus any explicit user_roles grants, deduplicated -- same union
     *  AuthorizationService computes for authorities, just names rather than authorities/
     *  permissions (a directory listing doesn't need this user's full permission set, just what
     *  role(s) they're shown as holding). */
    private java.util.List<String> roleNames(User user) {
        Set<String> names = new LinkedHashSet<>();
        names.add(user.getRole());
        user.getRoles().forEach(role -> names.add(role.getName()));
        return java.util.List.copyOf(names);
    }
}
