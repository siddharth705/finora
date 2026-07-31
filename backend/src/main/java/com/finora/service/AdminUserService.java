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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    public AdminUserService(UserRepository userRepository, AccountRepository accountRepository,
                             TransactionRepository transactionRepository, AuditService auditService,
                             AuthService authService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.authService = authService;
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
        if (req.phoneNumber() != null && !req.phoneNumber().isBlank() && !req.phoneNumber().equals(user.getPhoneNumber())) {
            if (userRepository.existsByPhoneNumber(req.phoneNumber())) {
                throw new ApiException(HttpStatus.CONFLICT, "Another account already uses this phone number.");
            }
            user.setPhoneNumber(req.phoneNumber());
        }
        if (req.lowBalanceThreshold() != null) user.setLowBalanceThreshold(req.lowBalanceThreshold());
        if (req.timezone() != null) user.setTimezone(req.timezone());
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
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
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
