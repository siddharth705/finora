package com.finora.repository;

import com.finora.entity.HeldStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface HeldStatementRepository extends JpaRepository<HeldStatement, UUID> {

    /** The reference an operator quotes. */
    Optional<HeldStatement> findByHeldId(String heldId);

    /** One import is held at most once -- {@code import_job_id} is UNIQUE in V144. */
    Optional<HeldStatement> findByImportJobId(UUID importJobId);

    Page<HeldStatement> findByStatusIn(Collection<HeldStatement.Status> statuses, Pageable pageable);

    long countByStatusIn(Collection<HeldStatement.Status> statuses);

    /**
     * The raw sequence value. Formatting is {@code HeldStatementIdGenerator}'s job -- the same
     * split {@code StatementAnalysisRecorder} uses for its {@code SA-} references.
     *
     * <p>{@code nextval} is transactional-but-not-rollback-safe by design: a rolled-back hold burns
     * its number rather than reissuing it. Gaps in the sequence are the correct trade -- a reused
     * reference would point at two different statements.
     */
    @Query(value = "SELECT nextval('held_statement_reference_seq')", nativeQuery = true)
    long nextHeldSequence();
}
