package com.finora.imports;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

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
 * import processing alone can never starve every other endpoint of a connection).
 *
 * BH-043: this used to acquire that permit with a blocking, fair (FIFO) wait of up to
 * ACQUIRE_TIMEOUT before giving up -- a genuine in-process queue. The bug: that wait ran on
 * whatever Tomcat request thread handled the upload. With max-concurrent=6 and Tomcat's default
 * 200-thread pool, a burst of uploads could leave close to 200 request threads parked for up to
 * the full timeout each -- and those are the SAME threads that serve every other endpoint in the
 * app (login, dashboard, ledger), so an import burst degraded totally unrelated functionality.
 * That's exactly the failure mode this class exists to prevent, just relocated from the DB
 * connection pool to the Tomcat thread pool instead of actually being avoided.
 *
 * So this now does an instant, non-blocking check instead of a blocking wait: if a permit is
 * free, take it and run immediately; if not, reject immediately rather than parking the calling
 * thread at all. No thread is ever held waiting on this semaphore -- the same "reject fast"
 * pattern RateLimiter/RateLimitFilter already use elsewhere in this codebase for the same reason.
 * A rejected request gets a clear, immediate "try again shortly" response -- an ApiException
 * with ErrorCode.IMPORT_SYSTEM_BUSY (HTTP 503), handled by GlobalExceptionHandler the same as
 * every other ApiException in the app.
 *
 * This is a single-instance, in-process gate, not a distributed rate limiter -- the right scope
 * for what this actually needs to solve on a single Railway instance, rather than reaching for
 * Redis/RabbitMQ/Kafka to solve a problem a language-level semaphore already solves correctly. If
 * genuinely horizontal scaling (multiple backend instances) is ever needed, this in-process gate
 * stops being sufficient on its own (each instance would enforce its own limit independently) --
 * that's the point at which an external mechanism would earn its complexity, not before.
 */
@Component
public class ImportConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImportConcurrencyLimiter.class);

    private final Semaphore permits;
    private final int maxConcurrent;

    public ImportConcurrencyLimiter(@Value("${app.import.max-concurrent:6}") int maxConcurrent) {
        // BH-043: fairness is deliberately NOT requested here (plain `new Semaphore(int)`, the
        // non-fair/default constructor). Fairness only ever mattered for ordering threads that
        // actually parked waiting on the semaphore -- and per Semaphore's own javadoc, the no-arg
        // tryAcquire() this class now uses always "barges" and grabs a free permit immediately
        // regardless of the fairness setting anyway, so a fair semaphore here would buy nothing
        // but its (real, if small) throughput cost.
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        log.info("Import concurrency limiter initialized: max {} concurrent imports, rejects immediately with 'busy' once the limit is reached",
                maxConcurrent);
    }

    /**
     * Runs `work` immediately if a permit is currently available. If the limit is already
     * reached, rejects immediately (BH-043: no blocking wait -- see class doc) with an
     * ApiException carrying ErrorCode.IMPORT_SYSTEM_BUSY, rather than parking the calling thread.
     */
    public <T> T runGated(Callable<T> work) throws Exception {
        if (!permits.tryAcquire()) {
            // getQueueLength() is deliberately not logged here: tryAcquire() with no arguments
            // never parks a thread in the semaphore's own wait queue, so that count would always
            // read ~0 and would be misleading rather than informative post-BH-043.
            log.warn("Import request rejected -- no processing slot available ({}/{} slots in use)",
                    maxConcurrent - permits.availablePermits(), maxConcurrent);
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY);
        }
        try {
            return work.call();
        } finally {
            permits.release();
        }
    }
}
