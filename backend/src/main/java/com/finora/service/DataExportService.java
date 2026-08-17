package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.accounts.AccountDto;
import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import com.finora.dto.CategoryDto;
import com.finora.dto.DataExportDto.AccountExportEntry;
import com.finora.dto.DataExportDto.GmailConnectionExportDto;
import com.finora.dto.DataExportDto.Manifest;
import com.finora.dto.DataExportDto.ManifestEntry;
import com.finora.dto.DataExportDto.MerchantExportDto;
import com.finora.dto.DataExportDto.NetWorthSnapshotExportDto;
import com.finora.dto.ImportDto.ImportSessionSummaryDto;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.RelationshipDto;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.dto.UserSettingsDto;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalDto;
import com.finora.goals.GoalService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.jobs.ImportJobDto;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.rules.RuleDto;
import com.finora.transactions.TransactionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Download My Data" (Phase C of the account-lifecycle work). Scope deliberately mirrors {@link
 * AccountPurgeSweepService}'s own purge scope exactly -- the same tables that get deleted/
 * anonymized there are the ones read here. Excluded, for the same reasons that class's own doc
 * comment excludes them: {@code audit_logs} (the app's own accountability record, not the user's
 * data), {@code statement_analysis_sessions} (layout-intelligence evidence, and already stripped
 * of anything personal for a purged user), {@code refresh_tokens}/device-session history (login
 * bookkeeping), {@code password_history} (never had anything to show), and the merchant-learning
 * tables ({@code merchant_aliases}/{@code merchant_category_map}/{@code
 * merchant_category_learning}/{@code merchant_learning_audit}/{@code merchant_learning_event} --
 * derived categorization intelligence, not data the user provided).
 *
 * <h2>Two phases, for one specific reason</h2>
 * {@link #buildBundle} runs entirely inside one {@code @Transactional(readOnly = true)} call,
 * synchronously, before the controller returns anything -- if it throws, the caller gets a normal
 * clean error response, no ZIP bytes ever sent. It deliberately does NOT resolve any statement's
 * original file bytes. {@link #writeZip} runs afterward, inside the {@code StreamingResponseBody}
 * callback the controller wires up -- which Spring runs on a separate thread, after the controller
 * method (and its transaction) has already returned. {@link #writeZip} resolves each statement's
 * bytes fresh, once per statement, via {@code StatementImportService#getFile} rather than touching
 * a {@code StatementImport} entity captured back in {@link #buildBundle} -- not because {@code
 * fileContent}'s {@code @Basic(fetch = FetchType.LAZY)} would throw outside its owning transaction
 * (bytecode enhancement, which this build does not configure, is required for Hibernate to honor
 * that annotation on a non-{@code @Lob} field at all -- without it the field loads eagerly
 * regardless), but because {@link #buildBundle} never loads a full {@code StatementImport} entity
 * in the first place: {@code StatementImportRepository.findMetadataByUserIdOrderByImportedAtDesc}
 * projects out every column except {@code fileContent}, so the bulk fetch that produces {@code
 * statementSummaries} below can never pull a user's entire statement history's raw bytes into heap
 * during this transaction, whether or not the LAZY annotation actually does anything.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final UserRepository userRepository;
    private final GoogleReauthVerifier googleReauthVerifier;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetService budgetService;
    private final GoalService goalService;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final RelationshipService relationshipService;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final MerchantRepository merchantRepository;
    private final ImportJobRepository importJobRepository;
    private final ImportSessionRepository importSessionRepository;
    private final ImportSessionService importSessionService;
    private final StatementImportRepository statementImportRepository;
    private final StatementImportService statementImportService;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final UserSettingsService userSettingsService;
    private final WorkspaceSettingsService workspaceSettingsService;
    private final BankManagementService bankManagementService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public DataExportService(UserRepository userRepository, GoogleReauthVerifier googleReauthVerifier,
                              AccountRepository accountRepository, TransactionRepository transactionRepository,
                              BudgetService budgetService, GoalService goalService, CategoryRepository categoryRepository,
                              CategoryRuleRepository categoryRuleRepository, RelationshipService relationshipService,
                              NetWorthSnapshotRepository netWorthSnapshotRepository, MerchantRepository merchantRepository,
                              ImportJobRepository importJobRepository, ImportSessionRepository importSessionRepository,
                              ImportSessionService importSessionService, StatementImportRepository statementImportRepository,
                              StatementImportService statementImportService, GmailConnectionRepository gmailConnectionRepository,
                              UserSettingsService userSettingsService, WorkspaceSettingsService workspaceSettingsService,
                              BankManagementService bankManagementService, AuditService auditService, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.googleReauthVerifier = googleReauthVerifier;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetService = budgetService;
        this.goalService = goalService;
        this.categoryRepository = categoryRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.relationshipService = relationshipService;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.merchantRepository = merchantRepository;
        this.importJobRepository = importJobRepository;
        this.importSessionRepository = importSessionRepository;
        this.importSessionService = importSessionService;
        this.statementImportRepository = statementImportRepository;
        this.statementImportService = statementImportService;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.userSettingsService = userSettingsService;
        this.workspaceSettingsService = workspaceSettingsService;
        this.bankManagementService = bankManagementService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * Phase 1: proves current-password, then gathers every in-scope table except statement file
     * bytes. Deliberately does NOT block {@code SUSPENDED}/{@code DEACTIVATED}/{@code
     * PENDING_DELETION}: a suspended or deactivated account has no path to a fresh login to even
     * reach this (the same "unreachable in practice" reasoning {@link AccountPurgeSweepService}
     * itself relies on) -- only a still-valid access token issued before the status changed
     * (up to 15 minutes stale) can. {@code PENDING_DELETION} is the same story now that deletion
     * is instant, not a deliberate window: it's either a sub-second state while {@code
     * UserAccountLifecycleService.requestDeletion}'s synchronous purge is actually in flight, or
     * an account stuck there because that purge failed and the crash-recovery sweep hasn't
     * retried it yet. Neither case is a reason to block a best-effort export -- there's no
     * "deliberate window" to protect anymore, and refusing one in the stuck case would only
     * penalize a user whose purge is the one that's broken.
     */
    @Transactional(readOnly = true)
    public ExportBundle buildBundle(UUID userId, String currentPassword, String googleIdToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!googleReauthVerifier.verify(user, currentPassword, googleIdToken)) {
            // recordEvenOnRollback, not record: this method is @Transactional(readOnly = true),
            // and throwing ApiException right after a plain record() call would roll the audit
            // write back along with the (nonexistent) rest of this transaction -- see that
            // method's own doc comment for how this was actually caught.
            auditService.recordEvenOnRollback(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, user.isGoogleAccount()
                    ? "We couldn't verify your Google account. Please try again."
                    : "Current password is incorrect.");
        }

        // Mirrors AccountPurgeSweepService.purgeOne()'s own table order.

        // Fetched once, ahead of accounts, and shared by both toAccountExportEntry (Finding 3:
        // per-account statementsCount/transactionsCount/lastImportedAt, computed the same way
        // AccountService.listForUser does it, batched rather than one query per account) and the
        // statement-summaries list below -- see StatementMetadata's own doc comment for why this
        // is a fileContent-free projection, not the entity-returning finder this class used to call.
        List<StatementMetadata> statementMetadata = statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId);
        Map<UUID, StatementMetadata> latestImportByAccount = new HashMap<>();
        Map<UUID, Integer> statementsCountByAccount = new HashMap<>();
        for (StatementMetadata m : statementMetadata) {
            latestImportByAccount.putIfAbsent(m.getAccountId(), m); // already ordered by importedAt desc
            statementsCountByAccount.merge(m.getAccountId(), 1, Integer::sum);
        }
        Map<UUID, Long> transactionsCountByAccount = transactionRepository.countByAccountForUser(userId).stream()
                .collect(Collectors.toMap(
                        TransactionRepository.AccountTransactionCount::getAccountId,
                        TransactionRepository.AccountTransactionCount::getCount));

        List<AccountExportEntry> accounts = accountRepository.findByUserIdIncludingDeleted(userId).stream()
                .map(a -> toAccountExportEntry(a, latestImportByAccount, statementsCountByAccount, transactionsCountByAccount))
                .toList();

        // Fetched once and reused for both categoryNames (transactions.json's category label) and
        // categories.json itself -- this used to query categoryRepository.findByUserId(userId)
        // twice, a few lines apart, for the same rows.
        List<Category> userCategories = categoryRepository.findByUserId(userId);
        Map<UUID, String> categoryNames = userCategories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        List<TransactionDto> transactions = transactionRepository.findByUserId(userId).stream()
                .map(t -> TransactionDto.from(t, categoryNames.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();

        List<BudgetDto> budgets = budgetService.listForUser(userId);
        List<GoalDto> goals = goalService.listForUser(userId);

        List<CategoryDto> categories = userCategories.stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem()))
                .toList();
        List<RuleDto> categoryRules = categoryRuleRepository.findByUserId(userId).stream()
                .map(RuleDto::from)
                .toList();

        List<RelationshipDto> relationships = relationshipService.listForUser(userId);

        List<NetWorthSnapshotExportDto> netWorthSnapshots = netWorthSnapshotRepository
                .findByUserIdOrderBySnapshotDateAsc(userId).stream()
                .map(NetWorthSnapshotExportDto::from)
                .toList();

        List<MerchantExportDto> merchants = merchantRepository.findByUserId(userId).stream()
                .map(MerchantExportDto::from)
                .toList();

        List<ImportJobDto.Progress> importJobs = importJobRepository
                .findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).stream()
                .map(ImportJobDto.Progress::of)
                .toList();

        // Bug fix (review): used to map every session unguarded -- one historical session whose
        // stagedRowsJson/sectionsJson fails to deserialize against the current record shape (e.g.
        // a future field rename) threw ImportSessionService.readJson's uncaught
        // IllegalStateException straight out of buildBundle, failing the user's entire export over
        // one unrelated, unreadable row. Same "one bad item doesn't sink the batch" discipline the
        // per-statement loop in writeZip already applies -- caught, logged, and that one session
        // dropped from the list rather than aborting everything else.
        List<ImportSessionSummaryDto> importSessions = importSessionRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .flatMap(session -> {
                    try {
                        return java.util.stream.Stream.of(toSessionSummary(session));
                    } catch (Exception e) {
                        log.warn("Data export: failed to summarize import session {} for user {}: {}",
                                session.getId(), userId, e.getMessage(), e);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();

        Map<UUID, Integer> duplicateCounts = statementImportService.duplicateCountsByStatementImport(userId);
        List<Summary> statementSummaries = statementMetadata.stream()
                .map(s -> Summary.from(s, duplicateCounts.getOrDefault(s.getId(), 0)))
                .toList();

        List<GmailConnectionExportDto> gmailConnections = gmailConnectionRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(GmailConnectionExportDto::from)
                .toList();

        UserSettingsDto userSettings = userSettingsService.get(userId);
        WorkspaceSettingsDto workspaceSettings = workspaceSettingsService.get(userId);

        return new ExportBundle(userId, user.getEmail(), accounts, transactions, budgets, goals, categories,
                categoryRules, relationships, netWorthSnapshots, merchants, importJobs, importSessions,
                statementSummaries, gmailConnections, userSettings, workspaceSettings);
    }

    /**
     * Phase 2: assembles the ZIP and streams it. Every statement's bytes are resolved via {@link
     * StatementImportService#getFile} individually, right here -- see this class's own doc comment
     * on why that has to happen from inside this method, not from data captured in {@link
     * #buildBundle}.
     *
     * <p>One statement's storage failure doesn't abort the export -- caught, logged, replaced with
     * a placeholder entry, and the rest continues. This is the same "one bad row doesn't sink the
     * batch" discipline {@link AccountPurgeSweepService} already established for its own per-
     * statement loop.
     */
    public void writeZip(UUID userId, ExportBundle bundle, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeJsonEntry(zos, "manifest.json", buildManifest(bundle));
            writeTextEntry(zos, "README.txt", buildReadmeText());

            writeJsonEntry(zos, "accounts.json", bundle.accounts());
            writeJsonEntry(zos, "transactions.json", bundle.transactions());
            writeJsonEntry(zos, "budgets.json", bundle.budgets());
            writeJsonEntry(zos, "goals.json", bundle.goals());
            writeJsonEntry(zos, "categories.json", bundle.categories());
            writeJsonEntry(zos, "category_rules.json", bundle.categoryRules());
            writeJsonEntry(zos, "relationships.json", bundle.relationships());
            writeJsonEntry(zos, "net_worth_history.json", bundle.netWorthSnapshots());
            writeJsonEntry(zos, "merchants.json", bundle.merchants());
            writeJsonEntry(zos, "import_jobs.json", bundle.importJobs());
            writeJsonEntry(zos, "import_sessions.json", bundle.importSessions());
            writeJsonEntry(zos, "statements.json", bundle.statementSummaries());
            writeJsonEntry(zos, "gmail_connection.json", bundle.gmailConnections());
            writeJsonEntry(zos, "account_settings.json", bundle.userSettings());
            writeJsonEntry(zos, "workspace_settings.json", bundle.workspaceSettings());

            for (Summary statement : bundle.statementSummaries()) {
                String entryName = "statements/" + statement.id() + "-" + sanitize(statement.fileName());
                try {
                    StatementImportService.FileDownload file = statementImportService.getFile(userId, statement.id());
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(file.content());
                    zos.closeEntry();
                } catch (IOException e) {
                    // Bug fix (review): a broken pipe (client disconnect mid-download) surfaces as
                    // an ordinary IOException from zos.write/putNextEntry/closeEntry above -- the
                    // SAME exception type a genuinely broken stream produces. Writing a recovery
                    // placeholder onto that same, now-dead stream would itself throw a second,
                    // uncaught IOException, misattributing an ordinary client-side cancel as a
                    // generic internal failure once it propagates out of this method. Re-thrown
                    // here, not converted to a placeholder: an IOException means the STREAM is
                    // unusable, not that this one file's storage read failed, so no further write
                    // to it (a placeholder or the next statement) can succeed either.
                    throw e;
                } catch (Exception e) {
                    log.warn("Data export: failed to read statement {} for user {}: {}",
                            statement.id(), userId, e.getMessage(), e);
                    writeTextEntry(zos, entryName + ".MISSING.txt",
                            "This file could not be included in your export (" + e.getClass().getSimpleName()
                                    + "). Contact support if you need it.");
                }
            }
        }
    }

    private AccountExportEntry toAccountExportEntry(Account a, Map<UUID, StatementMetadata> latestImportByAccount,
                                                      Map<UUID, Integer> statementsCountByAccount,
                                                      Map<UUID, Long> transactionsCountByAccount) {
        // Bug fix (review): used to call AccountDto's 2-arg from(Account, BankDto) overload, which
        // hardcodes statementsCount/transactionsCount/lastImportedAt to 0/0/null -- every exported
        // account misrepresented its own history regardless of how much it actually had, even
        // though transactions.json/statements.json elsewhere in this same archive had the real
        // numbers. Now computed the same way AccountService.listForUser does it: batched maps built
        // once in buildBundle, not a query per account.
        StatementMetadata latestImport = latestImportByAccount.get(a.getId());
        AccountDto dto = AccountDto.from(a, bankManagementService.resolve(a.getBankId()),
                latestImport == null ? null : latestImport.getImportedAt(),
                latestImport == null ? null : latestImport.getStatementPeriodStart(),
                latestImport == null ? null : latestImport.getStatementPeriodEnd(),
                statementsCountByAccount.getOrDefault(a.getId(), 0),
                transactionsCountByAccount.getOrDefault(a.getId(), 0L));
        return new AccountExportEntry(dto, a.getDeletedAt() != null, a.getDeletedAt());
    }

    /** Branches on session kind -- a MULTI_ACCOUNT session's row count has to come from {@code
     *  readSections}, not {@code readStagedRows}, which throws for anything but a SINGLE_ACCOUNT
     *  session (see this class's own scope doc and ImportSessionService.requireKind). */
    private ImportSessionSummaryDto toSessionSummary(ImportSession session) {
        int rowCount = switch (session.getSessionKind()) {
            case ImportSession.KIND_SINGLE_ACCOUNT -> importSessionService.readStagedRows(session).size();
            case ImportSession.KIND_MULTI_ACCOUNT -> importSessionService.readSections(session).stream()
                    .mapToInt(section -> section.rows().size()).sum();
            default -> 0;
        };
        return new ImportSessionSummaryDto(session.getId(), session.getFileName(), rowCount,
                session.getCreatedAt(), session.getExpiresAt());
    }

    private Manifest buildManifest(ExportBundle bundle) {
        List<ManifestEntry> included = List.of(
                new ManifestEntry("accounts.json", "Your bank/card/investment/loan accounts.", bundle.accounts().size()),
                new ManifestEntry("transactions.json", "Every transaction on your ledger.", bundle.transactions().size()),
                new ManifestEntry("budgets.json", "Your monthly category budgets.", bundle.budgets().size()),
                new ManifestEntry("goals.json", "Your savings goals.", bundle.goals().size()),
                new ManifestEntry("categories.json", "Your custom transaction categories.", bundle.categories().size()),
                new ManifestEntry("category_rules.json", "Your own auto-categorization rules.", bundle.categoryRules().size()),
                new ManifestEntry("relationships.json", "People/accounts you've linked transactions to.", bundle.relationships().size()),
                new ManifestEntry("net_worth_history.json", "Your saved net worth snapshots.", bundle.netWorthSnapshots().size()),
                new ManifestEntry("merchants.json", "Merchants Finora has recognized from your transactions.", bundle.merchants().size()),
                new ManifestEntry("import_jobs.json", "Your statement import job history.", bundle.importJobs().size()),
                new ManifestEntry("import_sessions.json", "Your statement staging session history.", bundle.importSessions().size()),
                new ManifestEntry("statements.json", "Metadata for every statement you've imported.", bundle.statementSummaries().size()),
                new ManifestEntry("statements/", "The original statement files you uploaded, where still retrievable.", bundle.statementSummaries().size()),
                new ManifestEntry("gmail_connection.json", "Your Gmail connection status, if any (no credentials).", bundle.gmailConnections().size()),
                new ManifestEntry("account_settings.json", "Your profile and account preferences.", null),
                new ManifestEntry("workspace_settings.json", "Your categorization workspace preferences.", null)
        );
        List<ManifestEntry> excluded = List.of(
                new ManifestEntry("audit_logs", "Your own actions are logged for security, not collected as your data.", null),
                new ManifestEntry("merchant_aliases, merchant_category_map, merchant_category_learning, merchant_learning_audit, merchant_learning_event",
                        "Derived categorization intelligence Finora builds from your transactions, not data you provided directly.", null),
                new ManifestEntry("refresh_tokens", "Login session bookkeeping (device/IP history), not your financial data.", null),
                new ManifestEntry("password_history", "Used only to block password reuse; never held anything to show.", null),
                new ManifestEntry("statement_analysis_sessions", "Internal parsing evidence Finora keeps to improve statement recognition, not part of your ledger.", null)
        );
        return new Manifest(Instant.now(), bundle.userId(), bundle.email(), included, excluded);
    }

    private String buildReadmeText() {
        return """
                This is your data export from Finora, generated on request.

                See manifest.json for the full list of what's included in this archive and what's
                deliberately excluded (with a one-line reason for each).

                If a file under statements/ ends in ".MISSING.txt" instead of containing your
                original document, that one file couldn't be retrieved at export time -- contact
                support if you need it.
                """;
    }

    private void writeJsonEntry(ZipOutputStream zos, String entryName, Object value) throws IOException {
        // Serialized to a byte[] first, not written straight to zos: ObjectMapper.writeValue(
        // OutputStream, Object) defaults AUTO_CLOSE_TARGET=true and would close the whole
        // ZipOutputStream after the first entry, silently truncating every entry after it.
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(bytes);
        zos.closeEntry();
    }

    private void writeTextEntry(ZipOutputStream zos, String entryName, String text) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** Keeps a user-chosen filename from escaping its intended statements/ directory inside the
     *  archive (a literal ".." or leading "/" in an uploaded filename) or otherwise confusing a
     *  ZIP-reading tool -- strips everything but word characters, dots, dashes and spaces. */
    private static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) return "statement";
        return fileName.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    /** Internal transport between {@link #buildBundle} and {@link #writeZip} -- never serialized
     *  directly. {@code statementSummaries} alone carries what {@link #writeZip} needs to resolve
     *  each statement's bytes ({@code id()}/{@code fileName()}) -- a separate entity list is no
     *  longer carried alongside it (removed in the same fix that made {@code buildBundle} stop
     *  loading full {@code StatementImport} entities at all; see this class's own doc comment). */
    public record ExportBundle(
            UUID userId, String email,
            List<AccountExportEntry> accounts, List<TransactionDto> transactions, List<BudgetDto> budgets,
            List<GoalDto> goals, List<CategoryDto> categories, List<RuleDto> categoryRules,
            List<RelationshipDto> relationships, List<NetWorthSnapshotExportDto> netWorthSnapshots,
            List<MerchantExportDto> merchants, List<ImportJobDto.Progress> importJobs,
            List<ImportSessionSummaryDto> importSessions,
            List<Summary> statementSummaries, List<GmailConnectionExportDto> gmailConnections,
            UserSettingsDto userSettings, WorkspaceSettingsDto workspaceSettings
    ) {}
}
