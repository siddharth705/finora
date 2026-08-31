package com.finora.dto;

import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One transaction, traced from its raw shape through to its final reconciliation verdict --
 * Phase 2's Founder Operations Dashboard, Reconciliation Explorer (docs/proposals/reconciliation-
 * evolution-roadmap-proposal.md, Part 9): "Raw -> Normalized -> Matched -> Confidence -> Final
 * classification, for any transaction." Assembled, not aggregated, same position {@code
 * ImportTraceDto} takes: every block reports what its own table recorded, no derived verdict.
 */
public final class ReconciliationExplorerDto {

    private ReconciliationExplorerDto() {
        // Namespace for the nested records, per CODING_STANDARDS' DTO convention.
    }

    public record Trace(Raw raw, Normalized normalized, List<Edge> edges, Classification classification) {}

    /** The transaction exactly as parsed/entered, before any reconciliation or categorization. */
    public record Raw(UUID transactionId, String description, BigDecimal amount,
                       Transaction.Type txnType, LocalDate txnDate, Transaction.Source source) {}

    /** Merchant resolves straight off the row (see {@link Transaction#getMerchant()}'s own
     *  comment); category is the one join this step actually performs. Null category means
     *  uncategorized, not a lookup failure. */
    public record Normalized(String merchant, String categoryName) {}

    /**
     * One {@link TransactionRelationship} edge touching this transaction directly (depth 1 --
     * this is "the matched edge" for THIS transaction, not a multi-hop graph walk). {@code
     * counterpartTransactionId} is whichever side of the edge isn't this transaction, since a
     * caller who already knows this transaction's id only cares about the other one.
     */
    public record Edge(UUID edgeId, UUID counterpartTransactionId, TransactionRelationship.RelationshipType relationshipType,
                        Integer confidence, Integer sourceTrust, TransactionRelationship.Status status,
                        TransactionRelationship.DetectionMethod detectionMethod, Map<String, Object> explanation) {}

    /** The transaction's own verdict and its one-shot explanation -- see {@code
     *  Transaction.reconciliationExplanation}'s own comment for why this is separate from each
     *  edge's own {@code explanation}: one transaction, one classification, but potentially many
     *  edges (a CC payment settling many spends has one edge per spend). */
    public record Classification(Transaction.ReconciliationStatus reconciliationStatus,
                                  Map<String, Object> transactionExplanation) {}
}
