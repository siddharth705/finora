package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.StagedAccountSectionFilter;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The section-index coordinate space, proved end to end.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>{@code ImportService} stages the FILTERED section list --
 * {@link StagedAccountSectionFilter#onlySectionsThatAreActuallyAccounts} applied to the generator's
 * raw output -- and that filtered list is what {@code createMultiSection} persists as
 * {@code sectionsJson}. Every section index the system speaks in afterwards is therefore an index
 * into the FILTERED list: {@code confirmMultiSection}'s loop index explicitly, and
 * {@code confirmSession}'s absent index implicitly (it means filtered section 0).
 *
 * <p>{@link ClosingBalanceEvidenceRederivationService} re-runs the generator, and used to index
 * into its RAW section list with those filtered indices. The two spaces agree only while nothing is
 * dropped. On the ordinary combined-statement shape -- deposit schedules printed above the savings
 * ledger -- they disagree by exactly the number of leading non-account sections, so the evidence
 * recorded for "section 0" described an empty deposit schedule while carrying the savings section's
 * label. The corpus would have shown {@code INSUFFICIENT}/{@code NOT_APPLICABLE} where the truth
 * was {@code SUPPORTED}/{@code VERIFIED}: not a gap in the measurement but its inverse, which is
 * the one kind of wrong a measurement corpus cannot survive.
 *
 * <p>Nothing here touches what is confirmed or persisted. The shadow path decides nothing, and
 * these tests assert about recorded observations only.
 *
 * <h2>Why this fixture</h2>
 *
 * <p>{@link PdfFixtureBuilder#buildDepositSchedulesBeforeCompositeAccountsSample} puts the
 * non-account sections FIRST, which is the only arrangement that can tell the two coordinate spaces
 * apart -- every pre-existing composite fixture leads with its transactional section, where a
 * filtered index and a raw index are the same number and the defect is invisible. Each test below
 * pins the raw shape it depends on, so if the extractor ever stops producing leading empty
 * sections, these tests fail as "the fixture no longer reproduces the shape" rather than silently
 * passing while proving nothing.
 */
class ClosingBalanceEvidenceSectionIndexIT extends AbstractIntegrationTest {

    /** The savings ledger's own closing balance: 50,000.00 opening + 55,000.00 in - 2,000.00 out.
     *  Supported by the savings section, and unsupportable by an empty deposit schedule. */
    private static final BigDecimal SAVINGS_CLOSING_BALANCE = new BigDecimal("103000.00");

    /** The SECOND account's own closing balance in the interspersed fixture: 10,000.00 opening +
     *  12,000.00 in - 1,500.00 out. Deliberately different from the savings one, so an assertion
     *  about staged index 1 cannot be satisfied by any other section in that document. */
    private static final BigDecimal CURRENT_ACCOUNT_CLOSING_BALANCE = new BigDecimal("20500.00");

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ClosingBalanceEvidenceRederivationService rederivationService;
    @Autowired private ClosingBalanceEvidenceShadowObserver observer;
    @Autowired private PdfPreviewGenerator pdfPreviewGenerator;
    @Autowired private StatementTotalsValidator statementTotalsValidator;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.statement-storage.provider", () -> "filesystem");
        registry.add("app.statement-storage.filesystem.root",
                () -> System.getProperty("java.io.tmpdir") + "/finora-section-index-it");
    }

    @AfterEach
    void cleanUp() {
        if (!createdUserIds.isEmpty()) {
            userRepository.deleteAllById(createdUserIds);
            createdUserIds.clear();
        }
    }

    private UUID newUser() {
        User user = new User();
        user.setEmail("section-index-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Section Index IT User");
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUserIds.add(id);
        return id;
    }

    private List<StagedAccountSection> rawSectionsOf(UUID userId, byte[] bytes) throws Exception {
        return pdfPreviewGenerator.generateSectionsWithContext(userId, "combined-statement.pdf", bytes)
                .sections();
    }

    // =================================================================================
    // 1. The confirmed savings section receives the SAVINGS evidence.
    // =================================================================================

    /**
     * Confirming staged section 0 of a composite statement whose savings ledger sits at RAW index 2
     * must assess the savings ledger.
     *
     * <p>The assertion is on the savings section's own arithmetic, not on a status alone: the
     * validator reconciles 50,000.00 + 55,000.00 - 2,000.00 against a claimed 103,000.00 and
     * VERIFIES. No other section in this document can produce that outcome, so the assertion
     * identifies which section was read rather than merely describing it.
     */
    @Test
    void confirmedSavingsSection_isAssessedOverTheSavingsSectionsOwnData() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeCompositeAccountsSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        // The shape this test depends on: four raw sections, two leading empty ones, savings at 2.
        List<StagedAccountSection> raw = rawSectionsOf(userId, bytes);
        assertThat(raw).hasSize(4);
        assertThat(raw.get(0).rows()).isEmpty();
        assertThat(raw.get(1).rows()).isEmpty();
        assertThat(raw.get(2).detectedAccount().closingBalance()).isEqualByComparingTo(SAVINGS_CLOSING_BALANCE);

        // ... and what the user is actually offered: two accounts, savings first.
        assertThat(staged.multiAccount()).isTrue();
        List<StagedAccountSection> persisted = importSessionService.readSections(
                importSessionService.getOwnedSession(userId, staged.sessionId()));
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(0).detectedAccount().closingBalance())
                .isEqualByComparingTo(SAVINGS_CLOSING_BALANCE);

        var evidence = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 0, SAVINGS_CLOSING_BALANCE);

        assertThat(evidence.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(evidence.assessment().financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);
    }

    // =================================================================================
    // 2. A preceding deposit schedule cannot steal the evidence.
    // =================================================================================

    /**
     * The failure shape itself: what the OLD code would have computed is constructed here from the
     * raw section it would have read, and asserted to be a DIFFERENT, weaker answer than the one
     * the service now produces for the same confirm.
     *
     * <p>Without this control the previous test would be satisfiable by a coincidence -- two
     * sections that happened to yield the same status. Pinning raw index 0's answer as
     * {@code NOT_APPLICABLE} while staged index 0's is {@code VERIFIED} proves the tests can tell
     * the sections apart, and proves the defect was real in this fixture rather than merely
     * possible.
     */
    @Test
    void precedingDepositSchedule_cannotStealTheSavingsSectionsEvidence() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeCompositeAccountsSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        List<StagedAccountSection> raw = rawSectionsOf(userId, bytes);
        StagedAccountSection wouldHaveBeenRead = raw.get(0);

        // What indexing the RAW list with a staged index produces -- the recorded defect.
        var strayFinding = statementTotalsValidator.check(wouldHaveBeenRead.rows(),
                wouldHaveBeenRead.detectedAccount().openingBalance(), SAVINGS_CLOSING_BALANCE);
        assertThat(strayFinding.outcome()).isEqualTo("NOT_APPLICABLE");

        // What the service actually produces for the same confirm.
        var evidence = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 0, SAVINGS_CLOSING_BALANCE);

        assertThat(evidence.statementTotals().outcome())
                .isNotEqualTo(strayFinding.outcome())
                .isEqualTo("VERIFIED");
        assertThat(evidence.assessment().financialValidation().status())
                .isEqualTo(EvidenceStatus.SUPPORTED);
    }

    /**
     * The credit-card section, staged index 1, is assessed as ITSELF -- not as the savings section
     * that precedes it and not as a raw-index-1 deposit schedule.
     *
     * <p>The second index matters independently of the first: a fix that mapped every index to the
     * first surviving account would pass the savings tests above and be just as wrong. This
     * section states no balances, so its honest answer is {@code NOT_APPLICABLE}, and that is
     * asserted rather than avoided.
     */
    @Test
    void secondStagedSection_isAssessedOverItsOwnSectionToo() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeCompositeAccountsSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        var savings = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 0, SAVINGS_CLOSING_BALANCE);
        var creditCard = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 1, SAVINGS_CLOSING_BALANCE);

        assertThat(savings.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(creditCard.statementTotals().outcome()).isEqualTo("NOT_APPLICABLE");
    }

    /**
     * The interspersed arrangement -- a deposit schedule BETWEEN the two accounts rather than
     * before them.
     *
     * <p>Adversarial: the two leading-schedule fixtures could both be satisfied by a fix that
     * merely skipped a LEADING run of non-account sections. Here staged section 0 is raw section 0
     * (so it is right either way and proves nothing), and staged section 1 is raw section 2. Only a
     * fix that applies the real filter -- which drops non-account sections wherever they occur --
     * lands on the current account for staged index 1 rather than on the deposit schedule.
     *
     * <p>The two accounts close at DIFFERENT balances on purpose, so staged index 1 is answerable
     * only by the section it actually names: the deposit schedule at raw index 1 cannot reconcile
     * 20,500.00, and neither can the savings ledger.
     */
    @Test
    void nonAccountSectionBetweenTwoAccounts_stillResolvesEachStagedIndexToItsOwnSection() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositScheduleBetweenAccountsSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        // The shape: the dropped section is in the MIDDLE, not at the front.
        List<StagedAccountSection> raw = rawSectionsOf(userId, bytes);
        assertThat(raw).hasSize(3);
        assertThat(raw.get(0).rows()).isNotEmpty();
        assertThat(raw.get(1).rows()).isEmpty();
        assertThat(raw.get(2).rows()).isNotEmpty();
        assertThat(staged.multiAccount()).isTrue();

        var first = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 0, SAVINGS_CLOSING_BALANCE);
        var second = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 1, CURRENT_ACCOUNT_CLOSING_BALANCE);

        assertThat(first.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(second.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(second.assessment().financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);

        // The control: raw index 1 -- what staged index 1 used to resolve to -- cannot answer this.
        assertThat(statementTotalsValidator.check(raw.get(1).rows(),
                raw.get(1).detectedAccount().openingBalance(), CURRENT_ACCOUNT_CLOSING_BALANCE).outcome())
                .isEqualTo("NOT_APPLICABLE");
        // And neither can the OTHER account, so index 1 is not merely landing on "some account".
        assertThat(rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), 0, CURRENT_ACCOUNT_CLOSING_BALANCE)
                .statementTotals().outcome()).isNotEqualTo("VERIFIED");
    }

    /**
     * Every branch of the filter, including the two that return the list unchanged -- so that a
     * future edit to {@link StagedAccountSectionFilter} cannot quietly change what a staged index
     * means for one shape while the PDF-driven tests above keep passing on theirs.
     *
     * <p>The all-empty branch is the one worth stating out loud: it returns the ORIGINAL list, so
     * filtered and raw space coincide there, and a document of nothing but empty sections is staged
     * with raw indices. That is not a bug to route around -- it is the definition both callers now
     * share, which is exactly why sharing the function rather than reimplementing the rule is what
     * makes the two agree.
     */
    @Test
    void theFilterDefinesOneIndexSpace_acrossEveryBranch() {
        StagedAccountSection populatedA = section("A", 2);
        StagedAccountSection populatedB = section("B", 1);
        StagedAccountSection empty = section("E", 0);

        // size <= 1: identity, even when the single section is empty.
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(List.of(empty)))
                .containsExactly(empty);

        // nothing to drop: identity.
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(
                List.of(populatedA, populatedB))).containsExactly(populatedA, populatedB);

        // leading, interspersed and trailing empties all collapse to the accounts, in order.
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(
                List.of(empty, empty, populatedA, populatedB)))
                .extracting(s -> s.detectedAccount().suggestedName()).containsExactly("A", "B");
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(
                List.of(populatedA, empty, populatedB)))
                .extracting(s -> s.detectedAccount().suggestedName()).containsExactly("A", "B");
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(
                List.of(populatedA, empty, populatedB, empty)))
                .extracting(s -> s.detectedAccount().suggestedName()).containsExactly("A", "B");

        // every section empty: the original list, unchanged and unrenumbered.
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(List.of(empty, empty)))
                .containsExactly(empty, empty);
    }

    private static StagedAccountSection section(String name, int rowCount) {
        List<com.finora.dto.ImportDto.StagedRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(new com.finora.dto.ImportDto.StagedRow(java.time.LocalDate.of(2026, 7, 1 + i),
                    name + " row " + i, BigDecimal.ONE, "EXPENSE", null, null, null, false, null,
                    null));
        }
        return new StagedAccountSection(new com.finora.dto.ImportDto.DetectedAccountInfo(name, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, 0.0, true,
                List.of(), null, null, null, null, null, null, null, null),
                List.copyOf(rows), rowCount, 0, List.of());
    }

    // =================================================================================
    // 3. A document that FILTERS DOWN to one account resolves to that account.
    // =================================================================================

    /**
     * The commoner and more dangerous shape. Three raw sections, two of them deposit schedules, one
     * savings ledger -- so the filtered list holds exactly ONE account, {@code ImportService} takes
     * its single-account staging branch, and the confirm that follows carries no section index at
     * all. "No index" has to resolve to staged section 0, which is RAW section 2.
     *
     * <p>Passing {@code null} is not an approximation of the real path here: it is literally what
     * {@code ImportService.confirmSession} passes to the observer.
     */
    @Test
    void documentFilteringDownToOneAccount_resolvesTheAbsentIndexToThatAccount() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeSingleAccountSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        // The shape: multi-section document, single-account session, savings at RAW index 2.
        List<StagedAccountSection> raw = rawSectionsOf(userId, bytes);
        assertThat(raw).hasSize(3);
        assertThat(raw.get(0).rows()).isEmpty();
        assertThat(raw.get(1).rows()).isEmpty();
        assertThat(StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts(raw)).hasSize(1);
        assertThat(staged.multiAccount()).isFalse();
        assertThat(staged.staging().detectedAccount().closingBalance())
                .isEqualByComparingTo(SAVINGS_CLOSING_BALANCE);

        var evidence = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                userId, staged.sessionId(), null, SAVINGS_CLOSING_BALANCE);

        assertThat(evidence.statementTotals().outcome()).isEqualTo("VERIFIED");
        assertThat(evidence.assessment().financialValidation().status()).isEqualTo(EvidenceStatus.SUPPORTED);

        // And the control, as above: raw index 0 -- what the absent index used to resolve to --
        // cannot produce that answer.
        assertThat(statementTotalsValidator.check(raw.get(0).rows(),
                raw.get(0).detectedAccount().openingBalance(), SAVINGS_CLOSING_BALANCE).outcome())
                .isEqualTo("NOT_APPLICABLE");
    }

    // =================================================================================
    // 4. The recorded section index and the recorded finding describe the same section.
    // =================================================================================

    /**
     * A fix that corrected the DATA and left the recorded index alone would still produce a corpus
     * that reads correctly row by row and is mislabelled -- which for a corpus is the same defect.
     * So this goes through the real observer and reads the real rows back out of
     * {@code import_verification_findings}.
     *
     * <p>Both sections are observed, and both rows are asserted: section 0's row carries the
     * savings section's outcome and section 1's row carries the credit-card section's, so the index
     * column is proved to track the data rather than merely to exist. The two are deliberately
     * different values -- if the index and the payload came apart, the two rows would be
     * indistinguishable and this test would fail.
     */
    @Test
    void recordedSectionIndexAndRecordedFinding_describeTheSameSection() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeCompositeAccountsSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);

        observer.observe(userId, staged.sessionId(), 0, SAVINGS_CLOSING_BALANCE);
        observer.observe(userId, staged.sessionId(), 1, SAVINGS_CLOSING_BALANCE);

        StatementAnalysisSession analysis = analysisRepository
                .findByImportSessionIdOrderByCreatedAtDesc(staged.sessionId()).stream()
                .findFirst().orElseThrow();
        List<ImportVerificationFinding> shadow = findingRepository
                .findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.getId()).stream()
                .filter(f -> ClosingBalanceEvidenceShadowObserver.RULE.equals(f.getRule()))
                .toList();

        assertThat(shadow).hasSize(2);
        ImportVerificationFinding forSavings = shadow.get(0);
        ImportVerificationFinding forCreditCard = shadow.get(1);
        assertThat(forSavings.getSectionIndex()).isZero();
        assertThat(forCreditCard.getSectionIndex()).isEqualTo(1);

        // Row 0 describes the savings ledger -- the section staged index 0 actually is.
        Map<String, Object> savingsDetails = detailsOf(forSavings);
        assertThat(savingsDetails).containsEntry("evidenceAvailable", true);
        assertThat(savingsDetails).containsEntry("statementTotalsOutcome", "VERIFIED");
        assertThat(savingsDetails).containsEntry("financialValidationStatus",
                EvidenceStatus.SUPPORTED.name());

        // Row 1 describes the credit-card section, and demonstrably not the savings one.
        Map<String, Object> creditCardDetails = detailsOf(forCreditCard);
        assertThat(creditCardDetails).containsEntry("evidenceAvailable", true);
        assertThat(creditCardDetails).containsEntry("statementTotalsOutcome", "NOT_APPLICABLE");

        // And the recorded outcome column agrees with a direct re-derivation at the SAME index --
        // the label, the payload and the service all naming one section.
        assertThat(forSavings.getOutcome()).isEqualTo(rederivationService
                .rederiveClosingBalanceEvidenceDetailed(userId, staged.sessionId(), 0,
                        SAVINGS_CLOSING_BALANCE).assessment().status().name());
        assertThat(forCreditCard.getOutcome()).isEqualTo(rederivationService
                .rederiveClosingBalanceEvidenceDetailed(userId, staged.sessionId(), 1,
                        SAVINGS_CLOSING_BALANCE).assessment().status().name());
    }

    /**
     * The single-account path's recorded row, for the same reason -- {@code confirmSession} passes
     * no index, the observer writes 0, and that 0 must name the savings ledger.
     */
    @Test
    void singleAccountPath_recordsTheAbsentIndexAsSectionZeroDescribingThatAccount() throws Exception {
        UUID userId = newUser();
        byte[] bytes = PdfFixtureBuilder.buildDepositSchedulesBeforeSingleAccountSample();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(
                userId, "combined-statement.pdf", bytes, null);
        assertThat(staged.multiAccount()).isFalse();

        observer.observe(userId, staged.sessionId(), null, SAVINGS_CLOSING_BALANCE);

        StatementAnalysisSession analysis = analysisRepository
                .findByImportSessionIdOrderByCreatedAtDesc(staged.sessionId()).stream()
                .findFirst().orElseThrow();
        List<ImportVerificationFinding> shadow = findingRepository
                .findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.getId()).stream()
                .filter(f -> ClosingBalanceEvidenceShadowObserver.RULE.equals(f.getRule()))
                .toList();

        assertThat(shadow).hasSize(1);
        assertThat(shadow.get(0).getSectionIndex()).isZero();
        assertThat(detailsOf(shadow.get(0))).containsEntry("statementTotalsOutcome", "VERIFIED");
        assertThat(detailsOf(shadow.get(0))).containsEntry("financialValidationStatus",
                EvidenceStatus.SUPPORTED.name());
    }

    private Map<String, Object> detailsOf(ImportVerificationFinding finding) throws Exception {
        return objectMapper.readValue(finding.getDetailsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}
