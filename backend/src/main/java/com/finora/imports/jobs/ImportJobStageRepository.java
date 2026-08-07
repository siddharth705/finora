package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportJobStageRepository extends JpaRepository<ImportJobStage, UUID> {

    /** Every stage of one job, in the order they were recorded — the trace view's only query. */
    List<ImportJobStage> findByJobIdOrderByRecordedAtAsc(UUID jobId);

    Optional<ImportJobStage> findByJobIdAndAttemptAndStage(UUID jobId, int attempt, ImportJob.Status stage);

    /**
     * The stage a job is currently inside, if any.
     *
     * <p>Used to close whatever was open when a job failed, so the worker does not have to remember
     * which stage it was in at the moment something threw — and so a stage that ends by throwing is
     * recorded as {@code FAILED} rather than left {@code RUNNING} forever.
     */
    List<ImportJobStage> findByJobIdAndAttemptAndOutcome(UUID jobId, int attempt,
                                                          ImportJobStage.Outcome outcome);
}
