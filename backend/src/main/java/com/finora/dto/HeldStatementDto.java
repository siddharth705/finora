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
 * @param triggerSummary every trust condition that fired, rendered by {@code TrustPredicate}. Not a
 *                       new signal -- a sentence about evidence the pipeline already computed.
 */
public record HeldStatementDto(
        UUID id,
        String heldId,
        UUID importJobId,
        String status,
        String triggerSummary,
        String reliabilityStatus,
        String textSource,
        Boolean headerReconstructionUncertain,
        String parserVersion,
        UUID assignedEngineerId,
        String engineerNotes,
        Instant createdAt,
        Instant assignedAt,
        Instant readyAt,
        Instant resolvedAt) {

    public static HeldStatementDto from(HeldStatement held) {
        return new HeldStatementDto(
                held.getId(),
                held.getHeldId(),
                held.getImportJobId(),
                held.getStatus().name(),
                held.getTriggerSummary(),
                held.getReliabilityStatus(),
                held.getTextSource(),
                held.getHeaderReconstructionUncertain(),
                held.getParserVersion(),
                held.getAssignedEngineerId(),
                held.getEngineerNotes(),
                held.getCreatedAt(),
                held.getAssignedAt(),
                held.getReadyAt(),
                held.getResolvedAt());
    }
}
