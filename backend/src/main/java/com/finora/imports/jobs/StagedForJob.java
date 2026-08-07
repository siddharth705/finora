package com.finora.imports.jobs;

import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.dto.ImportDto.StagingSessionResponse;
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
 */
public record StagedForJob(UUID sessionId, int totalParsed, int stagedRows) {

    public static StagedForJob of(StagingSessionResponse response) {
        var staging = response.staging();
        return new StagedForJob(
                response.sessionId(),
                staging.totalParsed(),
                staging.rows() == null ? 0 : staging.rows().size());
    }

    public static StagedForJob of(PdfStagingSessionResponse response) {
        // Summed across sections, matching how ParseDiagnostics already counts a composite
        // statement: the document is the unit a progress figure is about, not one of its sections.
        if (response.multiAccount()) {
            List<StagedAccountSection> sections =
                    response.sections() == null ? List.of() : response.sections();
            int staged = sections.stream().mapToInt(section -> section.rows().size()).sum();
            return new StagedForJob(response.sessionId(),
                    sections.stream().mapToInt(StagedAccountSection::totalParsed).sum(), staged);
        }
        var staging = response.staging();
        if (staging == null) return new StagedForJob(response.sessionId(), 0, 0);
        return new StagedForJob(
                response.sessionId(),
                staging.totalParsed(),
                staging.rows() == null ? 0 : staging.rows().size());
    }
}
