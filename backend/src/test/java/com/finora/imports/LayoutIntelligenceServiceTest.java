package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.RegisteredLayout;
import com.finora.entity.StatementImport;
import com.finora.imports.LayoutIntelligenceService.EvidenceReport;
import com.finora.imports.LayoutIntelligenceService.LayoutSummary;
import com.finora.imports.LayoutIntelligenceService.UnknownHeaderSummary;
import com.finora.repository.StatementImportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reporting over the layout data the pipeline already writes. Nothing here exercises parsing --
 * this service never runs during an import.
 */
class LayoutIntelligenceServiceTest {

    private final StatementImportRepository repository = mock(StatementImportRepository.class);
    // Mockito answers an empty list by default, which is the "registry has never been written to"
    // case -- every pre-registry assertion below still describes what the aggregate alone reports.
    private final LayoutRegistryService registry = mock(LayoutRegistryService.class);
    private final LayoutIntelligenceService service =
            new LayoutIntelligenceService(repository, registry, new ObjectMapper());

    private static final Instant JAN = Instant.parse("2026-01-15T10:00:00Z");

    private StatementImport imported(String fingerprint, Instant at, List<String> capabilities,
                                      List<String> unknownHeaders, Long durationMs) {
        StatementImport si = new StatementImport();
        ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
        si.setUserId(UUID.randomUUID());
        si.setLayoutFingerprint(fingerprint);
        si.setImportedAt(at);
        si.setImportDurationMs(durationMs);
        si.setActivatedCapabilitiesJson(
                capabilities.stream().map(c -> '"' + c + '"').reduce((a, b) -> a + "," + b)
                        .map(joined -> "[" + joined + "]").orElse("[]"));
        String headers = unknownHeaders.stream().map(h -> '"' + h + '"').reduce((a, b) -> a + "," + b)
                .map(joined -> "[" + joined + "]").orElse("[]");
        si.setLayoutMetadataJson("{\"sourceFormat\":\"PDF\",\"parser\":\"PdfPreviewGenerator\","
                + "\"pages\":1,\"tables\":1,\"columns\":5,\"headers\":[],\"unknownHeaders\":" + headers + "}");
        return si;
    }

    @Test
    void groupsImportsByFingerprintAndSeparatesStableFromUnstableCapabilities() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE", "DR_CR_SUFFIX"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of("RUNNING_BALANCE"), List.of(), 380L)));

        LayoutSummary layout = service.layoutOverview().get(0);

        // Fired on both imports vs only one: the split is the whole point, because an unstable
        // capability is either a genuinely varying document or a layout starting to drift.
        assertThat(layout.stableCapabilities()).containsExactly("RUNNING_BALANCE");
        assertThat(layout.unstableCapabilities()).containsExactly("DR_CR_SUFFIX");
        assertThat(layout.usageCount()).isEqualTo(2);
        assertThat(layout.isRecurring()).isTrue();
    }

    @Test
    void aSingleObservationIsNotReportedAsRecurring() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-ONCE", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L)));

        assertThat(service.layoutOverview().get(0).isRecurring()).isFalse();
    }

    @Test
    void ranksUnknownHeadersSpanningSeveralLayoutsAboveOnesConfinedToOne() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of("Tran Particular", "Chq Ref"), 400L),
                imported("FP-1-BBBB", JAN, List.of(), List.of("Tran Particular"), 400L),
                imported("FP-1-CCCC", JAN, List.of(), List.of("Chq Ref"), 400L),
                imported("FP-1-CCCC", JAN, List.of(), List.of("Chq Ref"), 400L),
                imported("FP-1-CCCC", JAN, List.of(), List.of("Chq Ref"), 400L)));

        List<UnknownHeaderSummary> headers = service.unknownHeaders();

        // "Chq Ref" appears in more IMPORTS (4 vs 2), but "Tran Particular" and "Chq Ref" both span
        // 2 layouts. Breadth across layouts is what identifies a hint-list gap rather than one
        // export's quirk, so it sorts first and import count only breaks ties.
        assertThat(headers).extracting(UnknownHeaderSummary::header)
                .containsExactly("Chq Ref", "Tran Particular");
        assertThat(headers.get(0).layoutCount()).isEqualTo(2);
        assertThat(headers.get(0).importCount()).isEqualTo(4);
        assertThat(headers.get(1).layoutCount()).isEqualTo(2);
    }

    @Test
    void timelineFlagsOnlyThePointsWhereStructureChanged() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(60, ChronoUnit.DAYS), List.of(), List.of("New Column"), 400L)));

        var timeline = service.timeline("FP-1-AAAA");

        // The first point can never be "changed" -- there is nothing before it to differ from.
        assertThat(timeline).extracting(p -> p.changedFromPrevious())
                .containsExactly(false, false, true);
    }

    @Test
    void driftIsNotReportedUntilThereIsAnEstablishedPatternToDivergeFrom() {
        // Two imports where the second differs: a change, but not yet evidence of anything. Calling
        // this a regression would train people to ignore the signal.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of(), List.of("New"), 400L)));

        assertThat(service.driftingLayouts()).isEmpty();
    }

    @Test
    void driftIsReportedOnceAStableLayoutSuddenlyChanges() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(60, ChronoUnit.DAYS), List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(90, ChronoUnit.DAYS), List.of(), List.of("Suddenly Unknown"), 400L)));

        assertThat(service.driftingLayouts()).extracting(LayoutSummary::fingerprint)
                .containsExactly("FP-1-AAAA");
    }

    @Test
    void evidenceReportSaysPlainlyWhenNoLayoutHasEverRecurred() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of(), 400L),
                imported("FP-1-BBBB", JAN, List.of(), List.of(), 400L)));

        EvidenceReport report = service.evidenceReport();

        assertThat(report.recurringLayouts()).isZero();
        // The whole point of the report: it has to be able to close the question, not just present
        // numbers that invite a hopeful reading.
        assertThat(report.verdict()).contains("do not build it on this evidence");
    }

    @Test
    void evidenceReportCallsOutNearIdenticalDurationsRatherThanImplyingAWin() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of(), 420L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of(), List.of(), 415L)));

        assertThat(service.evidenceReport().verdict()).contains("No performance case for layout reuse");
    }

    @Test
    void unmeasuredDurationsAreOmittedRatherThanCountedAsZero() {
        // Every row predating V53 has a null duration. Treating those as 0ms would manufacture a
        // spectacular and entirely fake speedup in exactly the report meant to decide the question.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of(), null),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of(), List.of(), null)));

        EvidenceReport report = service.evidenceReport();

        assertThat(report.medianDurationFirstEncounter()).isNull();
        assertThat(report.medianDurationRecurring()).isNull();
        assertThat(report.verdict()).contains("too few");
    }

    @Test
    void aMalformedRowDoesNotBlankTheWholeReport() {
        StatementImport broken = imported("FP-1-AAAA", JAN, List.of(), List.of(), 400L);
        broken.setLayoutMetadataJson("{not json");
        broken.setActivatedCapabilitiesJson("also not json");
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                broken, imported("FP-1-BBBB", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L)));

        // Telemetry, not a ledger: one unreadable row is skipped, the rest still reports.
        assertThat(service.layoutOverview()).hasSize(2);
        assertThat(service.layoutOverview()).extracting(LayoutSummary::fingerprint)
                .contains("FP-1-BBBB");
    }

    // ------------------------------------------------------------------ the registry overlay

    @Test
    void aLayoutWithNoRegistryRowIsStillReported() {
        // V68 backfilled every fingerprint in history and every confirmed import registers its own,
        // so this should not happen -- which is exactly why it must not be silently dropped. A
        // fingerprint that reports UNREGISTERED means an observation went missing, and the only way
        // anyone finds that out is by seeing the row.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L)));

        LayoutSummary layout = service.layoutOverview().get(0);

        assertThat(layout.status()).isEqualTo(LayoutIntelligenceService.UNREGISTERED);
        assertThat(layout.name()).isNull();
        assertThat(layout.usageCount()).isEqualTo(1);
    }

    @Test
    void theRegistryDecidesAlayoutsIdentityAndTheImportsDecideItsUsage() {
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of("RUNNING_BALANCE"), List.of(), 400L),
                imported("FP-1-AAAA", JAN.plus(30, ChronoUnit.DAYS), List.of("RUNNING_BALANCE"), List.of(), 400L)));
        when(registry.all()).thenReturn(List.of(
                registered("FP-1-AAAA", "HDFC Savings (PDF)", RegisteredLayout.Status.SUPPORTED,
                        "PDF", "PdfPreviewGenerator", JAN.minus(365, ChronoUnit.DAYS), JAN.plus(30, ChronoUnit.DAYS))));

        LayoutSummary layout = service.layoutOverview().get(0);

        assertThat(layout.name()).isEqualTo("HDFC Savings (PDF)");
        assertThat(layout.status()).isEqualTo("SUPPORTED");
        assertThat(layout.parser()).isEqualTo("PdfPreviewGenerator");
        // Usage, capabilities and headers are still the aggregate's answer -- the registry knows
        // what the layout IS, not what has happened to it.
        assertThat(layout.usageCount()).isEqualTo(2);
        assertThat(layout.stableCapabilities()).containsExactly("RUNNING_BALANCE");
    }

    @Test
    void firstSeenComesFromTheRegistryRatherThanFromSurvivingImports() {
        // The reason first_seen is a column and not a MIN(). Statement imports are soft-deleted and
        // hidden from every query here, so an aggregate first-seen creeps forward every time a user
        // tidies their uploads -- a layout Finora has handled for a year would report as new.
        Instant reallyFirstSeen = JAN.minus(400, ChronoUnit.DAYS);
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of(), 400L)));
        when(registry.all()).thenReturn(List.of(
                registered("FP-1-AAAA", null, RegisteredLayout.Status.OBSERVED, "PDF", null,
                        reallyFirstSeen, JAN)));

        assertThat(service.layoutOverview().get(0).firstSeen()).isEqualTo(reallyFirstSeen);
    }

    @Test
    void aRegisteredLayoutWhoseStatementsWereAllDeletedIsStillListed() {
        // The history loss the registry exists to stop. Every import of this layout is gone; the
        // fact that Finora encountered it is not.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of());
        when(registry.all()).thenReturn(List.of(
                registered("FP-1-GONE", "PNB Current", RegisteredLayout.Status.SUPPORTED, "CSV",
                        "CsvParser", JAN, JAN.plus(10, ChronoUnit.DAYS))));

        LayoutSummary layout = service.layoutOverview().get(0);

        assertThat(layout.fingerprint()).isEqualTo("FP-1-GONE");
        assertThat(layout.name()).isEqualTo("PNB Current");
        // Zero rather than absent: "we support this and nothing exercises it any more" is the
        // report, and it is a different statement from the layout not existing.
        assertThat(layout.usageCount()).isZero();
        assertThat(layout.isRecurring()).isFalse();
        assertThat(layout.medianDurationMs()).isNull();
    }

    @Test
    void aRegistryRowMissingItsSourceFormatDoesNotBlankTheAggregatesAnswer() {
        // V68 backfills source_format but leaves parser null, and an older row may have neither.
        // Overlaying a null over a value the aggregate does know would make the merge lose
        // information rather than add it.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of(
                imported("FP-1-AAAA", JAN, List.of(), List.of(), 400L)));
        when(registry.all()).thenReturn(List.of(
                registered("FP-1-AAAA", null, RegisteredLayout.Status.OBSERVED, null, null, JAN, JAN)));

        assertThat(service.layoutOverview().get(0).sourceFormat()).isEqualTo("PDF");
    }

    @Test
    void aLayoutWithNoSurvivingImportsNeverCountsAsDrifting() {
        // driftingLayouts() reads the overview, which now contains rows with no timeline at all.
        // Reporting one of those as drift would be a signal derived from nothing.
        when(repository.findAllWithLayoutFingerprint()).thenReturn(List.of());
        when(registry.all()).thenReturn(List.of(
                registered("FP-1-GONE", "PNB Current", RegisteredLayout.Status.SUPPORTED, "CSV",
                        "CsvParser", JAN, JAN)));

        assertThat(service.driftingLayouts()).isEmpty();
    }

    /**
     * A registry row as this service would read one.
     *
     * <p>Built reflectively because nothing constructs a {@code RegisteredLayout} in Java --
     * production rows come from an {@code ON CONFLICT} upsert, and giving the entity a public
     * constructor purely so a test could call it would advertise a second way to create one. The
     * upsert's own behaviour is covered against a real database in {@code LayoutRegistryIT}.
     */
    private RegisteredLayout registered(String fingerprint, String name, RegisteredLayout.Status status,
                                         String sourceFormat, String parser, Instant firstSeen, Instant lastSeen) {
        RegisteredLayout layout = BeanUtils.instantiateClass(RegisteredLayout.class);
        ReflectionTestUtils.setField(layout, "fingerprint", fingerprint);
        ReflectionTestUtils.setField(layout, "sourceFormat", sourceFormat);
        ReflectionTestUtils.setField(layout, "parser", parser);
        ReflectionTestUtils.setField(layout, "firstSeen", firstSeen);
        ReflectionTestUtils.setField(layout, "lastSeen", lastSeen);
        layout.rename(name);
        layout.moveTo(status);
        return layout;
    }

    /**
     * The privacy property this whole feature rests on, enforced instead of trusted.
     *
     * Platform-wide aggregation is only defensible because the results carry no user-identifying
     * data. That is easy to break by adding "just a userId for debugging" to a record, and a
     * reviewer would have to notice. This fails the build instead.
     */
    @Test
    void noResultTypeCanEverCarryUserIdentifyingData() {
        List<Class<?>> resultTypes = List.of(LayoutSummary.class, UnknownHeaderSummary.class,
                LayoutIntelligenceService.LayoutTimelinePoint.class, EvidenceReport.class);
        List<String> forbidden = List.of("user", "account", "transactionid", "bank", "filename",
                "email", "balance", "amount");

        for (Class<?> type : resultTypes) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                assertThat(forbidden.stream().noneMatch(name::contains))
                        .as("%s.%s looks like user-identifying data on an anonymised report",
                                type.getSimpleName(), component.getName())
                        .isTrue();
                assertThat(component.getType())
                        .as("%s.%s is a UUID -- these records are keyed by layout fingerprint, "
                                + "and a UUID here is almost certainly an entity id",
                                type.getSimpleName(), component.getName())
                        .isNotEqualTo(UUID.class);
            }
        }
    }
}
