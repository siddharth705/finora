package com.finora.imports.jobs;

import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.dto.ImportDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a worker pass produced, in the one shape the job row needs.
 *
 * <p>The three staging paths return three different envelopes — CSV, single-section PDF and
 * multi-section PDF — and the job row wants the same three numbers from all of them: which session
 * to send the user to, how many rows the document held, and how many of them staged. Normalising
 * here keeps {@link ImportJobWorker#runOne} reading as a lifecycle rather than as a branch over
 * response types.
 *
 * <p>Deliberately in the jobs package, not on {@code ImportService}: the asynchronous path is the
 * only caller that needs the three collapsed into one, and the staging service should not have to
 * know that a queue exists.
 *
 * <p>{@code bankName} is carried for one reason: the completion notification a previously-held
 * import sends names the bank, and this is the only moment the answer is in hand. An
 * {@code ImportJob} never learns it -- the account is chosen at confirm time, after the job is
 * already COMPLETED -- and re-reading it later would mean loading the session and parsing its
 * {@code detected_account_json} back out. Null when the parser could not name one; the caller
 * decides what to say instead, because the fallback belongs next to the copy that needs it.
 */
public record StagedForJob(UUID sessionId, int totalParsed, int stagedRows, String bankName,
                            List<ImportDto.VerificationReport> verificationReports,
                            List<LocalDate[]> statementPeriods) {

    /** One report per account section; a single-section statement yields one, and a path that
     *  produced none yields an empty list rather than null -- absent verification and verification
     *  that found nothing are different facts, and only the caller can tell them apart. */
    private static List<ImportDto.VerificationReport> reportsOf(ImportDto.VerificationReport one) {
        return one == null ? List.of() : List.of(one);
    }

    /**
     * One {@code {start, end}} pair per section, in section order, for the trust predicate to
     * check the document's own metadata against itself.
     *
     * <p>Either element may be null, and a section that reported no period at all still
     * contributes an entry. Dropping those would be the dangerous shortcut: the list would stop
     * describing the document, and a composite statement would silently look like a smaller one.
     * A null period is explicitly never a reason to hold, so carrying it costs nothing.
     *
     * <p>Note this is NOT index-aligned with {@code verificationReports}, which filters nulls out
     * -- these are two independent per-section views, and the predicate reads them separately.
     */
    private static LocalDate[] periodOf(DetectedAccountInfo detected) {
        return detected == null
                ? new LocalDate[]{null, null}
                : new LocalDate[]{detected.statementPeriodStart(), detected.statementPeriodEnd()};
    }

    /** The single-section envelopes carry exactly one section, so exactly one period.
     *
     *  <p>The explicit type argument is required: {@code List.of(array)} spreads the array as
     *  varargs and would yield a two-element {@code List<LocalDate>} instead of one period. */
    private static List<LocalDate[]> periodsOf(DetectedAccountInfo detected) {
        return List.<LocalDate[]>of(periodOf(detected));
    }

    /** {@code DetectedAccountInfo.suggestedName} is documented as the official bank name or a
     *  clean generic fallback, never a raw filename -- so it is safe to put in front of a
     *  customer as-is. */
    private static String bankNameOf(DetectedAccountInfo detected) {
        return detected == null ? null : detected.suggestedName();
    }

    public static StagedForJob of(StagingSessionResponse response) {
        var staging = response.staging();
        return new StagedForJob(
                response.sessionId(),
                staging.totalParsed(),
                staging.rows() == null ? 0 : staging.rows().size(),
                bankNameOf(staging.detectedAccount()),
                reportsOf(staging.verification()),
                periodsOf(staging.detectedAccount()));
    }

    public static StagedForJob of(PdfStagingSessionResponse response) {
        // Summed across sections, matching how ParseDiagnostics already counts a composite
        // statement: the document is the unit a progress figure is about, not one of its sections.
        if (response.multiAccount()) {
            List<StagedAccountSection> sections =
                    response.sections() == null ? List.of() : response.sections();
            int staged = sections.stream().mapToInt(section -> section.rows().size()).sum();
            // The first section's bank, not a joined list: a composite statement's sections are
            // several accounts at ONE bank, so naming it once is accurate, and a notification
            // titled "Your HDFC Bank, HDFC Bank statement is ready" is not.
            String bank = sections.stream()
                    .map(StagedAccountSection::detectedAccount)
                    .map(StagedForJob::bankNameOf)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            return new StagedForJob(response.sessionId(),
                    sections.stream().mapToInt(StagedAccountSection::totalParsed).sum(), staged, bank,
                    sections.stream()
                            .map(StagedAccountSection::verification)
                            .filter(java.util.Objects::nonNull)
                            .toList(),
                    // Every section, unfiltered -- unlike the reports above, a section that
                    // produced no period still has to be counted as a section.
                    sections.stream()
                            .map(StagedAccountSection::detectedAccount)
                            .map(StagedForJob::periodOf)
                            .toList());
        }
        var staging = response.staging();
        // No staging at all is not "a section with no period" -- there is no section. An empty
        // list keeps those two distinguishable.
        if (staging == null) {
            return new StagedForJob(response.sessionId(), 0, 0, null, List.of(), List.of());
        }
        return new StagedForJob(
                response.sessionId(),
                staging.totalParsed(),
                staging.rows() == null ? 0 : staging.rows().size(),
                bankNameOf(staging.detectedAccount()),
                reportsOf(staging.verification()),
                periodsOf(staging.detectedAccount()));
    }
}
