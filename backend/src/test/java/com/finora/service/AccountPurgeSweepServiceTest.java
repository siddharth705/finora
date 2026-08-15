package com.finora.service;

import com.finora.entity.Relationship;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalRepository;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailConnectionService;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantCategoryMapRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.PasswordChangeSessionRepository;
import com.finora.repository.PasswordHistoryRepository;
import com.finora.repository.PasswordResetTokenRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RelationshipIdentifierRepository;
import com.finora.repository.RelationshipRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The purge decision logic in isolation -- ordering, failure handling, and the idempotency
 * guarantee, given whatever mocked repositories hand it. {@link AccountPurgeSweepServiceIT} covers
 * what only a real Postgres can: that the native {@code hardDeleteByUserId} queries actually
 * bypass {@code @SQLDelete}/{@code @SQLRestriction}, and that the bulk transactions delete doesn't
 * trip the self-referential FKs.
 */
class AccountPurgeSweepServiceTest {

    private UserRepository userRepository;
    private GmailConnectionService gmailConnectionService;
    private GmailConnectionRepository gmailConnectionRepository;
    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private StatementImportService statementImportService;
    private RelationshipRepository relationshipRepository;
    private AccountRepository accountRepository;
    private AuditService auditService;
    private PasswordEncoder passwordEncoder;
    private TransactionTemplate transactionTemplate;
    private AccountPurgeSweepService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        gmailConnectionService = mock(GmailConnectionService.class);
        gmailConnectionRepository = mock(GmailConnectionRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        statementImportService = mock(StatementImportService.class);
        relationshipRepository = mock(RelationshipRepository.class);
        accountRepository = mock(AccountRepository.class);
        auditService = mock(AuditService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-random-hash");

        // Empty-by-default collections, so the per-item loops (relationships, accounts,
        // statements) never NPE in a test that isn't specifically exercising them.
        when(relationshipRepository.findByUserId(any())).thenReturn(List.<Relationship>of());
        when(accountRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(any())).thenReturn(List.<StatementImport>of());

        transactionTemplate = mock(TransactionTemplate.class);
        // Runs the real lambda passed to executeWithoutResult -- without this, none of the
        // bulk-delete-phase repository calls inside it would ever actually happen.
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new AccountPurgeSweepService(userRepository, gmailConnectionService, gmailConnectionRepository,
                transactionRepository,
                mock(MerchantLearningEventRepository.class), mock(MerchantLearningAuditRepository.class),
                mock(MerchantCategoryLearningRepository.class), mock(MerchantAliasRepository.class),
                mock(MerchantCategoryMapRepository.class), mock(MerchantRepository.class),
                mock(BudgetRepository.class), mock(GoalRepository.class),
                mock(CategoryRuleRepository.class), mock(CategoryRepository.class),
                relationshipRepository, mock(RelationshipIdentifierRepository.class),
                mock(NetWorthSnapshotRepository.class), mock(ImportJobRepository.class),
                mock(ImportSessionRepository.class), mock(PasswordHistoryRepository.class),
                mock(PasswordChangeSessionRepository.class), mock(PasswordResetTokenRepository.class),
                mock(AccountReactivationTokenRepository.class), mock(RefreshTokenRepository.class),
                mock(UserSettingsRepository.class), accountRepository,
                statementImportRepository, statementImportService, auditService, passwordEncoder, transactionTemplate);
        ReflectionTestUtils.setField(service, "sweepEnabled", true);
        ReflectionTestUtils.setField(service, "retentionHours", 48);
        ReflectionTestUtils.setField(service, "batchSize", 200);
    }

    private User pendingDeletionUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setFullName("Jane Doe");
        u.setPhoneNumber("+919876543210"); // synthetic-ok
        u.setPasswordHash("hashed");
        u.setStatus(User.STATUS_PENDING_DELETION);
        u.setDeletionRequestedAt(Instant.now().minus(49, ChronoUnit.HOURS));
        u.setDeactivationReason("TAKING_A_BREAK");
        u.setDeactivationNote("Back in a bit");
        com.finora.entity.Role role = new com.finora.entity.Role();
        role.setName("USER");
        u.setRoles(new java.util.HashSet<>(java.util.Set.of(role)));
        return u;
    }

    private void stubOneCandidate(User user) {
        when(userRepository.findIdsByStatusAndDeletionRequestedAtBefore(eq(User.STATUS_PENDING_DELETION), any(), any(PageRequest.class)))
                .thenReturn(List.of(userId));
        when(userRepository.findById(userId)).thenReturn(java.util.Optional.of(user));
    }

    @Test
    void sweep_purgesAnEligibleAccount_gmailDisconnectBeforeBulkDeleteBeforeStatementsBeforeFinalAnonymizeWrite() {
        User user = pendingDeletionUser();
        stubOneCandidate(user);
        StatementImport statement = new StatementImport();
        ReflectionTestUtils.setField(statement, "id", UUID.randomUUID());
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(statement));

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        InOrder inOrder = inOrder(gmailConnectionService, transactionRepository, statementImportService, userRepository);
        inOrder.verify(gmailConnectionService).disconnect(userId);
        inOrder.verify(transactionRepository).hardDeleteByUserId(userId);
        inOrder.verify(statementImportService).delete(userId, statement.getId());
        inOrder.verify(userRepository).save(argThat(u -> User.STATUS_DELETED.equals(u.getStatus())));

        assertThat(user.getStatus()).isEqualTo(User.STATUS_DELETED);
        assertThat(user.getDeletedAt()).isNotNull();
        verify(auditService).record(eq(userId), eq("ACCOUNT_PURGE_STARTED"), eq("User"), eq(userId), any());
        verify(auditService).record(eq(userId), eq("ACCOUNT_PURGED"), eq("User"), eq(userId), any());
    }

    @Test
    void sweep_anonymizesTheUserRow_keepingDeactivationReasonAndClearingTheNote() {
        User user = pendingDeletionUser();
        stubOneCandidate(user);

        service.sweep();

        assertThat(user.getEmail()).isEqualTo("deleted-" + userId + "@deleted.finora.invalid");
        assertThat(user.getPasswordHash()).isEqualTo("unusable-random-hash");
        assertThat(user.getFullName()).isEqualTo("Deleted User");
        assertThat(user.getPhoneNumber()).isNull();
        assertThat(user.getDeactivationNote()).isNull();
        // Kept -- churn analytics, same precedent reactivation not clearing it already established.
        assertThat(user.getDeactivationReason()).isEqualTo("TAKING_A_BREAK");
        assertThat(user.getStatus()).isEqualTo(User.STATUS_DELETED);
        // Explicit RBAC grants are functionally inert on a DELETED account, but cleared anyway --
        // regression test for a real gap this class's own bugs-and-gaps pass caught: user_roles has
        // a user_id FK like every other user-owned table, and the first version of purgeOne() never
        // touched it at all.
        assertThat(user.getRoles()).isEmpty();
    }

    @Test
    void sweep_toleratesA404FromGmailDisconnect_andContinuesThePurge() {
        User user = pendingDeletionUser();
        stubOneCandidate(user);
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "No Gmail account is connected."))
                .when(gmailConnectionService).disconnect(userId);

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(user.getStatus()).isEqualTo(User.STATUS_DELETED);
        // Still clears residual PII from any disconnected/revoked history rows.
        verify(gmailConnectionRepository).deleteByUserId(userId);
    }

    @Test
    void sweep_aNon404GmailFailure_surfacesAsAPurgeFailure_andLeavesTheAccountRetryable() {
        User user = pendingDeletionUser();
        stubOneCandidate(user);
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Google is unreachable"))
                .when(gmailConnectionService).disconnect(userId);

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        // Nothing past the failure point ran, and the row was never finalized to DELETED --
        // left exactly where the next sweep's discovery query will find it again.
        assertThat(user.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
        verify(transactionRepository, never()).hardDeleteByUserId(any());
        verify(userRepository, never()).save(any());
        verify(auditService).record(eq(userId), eq("ACCOUNT_PURGE_FAILED"), eq("User"), eq(userId), any());
    }

    @Test
    void sweep_onePurgeFailure_doesNotBlockTheRestOfTheBatch() {
        UUID failingUserId = UUID.randomUUID();
        UUID succeedingUserId = UUID.randomUUID();
        User failing = pendingDeletionUser();
        ReflectionTestUtils.setField(failing, "id", failingUserId);
        User succeeding = pendingDeletionUser();
        ReflectionTestUtils.setField(succeeding, "id", succeedingUserId);

        when(userRepository.findIdsByStatusAndDeletionRequestedAtBefore(eq(User.STATUS_PENDING_DELETION), any(), any(PageRequest.class)))
                .thenReturn(List.of(failingUserId, succeedingUserId));
        when(userRepository.findById(failingUserId)).thenReturn(java.util.Optional.of(failing));
        when(userRepository.findById(succeedingUserId)).thenReturn(java.util.Optional.of(succeeding));
        doThrow(new RuntimeException("boom")).when(gmailConnectionService).disconnect(failingUserId);

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(failing.getStatus()).isEqualTo(User.STATUS_PENDING_DELETION);
        assertThat(succeeding.getStatus()).isEqualTo(User.STATUS_DELETED);
    }

    @Test
    void purgeOne_isANoOp_whenTheUserIsAlreadyDeleted() {
        User user = pendingDeletionUser();
        user.setStatus(User.STATUS_DELETED);
        stubOneCandidate(user);

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        verifyNoInteractions(gmailConnectionService, transactionRepository, statementImportService);
        verify(auditService, never()).record(any(), eq("ACCOUNT_PURGE_STARTED"), any(), any(), any());
    }

    /** {@link AccountPurgeSweepService#MINIMUM_SAFETY_BUFFER}. Even a misconfigured retention-hours
     *  of 0 must not make the cutoff "now" -- the 48h window is the product decision itself here,
     *  not a tunable like statement storage's 90-day default. */
    @Test
    void sweep_enforcesAMinimumSafetyBuffer_evenIfRetentionHoursIsMisconfiguredToZero() {
        ReflectionTestUtils.setField(service, "retentionHours", 0);
        when(userRepository.findIdsByStatusAndDeletionRequestedAtBefore(eq(User.STATUS_PENDING_DELETION), any(), any(PageRequest.class)))
                .thenReturn(List.of());

        service.sweep();

        org.mockito.ArgumentCaptor<Instant> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).findIdsByStatusAndDeletionRequestedAtBefore(eq(User.STATUS_PENDING_DELETION), cutoffCaptor.capture(), any());
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(47, ChronoUnit.HOURS));
    }

    @Test
    void scheduledSweep_doesNothing_whenDisabled() {
        ReflectionTestUtils.setField(service, "sweepEnabled", false);

        service.scheduledSweep();

        verifyNoInteractions(userRepository, gmailConnectionService, transactionRepository, statementImportService);
    }

    @Test
    void sweep_oneFailedStatementPurge_doesNotAbortTheRestOfThePurge() {
        User user = pendingDeletionUser();
        stubOneCandidate(user);
        StatementImport ok = new StatementImport();
        ReflectionTestUtils.setField(ok, "id", UUID.randomUUID());
        StatementImport failing = new StatementImport();
        ReflectionTestUtils.setField(failing, "id", UUID.randomUUID());
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("boom")).when(statementImportService).delete(userId, failing.getId());

        AccountPurgeSweepService.Result result = service.sweep();

        assertThat(result.purged()).isEqualTo(1);
        verify(statementImportService).delete(userId, ok.getId());
        // A single statement's failure doesn't stop the rest of purgeOne -- the user row still
        // gets finalized to DELETED.
        assertThat(user.getStatus()).isEqualTo(User.STATUS_DELETED);
    }
}
