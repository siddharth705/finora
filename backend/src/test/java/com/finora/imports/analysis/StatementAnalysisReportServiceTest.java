package com.finora.imports.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.repository.RegisteredLayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatementAnalysisReportServiceTest {

    private StatementAnalysisSessionRepository repository;
    private RegisteredLayoutRepository registeredLayoutRepository;
    private StatementAnalysisReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(StatementAnalysisSessionRepository.class);
        registeredLayoutRepository = mock(RegisteredLayoutRepository.class);
        service = new StatementAnalysisReportService(repository, registeredLayoutRepository, new ObjectMapper());
        when(repository.count()).thenReturn(0L);
        when(repository.countByOutcome(any())).thenReturn(0L);
        when(repository.countDistinctLayouts()).thenReturn(0L);
    }

    private static StatementAnalysisSession parsed(String reference, String fingerprint, int rows,
                                                    String histogramJson) {
        return StatementAnalysisSession.parsed(reference, UUID.randomUUID(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "statement.pdf", "PDF", 1024L,
                fingerprint, 1, 250L, rows, histogramJson);
    }

    @Test
    void theHistogramIsExposedAsCountsRatherThanRawJson() {
        // The view is what a dashboard renders. Handing back the stored string would push JSON
        // parsing into every client and make the storage format part of the API contract.
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(
                parsed("SA-1", "FP-A", 2, "{\"NO_DATE_IN_ANCHOR_COLUMN\":97,\"UNANCHORED_ROWS_ABANDONED\":3}")));

        var view = service.recent(10).get(0);

        assertThat(view.unanchoredReasons())
                .containsExactly(Map.entry("NO_DATE_IN_ANCHOR_COLUMN", 97), Map.entry("UNANCHORED_ROWS_ABANDONED", 3));
        assertThat(view.unanchoredRowCount()).isEqualTo(100);
        assertThat(view.rowCount()).isEqualTo(2);
    }

    @Test
    void aRowWrittenBeforeDiagnosticsExistedStillReportsEverythingElse() {
        // Every row written between V59 and V60 has a null histogram. Those rows are still the only
        // record that those uploads happened, and refusing to show them would delete history to
        // avoid showing a blank field.
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(parsed("SA-OLD", "FP-LEGACY", 140, null)));

        var view = service.recent(10).get(0);

        assertThat(view.unanchoredReasons()).isEmpty();
        assertThat(view.layoutFingerprint()).isEqualTo("FP-LEGACY");
        assertThat(view.rowCount()).isEqualTo(140);
    }

    @Test
    void unreadableDiagnosticsDegradeOneFieldRatherThanTheWholeReport() {
        // The observation under test is that the OTHER fields survive. Asserting only that the
        // reasons are empty would pass identically against a service that threw and returned
        // nothing at all -- true for two different reasons, and only one is the property here.
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(parsed("SA-BAD", "FP-B", 5, "{not json at all")));

        var views = service.recent(10);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).unanchoredReasons()).isEmpty();
        assertThat(views.get(0).reference()).isEqualTo("SA-BAD");
        assertThat(views.get(0).rowCount()).isEqualTo(5);
    }

    @Test
    void theSummaryAddsReasonsAcrossDocumentsAndOrdersThemByTotal() {
        // The question the summary exists to answer: one reason dominating ACROSS documents is a
        // missing capability, the same reason confined to one document is that document. Summing
        // per reason is what makes those two distinguishable.
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(
                parsed("SA-1", "FP-A", 569, "{\"NO_DATE_IN_ANCHOR_COLUMN\":600,\"AMOUNT_UNPARSEABLE\":49}"),
                parsed("SA-2", "FP-B", 140, "{\"NO_DATE_IN_ANCHOR_COLUMN\":40}"),
                parsed("SA-3", "FP-C", 113, null)));

        var summary = service.summary();

        assertThat(summary.unanchoredReasons())
                .containsExactly(Map.entry("NO_DATE_IN_ANCHOR_COLUMN", 640), Map.entry("AMOUNT_UNPARSEABLE", 49));
        assertThat(summary.rowsExtractedInWindow()).isEqualTo(822);
        assertThat(summary.unanchoredRowsInWindow()).isEqualTo(689);
        assertThat(summary.analysesInWindow()).isEqualTo(3);
    }

    @Test
    void aNeverMeasuredRowCountDoesNotCountAsZeroRowsExtracted() {
        // Null and 0 both add nothing to the total, so the sum cannot tell them apart -- but the
        // per-row view must, because "extracted nothing" and "never opened" lead to different
        // investigations.
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of(
                StatementAnalysisSession.failed("SA-LOCKED", UUID.randomUUID(),
                        StatementAnalysisSession.Source.CUSTOMER_IMPORT, "locked.pdf", "PDF", 1L,
                        null, "IMPORT_008", "wrong password", 10L, null, null),
                parsed("SA-EMPTY", "FP-E", 0, null)));

        var views = service.recent(10);

        assertThat(views.get(0).rowCount()).isNull();
        assertThat(views.get(0).outcome()).isEqualTo("FAILED");
        assertThat(views.get(1).rowCount()).isZero();
        assertThat(service.summary().rowsExtractedInWindow()).isZero();
    }

    @Test
    void openingAnAnalysisShowsHowOftenItsLayoutHasAlreadyFailed() {
        // The point of the detail view: "seen 12 times, failed 11" and "seen once" are the same
        // document to a parser and completely different situations to a person deciding whether to
        // spend a day on it.
        when(repository.findByReference("SA-1")).thenReturn(java.util.Optional.of(parsed("SA-1", "FP-A", 2, null)));
        when(repository.countByLayoutFingerprint("FP-A")).thenReturn(12L);
        when(repository.countByLayoutFingerprintAndOutcome("FP-A", StatementAnalysisSession.Outcome.FAILED))
                .thenReturn(11L);

        var detail = service.detailByReference("SA-1").orElseThrow();

        assertThat(detail.timesLayoutSeen()).isEqualTo(12);
        assertThat(detail.timesLayoutFailed()).isEqualTo(11);
        assertThat(detail.analysis().reference()).isEqualTo("SA-1");
    }

    @Test
    void anUncharacterisedDocumentReportsNoLayoutHistoryRatherThanSeenOnce() {
        // A wrong PDF password never produces a fingerprint. Counting that as "this layout has been
        // seen once" would invent a layout that was never identified, and it would accumulate:
        // every locked file would look like another sighting of the same nonexistent thing.
        when(repository.findByReference("SA-LOCKED")).thenReturn(java.util.Optional.of(
                StatementAnalysisSession.failed("SA-LOCKED", UUID.randomUUID(),
                        StatementAnalysisSession.Source.CUSTOMER_IMPORT, "locked.pdf", "PDF", 1L,
                        null, "IMPORT_008", "wrong password", 10L, null, null)));

        var detail = service.detailByReference("SA-LOCKED").orElseThrow();

        assertThat(detail.timesLayoutSeen()).isZero();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).countByLayoutFingerprint(any());
    }

    @Test
    void anUnknownReferenceIsAbsentRatherThanAnEmptyRow() {
        when(repository.findByReference("SA-NOPE")).thenReturn(java.util.Optional.empty());
        assertThat(service.byReference("SA-NOPE")).isEmpty();
    }

    @Test
    void theRequestedLimitIsBoundedSoOneCallCannotPullTheWholeTable() {
        when(repository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(List.of());

        service.recent(100_000);

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findAllByOrderByCreatedAtDesc(pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
    }
}
