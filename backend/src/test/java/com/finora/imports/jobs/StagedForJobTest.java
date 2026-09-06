package com.finora.imports.jobs;

import com.finora.dto.ImportDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalising the three staging envelopes into the one shape the job row needs.
 *
 * <p>These cover the statement periods specifically, which the trust predicate reads to decide
 * whether a document's own metadata is self-consistent. A composite statement's sections each
 * carry their own period and one bad section is enough to hold the whole document, so what
 * matters here is that every section contributes -- not just the first, and not just the ones
 * that happened to produce a period.
 */
class StagedForJobTest {

    private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
    private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
    private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate JUL_31 = LocalDate.of(2026, 7, 31);

    /**
     * Shape copied from {@code ImportJobWorkerTest.staged()}, which copied it from
     * {@code ImportSessionServiceTest.sampleDetected()}. Positions 5 and 6 are the statement
     * period; every other component is deliberately null or a neutral default.
     */
    private static ImportDto.DetectedAccountInfo detected(LocalDate start, LocalDate end) {
        return new ImportDto.DetectedAccountInfo("HDFC Bank", "SAVINGS", null, null, start, end,
                null, null, null, null, null, null, null, null, "SAVINGS", 0.0, false,
                List.of(), null, null, null, null, null, null, null, null);
    }

    private static ImportDto.StagedAccountSection section(LocalDate start, LocalDate end) {
        return new ImportDto.StagedAccountSection(
                detected(start, end), List.of(), 0, 0, List.of());
    }

    private static ImportDto.PdfStagingSessionResponse multiAccount(
            ImportDto.StagedAccountSection... sections) {
        return new ImportDto.PdfStagingSessionResponse(
                UUID.randomUUID(), true, null, List.of(sections));
    }

    /** A composite statement's sections each carry their own period, and the predicate has to see
     *  all of them -- one bad section is enough to hold the document. */
    @Test
    void carriesOnePeriodPerSection() {
        StagedForJob staged = StagedForJob.of(
                multiAccount(section(AUG_1, AUG_31), section(JUL_1, JUL_31)));

        assertThat(staged.statementPeriods()).hasSize(2);
        assertThat(staged.statementPeriods().get(0)).containsExactly(AUG_1, AUG_31);
        assertThat(staged.statementPeriods().get(1)).containsExactly(JUL_1, JUL_31);
    }

    /**
     * A section with no period still contributes an entry.
     *
     * <p>Dropping it would be the dangerous shortcut: the predicate would then see a one-section
     * document where there were two, and a genuinely broken period in the OTHER section would
     * still be read -- but the count would silently stop describing the document. A null period is
     * explicitly not a reason to hold, so carrying it costs nothing and keeps the list honest.
     */
    @Test
    void aSectionWithNoPeriodYieldsNulls() {
        StagedForJob staged = StagedForJob.of(
                multiAccount(section(AUG_1, AUG_31), section(null, null)));

        assertThat(staged.statementPeriods()).hasSize(2);
        assertThat(staged.statementPeriods().get(1)).containsExactly(null, null);
    }

    /** A half-known period is carried as it is, rather than being discarded or completed. */
    @Test
    void aHalfKnownPeriodIsCarriedAsItIs() {
        StagedForJob staged = StagedForJob.of(multiAccount(section(AUG_1, null)));

        assertThat(staged.statementPeriods().get(0)).containsExactly(AUG_1, null);
    }

    /**
     * A section whose account was not detected at all still contributes an entry, for the same
     * counting reason -- and must not throw on the way.
     */
    @Test
    void aSectionWithNoDetectedAccountStillContributesAnEntry() {
        ImportDto.StagedAccountSection headless =
                new ImportDto.StagedAccountSection(null, List.of(), 0, 0, List.of());

        StagedForJob staged = StagedForJob.of(multiAccount(section(AUG_1, AUG_31), headless));

        assertThat(staged.statementPeriods()).hasSize(2);
        assertThat(staged.statementPeriods().get(1)).containsExactly(null, null);
    }

    // ------------------------------------------------------------------ the single-section paths

    @Test
    void theCsvPathCarriesItsOnePeriod() {
        ImportDto.StagingSessionResponse response = new ImportDto.StagingSessionResponse(
                UUID.randomUUID(),
                new ImportDto.StagingResponse(List.of(), 10, 0, detected(AUG_1, AUG_31), List.of()));

        StagedForJob staged = StagedForJob.of(response);

        assertThat(staged.statementPeriods()).hasSize(1);
        assertThat(staged.statementPeriods().getFirst()).containsExactly(AUG_1, AUG_31);
    }

    @Test
    void theSingleSectionPdfPathCarriesItsOnePeriod() {
        ImportDto.PdfStagingSessionResponse response = new ImportDto.PdfStagingSessionResponse(
                UUID.randomUUID(), false,
                new ImportDto.StagingResponse(List.of(), 10, 0, detected(AUG_1, AUG_31), List.of()),
                null);

        StagedForJob staged = StagedForJob.of(response);

        assertThat(staged.statementPeriods()).hasSize(1);
        assertThat(staged.statementPeriods().getFirst()).containsExactly(AUG_1, AUG_31);
    }

    /** The empty envelope -- no staging at all -- has no sections, so it has no periods. Distinct
     *  from a section that reported none, and it must not throw. */
    @Test
    void anEmptyPdfEnvelopeHasNoPeriods() {
        StagedForJob staged = StagedForJob.of(new ImportDto.PdfStagingSessionResponse(
                UUID.randomUUID(), false, null, null));

        assertThat(staged.statementPeriods()).isEmpty();
    }

    @Test
    void aMultiAccountResponseWithNoSectionsHasNoPeriods() {
        StagedForJob staged = StagedForJob.of(new ImportDto.PdfStagingSessionResponse(
                UUID.randomUUID(), true, null, null));

        assertThat(staged.statementPeriods()).isEmpty();
    }

    /**
     * The existing fields keep working, and the period list is independent of the report list.
     *
     * <p>Worth pinning together: {@code verificationReports} filters nulls out, so its length is
     * NOT the section count. Anything that later assumes the two lists are index-aligned would be
     * wrong, and this is where that assumption would first be visible.
     */
    @Test
    void periodsAreNotIndexAlignedWithVerificationReports() {
        StagedForJob staged = StagedForJob.of(
                multiAccount(section(AUG_1, AUG_31), section(JUL_1, JUL_31)));

        assertThat(staged.statementPeriods()).hasSize(2);
        assertThat(staged.verificationReports())
                .as("neither section produced a report, but both produced a period")
                .isEmpty();
    }
}
