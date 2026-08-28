package com.finora.service;

import com.finora.entity.TransactionRelationship;
import com.finora.repository.TransactionRelationshipRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the transaction graph (docs/proposals/reconciliation-evolution-roadmap-
 * proposal.md, Part 3) -- {@link TransactionRelationship} edges, additive alongside {@link
 * com.finora.entity.Transaction}'s single-pointer legacy columns rather than a replacement for
 * them. Do not confuse with {@link RelationshipService}, which is unrelated: that one is CRUD for
 * family/friend/own-account contact tags on a user's profile, this one is transaction-to-
 * transaction edges.
 */
@Service
public class TransactionGraphService {

    /**
     * How far {@link #getGraph} walks before stopping. A transfer/refund/reversal/duplicate edge
     * is a single hop in practice -- this exists to bound a pathological or future many-hop chain
     * (e.g. CC payment -> spend -> refund of that spend), not because real graphs are expected to
     * be this deep today.
     */
    static final int MAX_GRAPH_DEPTH = 5;

    private final TransactionRelationshipRepository repository;

    public TransactionGraphService(TransactionRelationshipRepository repository) {
        this.repository = repository;
    }

    /**
     * One edge a caller wants written, before it is known whether an equivalent live edge already
     * exists. {@code fromTransactionId}/{@code toTransactionId} order carries meaning per {@code
     * relationshipType} (e.g. the refund pass builds one with the income as {@code from} and the
     * expense it reverses as {@code to}) -- {@link #linkAll} does not normalize or dedupe the
     * reverse direction, since a real edge is directional even when the two transactions mutually
     * reference each other via their own legacy columns.
     */
    public record PendingEdge(UUID userId, UUID fromTransactionId, UUID toTransactionId,
                               TransactionRelationship.RelationshipType relationshipType,
                               BigDecimal matchedAmount, Integer confidence,
                               TransactionRelationship.Status status,
                               TransactionRelationship.DetectionMethod detectionMethod,
                               Map<String, Object> explanation) {
    }

    /**
     * Records a batch of edges in one round trip, idempotently: any {@code pending} entry whose
     * (from, to, type) shape already has a live (not superseded) edge is skipped rather than
     * duplicated. One {@code findByEitherSideIn} covering every transaction touched by the whole
     * batch, then one {@code saveAll} -- not one exists-check-plus-save per edge -- for the same
     * reason {@code ReconciliationService}'s own passes collect into a {@code dirty} set and write
     * it once: a reconciliation run over an import can produce hundreds of matches, and each
     * round trip was measured cost before that pattern existed (see {@code ReconciliationService}'s
     * own comment on its {@code dirty} set).
     *
     * <p>Reconciliation runs on every transaction create/update/delete and import, so the
     * idempotency guard matters here as much as it would for a single-edge write: without it,
     * re-running the same pass over an already-classified pair would insert a fresh row every time.
     */
    public List<TransactionRelationship> linkAll(List<PendingEdge> pending) {
        if (pending.isEmpty()) return List.of();

        Set<UUID> touchedTransactionIds = new LinkedHashSet<>();
        for (PendingEdge p : pending) {
            touchedTransactionIds.add(p.fromTransactionId());
            touchedTransactionIds.add(p.toTransactionId());
        }
        Set<String> liveEdgeKeys = new java.util.HashSet<>();
        for (TransactionRelationship existing : repository.findByEitherSideIn(new ArrayList<>(touchedTransactionIds))) {
            if (existing.getSupersededBy() == null) {
                liveEdgeKeys.add(edgeKey(existing.getFromTransactionId(), existing.getToTransactionId(),
                        existing.getRelationshipType()));
            }
        }

        // Also guards against the same batch naming the same edge twice -- the transfer pass, for
        // instance, can revisit a pair from either side across different frontier expansions in a
        // single run.
        Set<String> plannedThisBatch = new java.util.HashSet<>();
        List<TransactionRelationship> toSave = new ArrayList<>();
        for (PendingEdge p : pending) {
            String key = edgeKey(p.fromTransactionId(), p.toTransactionId(), p.relationshipType());
            if (liveEdgeKeys.contains(key) || !plannedThisBatch.add(key)) continue;

            TransactionRelationship edge = new TransactionRelationship();
            edge.setUserId(p.userId());
            edge.setFromTransactionId(p.fromTransactionId());
            edge.setToTransactionId(p.toTransactionId());
            edge.setRelationshipType(p.relationshipType());
            edge.setMatchedAmount(p.matchedAmount());
            edge.setConfidence(p.confidence());
            edge.setStatus(p.status());
            edge.setDetectionMethod(p.detectionMethod());
            edge.setExplanation(p.explanation());
            toSave.add(edge);
        }
        return toSave.isEmpty() ? List.of() : repository.saveAll(toSave);
    }

    private static String edgeKey(UUID from, UUID to, TransactionRelationship.RelationshipType type) {
        return from + "|" + to + "|" + type;
    }

    /**
     * Every edge reachable from {@code transactionId} within {@link #MAX_GRAPH_DEPTH} hops,
     * breadth-first. One query per depth level (not per node) -- {@link
     * TransactionRelationshipRepository#findByEitherSideIn} takes the whole frontier at once, so a
     * graph with a wide fan-out at one level still costs one round trip for that level, not one per
     * node in it.
     */
    public List<TransactionRelationship> getGraph(UUID transactionId, int depth) {
        int cappedDepth = Math.min(depth, MAX_GRAPH_DEPTH);
        Map<UUID, TransactionRelationship> edgesById = new LinkedHashMap<>();
        Set<UUID> visitedTransactions = new java.util.HashSet<>(Set.of(transactionId));
        Deque<UUID> frontier = new ArrayDeque<>(List.of(transactionId));

        for (int hop = 0; hop < cappedDepth && !frontier.isEmpty(); hop++) {
            List<UUID> frontierIds = new ArrayList<>(frontier);
            frontier.clear();
            List<TransactionRelationship> edges = repository.findByEitherSideIn(frontierIds);
            for (TransactionRelationship edge : edges) {
                edgesById.putIfAbsent(edge.getId(), edge);
                UUID other = frontierIds.contains(edge.getFromTransactionId())
                        ? edge.getToTransactionId() : edge.getFromTransactionId();
                if (visitedTransactions.add(other)) {
                    frontier.add(other);
                }
            }
        }
        return new ArrayList<>(edgesById.values());
    }

    /**
     * A human ruling on a candidate edge -- {@code CANDIDATE}/{@code AUTO_CONFIRMED} moving to
     * {@code USER_CONFIRMED} or {@code REJECTED}. Mutates the existing row's status rather than
     * writing a new edge and superseding the old one: unlike {@code Transaction.isDuplicateOf}'s
     * {@code notDuplicateConfirmedAt} pattern (a separate sentinel column), this table already has a
     * {@code status} column built to carry exactly this state, so a second mechanism for the same
     * fact would be redundant.
     */
    public TransactionRelationship setStatus(UUID edgeId, TransactionRelationship.Status status) {
        TransactionRelationship edge = repository.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("No such transaction relationship: " + edgeId));
        edge.setStatus(status);
        return repository.save(edge);
    }
}
