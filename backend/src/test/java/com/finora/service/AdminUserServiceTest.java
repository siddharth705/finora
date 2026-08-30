package com.finora.service;

import com.finora.dto.AdminDtos.AdminUpdateUserRequest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-level coverage for AdminUserService's suspend/reactivate state machine -- the parts that
 * don't need a real database (permission gating and the actual HTTP contract are covered by
 * AdminUserControllerIT instead). Focuses on the two invariants that matter most for an action
 * this consequential: an admin can never lock themselves out, and re-applying the same action
 * twice is a safe no-op rather than an error or a duplicate audit entry.
 */
class AdminUserServiceTest {

    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private AuditService auditService;
    private RefreshTokenService refreshTokenService;
    private AdminUserService adminUserService;
    private final UUID adminId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        auditService = mock(AuditService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        adminUserService = new AdminUserService(
                userRepository, accountRepository, transactionRepository, auditService,
                mock(AuthService.class), refreshTokenService);
    }

    private User user(UUID id, String status) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setEmail("target@example.com");
        u.setFullName("Target User");
        u.setStatus(status);
        return u;
    }

    @Test
    void suspend_rejectsAnAdminSuspendingTheirOwnAccount() {
        try {
            adminUserService.suspend(adminId, adminId);
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(userRepository, never()).save(any());
            verifyNoInteractions(auditService);
            return;
        }
        throw new AssertionError("Expected suspend() to reject a self-suspend attempt");
    }

    @Test
    void suspend_setsStatusAndRecordsAuditEntry_forAnActiveUser() {
        User target = user(targetId, "ACTIVE");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.suspend(targetId, adminId);

        assertThat(target.getStatus()).isEqualTo("SUSPENDED");
        verify(userRepository).save(target);
        verify(auditService).record(eq(targetId), eq("ACCOUNT_SUSPENDED"), eq("User"), eq(targetId), any());
    }

    /**
     * Suspension has to evict, not just bar the door.
     *
     * <p>Status alone stops new logins and refreshes, but does nothing about an access token
     * already issued: CurrentUserDetailsService builds the principal without {@code .disabled()}
     * and AuthorizationService.effectiveAuthorities never reads user.status, so JwtAuthFilter
     * keeps authenticating it. SetupService documents that gap and works around it for the
     * bootstrap account alone; ordinary suspension had no equivalent, so a suspended account kept
     * its session alive by refreshing.
     */
    @Test
    void suspend_revokesEveryRefreshToken_soTheSessionCannotOutliveTheSuspension() {
        User target = user(targetId, "ACTIVE");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.suspend(targetId, adminId);

        verify(refreshTokenService).revokeAllForUser(targetId);
    }

    /**
     * phoneVerified is a security control, not a label: AuthService.resetPassword and
     * verifyPhoneWithFirebase both accept a Firebase token only when its phone_number matches
     * User.phoneNumber, which is the whole reason the reset flow has a second factor. Leaving the
     * flag set after an admin repoints the number would hand that second factor to whoever holds
     * the new handset -- reachable by an admin with only USER_UPDATE, against any account
     * including a SUPER_ADMIN.
     */
    @Test
    void updateProfile_clearsPhoneVerification_whenAnAdminChangesTheNumber() {
        User target = user(targetId, "ACTIVE");
        target.setPhoneNumber("+919999999999");
        target.setPhoneVerified(true);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.existsByPhoneNumberAndAccountScope(any(), any())).thenReturn(false);

        adminUserService.updateProfile(adminId, targetId, new AdminUpdateUserRequest(
                null, "+918888888888", null, null));

        assertThat(target.isPhoneVerified()).isFalse();
        verify(refreshTokenService).revokeAllForUser(targetId);
        verify(auditService).record(eq(targetId), eq("PHONE_VERIFICATION_RESET"), eq("User"), eq(targetId), any());
    }

    /** Only an actual change resets verification -- resubmitting the same number (an admin saving
     *  an unrelated field on the same form) must not sign the user out and force re-verification. */
    @Test
    void updateProfile_leavesPhoneVerificationAlone_whenTheNumberIsUnchanged() {
        User target = user(targetId, "ACTIVE");
        target.setPhoneNumber("+919999999999");
        target.setPhoneVerified(true);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.updateProfile(adminId, targetId, new AdminUpdateUserRequest(
                "Renamed User", "+919999999999", null, null));

        assertThat(target.isPhoneVerified()).isTrue();
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    @Test
    void suspend_isIdempotent_forAnAlreadySuspendedUser() {
        User target = user(targetId, "SUSPENDED");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.suspend(targetId, adminId);

        // No state change, no save, no duplicate audit entry -- a double-click or a stale admin
        // UI retrying the same action shouldn't write a second "just suspended" event.
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void reactivate_setsStatusAndRecordsAuditEntry_forASuspendedUser() {
        User target = user(targetId, "SUSPENDED");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.reactivate(targetId, adminId, null);

        assertThat(target.getStatus()).isEqualTo("ACTIVE");
        verify(userRepository).save(target);
        // Distinct from the self-service ACCOUNT_REACTIVATED action -- see AdminUserService's own
        // doc comment on why an admin-initiated reactivation is a different actor/trust boundary.
        verify(auditService).record(eq(targetId), eq("ACCOUNT_REACTIVATED_BY_ADMIN"), eq("User"), eq(targetId), any());
    }

    @Test
    void reactivate_withAReason_includesItInTheAuditEntry() {
        User target = user(targetId, "DEACTIVATED");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.reactivate(targetId, adminId, "Verified identity over a support ticket.");

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).record(eq(targetId), eq("ACCOUNT_REACTIVATED_BY_ADMIN"), eq("User"), eq(targetId), captor.capture());
        assertThat(captor.getValue()).containsEntry("reason", "Verified identity over a support ticket.");
    }

    @Test
    void suspend_throws404_whenUserDoesNotExist() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        try {
            adminUserService.suspend(targetId, adminId);
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            return;
        }
        throw new AssertionError("Expected suspend() to throw for an unknown user");
    }

    // --- Support-assisted profile edit (updateProfile / AdminUpdateUserRequest) -- the
    // phone-uniqueness guard is the one piece of real logic here beyond field assignment, so it's
    // the part worth locking in at the unit level (the HTTP contract itself is AdminUserControllerIT's
    // job). ---

    @Test
    void updateProfile_rejectsAPhoneNumberAlreadyUsedByAnotherAccount() {
        User target = user(targetId, "ACTIVE");
        target.setPhoneNumber("+919876500001");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.existsByPhoneNumberAndAccountScope("+919876500099", "USER")).thenReturn(true);  // synthetic-ok: sequential test number, not a real subscriber

        try {
            adminUserService.updateProfile(adminId, targetId, new AdminUpdateUserRequest(null, "+919876500099", null, null));  // synthetic-ok: sequential test number, not a real subscriber
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            verify(userRepository, never()).save(any());
            assertThat(target.getPhoneNumber()).isEqualTo("+919876500001");
            return;
        }
        throw new AssertionError("Expected updateProfile() to reject a phone number already in use");
    }

    @Test
    void updateProfile_updatesOnlyTheSuppliedFields() {
        User target = user(targetId, "ACTIVE");
        target.setPhoneNumber("+919876500001");
        target.setTimezone("Asia/Kolkata");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.updateProfile(adminId, targetId, new AdminUpdateUserRequest("New Name", null, null, null));

        assertThat(target.getFullName()).isEqualTo("New Name");
        // Untouched fields (phoneNumber wasn't supplied) must survive exactly as they were.
        assertThat(target.getPhoneNumber()).isEqualTo("+919876500001");
        assertThat(target.getTimezone()).isEqualTo("Asia/Kolkata");
        verify(userRepository).save(target);
        verify(auditService).record(eq(targetId), eq("USER_PROFILE_UPDATED_BY_ADMIN"), eq("User"), eq(targetId), any());
    }

    /**
     * SEC-12 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Field names only,
     * not before/after values -- see updateProfile()'s own comment for why. This is what makes the
     * audit trail reconstructable at all: before this, the metadata carried only who/when, and two
     * edits to the same account were indistinguishable from the audit row alone.
     */
    @Test
    void updateProfile_recordsWhichFieldsActuallyChanged() {
        User target = user(targetId, "ACTIVE");
        target.setPhoneNumber("+919876500001");
        target.setTimezone("Asia/Kolkata");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.updateProfile(adminId, targetId,
                new AdminUpdateUserRequest("New Name", null, java.math.BigDecimal.valueOf(500), "Asia/Tokyo"));

        @SuppressWarnings("unchecked")
        var metadata = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(targetId), eq("USER_PROFILE_UPDATED_BY_ADMIN"), eq("User"), eq(targetId), metadata.capture());
        assertThat((List<String>) metadata.getValue().get("changedFields"))
                .containsExactlyInAnyOrder("fullName", "lowBalanceThreshold", "timezone");
    }

    @Test
    void updateProfile_recordsAnEmptyChangedFieldsList_whenNothingWasActuallySupplied() {
        User target = user(targetId, "ACTIVE");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        adminUserService.updateProfile(adminId, targetId, new AdminUpdateUserRequest(null, null, null, null));

        @SuppressWarnings("unchecked")
        var metadata = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(targetId), eq("USER_PROFILE_UPDATED_BY_ADMIN"), eq("User"), eq(targetId), metadata.capture());
        assertThat((List<String>) metadata.getValue().get("changedFields")).isEmpty();
    }

    // --- Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
    // account's transactions deliberately keep deleted_at unset, so getUser's transactionCount
    // must be scoped to exactly the user's live account ids, not just their userId -- unlike
    // accountCount, which is already correctly scoped via Account's own @SQLRestriction. ---

    @Test
    void getUser_scopesTransactionCount_toExactlyTheLiveAccountIds() {
        User target = user(targetId, "ACTIVE");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        Account liveAccount = new Account();
        ReflectionTestUtils.setField(liveAccount, "id", UUID.randomUUID());
        liveAccount.setUserId(targetId);
        when(accountRepository.findByUserId(targetId)).thenReturn(List.of(liveAccount));
        when(transactionRepository.countByUserIdAndAccountIdIn(eq(targetId), any())).thenReturn(7L);

        var result = adminUserService.getUser(targetId);

        assertThat(result.transactionCount()).isEqualTo(7L);
        verify(transactionRepository).countByUserIdAndAccountIdIn(targetId, List.of(liveAccount.getId()));
        verify(transactionRepository, never()).countByUserId(any());
    }

    @Test
    void getUser_withNoLiveAccounts_reportsZeroTransactions_withoutQueryingTransactionCount() {
        User target = user(targetId, "ACTIVE");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(accountRepository.findByUserId(targetId)).thenReturn(List.of());

        var result = adminUserService.getUser(targetId);

        assertThat(result.transactionCount()).isEqualTo(0L);
        verify(transactionRepository, never()).countByUserIdAndAccountIdIn(any(), any());
    }
}
