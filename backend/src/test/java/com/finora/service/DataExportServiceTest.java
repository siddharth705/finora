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
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalService;
import com.finora.budgets.BudgetService;
import com.finora.imports.ImportSessionService;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
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
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private ImportSessionRepository importSessionRepository;
    private ImportSessionService importSessionService;
    private StatementImportRepository statementImportRepository;
    private StatementImportService statementImportService;
    private AuditService auditService;
    private DataExportService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        accountRepository = mock(AccountRepository.class);
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        BudgetService budgetService = mock(BudgetService.class);
        GoalService goalService = mock(GoalService.class);
        categoryRepository = mock(CategoryRepository.class);
        CategoryRuleRepository categoryRuleRepository = mock(CategoryRuleRepository.class);
        RelationshipService relationshipService = mock(RelationshipService.class);
        NetWorthSnapshotRepository netWorthSnapshotRepository = mock(NetWorthSnapshotRepository.class);
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        ImportJobRepository importJobRepository = mock(ImportJobRepository.class);
        importSessionRepository = mock(ImportSessionRepository.class);
        importSessionService = mock(ImportSessionService.class);
        statementImportRepository = mock(StatementImportRepository.class);
        statementImportService = mock(StatementImportService.class);
        GmailConnectionRepository gmailConnectionRepository = mock(GmailConnectionRepository.class);
        UserSettingsService userSettingsService = mock(UserSettingsService.class);
        WorkspaceSettingsService workspaceSettingsService = mock(WorkspaceSettingsService.class);
        BankManagementService bankManagementService = mock(BankManagementService.class);
        auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Empty-by-default collections, matching AccountPurgeSweepServiceTest's own convention --
        // so a test exercising one table never NPEs on every other one it doesn't care about.
        when(accountRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(transactionRepository.findByUserId(any())).thenReturn(List.of());
        when(budgetService.listForUser(any())).thenReturn(List.of());
        when(goalService.listForUser(any())).thenReturn(List.of());
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        when(categoryRuleRepository.findByUserId(any())).thenReturn(List.of());
        when(relationshipService.listForUser(any())).thenReturn(List.of());
        when(netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(any())).thenReturn(List.of());
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(statementImportService.duplicateCountsByStatementImport(any())).thenReturn(Map.of());
        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(any())).thenReturn(List.of());
        when(gmailConnectionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userSettingsService.get(any()))
                .thenReturn(new UserSettingsDto("jane@example.com", "Jane Doe", null, "light", "Asia/Kolkata",
                        null, false, Instant.now(), null));
        when(workspaceSettingsService.get(any())).thenReturn(new WorkspaceSettingsDto(90, Instant.now()));

        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        service = new DataExportService(userRepository, passwordEncoder, accountRepository, transactionRepository,
                budgetService, goalService, categoryRepository, categoryRuleRepository, relationshipService,
                netWorthSnapshotRepository, merchantRepository, importJobRepository, importSessionRepository,
                importSessionService, statementImportRepository, statementImportService, gmailConnectionRepository,
                userSettingsService, workspaceSettingsService, bankManagementService, auditService, objectMapper);
    }

    private User user() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setPasswordHash("hashed");
        return u;
    }

    @Test
    void buildBundle_wrongPassword_rejectsBeforeTouchingAnyRepository() {
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.buildBundle(userId, "wrong-password"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(auditService).recordEvenOnRollback(eq(userId), eq("INVALID_CURRENT_PASSWORD"), eq("User"), eq(userId));
        verifyNoInteractions(accountRepository, categoryRepository, importSessionRepository, statementImportRepository);
    }

    @Test
    void buildBundle_correctPassword_proceeds() {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password");

        assertThat(bundle.userId()).isEqualTo(userId);
        assertThat(bundle.email()).isEqualTo("jane@example.com");
        assertThat(bundle.accounts()).isEmpty();
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

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password");

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

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password");

        Map<UUID, ImportSessionSummaryDto> byId = bundle.importSessions().stream()
                .collect(java.util.stream.Collectors.toMap(ImportSessionSummaryDto::id, s -> s));
        assertThat(byId.get(single.getId()).rowCount()).isEqualTo(3);
        assertThat(byId.get(multi.getId()).rowCount()).isEqualTo(5);
    }

    private static List<StagedRow> nRows(int n) {
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(null);
        return rows;
    }

    private static StagedAccountSection section(int rowCount) {
        return new StagedAccountSection(null, nRows(rowCount), rowCount, 0, List.of());
    }

    /** One statement's storage failure must not abort the whole export -- same "one bad row
     *  doesn't sink the batch" discipline AccountPurgeSweepService already established for its
     *  own per-statement loop. */
    @Test
    void writeZip_oneStatementFileReadFails_writesPlaceholderAndContinues() throws IOException {
        StatementImport ok = new StatementImport();
        ReflectionTestUtils.setField(ok, "id", UUID.randomUUID());
        ok.setFileName("ok.csv");
        StatementImport failing = new StatementImport();
        ReflectionTestUtils.setField(failing, "id", UUID.randomUUID());
        failing.setFileName("failing.pdf");

        when(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(failing, ok));
        when(statementImportService.getFile(userId, ok.getId()))
                .thenReturn(new StatementImportService.FileDownload("ok.csv", "hello".getBytes(), "text/csv"));
        when(statementImportService.getFile(userId, failing.getId()))
                .thenThrow(new RuntimeException("object storage unreachable"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password");
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        String okEntry = "statements/" + ok.getId() + "-ok.csv";
        String failingPlaceholder = "statements/" + failing.getId() + "-failing.pdf.MISSING.txt";
        assertThat(entries).containsKey(okEntry);
        assertThat(new String(entries.get(okEntry))).isEqualTo("hello");
        assertThat(entries).containsKey(failingPlaceholder);
        assertThat(new String(entries.get(failingPlaceholder))).contains("RuntimeException");
        // The failed file's own bad bytes never got a normal, unmarked entry.
        assertThat(entries).doesNotContainKey("statements/" + failing.getId() + "-failing.pdf");
    }

    @Test
    void writeZip_manifestListsEveryOutOfScopeTableWithAReason() throws IOException {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password");
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

        List<String> includedNames = new ArrayList<>();
        manifest.get("included").forEach(n -> includedNames.add(n.get("name").asText()));
        assertThat(includedNames).contains("accounts.json", "transactions.json", "statements/");
        // manifest.json/README.txt describe the archive itself, not one more table in it.
        assertThat(includedNames).doesNotContain("manifest.json", "README.txt");

        assertThat(entries).containsKey("README.txt");
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
