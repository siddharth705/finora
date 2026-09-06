package com.finora.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link HeldStatementDto} plus the two things an operator actually needs to decide: the evidence
 * behind {@code triggerSummary} and the history of what happened to this hold since.
 *
 * <p>Still carries no statement content and no object key -- exactly the same split
 * {@code HeldStatementDto} already draws. This view explains a decision; it does not display the
 * document. Opening the document is the download endpoint, gated and audited separately.
 *
 * @param fileName the original upload's filename (with its real extension -- "hdfc-june.pdf" or
 *                 "sbi-statement.csv"), so the client's download button can save the file under
 *                 its real name instead of guessing one. Found in review: the download endpoint's
 *                 own {@code Content-Disposition} header has always carried this correctly, but
 *                 nothing on the client read it -- this codebase's established convention is that
 *                 a download's filename comes from the caller, not from parsing that header (see
 *                 {@code statementImportsApi.downloadFile}/{@code accountLifecycleApi.exportData}
 *                 on the frontend), so the caller needs a place to get it from. Read live from
 *                 {@code ImportJob}, not snapshotted onto {@code HeldStatement} the way {@code
 *                 bankName} is -- unlike bankName, {@code fileName} is a real, persistent column on
 *                 {@code ImportJob} the download endpoint already depends on being live at download
 *                 time to retrieve the bytes at all, so reading it here adds no new fragility. Null
 *                 in the one case {@code requireJob}'s own doc names: the job was deleted out from
 *                 under an open review (e.g. the owning account was purged mid-hold).
 * @param findings the same structural evidence {@code ImportTraceDto.Finding} shows an engineer
 *                 diagnosing a parser failure, reused here rather than re-derived, because the
 *                 operator's whole job on this screen is to judge whether that evidence justified
 *                 the hold -- a sentence about it is not enough to judge with.
 * @param timeline every event this hold has recorded, oldest first, read as a narrative.
 */
public record HeldStatementDetailDto(
        HeldStatementDto summary,
        String fileName,
        List<FindingView> findings,
        List<EventView> timeline) {

    /** One verification rule's outcome for one section -- the printed-versus-parsed numbers
     *  behind the trigger, not a sentence summarising them. */
    public record FindingView(int sectionIndex, String rule, String outcome,
                              Map<String, Object> details, Instant createdAt) {}

    /** One entry in the hold's audit history. {@code actorId} null means the system acted. */
    public record EventView(String eventType, String fromStatus, String toStatus, String notes,
                            UUID actorId, Instant createdAt) {}
}
