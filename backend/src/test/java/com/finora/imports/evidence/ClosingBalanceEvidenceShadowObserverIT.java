package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C-9 shadow mode, end to end: a real statement, a real staged session, the real re-derivation, the
 * real allowlist and a real row in {@code import_verification_findings}.
 *
 * <p>The three things only a real context can prove are here rather than in the unit test:
 *
 * <ol>
 *   <li><b>The owner key resolves.</b> {@code import_verification_findings} is owned by a
 *       {@link StatementAnalysisSession}, and confirm holds only an {@code ImportSession} id. The
 *       link between them ({@code statement_analysis_sessions.import_session_id}, written on every
 *       staging path) is what closes that gap, and it is exercised here against the real schema and
 *       its one-owner CHECK constraint rather than asserted on paper.</li>
 *   <li><b>An observation inside a caller's transaction cannot poison it.</b> See
 *       {@link #anObservationThatFails_doesNotPoisonTheCallersTransaction} -- the failure mode that
 *       makes the difference between shadow mode and an outage.</li>
 *   <li><b>What the real corpus will actually look like.</b> The recorded row for the golden
 *       fixture is asserted value by value, so the PM's first question -- what distribution should
 *       I expect -- has an answer in the repository rather than in a prediction.</li>
 * </ol>
 */
class ClosingBalanceEvidenceShadowObserverIT extends AbstractIntegrationTest {

    @Autowired private ClosingBalanceEvidenceShadowObserver observer;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private StatementAnalysisRecorder analysisRecorder;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.statement-storage.provider", () -> "filesystem");
        registry.add("app.statement-storage.filesystem.root",
                () -> System.getProperty("java.io.tmpdir") + "/finora-c9-shadow-it");
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
        user.setEmail("c9-shadow-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("C9 Shadow IT User");
        user.setPhoneVerified(true);
        UUID id = userRepository.save(user).getId();
        createdUserIds.add(id);
        return id;
    }

    private static DetectedAccountInfo emptyDetectedAccount() {
        return new DetectedAccountInfo(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, 0.0, true, List.of(), null, null, null, null, null, null, null, null);
    }

    private ImportSession stagedSession(UUID userId) throws Exception {
        byte[] bytes = Files.readAllBytes(
                Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));
        return importSessionService.createSession(userId, "statement.pdf", bytes, List.of(),
                emptyDetectedAccount());
    }

    /** The staging-time analysis row, created exactly the way {@code ImportService.recordPdfParsed}
     *  creates it -- naming the import session, which is the whole of the owner-key link. */
    private StatementAnalysisSession analysisFor(UUID userId, ImportSession session) {
        String reference = analysisRecorder.recordParsed(userId,
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 1024,
                "FP-C9-SHADOW", 1, 12L, ParseDiagnostics.NONE, session.getId());
        return analysisRepository.findByReference(reference).orElseThrow();
    }

    private Map<String, Object> detailsOf(ImportVerificationFinding finding) throws Exception {
        return objectMapper.readValue(finding.getDetailsJson(), new
                com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    // --- (d) the real CLOSING_BALANCE path, against a real statement ---

    @Test
    void aRealStatement_isObservedAndRecordedAgainstItsAnalysisSession() throws Exception {
        UUID userId = newUser();
        ImportSession session = stagedSession(userId);
        StatementAnalysisSession analysis = analysisFor(userId, session);

        observer.observe(userId, session.getId(), null, new BigDecimal("117209.50"));

        List<ImportVerificationFinding> findings = findingRepository
                .findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.getId());
        assertThat(findings).hasSize(1);
        ImportVerificationFinding finding = findings.get(0);
        assertThat(finding.getRule()).isEqualTo("CLOSING_BALANCE_EVIDENCE_SHADOW");
        // Distinguishable from the live gate's own rule name by construction -- shadow observation
        // and enforcement must never be confused in this table.
        assertThat(finding.getRule()).isNotEqualTo(com.finora.imports.ClosingBalanceGuard.RULE);
        assertThat(finding.getSectionIndex()).isZero();
        assertThat(finding.getImportJobId()).as("one owner, and it is the analysis session").isNull();

        Map<String, Object> details = detailsOf(finding);
        assertThat(details).containsEntry("evidenceAvailable", true);
        // The measurement the PM actually asked for: the validator's own verdict on this document.
        assertThat(details).containsEntry("statementTotalsOutcome", "VERIFIED");
        assertThat(details).containsEntry("financialValidationStatus", "SUPPORTED");
        assertThat(details).containsEntry("structuralStatus", "SUPPORTED");
        // ...and the two axes kept apart. Corroboration is INSUFFICIENT while the comparison is
        // UNCONTESTED: one acquisition source means there is nothing to corroborate against, which
        // is a different statement from "the sources disagreed" and must stay readable as such.
        assertThat(details).containsEntry("corroborationStatus", "INSUFFICIENT");
        assertThat(details).containsEntry("evidenceComparison", "UNCONTESTED");
        assertThat(details).containsEntry("sameFactGroupSize", 1);
        assertThat(details).containsEntry("excludedAsUncertainCount", 0);
        assertThat(details).containsEntry("contradictionCount", 0);
        // The whole-assessment verdict, recorded as its own value and equal to the outcome column.
        assertThat(details).containsEntry("evidenceStatus", "INSUFFICIENT");
        assertThat(finding.getOutcome()).isEqualTo("INSUFFICIENT");
        assertThat(details).containsKey("elapsedMs");
        // No part of the statement reaches this table.
        assertThat(finding.getDetailsJson()).doesNotContain("117209");
    }

    @Test
    void aSessionThatCannotBeReDerived_recordsThatFactRatherThanNothing() {
        UUID userId = newUser();
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName("statement.pdf");
        session.setFileContent("this is not a pdf".getBytes());
        session.setExpiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(48)));
        session = importSessionRepository.save(session);
        StatementAnalysisSession analysis = analysisFor(userId, session);

        observer.observe(userId, session.getId(), null, new BigDecimal("100.00"));

        List<ImportVerificationFinding> findings = findingRepository
                .findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.getId());
        assertThat(findings).hasSize(1);
        // "No evidence" and "insufficient evidence" are different findings; the outcome column
        // keeps them apart rather than letting an unreadable document look like a weak one.
        assertThat(findings.get(0).getOutcome()).isEqualTo("UNAVAILABLE");
        assertThat(findings.get(0).getDetailsJson()).contains("\"evidenceAvailable\":false");
        assertThat(findings.get(0).getDetailsJson()).contains("failureType");
    }

    @Test
    void anImportSessionWithNoAnalysisRow_recordsNothingAndFailsNothing() throws Exception {
        UUID userId = newUser();
        ImportSession session = stagedSession(userId);
        // No analysis row for this session at all -- the shape a staging path whose telemetry write
        // failed leaves behind. A finding with no owner is not worth inventing one for.
        long before = findingRepository.count();

        assertThatCode(() -> observer.observe(userId, session.getId(), null, new BigDecimal("117209.50")))
                .doesNotThrowAnyException();

        assertThat(findingRepository.count()).isEqualTo(before);
    }

    // --- (b) the failure that would otherwise be invisible until production ---

    @Test
    void anObservationThatFails_doesNotPoisonTheCallersTransaction() throws Exception {
        UUID userId = newUser();
        ImportSession session = stagedSession(userId);
        // Already CONFIRMED, so the re-derivation's own getOwnedSession rejects it with an
        // ApiException -- the single most likely failure at this seam.
        session.setStatus(ImportSession.STATUS_CONFIRMED);
        importSessionRepository.save(session);

        java.util.concurrent.atomic.AtomicReference<UUID> markerId = new java.util.concurrent.atomic.AtomicReference<>();
        TransactionTemplate caller = new TransactionTemplate(transactionManager);

        // The point of the test. getOwnedSession is @Transactional(readOnly = true), so called from
        // inside this transaction it PARTICIPATES in it -- and an exception escaping a participating
        // transaction marks the shared one rollback-only, which surfaces as an
        // UnexpectedRollbackException at commit and would have failed a customer's confirm that had
        // already done all of its work. The observer suspends this transaction for exactly that
        // reason; without the suspension, this commit throws.
        assertThatCode(() -> caller.executeWithoutResult(status -> {
            observer.observe(userId, session.getId(), null, new BigDecimal("117209.50"));
            ImportSession written = new ImportSession();
            written.setUserId(userId);
            written.setFileName("written-after-the-failed-observation.csv");
            written.setFileContent(new byte[]{1});
            written.setExpiresAt(java.time.Instant.now().plus(java.time.Duration.ofHours(1)));
            markerId.set(importSessionRepository.save(written).getId());
        })).doesNotThrowAnyException();

        // Committed, not rolled back -- the caller's work survived the failed observation.
        assertThat(importSessionRepository.findById(markerId.get())).isPresent();
    }
}
