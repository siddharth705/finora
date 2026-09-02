package com.finora.imports.jobs;

/**
 * BH-058 defence for the import-job queue: empties it before a test that needs to see its own job
 * run.
 *
 * <h2>Why this is load-bearing rather than tidiness</h2>
 * {@code import_jobs} is global. One Testcontainers Postgres is shared by every {@code *IT} class
 * in the JVM, and {@code app.import.queue.enabled} defaults to OFF — under test included — so a job
 * any test enqueues stays QUEUED for the rest of the run unless that test drains it. Most do not.
 *
 * <p>{@code claimDueJobs} is {@code ORDER BY next_attempt_at ... LIMIT :batchSize}, unscoped by
 * user, and {@link ImportJobStore#BATCH_SIZE} is <b>10</b>. So the usual shape in this package —
 * enqueue a job, call {@code drainOnce()} once, assert the job reached COMPLETED — silently assumes
 * fewer than ten older QUEUED jobs exist. Past that, the single pass claims ten of other tests'
 * leftovers and returns, the job under test is never claimed, and the assertion fails reporting
 * QUEUED instead of COMPLETED. The work was never late; it never happened.
 *
 * <p>This is the same defect {@code MerchantLearningNudgeIT} was fixed for, against the sibling
 * queue whose batch size is 50. This one is five times easier to trip, and measurement says it is
 * already at the edge rather than hypothetical: instrumenting a full-suite run showed
 * {@code ImportJobEndpointIT} climbing from one leftover job to eight across its own methods, and
 * {@code QueueOverheadMeasurementIT} starting a method with <b>eleven</b> — past the batch size
 * already, and surviving only because its own {@code warmUp()} happens to drain ten first.
 *
 * <p>Draining rather than scoping, deliberately. Where a claim's <em>result</em> is what gets
 * polluted, scoping the assertion to the fixture is the better fix and is what
 * {@code MerchantLearningQueueIT} does. That does not apply here: the job under test is not
 * returned-and-mixed-with-others, it is never claimed at all, so there is nothing to filter. The
 * queue has to actually be reachable.
 *
 * <p>Safe to do from setup because {@code AbstractIntegrationTest} is {@code @Isolated}, so no
 * other {@code *IT} class is running to enqueue anything concurrently.
 */
final class ImportJobQueueBacklog {

    /** Bound on the drain, so a queue that somehow refuses to empty fails loudly here rather than
     *  spinning. Each pass claims at most {@link ImportJobStore#BATCH_SIZE}. */
    private static final int MAX_DRAIN_PASSES = 100;

    private ImportJobQueueBacklog() {
    }

    /** Runs jobs left behind by the rest of the suite until nothing claimable remains, so the next
     *  {@code drainOnce()} reaches the job the caller is about to enqueue. */
    static void empty(ImportJobWorker worker) {
        int passes = 0;
        while (worker.drainOnce() > 0) {
            if (++passes >= MAX_DRAIN_PASSES) {
                throw new IllegalStateException("The import job queue would not drain in "
                        + MAX_DRAIN_PASSES + " passes; a test cannot establish its precondition "
                        + "that the job it enqueues is one drainOnce() will claim.");
            }
        }
    }
}
