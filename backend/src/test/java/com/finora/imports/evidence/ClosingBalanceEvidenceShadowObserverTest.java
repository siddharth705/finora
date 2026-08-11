package com.finora.imports.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.dto.ImportDto;
import com.finora.imports.analysis.ImportVerificationRecorder;
import com.finora.imports.pdf.TextSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * C-9 shadow mode -- what the observer records, and that nothing it touches can throw.
 *
 * <p>Two properties are proved here that the end-to-end IT cannot isolate: that the five axes reach
 * the recorder as five separate values (not one collapsed verdict), and that every failure mode of
 * both collaborators is contained. {@link ClosingBalanceEvidenceShadowObserverIT} then proves the
 * same path against a real statement, a real database and a real analysis session.
 */
class ClosingBalanceEvidenceShadowObserverTest {

    private ClosingBalanceEvidenceRederivationService rederivationService;
    private ImportVerificationRecorder recorder;
    private ClosingBalanceEvidenceShadowObserver observer;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final BigDecimal claim = new BigDecimal("117209.50");

    @BeforeEach
    void setUp() {
        rederivationService = mock(ClosingBalanceEvidenceRederivationService.class);
        recorder = mock(ImportVerificationRecorder.class);
        observer = new ClosingBalanceEvidenceShadowObserver(rederivationService, recorder, noOpTransactions());
    }

    /** A transaction manager that does nothing: this class is about the observer's own logic, and
     *  the suspension behaviour that matters in production is proved by the IT against a real one. */
    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus(false);
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureDetails(String expectedOutcome) {
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(recorder).recordEvidenceShadow(org.mockito.ArgumentMatchers.eq(sessionId), anyInt(),
                org.mockito.ArgumentMatchers.eq(ClosingBalanceEvidenceShadowObserver.RULE),
                org.mockito.ArgumentMatchers.eq(expectedOutcome), details.capture());
        return details.getValue();
    }

    private ClosingBalanceEvidenceRederivationService.ClosingBalanceEvidence evidenceWith(
            ImportDto.VerificationFinding statementTotals) {
        var fact = new FieldFact<>(MaterialField.CLOSING_BALANCE, claim,
                List.of(new ProvenanceNode.Acquisition(TextSource.NATIVE_PDF),
                        new ProvenanceNode.SectionAttribution(0, TextSource.NATIVE_PDF)));
        var observation = new MetadataObservation<>(fact, 0, null, "Closing Balance");
        var fieldObservation = new MetadataFieldObservation<>(observation,
                com.finora.imports.product.EvidenceSource.ROW_DATA);
        FieldAssessment assessment = MetadataEvidencePipeline.assess(MaterialField.CLOSING_BALANCE,
                List.of(fieldObservation), claim,
                new FinancialValidationContext(null, statementTotals, 0, TextSource.NATIVE_PDF));
        return new ClosingBalanceEvidenceRederivationService.ClosingBalanceEvidence(
                assessment, statementTotals, EvidenceComparison.UNCONTESTED, 1, 0, 0);
    }

    private static ImportDto.VerificationFinding totals(String outcome, Map<String, Object> details) {
        return new ImportDto.VerificationFinding("STATEMENT_TOTALS", outcome, details);
    }

    // --- (a) it runs, and records what it should ---

    @Test
    void aSuccessfulAssessment_recordsEveryAxisSeparately() throws Exception {
        when(rederivationService.rederiveClosingBalanceEvidenceDetailed(userId, sessionId, null, claim))
                .thenReturn(evidenceWith(totals("VERIFIED", Map.of())));

        observer.observe(userId, sessionId, null, claim);

        Map<String, Object> details = captureDetails(EvidenceStatus.INSUFFICIENT.name());
        assertThat(details).containsEntry("evidenceAvailable", true);
        // The validator's own verdict, kept as itself rather than as the dimension it produced.
        assertThat(details).containsEntry("statementTotalsOutcome", "VERIFIED");
        assertThat(details).containsEntry("financialValidationStatus", EvidenceStatus.SUPPORTED.name());
        // The correlation axis and the status axis, side by side and distinguishable. Under
        // today's single-source routing the group is always one fact, so the comparison is
        // UNCONTESTED while the status is INSUFFICIENT -- two different statements about the same
        // observation, which is exactly why they are not one field.
        assertThat(details).containsEntry("evidenceComparison", EvidenceComparison.UNCONTESTED.name());
        assertThat(details).containsEntry("corroborationStatus", EvidenceStatus.INSUFFICIENT.name());
        assertThat(details).containsEntry("sameFactGroupSize", 1);
        assertThat(details).containsEntry("excludedAsUncertainCount", 0);
        assertThat(details).containsEntry("contradictionCount", 0);
        assertThat(details).containsEntry("evidenceStatus", EvidenceStatus.INSUFFICIENT.name());
        assertThat(details).containsKey("elapsedMs");
        assertThat(details).doesNotContainKey("failureType");
    }

    @Test
    void aFailedStatementTotalsCheck_recordsItsSuspectedCause_notJustThatItFailed() throws Exception {
        // The distinction the whole first corpus exists to measure: a FAILED check blamed on the
        // OPENING_BALANCE is evidence FOR the closing balance, one blamed on TRANSACTIONS is not.
        when(rederivationService.rederiveClosingBalanceEvidenceDetailed(userId, sessionId, 2, claim))
                .thenReturn(evidenceWith(totals("FAILED", Map.of("suspectedCause", "OPENING_BALANCE"))));

        observer.observe(userId, sessionId, 2, claim);

        Map<String, Object> details = captureDetails(EvidenceStatus.INSUFFICIENT.name());
        assertThat(details).containsEntry("statementTotalsOutcome", "FAILED");
        assertThat(details).containsEntry("suspectedCause", "OPENING_BALANCE");
        assertThat(details).containsEntry("financialValidationStatus", EvidenceStatus.SUPPORTED.name());
        verify(recorder).recordEvidenceShadow(any(), org.mockito.ArgumentMatchers.eq(2), anyString(),
                anyString(), any());
    }

    // --- (b) it cannot throw, whatever fails ---

    @Test
    void whenReDerivationFails_theFailureIsRecordedAsUnavailable_andNothingPropagates() throws Exception {
        when(rederivationService.rederiveClosingBalanceEvidenceDetailed(any(), any(), any(), any()))
                .thenThrow(new IOException("pdf is not a pdf"));

        assertThatCode(() -> observer.observe(userId, sessionId, null, claim)).doesNotThrowAnyException();

        Map<String, Object> details = captureDetails(ClosingBalanceEvidenceShadowObserver.OUTCOME_UNAVAILABLE);
        assertThat(details).containsEntry("evidenceAvailable", false);
        // The type, never the message -- a message can carry a file name or a fragment of the
        // document, and this table holds neither.
        assertThat(details).containsEntry("failureType", "IOException");
        assertThat(details.values()).noneMatch(v -> String.valueOf(v).contains("pdf is not a pdf"));
        assertThat(details).containsKey("elapsedMs");
    }

    @Test
    void whenReDerivationThrowsAnError_itIsStillContained() throws Exception {
        when(rederivationService.rederiveClosingBalanceEvidenceDetailed(any(), any(), any(), any()))
                .thenThrow(new StackOverflowError("hostile document"));

        assertThatCode(() -> observer.observe(userId, sessionId, null, claim)).doesNotThrowAnyException();

        assertThat(captureDetails(ClosingBalanceEvidenceShadowObserver.OUTCOME_UNAVAILABLE))
                .containsEntry("failureType", "StackOverflowError");
    }

    @Test
    void whenTheRecorderItselfThrows_nothingPropagates() throws Exception {
        when(rederivationService.rederiveClosingBalanceEvidenceDetailed(any(), any(), any(), any()))
                .thenReturn(evidenceWith(totals("VERIFIED", Map.of())));
        doThrow(new IllegalStateException("telemetry table is unreachable"))
                .when(recorder).recordEvidenceShadow(any(), anyInt(), anyString(), anyString(), any());

        assertThatCode(() -> observer.observe(userId, sessionId, null, claim)).doesNotThrowAnyException();
    }

    @Test
    void withNothingToAssess_nothingIsComputedAndNothingIsRecorded() {
        observer.observe(userId, sessionId, null, null);
        observer.observe(userId, null, null, claim);
        observer.observe(null, sessionId, null, claim);

        verifyNoInteractions(rederivationService);
        verifyNoInteractions(recorder);
    }
}
