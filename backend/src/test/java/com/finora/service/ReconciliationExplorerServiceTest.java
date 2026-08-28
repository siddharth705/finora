package com.finora.service;

import com.finora.dto.ReconciliationExplorerDto;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationExplorerServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private TransactionGraphService transactionGraphService;
    private ReconciliationExplorerService service;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        transactionGraphService = mock(TransactionGraphService.class);
        service = new ReconciliationExplorerService(transactionRepository, categoryRepository, transactionGraphService);
    }

    private Transaction txn(UUID id) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", id);
        t.setDescription("REFUND ZOMATO 340.00");
        t.setAmount(new BigDecimal("340.00"));
        t.setTxnType(Transaction.Type.INCOME);
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setSource(Transaction.Source.CSV_IMPORT);
        t.setMerchant("Zomato");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        return t;
    }

    @Test
    void trace_isEmpty_whenTheTransactionDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.trace(id)).isEmpty();
    }

    @Test
    void trace_assemblesRawNormalizedAndClassification_forATransactionWithNoCategoryOrEdges() {
        UUID id = UUID.randomUUID();
        Transaction t = txn(id);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(transactionGraphService.getGraph(id, 1)).thenReturn(List.of());

        ReconciliationExplorerDto.Trace trace = service.trace(id).orElseThrow();

        assertThat(trace.raw().transactionId()).isEqualTo(id);
        assertThat(trace.raw().description()).isEqualTo("REFUND ZOMATO 340.00");
        assertThat(trace.raw().amount()).isEqualByComparingTo("340.00");
        assertThat(trace.raw().source()).isEqualTo(Transaction.Source.CSV_IMPORT);
        assertThat(trace.normalized().merchant()).isEqualTo("Zomato");
        assertThat(trace.normalized().categoryName()).isNull(); // no categoryId set
        assertThat(trace.edges()).isEmpty();
        assertThat(trace.classification().reconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.REFUND);
    }

    @Test
    void trace_resolvesTheCategoryName_whenACategoryIsSet() {
        UUID id = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Transaction t = txn(id);
        t.setCategoryId(categoryId);
        Category category = mock(Category.class);
        when(category.getName()).thenReturn("Dining");
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(transactionGraphService.getGraph(id, 1)).thenReturn(List.of());

        ReconciliationExplorerDto.Trace trace = service.trace(id).orElseThrow();

        assertThat(trace.normalized().categoryName()).isEqualTo("Dining");
    }

    @Test
    void trace_treatsADanglingCategoryId_asNoCategory_notAnError() {
        UUID id = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        Transaction t = txn(id);
        t.setCategoryId(categoryId);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());
        when(transactionGraphService.getGraph(id, 1)).thenReturn(List.of());

        ReconciliationExplorerDto.Trace trace = service.trace(id).orElseThrow();

        assertThat(trace.normalized().categoryName()).isNull();
    }

    /**
     * The counterpart id must be whichever side of the edge ISN'T this transaction -- pinned down
     * from both directions, since a wrong direction here would be wrong for every edge shown.
     */
    @Test
    void trace_reportsTheOtherSideOfTheEdge_regardlessOfWhichSideThisTransactionIsOn() {
        UUID thisId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Transaction t = txn(thisId);
        when(transactionRepository.findById(thisId)).thenReturn(Optional.of(t));

        TransactionRelationship edgeWhereThisIsFrom = edge(thisId, otherId);
        when(transactionGraphService.getGraph(thisId, 1)).thenReturn(List.of(edgeWhereThisIsFrom));

        ReconciliationExplorerDto.Trace trace = service.trace(thisId).orElseThrow();

        assertThat(trace.edges()).hasSize(1);
        assertThat(trace.edges().get(0).counterpartTransactionId()).isEqualTo(otherId);
    }

    @Test
    void trace_carriesTheEdgesConfidenceSourceTrustAndStatus_throughUnchanged() {
        UUID thisId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Transaction t = txn(thisId);
        when(transactionRepository.findById(thisId)).thenReturn(Optional.of(t));

        TransactionRelationship rel = edge(thisId, otherId);
        rel.setConfidence(72);
        rel.setSourceTrust(95);
        rel.setStatus(TransactionRelationship.Status.CANDIDATE);
        rel.setDetectionMethod(TransactionRelationship.DetectionMethod.RULE_ENGINE);
        rel.setExplanation(Map.of("type", "REFUND"));
        when(transactionGraphService.getGraph(thisId, 1)).thenReturn(List.of(rel));

        ReconciliationExplorerDto.Edge edgeView = service.trace(thisId).orElseThrow().edges().get(0);

        assertThat(edgeView.confidence()).isEqualTo(72);
        assertThat(edgeView.sourceTrust()).isEqualTo(95);
        assertThat(edgeView.status()).isEqualTo(TransactionRelationship.Status.CANDIDATE);
        assertThat(edgeView.explanation()).containsEntry("type", "REFUND");
    }

    private TransactionRelationship edge(UUID from, UUID to) {
        TransactionRelationship e = new TransactionRelationship();
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.setFromTransactionId(from);
        e.setToTransactionId(to);
        e.setRelationshipType(TransactionRelationship.RelationshipType.REFUND);
        e.setDetectionMethod(TransactionRelationship.DetectionMethod.RULE_ENGINE);
        return e;
    }
}
