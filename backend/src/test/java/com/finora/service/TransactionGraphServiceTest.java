package com.finora.service;

import com.finora.entity.TransactionRelationship;
import com.finora.repository.TransactionRelationshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionGraphServiceTest {

    private TransactionRelationshipRepository repository;
    private TransactionGraphService graphService;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRelationshipRepository.class);
        graphService = new TransactionGraphService(repository);
        // save()/saveAll() echo their argument back, same as a real JPA save() with no generated
        // fields this test cares about.
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        // No pre-existing edges touch any transaction id, by default -- each test overrides this
        // only when it specifically wants to exercise the idempotency guard.
        when(repository.findByEitherSideIn(any())).thenReturn(List.of());
    }

    private TransactionGraphService.PendingEdge pendingEdge(UUID from, UUID to,
                                                              TransactionRelationship.RelationshipType type) {
        return new TransactionGraphService.PendingEdge(userId, from, to, type, new BigDecimal("100.00"), 100, 95,
                TransactionRelationship.Status.AUTO_CONFIRMED, TransactionRelationship.DetectionMethod.RULE_ENGINE,
                Map.of("type", type.name()));
    }

    private TransactionRelationship edge(UUID from, UUID to, TransactionRelationship.RelationshipType type) {
        TransactionRelationship e = new TransactionRelationship();
        org.springframework.test.util.ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setFromTransactionId(from);
        e.setToTransactionId(to);
        e.setRelationshipType(type);
        return e;
    }

    @Test
    void linkAll_savesANewEdge_whenNoneExistsYet() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        List<TransactionRelationship> result = graphService.linkAll(
                List.of(pendingEdge(from, to, TransactionRelationship.RelationshipType.TRANSFER)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFromTransactionId()).isEqualTo(from);
        assertThat(result.get(0).getToTransactionId()).isEqualTo(to);
        assertThat(result.get(0).getUserId()).isEqualTo(userId);
        verify(repository, times(1)).saveAll(any());
    }

    @Test
    void linkAll_returnsEmpty_forAnEmptyBatch_withoutQueryingTheRepository() {
        List<TransactionRelationship> result = graphService.linkAll(List.of());

        assertThat(result).isEmpty();
        verify(repository, never()).findByEitherSideIn(any());
        verify(repository, never()).saveAll(any());
    }

    /**
     * Reconciliation runs after every transaction create/update/delete and import -- without this
     * guard, re-running the same pass over an already-classified pair would insert a fresh
     * duplicate row on every single run.
     */
    @Test
    void linkAll_doesNotDuplicateAnExistingLiveEdge() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(repository.findByEitherSideIn(any()))
                .thenReturn(List.of(edge(from, to, TransactionRelationship.RelationshipType.DUPLICATE)));

        List<TransactionRelationship> result = graphService.linkAll(
                List.of(pendingEdge(from, to, TransactionRelationship.RelationshipType.DUPLICATE)));

        assertThat(result).isEmpty();
        verify(repository, never()).saveAll(any());
    }

    /**
     * A batch can name the same (from, to, type) edge twice within itself -- e.g. the transfer
     * pass revisiting a pair from either side across frontier expansions in the same run -- and
     * that must collapse to one saved row, not two.
     */
    @Test
    void linkAll_collapsesADuplicateWithinTheSameBatch() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        List<TransactionRelationship> result = graphService.linkAll(List.of(
                pendingEdge(from, to, TransactionRelationship.RelationshipType.TRANSFER),
                pendingEdge(from, to, TransactionRelationship.RelationshipType.TRANSFER)));

        assertThat(result).hasSize(1);
    }

    @Test
    void linkAll_savesTheEdgesThatDontAlreadyExist_amongAMixedBatch() {
        UUID from1 = UUID.randomUUID();
        UUID to1 = UUID.randomUUID();
        UUID from2 = UUID.randomUUID();
        UUID to2 = UUID.randomUUID();
        when(repository.findByEitherSideIn(any()))
                .thenReturn(List.of(edge(from1, to1, TransactionRelationship.RelationshipType.REFUND)));

        List<TransactionRelationship> result = graphService.linkAll(List.of(
                pendingEdge(from1, to1, TransactionRelationship.RelationshipType.REFUND),
                pendingEdge(from2, to2, TransactionRelationship.RelationshipType.REVERSAL)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFromTransactionId()).isEqualTo(from2);
    }

    @Test
    void getGraph_returnsOneHopEdges_withinDepth() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TransactionRelationship edgeAB = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);

        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(edgeAB));

        List<TransactionRelationship> graph = graphService.getGraph(a, 1);

        assertThat(graph).containsExactly(edgeAB);
    }

    /**
     * A depth-1 walk from {@code a} must not pull in an edge that is two hops away
     * ({@code b -> c}) -- only edges reachable within the requested depth belong in the result.
     */
    @Test
    void getGraph_doesNotReachBeyondTheRequestedDepth() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        TransactionRelationship edgeAB = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);
        TransactionRelationship edgeBC = edge(b, c, TransactionRelationship.RelationshipType.REFUND);

        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(edgeAB));

        List<TransactionRelationship> graph = graphService.getGraph(a, 1);

        assertThat(graph).containsExactly(edgeAB).doesNotContain(edgeBC);
    }

    @Test
    void getGraph_walksMultipleHops_whenDepthAllows() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        TransactionRelationship edgeAB = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);
        TransactionRelationship edgeBC = edge(b, c, TransactionRelationship.RelationshipType.REFUND);

        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(edgeAB));
        when(repository.findByEitherSideIn(List.of(b))).thenReturn(List.of(edgeBC));

        List<TransactionRelationship> graph = graphService.getGraph(a, 2);

        assertThat(graph).containsExactlyInAnyOrder(edgeAB, edgeBC);
    }

    /**
     * A cycle (a transfer pair, where both sides reference each other) must not send the walk
     * back and forth forever -- the visited-transaction guard is what stops it.
     */
    @Test
    void getGraph_doesNotLoopForever_onACycle() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TransactionRelationship edgeAB = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);
        TransactionRelationship edgeBA = edge(b, a, TransactionRelationship.RelationshipType.TRANSFER);

        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(edgeAB));
        when(repository.findByEitherSideIn(List.of(b))).thenReturn(List.of(edgeBA));

        List<TransactionRelationship> graph = graphService.getGraph(a, TransactionGraphService.MAX_GRAPH_DEPTH);

        assertThat(graph).containsExactlyInAnyOrder(edgeAB, edgeBA);
    }

    @Test
    void setStatus_updatesAnExistingEdge() {
        UUID edgeId = UUID.randomUUID();
        TransactionRelationship existing = edge(UUID.randomUUID(), UUID.randomUUID(),
                TransactionRelationship.RelationshipType.DUPLICATE);
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", edgeId);
        existing.setStatus(TransactionRelationship.Status.CANDIDATE);
        when(repository.findById(edgeId)).thenReturn(java.util.Optional.of(existing));

        TransactionRelationship updated = graphService.setStatus(edgeId, TransactionRelationship.Status.REJECTED);

        assertThat(updated.getStatus()).isEqualTo(TransactionRelationship.Status.REJECTED);
    }

    @Test
    void setStatus_throwsForAnUnknownEdge() {
        UUID edgeId = UUID.randomUUID();
        when(repository.findById(edgeId)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphService.setStatus(edgeId, TransactionRelationship.Status.REJECTED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supersede_pointsTheOldEdgeAtTheNewOne() {
        UUID oldEdgeId = UUID.randomUUID();
        UUID newEdgeId = UUID.randomUUID();
        TransactionRelationship old = edge(UUID.randomUUID(), UUID.randomUUID(),
                TransactionRelationship.RelationshipType.TRANSFER);
        org.springframework.test.util.ReflectionTestUtils.setField(old, "id", oldEdgeId);
        when(repository.findById(oldEdgeId)).thenReturn(java.util.Optional.of(old));

        TransactionRelationship updated = graphService.supersede(oldEdgeId, newEdgeId);

        assertThat(updated.getSupersededBy()).isEqualTo(newEdgeId);
    }

    @Test
    void supersede_throwsForAnUnknownEdge() {
        UUID oldEdgeId = UUID.randomUUID();
        when(repository.findById(oldEdgeId)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphService.supersede(oldEdgeId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectEdgesTouchingTransactions_rejectsEveryLiveEdge_regardlessOfType() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        TransactionRelationship transfer = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);
        TransactionRelationship ccPayment = edge(a, c, TransactionRelationship.RelationshipType.CC_PAYMENT);
        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(transfer, ccPayment));

        int rejected = graphService.rejectEdgesTouchingTransactions(List.of(a));

        assertThat(rejected).isEqualTo(2);
        assertThat(transfer.getStatus()).isEqualTo(TransactionRelationship.Status.REJECTED);
        assertThat(ccPayment.getStatus()).isEqualTo(TransactionRelationship.Status.REJECTED);
        verify(repository).saveAll(List.of(transfer, ccPayment));
    }

    @Test
    void rejectEdgesTouchingTransactions_skipsAnEdgeAlreadyRejectedOrSuperseded() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        TransactionRelationship alreadyRejected = edge(a, b, TransactionRelationship.RelationshipType.TRANSFER);
        alreadyRejected.setStatus(TransactionRelationship.Status.REJECTED);
        TransactionRelationship alreadySuperseded = edge(a, c, TransactionRelationship.RelationshipType.CC_PAYMENT);
        alreadySuperseded.setSupersededBy(UUID.randomUUID());
        when(repository.findByEitherSideIn(List.of(a))).thenReturn(List.of(alreadyRejected, alreadySuperseded));

        int rejected = graphService.rejectEdgesTouchingTransactions(List.of(a));

        assertThat(rejected).isZero();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void rejectEdgesTouchingTransactions_shortCircuits_onAnEmptyCollection() {
        int rejected = graphService.rejectEdgesTouchingTransactions(List.of());

        assertThat(rejected).isZero();
        verify(repository, never()).findByEitherSideIn(any());
    }

    private com.finora.entity.Transaction txn(UUID id) {
        com.finora.entity.Transaction t = new com.finora.entity.Transaction();
        org.springframework.test.util.ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    @Test
    void ccPaymentFromTransactionIds_returnsTheFromSide_ofALiveCcPaymentEdge() {
        UUID payment = UUID.randomUUID();
        UUID charge = UUID.randomUUID();
        TransactionRelationship ccPayment = edge(payment, charge, TransactionRelationship.RelationshipType.CC_PAYMENT);
        ccPayment.setStatus(TransactionRelationship.Status.CANDIDATE);
        when(repository.findByFromTransactionIdInAndRelationshipTypeAndStatusNotAndSupersededByIsNull(
                List.of(payment, charge), TransactionRelationship.RelationshipType.CC_PAYMENT,
                TransactionRelationship.Status.REJECTED))
                .thenReturn(List.of(ccPayment));

        Set<UUID> result = graphService.ccPaymentFromTransactionIds(List.of(txn(payment), txn(charge)));

        assertThat(result).containsExactly(payment);
    }

    @Test
    void ccPaymentFromTransactionIds_returnsEmpty_whenNoEdgesTouchTheBatch() {
        UUID a = UUID.randomUUID();
        when(repository.findByFromTransactionIdInAndRelationshipTypeAndStatusNotAndSupersededByIsNull(
                List.of(a), TransactionRelationship.RelationshipType.CC_PAYMENT, TransactionRelationship.Status.REJECTED))
                .thenReturn(List.of());

        assertThat(graphService.ccPaymentFromTransactionIds(List.of(txn(a)))).isEmpty();
    }

    @Test
    void ccPaymentFromTransactionIds_shortCircuits_onAnEmptyCollection() {
        Set<UUID> result = graphService.ccPaymentFromTransactionIds(List.of());

        assertThat(result).isEmpty();
        verify(repository, never()).findByFromTransactionIdInAndRelationshipTypeAndStatusNotAndSupersededByIsNull(
                any(), any(), any());
    }
}
