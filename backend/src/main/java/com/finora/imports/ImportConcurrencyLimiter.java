package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how many statement imports (CSV or PDF) can actually be mid-parse at the same time.
 *
 * The problem this solves: ImportController's stage()/stagePdf() run the entire parse --
 * PDFBox text extraction, table detection, transaction normalization, categorization, duplicate
 * detection -- synchronously on whatever Tomcat request thread handled the upload, with no cap
 * at all on how many could run at once. A burst of simultaneous uploads (the brief's own example:
 * 10,000 users importing at the same moment) would let Tomcat spin up threads for all of them
 * (up to its own pool limit) and let every one of those threads simultaneously: hold a
 * multi-megabyte file in memory for PDFBox to chew on, AND compete for one of only
 * DB_POOL_MAX_SIZE=10 database connections (see application.yml) for the account/category/
 * duplicate-detection queries the pipeline needs. Past a fairly small number of genuinely
 * concurrent imports, that's a realistic path to the JVM actually running out of memory (not a
 * graceful failure -- the whole app going down, every user's request included, not just the
 * import ones) well before it's a path to correct results for 10,000 people at once.
 *
 * What this does about it: a bounded permit pool sized well under the DB connection pool (so
 * import processing alone can never starve every other endpoint of a connection), acquired with
 * a fair (FIFO) semaphore -- which is a genuine, real queue: a burst of requests past the
 * concurrency limit doesn't get rejected outright, it waits in arrival order for a slot to open
 * up, same as any request-queue would, just implemented as an in-process primitive rather than
 * external broker infrastructure. That's the right scope for what this actually needs to solve
 * -- a single Railway instance, not a distributed system -- rather than reaching for Redis/
 * RabbitMQ/Kafka to solve a problem a language-level semaphore already solves correctly. If
 * genuinely horizontal scaling (multiple backend instances) is ever needed, this in-process gate
 * stops being sufficient on its own (each instance would enforce its own limit independently) --
 * that's the point at which an external queue would earn its complexity, not before.
 *
 * A request that's still waiting after ACQUIRE_TIMEOUT gets a clear, immediate "try again
 * shortly" response instead of hanging indefinitely or piling up unboundedly -- an ApiException
 * with ErrorCode.IMPORT_SYSTEM_BUSY (HTTP 503), handled by GlobalExceptionHandler the same as
 * every other ApiException in the app.
 */
@Component
public class ImportConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImportConcurrencyLimiter.class);

    private final Semaphore permits;
    private final long acquireTimeoutMs;
    private final int maxConcurrent;

    public ImportConcurrencyLimiter(
            @Value("${app.import.max-concurrent:6}") int maxConcurrent,
            @Value("${app.import.acquire-timeout-ms:20000}") long acquireTimeoutMs) {
        // fair=true: FIFO ordering for threads waiting on a permit -- the actual "queue" behavior,
        // not just a bare concurrency cap. Without fairness, Java's Semaphore doesn't guarantee
        // any particular order threads are granted permits in, which would mean someone could
        // wait behind a burst indefinitely by bad luck even after others who arrived later got
        // through first.
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.maxConcurrent = maxConcurrent;
        log.info("Import concurrency limiter initialized: max {} concurrent imports, {}ms queue wait before returning 'busy'",
                maxConcurrent, acquireTimeoutMs);
    }

    /**
     * Runs `work` once a permit is available, waiting in FIFO order behind anything already
     * running or already waiting if the limit is currently reached. Throws an ApiException with
     * ErrorCode.IMPORT_SYSTEM_BUSY if no permit opens up within the configured timeout, rather
     * than waiting indefinitely.
     */
    public <T> T runGated(Callable<T> work) throws Exception {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY,
                    "Import was interrupted while waiting to be processed. Please try again.");
        }
        if (!acquired) {
            log.warn("Import request timed out waiting {}ms for a processing slot ({}/{} slots in use, {} others also waiting)",
                    acquireTimeoutMs, maxConcurrent - permits.availablePermits(), maxConcurrent, permits.getQueueLength());
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY);
        }
        try {
            return work.call();
        } finally {
            permits.release();
        }
    }
}
