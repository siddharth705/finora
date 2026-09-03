package com.finora.imports.jobs;

import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;

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
public record StagedForJob(UUID sessionId, int totalParsed, int stagedRows, String bankName) {

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
                bankNameOf(staging.detectedAccount()));
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
                    sections.stream().mapToInt(StagedAccountSection::totalParsed).sum(), staged, bank);
        }
        var staging = response.staging();
        if (staging == null) return new StagedForJob(response.sessionId(), 0, 0, null);
        return new StagedForJob(
                response.sessionId(),
                staging.totalParsed(),
                staging.rows() == null ? 0 : staging.rows().size(),
                bankNameOf(staging.detectedAccount()));
    }
}
