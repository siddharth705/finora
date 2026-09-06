package com.finora.dto;

import com.finora.entity.HeldStatement;

import java.time.Instant;
import java.util.UUID;

/**
 * One held statement as an operator sees it.
 *
 * <p>Carries no statement content and no row data -- the Held ID, why it fired, and where it is in
 * the review. That is deliberate and follows {@link HeldImportDto}'s own split: browsing a queue
 * should not put customer financial detail on screen, so listing is cheap and unaudited while
 * anything that opens the document is audited separately.
 *
 * @param userId a bare id, same reason {@link HeldImportDto#userId} is: this controller never joins
 *               to a user's contact details, so no email or phone can reach this screen even
 *               indirectly.
 * @param bankName the snapshot from V150, not a live read -- see {@code HeldStatement}'s own doc for
 *                 why {@code import_sessions}, the only other source, cannot be joined instead. Null
 *                 when the parser could not name a bank.
 * @param triggerSummary every trust condition that fired, rendered by {@code TrustPredicate}. Not a
 *                       new signal -- a sentence about evidence the pipeline already computed.
 */
public record HeldStatementDto(
        UUID id,
        String heldId,
        UUID importJobId,
        UUID userId,
        String bankName,
        String status,
        String triggerSummary,
        String reliabilityStatus,
        String textSource,
        Boolean headerReconstructionUncertain,
        String parserVersion,
        UUID assignedEngineerId,
        String engineerNotes,
        String rootCause,
        String fixReference,
        Boolean falsePositive,
        Instant createdAt,
        Instant assignedAt,
        Instant readyAt,
        Instant resolvedAt) {

    public static HeldStatementDto from(HeldStatement held) {
        return new HeldStatementDto(
                held.getId(),
                held.getHeldId(),
                held.getImportJobId(),
                held.getUserId(),
                held.getBankName(),
                held.getStatus().name(),
                held.getTriggerSummary(),
                held.getReliabilityStatus(),
                held.getTextSource(),
                held.getHeaderReconstructionUncertain(),
                held.getParserVersion(),
                held.getAssignedEngineerId(),
                held.getEngineerNotes(),
                held.getRootCause(),
                held.getFixReference(),
                held.getFalsePositive(),
                held.getCreatedAt(),
                held.getAssignedAt(),
                held.getReadyAt(),
                held.getResolvedAt());
    }
}
