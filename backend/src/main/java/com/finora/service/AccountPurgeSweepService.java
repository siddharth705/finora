package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Relationship;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalRepository;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailConnectionService;
import com.finora.repository.AccountReactivationTokenRepository;
import com.finora.repository.EmailVerificationTokenRepository;
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
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase B of the account-lifecycle work. Purges (anonymizes) an account -- see {@code
 * UserAccountLifecycleService.requestDeletion} for how an account gets to {@code
 * PENDING_DELETION} in the first place, and {@code User.STATUS_PENDING_DELETION}'s own doc
 * comment for the state machine.
 *
 * <h2>Two callers, one purge</h2>
 * {@link #purgeOne} runs twice over: {@code requestDeletion()} calls it directly, synchronously,
 * the moment a deletion is authorized (product decision: instant, not delayed -- see that
 * method's own doc comment for why). {@link #scheduledSweep} is no longer what makes deletion
 * happen; it is the crash-recovery backstop for the case the synchronous call never got that far
 * -- a process crash, an exception thrown before {@code purgeOne} could finish. Either caller
 * reaches the exact same method, which is what makes the backstop trivial: a half-purged account
 * is still sitting at {@code PENDING_DELETION}, and the next sweep pass re-runs {@code purgeOne}
 * on it from scratch, tolerant of every step above having already run once (see "Idempotent by
 * construction" below).
 *
 * <h2>Idempotent by construction, not via a job-tracking table</h2>
 * Modeled directly on {@link com.finora.imports.storage.StatementStorageSweepService}: no dedicated
 * job table, just a per-user try/catch and an ordering guarantee. {@link #purgeOne} writes {@code
 * users.status = DELETED} as its LAST step, only after every other table has already been cleared
 * or anonymized. A crash (or thrown exception) anywhere before that leaves the row at {@code
 * PENDING_DELETION}, exactly where the next sweep's discovery query will find it again -- every step
 * above tolerates being re-run: bulk deletes are no-ops on already-empty tables, and {@code
 * GmailConnectionService.disconnect} throws a catchable 404 once there is nothing left to
 * disconnect.
 *
 * <h2>Not class-level {@code @Transactional}</h2>
 * Gmail revocation is an outbound HTTPS call to Google; running it with a pooled database
 * connection held open is the BH-016/BH-047 failure mode this codebase has already been burned by
 * twice. The bulk-delete phase gets its own short transaction via the injected {@link
 * TransactionTemplate} instead, the same split {@link GmailConnectionService#disconnect} itself
 * already uses.
 *
 * <h2>Anonymized, not deleted: {@code statement_analysis_sessions}</h2>
 * Has a {@code user_id} column but deliberately no foreign key (see {@code
 * V59__statement_analysis_sessions.sql}'s own comment): the layout-intelligence evidence itself
 * should outlive the account that produced it. But two of its other columns are not evidence, they
 * are personal -- {@code file_name} is literally the name of the file the user uploaded, and {@code
 * failure_detail} can hold a fragment of the document that defeated the parser (see {@link
 * com.finora.imports.analysis.StatementAnalysisSessionRepository#anonymizeByUserId} for the exact
 * columns cleared). Deleting the row would defeat the reason for collecting it; leaving those two
 * columns populated would defeat "all your data" in the deletion confirmation email. Anonymized in
 * place, same pattern as {@code accounts} below.
 *
 * <h2>Explicitly excluded -- do not "fix" this later</h2>
 * {@code merchant_templates} -- global/admin-curated, no {@code user_id} column at all.
 * {@code gmail_oauth_states} is left out too, but not because it's excluded on purpose the same
 * way -- it already self-sweeps on a 10-minute TTL and is hash-only (no plaintext PII), so there is
 * nothing here for a user-scoped purge to usefully do to it.
 */
@Component
public class AccountPurgeSweepService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeSweepService.class);

    /** No longer a user-facing safety window (deletion is instant -- see {@code
     *  UserAccountLifecycleService.requestDeletion}'s own doc comment on that product decision).
     *  What this floor still does: bound how soon the crash-recovery sweep retries an account
     *  that got stuck at {@code PENDING_DELETION} because the synchronous purge attempt failed or
     *  the process crashed mid-way -- see this class's own "Two callers, one purge" doc. */
    static final Duration MINIMUM_SAFETY_BUFFER = Duration.ofHours(48);

    @Value("${app.account-purge.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.account-purge.sweep.retention-hours:48}")
    private int retentionHours;

    /** How many candidates one sweep run considers. Same reasoning as {@code
     *  StatementStorageSweepService.batchSize}: a backlog drains across runs rather than in one
     *  unbounded pass. */
    @Value("${app.account-purge.sweep.batch-size:200}")
    private int batchSize;

    private final UserRepository userRepository;
    private final GmailConnectionService gmailConnectionService;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final TransactionRepository transactionRepository;
    private final MerchantLearningEventRepository merchantLearningEventRepository;
    private final MerchantLearningAuditRepository merchantLearningAuditRepository;
    private final MerchantCategoryLearningRepository merchantCategoryLearningRepository;
    private final MerchantAliasRepository merchantAliasRepository;
    private final MerchantCategoryMapRepository merchantCategoryMapRepository;
    private final MerchantRepository merchantRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final CategoryRepository categoryRepository;
    private final RelationshipRepository relationshipRepository;
    private final RelationshipIdentifierRepository relationshipIdentifierRepository;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final ImportJobRepository importJobRepository;
    private final ImportSessionRepository importSessionRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final PasswordChangeSessionRepository passwordChangeSessionRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AccountReactivationTokenRepository accountReactivationTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final AccountRepository accountRepository;
    private final StatementImportRepository statementImportRepository;
    private final StatementImportService statementImportService;
    private final StatementAnalysisSessionRepository statementAnalysisSessionRepository;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public AccountPurgeSweepService(UserRepository userRepository,
                                     GmailConnectionService gmailConnectionService,
                                     GmailConnectionRepository gmailConnectionRepository,
                                     TransactionRepository transactionRepository,
                                     MerchantLearningEventRepository merchantLearningEventRepository,
                                     MerchantLearningAuditRepository merchantLearningAuditRepository,
                                     MerchantCategoryLearningRepository merchantCategoryLearningRepository,
                                     MerchantAliasRepository merchantAliasRepository,
                                     MerchantCategoryMapRepository merchantCategoryMapRepository,
                                     MerchantRepository merchantRepository,
                                     BudgetRepository budgetRepository,
                                     GoalRepository goalRepository,
                                     CategoryRuleRepository categoryRuleRepository,
                                     CategoryRepository categoryRepository,
                                     RelationshipRepository relationshipRepository,
                                     RelationshipIdentifierRepository relationshipIdentifierRepository,
                                     NetWorthSnapshotRepository netWorthSnapshotRepository,
                                     ImportJobRepository importJobRepository,
                                     ImportSessionRepository importSessionRepository,
                                     PasswordHistoryRepository passwordHistoryRepository,
                                     PasswordChangeSessionRepository passwordChangeSessionRepository,
                                     PasswordResetTokenRepository passwordResetTokenRepository,
                                     AccountReactivationTokenRepository accountReactivationTokenRepository,
                                     EmailVerificationTokenRepository emailVerificationTokenRepository,
                                     RefreshTokenRepository refreshTokenRepository,
                                     UserSettingsRepository userSettingsRepository,
                                     AccountRepository accountRepository,
                                     StatementImportRepository statementImportRepository,
                                     StatementImportService statementImportService,
                                     StatementAnalysisSessionRepository statementAnalysisSessionRepository,
                                     AuditService auditService,
                                     PasswordEncoder passwordEncoder,
                                     TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.gmailConnectionService = gmailConnectionService;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.transactionRepository = transactionRepository;
        this.merchantLearningEventRepository = merchantLearningEventRepository;
        this.merchantLearningAuditRepository = merchantLearningAuditRepository;
        this.merchantCategoryLearningRepository = merchantCategoryLearningRepository;
        this.merchantAliasRepository = merchantAliasRepository;
        this.merchantCategoryMapRepository = merchantCategoryMapRepository;
        this.merchantRepository = merchantRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.categoryRepository = categoryRepository;
        this.relationshipRepository = relationshipRepository;
        this.relationshipIdentifierRepository = relationshipIdentifierRepository;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.importJobRepository = importJobRepository;
        this.importSessionRepository = importSessionRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordChangeSessionRepository = passwordChangeSessionRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.accountReactivationTokenRepository = accountReactivationTokenRepository;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.accountRepository = accountRepository;
        this.statementImportRepository = statementImportRepository;
        this.statementImportService = statementImportService;
        this.statementAnalysisSessionRepository = statementAnalysisSessionRepository;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The scheduled trigger. Flag-gated for the same reason every other sweep in this codebase is:
     * an integration suite needs deterministic state, and a background thread purging accounts
     * mid-test is the cross-test pollution BH-058 was about. {@code application-test.yml} turns it
     * off and tests call {@link #sweep()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next sweep starts after the previous one
     * finishes, so a slow run (Gmail revocation calls, per-user work) cannot pile up overlapping
     * passes.
     */
    @Scheduled(fixedDelayString = "${app.account-purge.sweep.interval-ms:21600000}",
            initialDelayString = "${app.account-purge.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.purged() > 0 || result.failed() > 0) {
            log.info("Account purge sweep: {} account(s) purged, {} failed.", result.purged(), result.failed());
        }
    }

    /**
     * Runs one sweep pass: discovers accounts past the retention window and purges each in turn.
     *
     * <p>One user's failure is caught here, not inside {@link #purgeOne}, and does not stop the
     * batch -- the failed account stays at {@code PENDING_DELETION} and is retried whole (from
     * step 1) on the next run.
     *
     * @return how many accounts were purged or failed, so a caller or test can see the sweep did
     *         something
     */
    public Result sweep() {
        Instant cutoff = Instant.now().minus(effectiveRetention());
        List<UUID> candidates = userRepository.findIdsByStatusAndDeletionRequestedAtBefore(
                User.STATUS_PENDING_DELETION, cutoff, PageRequest.of(0, batchSize));

        int purged = 0;
        int failed = 0;
        for (UUID userId : candidates) {
            try {
                purgeOne(userId);
                purged++;
            } catch (Exception e) {
                failed++;
                String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("Account purge failed for user {}: {}", userId, message, e);
                auditService.record(userId, "ACCOUNT_PURGE_FAILED", "User", userId, Map.of("error", message));
            }
        }
        return new Result(purged, failed);
    }

    /**
     * Purges one account. Order matters -- see this class's own doc on why anonymizing {@code
     * users} has to be the very last write.
     *
     * <p>Package-private, not private: {@code UserAccountLifecycleService.requestDeletion} (same
     * package) calls this directly to make deletion instant -- see this class's own "Two callers,
     * one purge" doc. No ownership check here, same trust boundary as the scheduled caller: this
     * method takes {@code userId} on faith, safe today because both callers only ever pass an id
     * they already own the right to act on (the sweep discovers it from a status-scoped query;
     * requestDeletion passes the authenticated caller's own id). A future caller that passes a
     * less-trusted id would need to add that check itself, not assume this method has it.
     */
    void purgeOne(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted()) {
            // Nothing left to do -- an idempotent retry landing here a second time, or a row that
            // somehow no longer exists.
            return;
        }

        auditService.record(userId, "ACCOUNT_PURGE_STARTED", "User", userId, Map.of());

        try {
            gmailConnectionService.disconnect(userId);
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.NOT_FOUND) throw e;
            // No live connection -- the expected case on a retry, or a user who never connected
            // Gmail in the first place.
        }
        // Clears PII (googleEmail/googleUserId) from disconnected/revoked history rows too, not
        // just whatever was live a moment ago.
        gmailConnectionRepository.deleteByUserId(userId);

        transactionTemplate.executeWithoutResult(tx -> {
            transactionRepository.hardDeleteByUserId(userId);

            merchantLearningEventRepository.deleteByUserId(userId);
            merchantLearningAuditRepository.deleteByUserId(userId);
            merchantCategoryLearningRepository.deleteByUserId(userId);
            merchantAliasRepository.deleteByUserId(userId);
            merchantCategoryMapRepository.deleteByUserId(userId);
            merchantRepository.deleteByUserId(userId);

            budgetRepository.hardDeleteByUserId(userId);
            goalRepository.hardDeleteByUserId(userId);

            // Only ever matches this user's own scope=USER rows -- scope=GLOBAL rows always have
            // user_id IS NULL and are never touched.
            categoryRuleRepository.deleteByUserId(userId);
            categoryRepository.deleteByUserId(userId);

            List<Relationship> relationships = relationshipRepository.findByUserId(userId);
            for (Relationship relationship : relationships) {
                relationshipIdentifierRepository.deleteByRelationshipId(relationship.getId());
            }
            relationshipRepository.deleteAll(relationships);

            netWorthSnapshotRepository.deleteByUserId(userId);
            importJobRepository.deleteByUserId(userId);
            importSessionRepository.deleteByUserId(userId);
            passwordHistoryRepository.deleteByUserId(userId);
            // Includes the very DELETION_CONFIRMED session that authorized this deletion.
            passwordChangeSessionRepository.deleteByUserId(userId);
            passwordResetTokenRepository.deleteByUserId(userId);
            accountReactivationTokenRepository.deleteByUserId(userId);
            emailVerificationTokenRepository.deleteByUserId(userId);
            refreshTokenRepository.deleteByUserId(userId);
            userSettingsRepository.deleteByUserId(userId);

            // Evidence outlives the account (no FK, by design -- see this class's own doc on why),
            // but two of its columns aren't evidence, they're personal. See
            // StatementAnalysisSessionRepository.anonymizeByUserId's own doc for exactly what's
            // cleared and why the rest is left alone.
            statementAnalysisSessionRepository.anonymizeByUserId(userId);

            // Never hard-deleted -- an ON DELETE CASCADE from accounts would vaporize
            // statement_imports rows outside Hibernate's @SQLDelete interceptor entirely,
            // permanently orphaning any R2 object those rows still track. Anonymize the
            // plain-text-identity fields in place instead; the financial shape (balance, type,
            // bank) stays, since nothing downstream reads it as personal data.
            List<Account> accounts = accountRepository.findByUserIdIncludingDeleted(userId);
            for (Account account : accounts) {
                account.setAccountHolderName(null);
                account.setAccountNumberMasked(null);
                account.setBranchName(null);
                account.setIfscCode(null);
            }
            accountRepository.saveAll(accounts);
        });

        // One statement at a time, each in its own try/catch, reusing StatementImportService.delete
        // as-is -- by now this user's transactions are already gone, so each call just soft-deletes
        // the statement_imports row itself. Every statement is attempted (best-effort, so one bad
        // row doesn't block the rest), but a failure is collected rather than swallowed: finalizing
        // the user to DELETED below must not happen unless every statement actually purged, or the
        // failed row falls out of the next sweep's PENDING_DELETION discovery query and is never
        // retried -- exactly the class-level idempotency guarantee this method's own doc comment
        // promises.
        // Metadata projection, not the entity-returning finder: see
        // StatementImportRepository.StatementMetadata's own doc comment -- only .getId() is
        // needed to drive statementImportService.delete below.
        RuntimeException statementPurgeFailure = null;
        for (StatementMetadata statement : statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)) {
            try {
                statementImportService.delete(userId, statement.getId());
            } catch (Exception e) {
                log.error("Failed to purge statement {} for user {} during account purge: {}",
                        statement.getId(), userId, e.getMessage(), e);
                if (statementPurgeFailure == null) {
                    statementPurgeFailure = new IllegalStateException(
                            "Failed to purge all statements for user " + userId, e);
                } else {
                    statementPurgeFailure.addSuppressed(e);
                }
            }
        }
        if (statementPurgeFailure != null) {
            throw statementPurgeFailure;
        }

        Instant now = Instant.now();
        user.setEmail("deleted-" + userId + "@deleted.finora.invalid");
        // Random and discarded immediately -- nobody, including this user, will ever know it.
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID() + UUID.randomUUID().toString()));
        user.setFullName("Deleted User");
        user.setPhoneNumber(null);
        // deactivationReason is kept -- churn analytics, the same "persists indefinitely"
        // precedent reactivation not clearing it already established.
        user.setDeactivationNote(null);
        // Explicit RBAC grants (user_roles) -- functionally inert on a DELETED account (login()
        // rejects it unconditionally, so nothing can ever exercise them again), but a self-service
        // account-scope check already blocks admin accounts from this flow, so this is only ever
        // clearing a consumer account's own grants. Legacy user.role stays untouched, same as the
        // rest of the "everything else on User" list -- it's a plain string, not a table row, and
        // carries no more identity than the STATUS_DELETED row already does.
        user.getRoles().clear();
        user.setStatus(User.STATUS_DELETED);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        auditService.record(userId, "ACCOUNT_PURGED", "User", userId, Map.of());
    }

    /** See {@link #MINIMUM_SAFETY_BUFFER}. */
    private Duration effectiveRetention() {
        Duration configured = Duration.ofHours(retentionHours);
        return configured.compareTo(MINIMUM_SAFETY_BUFFER) > 0 ? configured : MINIMUM_SAFETY_BUFFER;
    }

    public record Result(int purged, int failed) {
    }
}
