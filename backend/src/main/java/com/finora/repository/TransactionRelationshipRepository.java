package com.finora.repository;

import com.finora.entity.TransactionRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TransactionRelationshipRepository extends JpaRepository<TransactionRelationship, UUID> {

    /**
     * Every edge touching any of {@code transactionIds} from either side -- the graph is walked
     * from an arbitrary starting transaction, and a transfer pair (for example) is symmetric, so a
     * caller cannot know in advance which side of {@code from}/{@code to} a given id will be on.
     * Takes a whole BFS frontier (or, in {@link com.finora.service.TransactionGraphService#linkAll},
     * a whole batch's touched transactions) at once rather than one id at a time, so a wide
     * fan-out still costs one round trip, not one per node.
     */
    @Query("""
           SELECT r FROM TransactionRelationship r
            WHERE r.fromTransactionId IN :transactionIds OR r.toTransactionId IN :transactionIds
           """)
    List<TransactionRelationship> findByEitherSideIn(@Param("transactionIds") List<UUID> transactionIds);
}
