package com.finora.service;

import com.finora.dto.ReconciliationExplorerDto;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles the Reconciliation Explorer's trace (docs/proposals/reconciliation-evolution-roadmap-
 * proposal.md, Part 9) for one transaction. Every piece already exists somewhere -- {@link
 * TransactionRepository} for the row, {@link CategoryRepository} for its category, {@link
 * TransactionGraphService#getGraph} for its matched edges -- what did not exist was a query that
 * put them together, the same gap {@code ImportTraceService} closed for imports.
 */
@Service
public class ReconciliationExplorerService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionGraphService transactionGraphService;

    public ReconciliationExplorerService(TransactionRepository transactionRepository,
                                          CategoryRepository categoryRepository,
                                          TransactionGraphService transactionGraphService) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.transactionGraphService = transactionGraphService;
    }

    public Optional<ReconciliationExplorerDto.Trace> trace(UUID transactionId) {
        return transactionRepository.findById(transactionId).map(t -> new ReconciliationExplorerDto.Trace(
                new ReconciliationExplorerDto.Raw(t.getId(), t.getDescription(), t.getAmount(),
                        t.getTxnType(), t.getTxnDate(), t.getSource()),
                new ReconciliationExplorerDto.Normalized(t.getMerchant(), categoryNameOf(t)),
                edgesFor(t.getId()),
                new ReconciliationExplorerDto.Classification(t.getReconciliationStatus(), t.getReconciliationExplanation())));
    }

    private String categoryNameOf(Transaction t) {
        if (t.getCategoryId() == null) return null;
        return categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse(null);
    }

    /**
     * Depth 1 -- edges touching this transaction directly, not a multi-hop graph walk. "The
     * matched edge" for a single transaction's trace is what it was matched into, not everything
     * reachable from it.
     */
    private List<ReconciliationExplorerDto.Edge> edgesFor(UUID transactionId) {
        return transactionGraphService.getGraph(transactionId, 1).stream()
                .map(edge -> new ReconciliationExplorerDto.Edge(
                        edge.getId(),
                        counterpartOf(edge, transactionId),
                        edge.getRelationshipType(),
                        edge.getConfidence(),
                        edge.getSourceTrust(),
                        edge.getStatus(),
                        edge.getDetectionMethod(),
                        edge.getExplanation()))
                .toList();
    }

    private UUID counterpartOf(TransactionRelationship edge, UUID transactionId) {
        return edge.getFromTransactionId().equals(transactionId)
                ? edge.getToTransactionId() : edge.getFromTransactionId();
    }
}
