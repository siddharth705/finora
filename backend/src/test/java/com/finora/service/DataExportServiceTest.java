package com.finora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.ImportSessionSummaryDto;
import com.finora.dto.UserSettingsDto;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.ImportSession;
import com.finora.entity.Merchant;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.Goal;
import com.finora.goals.GoalContribution;
import com.finora.goals.GoalContributionRepository;
import com.finora.goals.GoalRepository;
import com.finora.budgets.BudgetService;
import com.finora.imports.ImportSessionService;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.support.FeedbackDto;
import com.finora.support.SupportTicketDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DataExportService} in isolation -- the password gate, the two locked-in scope
 * decisions this plan's "Findings" section flags as easy to silently regress (soft-deleted
 * accounts, MULTI_ACCOUNT session row counts), and the per-statement best-effort ZIP writing.
 * No real-Postgres concern here (no lazy-loading, no native queries) -- unlike
 * {@link AccountPurgeSweepServiceIT}'s split from its own unit test, this class needed no IT.
 */
class DataExportServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private com.finora.integrations.google.login.GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private com.finora.integrations.apple.login.AppleIdTokenVerifierService appleIdTokenVerifierService;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private BudgetService budgetService;
    private GoalRepository goalRepository;
    private GoalContributionRepository goalContributionRepository;
    private CategoryRepository categoryRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RelationshipService relationshipService;
    private NetWorthSnapshotRepository netWorthSnapshotRepository;
    private MerchantRepository merchantRepository;
    private ImportJobRepository importJobRepository;
    private ImportSessionRepository importSessionRepository;
    private ImportSessionService importSessionService;
    private StatementImportRepository statementImportRepository;
    private StatementImportService statementImportService;
    private GmailConnectionRepository gmailConnectionRepository;
    private UserSettingsService userSettingsService;
    private WorkspaceSettingsService workspaceSettingsService;
    private BankManagementService bankManagementService;
    private AuditService auditService;
    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private PlanChangeRepository planChangeRepository;
    private SupportTicketRepository supportTicketRepository;
    private SupportTicketAttachmentRepository supportTicketAttachmentRepository;
    private FeedbackEntryRepository feedbackEntryRepository;
    private DataExportService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        googleIdTokenVerifierService = mock(com.finora.integrations.google.login.GoogleIdTokenVerifierService.class);
        appleIdTokenVerifierService = mock(com.finora.integrations.apple.login.AppleIdTokenVerifierService.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        budgetService = mock(BudgetService.class);
        goalRepository = mock(GoalRepository.class);
        goalContributionRepository = mock(GoalContributionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        relationshipService = mock(RelationshipService.class);
        netWorthSnapshotRepository = mock(NetWorthSnapshotRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        importSessionRepository = mock(ImportSessionRepository.class);
        importSessionService = mock(ImportSessionService.class);
        statementImportRepository = mock(StatementImportRepository.class);
        statementImportService = mock(StatementImportService.class);
        gmailConnectionRepository = mock(GmailConnectionRepository.class);
        userSettingsService = mock(UserSettingsService.class);
        workspaceSettingsService = mock(WorkspaceSettingsService.class);
        bankManagementService = mock(BankManagementService.class);
        auditService = mock(AuditService.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        planChangeRepository = mock(PlanChangeRepository.class);
        supportTicketRepository = mock(SupportTicketRepository.class);
        supportTicketAttachmentRepository = mock(SupportTicketAttachmentRepository.class);
        feedbackEntryRepository = mock(FeedbackEntryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Empty-by-default collections, matching AccountPurgeSweepServiceTest's own convention --
        // so a test exercising one table never NPEs on every other one it doesn't care about.
        when(accountRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(transactionRepository.findByUserId(any())).thenReturn(List.of());
        when(transactionRepository.countByAccountForUser(any())).thenReturn(List.of());
        when(budgetService.listForUser(any())).thenReturn(List.of());
        when(goalRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(any())).thenReturn(List.of());
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        when(categoryRuleRepository.findByUserId(any())).thenReturn(List.of());
        when(relationshipService.listForUser(any())).thenReturn(List.of());
        when(netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(any())).thenReturn(List.of());
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(statementImportService.duplicateCountsByStatementImport(any())).thenReturn(Map.of());
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(any())).thenReturn(List.of());
        when(gmailConnectionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userSettingsService.get(any()))
                .thenReturn(new UserSettingsDto("jane@example.com", "Jane Doe", null, "light", "Asia/Kolkata",
                        null, false, Instant.now(), null, User.SIGN_IN_METHOD_PASSWORD, true));
        when(workspaceSettingsService.get(any())).thenReturn(new WorkspaceSettingsDto(90, Instant.now()));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(planRepository.findAllById(any())).thenReturn(List.of());
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(Page.empty());
        when(supportTicketAttachmentRepository.findMetadataByTicketIdIn(any())).thenReturn(List.of());
        when(feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        service = new DataExportService(userRepository,
                new GoogleReauthVerifier(passwordEncoder, googleIdTokenVerifierService, appleIdTokenVerifierService), accountRepository, transactionRepository,
                budgetService, goalRepository, goalContributionRepository, categoryRepository, categoryRuleRepository, relationshipService,
                netWorthSnapshotRepository, merchantRepository, importJobRepository, importSessionRepository,
                importSessionService, statementImportRepository, statementImportService, gmailConnectionRepository,
                userSettingsService, workspaceSettingsService, bankManagementService, auditService,
                subscriptionRepository, planRepository, planChangeRepository,
                supportTicketRepository, supportTicketAttachmentRepository, feedbackEntryRepository, objectMapper);
    }

    private User user() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setPasswordHash("hashed");
        return u;
    }

    /** Bug fix (review): used to verifyNoInteractions on only 4 of ~14 injected dependencies,
     *  so a regression reordering buildBundle to run an expensive query before the password
     *  check -- the exact thing this test's own name promises to catch -- would have passed
     *  undetected as long as it didn't touch one of those specific four. Every repository/service
     *  buildBundle can reach is checked now. */
    @Test
    void buildBundle_wrongPassword_rejectsBeforeTouchingAnyRepository() {
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.buildBundle(userId, "wrong-password", null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(auditService).recordEvenOnRollback(eq(userId), eq("INVALID_CURRENT_PASSWORD"), eq("User"), eq(userId));
        verifyNoInteractions(accountRepository, transactionRepository, budgetService, goalRepository,
                goalContributionRepository, categoryRepository,
                categoryRuleRepository, relationshipService, netWorthSnapshotRepository, merchantRepository,
                importJobRepository, importSessionRepository, importSessionService, statementImportRepository,
                statementImportService, gmailConnectionRepository, userSettingsService, workspaceSettingsService,
                bankManagementService, subscriptionRepository, planRepository, planChangeRepository,
                supportTicketRepository, supportTicketAttachmentRepository, feedbackEntryRepository);
    }

    @Test
    void buildBundle_correctPassword_proceeds() {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.userId()).isEqualTo(userId);
        assertThat(bundle.email()).isEqualTo("jane@example.com");
        assertThat(bundle.accounts()).isEmpty();
    }

    @Test
    void buildBundle_onAGoogleAccount_verifiesAFreshGoogleTokenInsteadOfAPassword() {
        User googleUser = user();
        googleUser.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(googleUser));
        when(googleIdTokenVerifierService.verify("fresh-google-token"))
                .thenReturn(new com.finora.integrations.google.login.GoogleIdentity(googleUser.getEmail(), "Jane"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, null, "fresh-google-token", null);

        assertThat(bundle.userId()).isEqualTo(userId);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // D-26 gap closed: an Apple-only account used to fall through to the password branch here
    // and fail forever -- mirrors the Google test immediately above.
    @Test
    void buildBundle_onAnAppleAccount_verifiesAFreshAppleTokenInsteadOfAPassword() {
        User appleUser = user();
        appleUser.setSignInMethod(User.SIGN_IN_METHOD_APPLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(appleUser));
        when(appleIdTokenVerifierService.verify("fresh-apple-token"))
                .thenReturn(new com.finora.integrations.apple.login.AppleIdentity(appleUser.getEmail(), "apple-subject"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, null, null, "fresh-apple-token");

        assertThat(bundle.userId()).isEqualTo(userId);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    /** Finding 4: the purge scope this export mirrors reads accounts via
     *  findByUserIdIncludingDeleted, not the filtered finder -- a soft-deleted account must still
     *  appear in the export, explicitly marked, rather than silently vanishing. */
    @Test
    void buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted() {
        Account active = new Account();
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        active.setUserId(userId);
        active.setAccountType(Account.Type.SAVINGS);
        active.setName("Active Savings");

        Account deleted = new Account();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setAccountType(Account.Type.SAVINGS);
        deleted.setName("Closed Account");
        Instant deletedAt = Instant.now();
        deleted.setDeletedAt(deletedAt);

        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(active, deleted));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.accounts()).hasSize(2);
        assertThat(bundle.accounts()).anySatisfy(e -> {
            assertThat(e.account().id()).isEqualTo(active.getId());
            assertThat(e.deleted()).isFalse();
            assertThat(e.deletedAt()).isNull();
        });
        assertThat(bundle.accounts()).anySatisfy(e -> {
            assertThat(e.account().id()).isEqualTo(deleted.getId());
            assertThat(e.deleted()).isTrue();
            assertThat(e.deletedAt()).isEqualTo(deletedAt);
        });
    }

    /** Mirrors buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted -- goals.json reads
     *  via GoalRepository.findByUserIdIncludingDeleted, not GoalService.listForUser (which stays
     *  filtered on purpose, since it's also the live Goals page's own data source): a soft-deleted
     *  goal must still appear in the export, explicitly marked, rather than silently vanishing. */
    @Test
    void buildBundle_goals_includesSoftDeletedGoalMarkedDeleted() {
        Goal active = new Goal();
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        active.setUserId(userId);
        active.setName("Emergency Fund");
        active.setTargetAmount(BigDecimal.valueOf(100000));
        active.setCurrentAmount(BigDecimal.valueOf(20000));

        Goal deleted = new Goal();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setName("Old Vacation Fund");
        deleted.setTargetAmount(BigDecimal.valueOf(50000));
        deleted.setCurrentAmount(BigDecimal.ZERO);
        Instant deletedAt = Instant.now();
        deleted.setDeletedAt(deletedAt);

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(active, deleted));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goals()).hasSize(2);
        assertThat(bundle.goals()).anySatisfy(e -> {
            assertThat(e.goal().id()).isEqualTo(active.getId());
            assertThat(e.deleted()).isFalse();
            assertThat(e.deletedAt()).isNull();
        });
        assertThat(bundle.goals()).anySatisfy(e -> {
            assertThat(e.goal().id()).isEqualTo(deleted.getId());
            assertThat(e.deleted()).isTrue();
            assertThat(e.deletedAt()).isEqualTo(deletedAt);
        });
    }

    /** goal_contributions.json -- one batched findByGoalIdInOrderByContributedAtDesc call across
     *  every one of this user's goals, not one query per goal. */
    @Test
    void buildBundle_goalContributions_batchFetchesAcrossAllUserGoals() {
        Goal goalOne = new Goal();
        UUID goalOneId = UUID.randomUUID();
        ReflectionTestUtils.setField(goalOne, "id", goalOneId);
        goalOne.setUserId(userId);
        goalOne.setName("Emergency Fund");
        goalOne.setTargetAmount(BigDecimal.valueOf(100000));
        goalOne.setCurrentAmount(BigDecimal.valueOf(20000));

        Goal goalTwo = new Goal();
        UUID goalTwoId = UUID.randomUUID();
        ReflectionTestUtils.setField(goalTwo, "id", goalTwoId);
        goalTwo.setUserId(userId);
        goalTwo.setName("Vacation");
        goalTwo.setTargetAmount(BigDecimal.valueOf(50000));
        goalTwo.setCurrentAmount(BigDecimal.ZERO);

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(goalOne, goalTwo));

        GoalContribution contribution = new GoalContribution();
        UUID contributionId = UUID.randomUUID();
        ReflectionTestUtils.setField(contribution, "id", contributionId);
        contribution.setGoalId(goalOneId);
        contribution.setAmount(BigDecimal.valueOf(5000));
        contribution.setContributedAt(LocalDate.of(2026, 7, 15));
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(List.of(goalOneId, goalTwoId)))
                .thenReturn(List.of(contribution));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goalContributions()).hasSize(1);
        var dto = bundle.goalContributions().get(0);
        assertThat(dto.id()).isEqualTo(contributionId);
        assertThat(dto.goalId()).isEqualTo(goalOneId);
        assertThat(dto.amount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(dto.contributedAt()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    /** A soft-deleted goal's own contribution history must still export -- the goal IDs fed into
     *  the batched contribution lookup come from the including-deleted list, not a filtered one. */
    @Test
    void buildBundle_goalContributions_includesHistoryForASoftDeletedGoal() {
        Goal deletedGoal = new Goal();
        UUID deletedGoalId = UUID.randomUUID();
        ReflectionTestUtils.setField(deletedGoal, "id", deletedGoalId);
        deletedGoal.setUserId(userId);
        deletedGoal.setName("Closed Goal");
        deletedGoal.setTargetAmount(BigDecimal.valueOf(10000));
        deletedGoal.setCurrentAmount(BigDecimal.valueOf(3000));
        deletedGoal.setDeletedAt(Instant.now());

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(deletedGoal));

        GoalContribution contribution = new GoalContribution();
        ReflectionTestUtils.setField(contribution, "id", UUID.randomUUID());
        contribution.setGoalId(deletedGoalId);
        contribution.setAmount(BigDecimal.valueOf(3000));
        contribution.setContributedAt(LocalDate.of(2026, 5, 1));
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(List.of(deletedGoalId)))
                .thenReturn(List.of(contribution));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goalContributions()).hasSize(1);
        assertThat(bundle.goalContributions().get(0).goalId()).isEqualTo(deletedGoalId);
    }

    /** subscriptions.json -- planId resolved to the plan's own code/name via a batched lookup,
     *  the same way transactions.json resolves categoryId to categoryName. */
    @Test
    void buildBundle_subscriptions_resolvesPlanCodeAndName() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("PREMIUM");
        plan.setName("Premium");

        Subscription subscription = new Subscription();
        UUID subscriptionId = UUID.randomUUID();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        subscription.setRenewalDate(LocalDate.of(2026, 2, 1));
        subscription.setPaymentProvider("STRIPE");
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of(plan));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.id()).isEqualTo(subscriptionId);
        assertThat(dto.planCode()).isEqualTo("PREMIUM");
        assertThat(dto.planName()).isEqualTo("Premium");
        assertThat(dto.status()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(dto.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(dto.renewalDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(dto.paymentProvider()).isEqualTo("STRIPE");
    }

    /** A subscription whose plan row no longer exists must still appear in the export -- with a
     *  null planCode/planName -- rather than being silently dropped or throwing an NPE. */
    @Test
    void buildBundle_subscriptions_missingPlanRowFailsSoftWithNullCodeAndName() {
        UUID planId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setStartDate(LocalDate.of(2025, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of());

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.planCode()).isNull();
        assertThat(dto.planName()).isNull();
        assertThat(dto.status()).isEqualTo(Subscription.STATUS_CANCELLED);
    }

    /** Mirrors buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted -- a soft-deleted
     *  subscription must still appear in the export, explicitly marked, not silently vanish.
     *  Nothing soft-deletes a Subscription today, but the entity supports it, and this class's
     *  own "mirrors the purge scope exactly" rule means the export can't quietly assume otherwise. */
    @Test
    void buildBundle_subscriptions_includesSoftDeletedSubscriptionMarkedDeleted() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("FREE");
        plan.setName("Free");

        Subscription deleted = new Subscription();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setPlanId(planId);
        deleted.setStatus(Subscription.STATUS_CANCELLED);
        deleted.setStartDate(LocalDate.of(2025, 1, 1));
        Instant deletedAt = Instant.parse("2026-03-01T00:00:00Z");
        deleted.setDeletedAt(deletedAt);
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(deleted));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of(plan));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.deleted()).isTrue();
        assertThat(dto.deletedAt()).isEqualTo(deletedAt);
        assertThat(dto.planCode()).isEqualTo("FREE");
    }

    /** plan_changes.json -- one batched findBySubscriptionIdInOrderByCreatedAtDesc call across
     *  every one of this user's subscriptions, and fromPlanId/toPlanId resolved to each plan's
     *  own code/name via the same batched Plan lookup subscriptions.json's planId uses --
     *  including a fromPlanId the current subscription isn't even on anymore. */
    @Test
    void buildBundle_planChanges_batchFetchesAndResolvesFromAndToPlanCodeAndName() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(UUID.randomUUID());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));

        UUID fromPlanId = UUID.randomUUID();
        UUID toPlanId = UUID.randomUUID();
        Plan fromPlan = new Plan();
        ReflectionTestUtils.setField(fromPlan, "id", fromPlanId);
        fromPlan.setCode("FREE");
        fromPlan.setName("Free");
        Plan toPlan = new Plan();
        ReflectionTestUtils.setField(toPlan, "id", toPlanId);
        toPlan.setCode("PLUS");
        toPlan.setName("Plus");
        when(planRepository.findAllById(any())).thenReturn(List.of(fromPlan, toPlan));

        PlanChange change = new PlanChange();
        UUID changeId = UUID.randomUUID();
        ReflectionTestUtils.setField(change, "id", changeId);
        change.setSubscriptionId(subscriptionId);
        change.setFromPlanId(fromPlanId);
        change.setToPlanId(toPlanId);
        change.setReason(PlanChange.REASON_USER_INITIATED);
        change.setEffectiveAt(Instant.parse("2026-02-01T00:00:00Z"));
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(List.of(subscriptionId)))
                .thenReturn(List.of(change));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.planChanges()).hasSize(1);
        var dto = bundle.planChanges().get(0);
        assertThat(dto.id()).isEqualTo(changeId);
        assertThat(dto.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(dto.fromPlanCode()).isEqualTo("FREE");
        assertThat(dto.fromPlanName()).isEqualTo("Free");
        assertThat(dto.toPlanCode()).isEqualTo("PLUS");
        assertThat(dto.toPlanName()).isEqualTo("Plus");
        assertThat(dto.reason()).isEqualTo(PlanChange.REASON_USER_INITIATED);
        assertThat(dto.effectiveAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    /** A subscription's very first plan change has no fromPlanId at all (there was no prior
     *  plan) -- must map to a null fromPlanCode/fromPlanName rather than throwing on a null map
     *  lookup key. */
    @Test
    void buildBundle_planChanges_firstChangeHasNullFromPlan() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(UUID.randomUUID());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));

        UUID toPlanId = UUID.randomUUID();
        Plan toPlan = new Plan();
        ReflectionTestUtils.setField(toPlan, "id", toPlanId);
        toPlan.setCode("FREE");
        toPlan.setName("Free");
        when(planRepository.findAllById(any())).thenReturn(List.of(toPlan));

        PlanChange change = new PlanChange();
        ReflectionTestUtils.setField(change, "id", UUID.randomUUID());
        change.setSubscriptionId(subscriptionId);
        change.setFromPlanId(null);
        change.setToPlanId(toPlanId);
        change.setReason(PlanChange.REASON_USER_INITIATED);
        change.setEffectiveAt(Instant.now());
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(List.of(subscriptionId)))
                .thenReturn(List.of(change));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.planChanges()).hasSize(1);
        assertThat(bundle.planChanges().get(0).fromPlanCode()).isNull();
        assertThat(bundle.planChanges().get(0).fromPlanName()).isNull();
        assertThat(bundle.planChanges().get(0).toPlanCode()).isEqualTo("FREE");
    }

    /** Finding 3: ImportSessionService.readStagedRows() throws for anything but a SINGLE_ACCOUNT
     *  session -- a MULTI_ACCOUNT session's row count must come from readSections() instead, or
     *  any user who ever staged a composite statement can't export their data at all. */
    @Test
    void buildBundle_importSessions_multiAccountSessionUsesSectionsNotStagedRows() {
        ImportSession single = new ImportSession();
        ReflectionTestUtils.setField(single, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(single, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        single.setFileName("single.csv");

        ImportSession multi = new ImportSession();
        ReflectionTestUtils.setField(multi, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(multi, "sessionKind", ImportSession.KIND_MULTI_ACCOUNT);
        multi.setFileName("multi.pdf");

        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(single, multi));
        when(importSessionService.readStagedRows(single)).thenReturn(nRows(3));
        // A MULTI_ACCOUNT session throws from readStagedRows() in the real implementation --
        // stubbing it to throw here too, so this test fails loudly if the service under test
        // ever calls the wrong method for this session's kind.
        when(importSessionService.readStagedRows(multi))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "wrong kind"));
        when(importSessionService.readSections(multi)).thenReturn(List.of(
                section(2), section(3)));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        Map<UUID, ImportSessionSummaryDto> byId = bundle.importSessions().stream()
                .collect(java.util.stream.Collectors.toMap(ImportSessionSummaryDto::id, s -> s));
        assertThat(byId.get(single.getId()).rowCount()).isEqualTo(3);
        assertThat(byId.get(multi.getId()).rowCount()).isEqualTo(5);
    }

    /** Bug fix (review): buildBundle used to map every import session unguarded -- one session
     *  whose staged JSON fails to deserialize threw ImportSessionService.readJson's uncaught
     *  IllegalStateException straight out of buildBundle, failing the ENTIRE export over one
     *  unrelated, unreadable session. Now caught, logged, and that one session dropped -- the
     *  rest of the export (including the other, healthy session) still succeeds. */
    @Test
    void buildBundle_importSessions_oneUnreadableSessionIsDroppedNotFatal() {
        ImportSession healthy = new ImportSession();
        ReflectionTestUtils.setField(healthy, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(healthy, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        healthy.setFileName("healthy.csv");

        ImportSession corrupted = new ImportSession();
        ReflectionTestUtils.setField(corrupted, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(corrupted, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        corrupted.setFileName("corrupted.csv");

        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(healthy, corrupted));
        when(importSessionService.readStagedRows(healthy)).thenReturn(nRows(4));
        when(importSessionService.readStagedRows(corrupted))
                .thenThrow(new IllegalStateException("failed to deserialize stagedRowsJson"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.importSessions()).hasSize(1);
        assertThat(bundle.importSessions().get(0).id()).isEqualTo(healthy.getId());
        assertThat(bundle.importSessions().get(0).rowCount()).isEqualTo(4);
    }

    private static List<StagedRow> nRows(int n) {
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(null);
        return rows;
    }

    private static StagedAccountSection section(int rowCount) {
        return new StagedAccountSection(null, nRows(rowCount), rowCount, 0, List.of());
    }

    private static StatementImportRepository.StatementMetadata statementMetadata(UUID id, String fileName) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getId()).thenReturn(id);
        when(m.getFileName()).thenReturn(fileName);
        return m;
    }

    /** One statement's storage failure (a non-IOException, e.g. a bad object key) must not abort
     *  the whole export -- same "one bad row doesn't sink the batch" discipline
     *  AccountPurgeSweepService already established for its own per-statement loop. */
    @Test
    void writeZip_oneStatementFileReadFails_writesPlaceholderAndContinues() throws IOException {
        UUID okId = UUID.randomUUID();
        UUID failingId = UUID.randomUUID();
        // Built as separate statements, not inline inside the when(...).thenReturn(...) call
        // below -- each statementMetadata(...) call does its own when(...).thenReturn(...)
        // internally, and Mockito's stubbing-in-progress state doesn't nest: calling when()
        // again before an outer when(...) has received its thenReturn(...) throws
        // UnfinishedStubbingException.
        StatementImportRepository.StatementMetadata failingMeta = statementMetadata(failingId, "failing.pdf");
        StatementImportRepository.StatementMetadata okMeta = statementMetadata(okId, "ok.csv");
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(failingMeta, okMeta));
        when(statementImportService.getFile(userId, okId))
                .thenReturn(new StatementImportService.FileDownload("ok.csv", "hello".getBytes(), "text/csv"));
        // Not an IOException: a storage-layer failure (bad object key, missing row) is exactly
        // the case this loop's placeholder path exists for -- see the sibling test below for the
        // IOException (broken pipe) case, which must NOT get a placeholder.
        when(statementImportService.getFile(userId, failingId))
                .thenThrow(new RuntimeException("object storage unreachable"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        String okEntry = "statements/" + okId + "-ok.csv";
        String failingPlaceholder = "statements/" + failingId + "-failing.pdf.MISSING.txt";
        assertThat(entries).containsKey(okEntry);
        assertThat(new String(entries.get(okEntry))).isEqualTo("hello");
        assertThat(entries).containsKey(failingPlaceholder);
        assertThat(new String(entries.get(failingPlaceholder))).contains("RuntimeException");
        // The failed file's own bad bytes never got a normal, unmarked entry.
        assertThat(entries).doesNotContainKey("statements/" + failingId + "-failing.pdf");
    }

    /** Bug fix (review): an IOException reading/writing one statement (a broken pipe / client
     *  disconnect being the realistic case, but any IOException means the same thing -- the
     *  STREAM is unusable) used to be caught by the same handler as an ordinary storage failure,
     *  which then tried to write a ".MISSING.txt" placeholder onto that same broken stream --
     *  throwing a second, uncaught exception, misattributing an ordinary client-side cancel as a
     *  generic internal failure once it propagated out of writeZip. An IOException must propagate
     *  directly instead, with no placeholder attempted and no later statement reached. */
    @Test
    void writeZip_ioExceptionMidStatement_propagatesWithoutAttemptingAPlaceholder() throws IOException {
        UUID brokenId = UUID.randomUUID();
        UUID neverReachedId = UUID.randomUUID();
        StatementImportRepository.StatementMetadata brokenMeta = statementMetadata(brokenId, "broken.csv");
        StatementImportRepository.StatementMetadata neverReachedMeta = statementMetadata(neverReachedId, "later.csv");
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(brokenMeta, neverReachedMeta));
        // getFile() itself can't be stubbed to throw IOException -- its real signature declares no
        // checked exception, so Mockito rejects it (correctly: this method never throws one). The
        // realistic source of a genuine IOException here is the ZIP output stream itself going bad
        // mid-write (a broken pipe), so this test breaks the OutputStream instead, once real bytes
        // start flowing for this statement. 50KB of random (incompressible) content guarantees the
        // deflated output for this entry alone comfortably exceeds the failure threshold, regardless
        // of exactly how the manifest/README/14 JSON entries ahead of it compress.
        byte[] largeIncompressibleContent = new byte[50_000];
        new java.util.Random(42).nextBytes(largeIncompressibleContent);
        when(statementImportService.getFile(userId, brokenId))
                .thenReturn(new StatementImportService.FileDownload("broken.csv", largeIncompressibleContent, "text/csv"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        OutputStream out = new FailAfterNBytesOutputStream(new ByteArrayOutputStream(), 4096);

        assertThatThrownBy(() -> service.writeZip(userId, bundle, out)).isInstanceOf(IOException.class);
        // The stream stayed broken -- the loop must not have gone on to attempt the next
        // statement after the one that broke it.
        verify(statementImportService, org.mockito.Mockito.never()).getFile(userId, neverReachedId);
    }

    /** Starts passing bytes through untouched, then throws on every write once a byte-count
     *  threshold is crossed -- standing in for a client disconnecting partway through a download,
     *  without depending on exactly which internal ZipOutputStream/Deflater call happens to be
     *  in flight at that moment. */
    private static final class FailAfterNBytesOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream delegate;
        private final int failAfterBytes;
        private int written = 0;

        FailAfterNBytesOutputStream(java.io.OutputStream delegate, int failAfterBytes) {
            this.delegate = delegate;
            this.failAfterBytes = failAfterBytes;
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= failAfterBytes) throw new IOException("simulated broken pipe");
            written++;
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written >= failAfterBytes) throw new IOException("simulated broken pipe");
            written += len;
            delegate.write(b, off, len);
        }
    }

    /** Bug fix (review): toAccountExportEntry used to call AccountDto's 2-arg overload, which
     *  hardcodes statementsCount/transactionsCount/lastImportedAt to 0/0/null regardless of the
     *  account's real history -- see this test's own commit for the fix. Proves the real values
     *  now come through, computed the same batched way AccountService.listForUser does it. */
    @Test
    void buildBundle_accounts_computesRealStatementAndTransactionStats() {
        Account account = new Account();
        UUID accountId = UUID.randomUUID();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        account.setName("Everyday Savings");
        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(account));

        LocalDate period = LocalDate.of(2026, 7, 1);
        StatementImportRepository.StatementMetadata statement = mock(StatementImportRepository.StatementMetadata.class);
        when(statement.getId()).thenReturn(UUID.randomUUID());
        when(statement.getAccountId()).thenReturn(accountId);
        when(statement.getFileName()).thenReturn("july.csv");
        when(statement.getImportedAt()).thenReturn(Instant.parse("2026-07-05T00:00:00Z"));
        when(statement.getStatementPeriodStart()).thenReturn(period);
        when(statement.getStatementPeriodEnd()).thenReturn(period.plusDays(30));
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(statement));

        TransactionRepository.AccountTransactionCount count = mock(TransactionRepository.AccountTransactionCount.class);
        when(count.getAccountId()).thenReturn(accountId);
        when(count.getCount()).thenReturn(42L);
        when(transactionRepository.countByAccountForUser(userId)).thenReturn(List.of(count));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.accounts()).hasSize(1);
        var dto = bundle.accounts().get(0).account();
        assertThat(dto.statementsCount()).isEqualTo(1);
        assertThat(dto.transactionsCount()).isEqualTo(42L);
        assertThat(dto.lastImportedAt()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
        assertThat(dto.lastStatementPeriodStart()).isEqualTo(period);
    }

    /** transactions.json's real transformation -- category id resolved to a name via a batched
     *  lookup, not passed through untouched. */
    @Test
    void buildBundle_transactions_resolvesCategoryNameFromId() {
        Category category = new Category();
        UUID categoryId = UUID.randomUUID();
        ReflectionTestUtils.setField(category, "id", categoryId);
        category.setUserId(userId);
        category.setName("Groceries");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(category));

        Transaction categorized = new Transaction();
        ReflectionTestUtils.setField(categorized, "id", UUID.randomUUID());
        categorized.setUserId(userId);
        categorized.setCategoryId(categoryId);
        categorized.setTxnDate(LocalDate.of(2026, 7, 10));
        categorized.setAmount(BigDecimal.valueOf(500));
        categorized.setTxnType(Transaction.Type.EXPENSE);
        categorized.setDescription("Big Bazaar");

        Transaction uncategorized = new Transaction();
        ReflectionTestUtils.setField(uncategorized, "id", UUID.randomUUID());
        uncategorized.setUserId(userId);
        uncategorized.setCategoryId(null);
        uncategorized.setTxnDate(LocalDate.of(2026, 7, 11));
        uncategorized.setAmount(BigDecimal.valueOf(100));
        uncategorized.setTxnType(Transaction.Type.EXPENSE);
        uncategorized.setDescription("Cash withdrawal");

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(categorized, uncategorized));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.transactions()).hasSize(2);
        assertThat(bundle.transactions()).anySatisfy(t -> {
            assertThat(t.description()).isEqualTo("Big Bazaar");
            assertThat(t.categoryName()).isEqualTo("Groceries");
        });
        assertThat(bundle.transactions()).anySatisfy(t -> {
            assertThat(t.description()).isEqualTo("Cash withdrawal");
            assertThat(t.categoryName()).isEqualTo("Uncategorized");
        });
    }

    /** net_worth_history.json's field mapping -- catches an argument-order mistake between
     *  totalAssets/totalLiabilities/netWorth, which an empty-list-only test never could. */
    @Test
    void buildBundle_netWorthSnapshots_mapsAllFieldsCorrectly() {
        NetWorthSnapshot snapshot = new NetWorthSnapshot();
        ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
        snapshot.setUserId(userId);
        snapshot.setSnapshotDate(LocalDate.of(2026, 6, 30));
        snapshot.setTotalAssets(BigDecimal.valueOf(500000));
        snapshot.setTotalLiabilities(BigDecimal.valueOf(120000));
        snapshot.setNetWorth(BigDecimal.valueOf(380000));
        when(netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId)).thenReturn(List.of(snapshot));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.netWorthSnapshots()).hasSize(1);
        var dto = bundle.netWorthSnapshots().get(0);
        assertThat(dto.totalAssets()).isEqualByComparingTo(BigDecimal.valueOf(500000));
        assertThat(dto.totalLiabilities()).isEqualByComparingTo(BigDecimal.valueOf(120000));
        assertThat(dto.netWorth()).isEqualByComparingTo(BigDecimal.valueOf(380000));
    }

    /** merchants.json's identity-only mapping -- the regression this plan's own Finding 2 exists
     *  to catch: MerchantExportDto must never carry topCategory/topCategoryConfidence/distribution
     *  (derived from the excluded merchant-learning tables). Structurally impossible today since
     *  the DTO has no such fields, but this pins the identity fields it does carry actually map
     *  correctly rather than just compiling. */
    @Test
    void buildBundle_merchants_mapsIdentityFieldsOnly() {
        Merchant merchant = new Merchant();
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        merchant.setUserId(userId);
        merchant.setCanonicalName("Amazon");
        merchant.setLogoUrl("https://example.com/amazon.png");
        merchant.setWebsite("https://amazon.in");
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.merchants()).hasSize(1);
        var dto = bundle.merchants().get(0);
        assertThat(dto.canonicalName()).isEqualTo("Amazon");
        assertThat(dto.website()).isEqualTo("https://amazon.in");
        assertThat(dto.lifecycleStatus()).isEqualTo("APPROVED");
    }

    /** category_rules.json now goes through the shared RuleDto.from(CategoryRule) factory (also
     *  used by RuleService) instead of a hand-duplicated mapping -- proves the shared path still
     *  produces the right shape for export specifically. */
    @Test
    void buildBundle_categoryRules_mapsViaSharedRuleDtoFactory() {
        CategoryRule rule = new CategoryRule();
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        rule.setUserId(userId);
        rule.setScope(CategoryRule.Scope.USER);
        rule.setField(CategoryRule.Field.MERCHANT);
        rule.setOperator(CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("Amazon");
        rule.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.categoryRules()).hasSize(1);
        var dto = bundle.categoryRules().get(0);
        assertThat(dto.scope()).isEqualTo("USER");
        assertThat(dto.field()).isEqualTo("MERCHANT");
        assertThat(dto.comparisonValue()).isEqualTo("Amazon");
    }

    /** gmail_connection.json's scope-splitting logic -- grantedScopes is stored as one
     *  space-separated string and must come back as a real list, not a single-element list
     *  containing the whole string. */
    @Test
    void buildBundle_gmailConnections_splitsGrantedScopesIntoAList() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleEmail("jane@example.com");
        connection.setGrantedScopes("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/userinfo.email");
        when(gmailConnectionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(connection));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.gmailConnections()).hasSize(1);
        var dto = bundle.gmailConnections().get(0);
        assertThat(dto.grantedScopes()).containsExactly(
                "https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/userinfo.email");
        assertThat(dto.googleEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void writeZip_manifestListsEveryOutOfScopeTableWithAReason() throws IOException {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        List<String> excludedNames = new ArrayList<>();
        manifest.get("excluded").forEach(n -> excludedNames.add(n.get("name").asText()));

        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("audit_logs"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("merchant_category_learning"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("refresh_tokens"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("password_history"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("statement_analysis_sessions"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("subscription_events"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("support_ticket_attachments"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("support_ticket_internal_notes"));
        assertThat(excludedNames).noneSatisfy(n -> assertThat(n).contains("plan_changes"));

        List<String> includedNames = new ArrayList<>();
        manifest.get("included").forEach(n -> includedNames.add(n.get("name").asText()));
        assertThat(includedNames).contains("accounts.json", "transactions.json", "statements/", "goal_contributions.json",
                "subscriptions.json", "plan_changes.json", "support_tickets.json", "feedback.json");
        // manifest.json/README.txt describe the archive itself, not one more table in it.
        assertThat(includedNames).doesNotContain("manifest.json", "README.txt");

        assertThat(entries).containsKey("README.txt");
    }

    /** support_tickets.json/feedback.json (Phase 7): attachment metadata (filename, no bytes)
     *  comes through via the batched findMetadataByTicketIdIn, grouped back to the right ticket --
     *  and neither JSON entry ever carries an internal note, which is structurally impossible
     *  anyway since SupportTicketDto.Detail has no such field, but this pins the real values
     *  actually flow through rather than just compiling. */
    @Test
    void buildBundle_supportTicketsAndFeedback_includeAttachmentMetadataButNeverNoteContent() {
        SupportTicket ticket = new SupportTicket();
        UUID ticketId = UUID.randomUUID();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTicketNumber("SUP-000042");
        ticket.setUserId(userId);
        ticket.setCategory(SupportTicket.Category.STATEMENT_IMPORT);
        ticket.setSource(ClientPlatform.WEB);
        ticket.setSubject("Import stuck");
        ticket.setDescription("Progress bar froze at 60%.");
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        SupportTicketAttachment attachment = new SupportTicketAttachment();
        UUID attachmentId = UUID.randomUUID();
        ReflectionTestUtils.setField(attachment, "id", attachmentId);
        attachment.setTicketId(ticketId);
        attachment.setFilename("screenshot.png");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(2048);
        SupportTicketAttachmentRepository.AttachmentMetadata attachmentMetadata =
                mock(SupportTicketAttachmentRepository.AttachmentMetadata.class);
        when(attachmentMetadata.getId()).thenReturn(attachmentId);
        when(attachmentMetadata.getTicketId()).thenReturn(ticketId);
        when(attachmentMetadata.getFilename()).thenReturn("screenshot.png");
        when(attachmentMetadata.getContentType()).thenReturn("image/png");
        when(attachmentMetadata.getSizeBytes()).thenReturn(2048L);
        when(supportTicketAttachmentRepository.findMetadataByTicketIdIn(List.of(ticketId)))
                .thenReturn(List.of(attachmentMetadata));

        FeedbackEntry feedback = new FeedbackEntry();
        ReflectionTestUtils.setField(feedback, "id", UUID.randomUUID());
        feedback.setUserId(userId);
        feedback.setType(FeedbackEntry.Type.BUG);
        feedback.setContext(FeedbackEntry.Context.IMPORT_FLOW);
        feedback.setSource(ClientPlatform.WEB);
        feedback.setMessage("The import bar sticks at 60%.");
        when(feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(feedback));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.supportTickets()).hasSize(1);
        SupportTicketDto.Detail ticketDto = bundle.supportTickets().get(0);
        assertThat(ticketDto.ticketNumber()).isEqualTo("SUP-000042");
        assertThat(ticketDto.attachments()).hasSize(1);
        assertThat(ticketDto.attachments().get(0).filename()).isEqualTo("screenshot.png");
        assertThat(ticketDto.attachments().get(0).sizeBytes()).isEqualTo(2048);

        assertThat(bundle.feedback()).hasSize(1);
        FeedbackDto.Summary feedbackDto = bundle.feedback().get(0);
        assertThat(feedbackDto.message()).isEqualTo("The import bar sticks at 60%.");
        assertThat(feedbackDto.type()).isEqualTo(FeedbackEntry.Type.BUG);
    }

    /** A ticket with no attachment must not throw looking itself up in the batched-and-grouped
     *  map -- the missing-key case getOrDefault(..., List.of()) exists for. */
    @Test
    void buildBundle_supportTickets_ticketWithNoAttachmentGetsAnEmptyList() {
        SupportTicket ticket = new SupportTicket();
        UUID ticketId = UUID.randomUUID();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTicketNumber("SUP-000007");
        ticket.setUserId(userId);
        ticket.setCategory(SupportTicket.Category.OTHER);
        ticket.setSource(ClientPlatform.WEB);
        ticket.setSubject("No attachment");
        ticket.setDescription("Text-only ticket.");
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.supportTickets()).hasSize(1);
        assertThat(bundle.supportTickets().get(0).attachments()).isEmpty();
    }

    private Map<String, byte[]> writeZipAndReadEntries(DataExportService.ExportBundle bundle) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);

        Map<String, byte[]> entries = new java.util.HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }
}
