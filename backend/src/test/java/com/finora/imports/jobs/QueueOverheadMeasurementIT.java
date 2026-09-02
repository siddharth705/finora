package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.StatementUpload;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What routing a statement through the queue costs, for statements small enough that it might not
 * be worth it.
 *
 * <h2>The question this exists to answer</h2>
 *
 * <p>{@code docs/engineering/milestone-2-import-at-scale.md} item 5 leaves one thing open: <i>"When
 * async applies. Every import, or only above a threshold? A 3-row CSV routed through a queue is a
 * worse experience than a synchronous one. Measure before choosing."</i> This is that measurement,
 * and it is a test rather than a document for the reason {@code ImportQueryCountIT} spells out —
 * the last performance document in this repository was stale within forty minutes, because a
 * measurement that takes a manual session to repeat gets repeated never.
 *
 * <h2>What it measures, and what it deliberately does not</h2>
 *
 * <p>Both paths do the same parsing work, so the parse cancels out and what remains is the queue's
 * <b>fixed overhead</b>: storing the bytes to object storage, writing and committing the job row,
 * claiming it back, and re-reading the content. That is the number the threshold decision turns on,
 * because it is the part a small statement pays for and gets nothing back from.
 *
 * <p><b>It does not measure what the user experiences</b>, and that gap matters more than the
 * numbers below. The client polls every 1.5s, so the floor on perceived latency for a queued import
 * is roughly one poll interval however fast the server is — which for a 3-row CSV is likely to
 * dominate everything measured here. Server overhead is the part that can be measured repeatably;
 * the poll interval is a client constant, and the two have to be added by hand when the threshold is
 * finally chosen.
 *
 * <p>Storage is the filesystem provider. R2 would add a real network round trip per upload and per
 * claim, so treat the storage component of these figures as a floor rather than an estimate.
 *
 * <h2>Not a ratchet</h2>
 *
 * <p>Unlike {@code ImportQueryCountIT} this asserts almost nothing — wall-clock timings on a shared
 * CI machine are far too noisy to gate a build on, and a flaky performance assertion gets muted,
 * which loses the measurement entirely. It prints, and it asserts only the one thing that is a
 * genuine correctness claim rather than a timing one: that both paths staged the same rows.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-queue-overhead-it",
        "app.import.queue.enabled=false"
})
class QueueOverheadMeasurementIT extends AbstractIntegrationTest {

    @Autowired private ImportJobService jobService;
    @Autowired private ImportJobWorker worker;

    /** BH-058. The suite shares one import_jobs table and leaves jobs QUEUED in it; drainOnce()
     *  claims only the oldest ImportJobStore.BATCH_SIZE of them, so without this the job each test
     *  enqueues below can fall outside the batch and never run. See ImportJobQueueBacklog. */
    @BeforeEach
    void emptyTheQueueTheRestOfTheSuiteLeftBehind() {
        ImportJobQueueBacklog.empty(worker);
    }
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;

    /** Row counts that bracket the decision: a statement nobody would queue, one nobody would not,
     *  and the awkward middle where the answer is actually a judgement call. */
    private static final int[] SIZES = { 3, 50, 500 };

    /** Odd, so the median is a real observation rather than an average of two. Five runs of three
     *  sizes on two paths is thirty imports, which is still well under ten seconds. */
    private static final int REPEATS = 5;

    private User user() {
        User user = new User();
        user.setEmail("queue-overhead-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Queue Overhead IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private static String csv(int rows) {
        StringBuilder out = new StringBuilder("Date,Description,Amount,Type\n");
        for (int i = 0; i < rows; i++) {
            // Distinct descriptions so merchant resolution does the work it does in a real
            // statement rather than hitting the same alias every row.
            out.append("2026-07-%02d,MERCHANT %04d,%d.50,DEBIT%n"
                    .formatted((i % 28) + 1, i, 100 + i));
        }
        return out.toString();
    }

    @Test
    void queueOverheadByStatementSize() throws Exception {
        // One warm pass before timing anything: the first import in a JVM pays for Hibernate
        // statement preparation, connection warm-up and class loading, none of which a real
        // deployment pays per upload. Timing it would attribute all of that to the queue.
        warmUp();

        System.out.printf("%n[queue-overhead] rows | synchronous med (min-max) | queued med (min-max) "
                + "| overhead   [median of %d, alternating order]%n", REPEATS);
        for (int rows : SIZES) {
            String content = csv(rows);
            long[] syncRuns = new long[REPEATS];
            long[] asyncRuns = new long[REPEATS];

            for (int run = 0; run < REPEATS; run++) {
                // Alternated, because a fixed order silently attributes continuing JVM warm-up to
                // whichever path runs second. The first version of this harness ran synchronous
                // first every time and reported the QUEUE as 222ms faster at 500 rows, which is not
                // a thing that can be true -- the queue does strictly more work for the same parse.
                if (run % 2 == 0) {
                    syncRuns[run] = timeSync(rows, content);
                    asyncRuns[run] = timeQueued(rows, content);
                } else {
                    asyncRuns[run] = timeQueued(rows, content);
                    syncRuns[run] = timeSync(rows, content);
                }
            }

            long syncMs = median(syncRuns);
            long asyncMs = median(asyncRuns);
            long delta = asyncMs - syncMs;
            // The spread is printed because the difference is a small constant and the runs are
            // not: past ~50 rows the parse dominates and its own variance is wider than the
            // overhead being measured, which is how a first version of this reported the queue as
            // FASTER. A negative delta never means the queue saved work -- it does strictly more of
            // it -- so when |delta| falls inside the spread the honest reading is "not resolvable",
            // and the table has to make that visible rather than leave it to be inferred.
            boolean resolvable = Math.abs(delta) > spread(syncRuns);
            System.out.printf("[queue-overhead] %4d | %4d ms (%d-%d) | %4d ms (%d-%d) | %-24s | seen at ~%4d ms%n",
                    rows,
                    syncMs, min(syncRuns), max(syncRuns),
                    asyncMs, min(asyncRuns), max(asyncRuns),
                    resolvable ? "%+d ms".formatted(delta)
                               : "below noise (spread %d ms)".formatted(spread(syncRuns)),
                    firstPollAtOrAfter(asyncMs));
        }
        // The figures above are server-side. What a user experiences is the first poll AFTER the
        // work finishes, so the client's schedule -- not this table -- sets the floor. It used to be
        // a flat 1500 ms, which dominated every row here by two orders of magnitude; it is now
        // ImportProgress.POLL_SCHEDULE_MS, {100, 200, 400, 800, 1500}, cumulative.
        System.out.printf("[queue-overhead] \"seen at\" = first poll at or after completion, on the "
                + "client's cumulative schedule: 100, 300, 700, 1500, then every 1500 ms.%n");
    }

    /**
     * Where the client's cumulative poll schedule first lands at or after {@code completedAtMs}.
     *
     * <p>Mirrors {@code ImportProgress.POLL_SCHEDULE_MS} in the web app. Duplicated rather than
     * shared because a Java test cannot import a TypeScript constant, and stated here so the number
     * this harness prints is derived rather than asserted -- if the client's schedule changes and
     * this does not, the two disagreeing is the signal.
     */
    private static long firstPollAtOrAfter(long completedAtMs) {
        int[] schedule = {100, 200, 400, 800};
        long at = 0;
        for (int delay : schedule) {
            at += delay;
            if (at >= completedAtMs) return at;
        }
        while (at < completedAtMs) at += 1500;
        return at;
    }

    private void warmUp() throws Exception {
        importService.parseAndStageWithSession(
                user().getId(), "warmup.csv", csv(20).getBytes(StandardCharsets.UTF_8));
        User warm = user();
        jobService.accept(warm.getId(), file(20, csv(20)), StatementUpload.Format.CSV);
        worker.drainOnce();
    }

    private static MockMultipartFile file(int rows, String content) {
        return new MockMultipartFile("file", "queued-" + rows + ".csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private long timeSync(int rows, String content) throws Exception {
        UUID userId = user().getId();
        long startedAt = System.nanoTime();
        importService.parseAndStageWithSession(
                userId, "sync-" + rows + ".csv", content.getBytes(StandardCharsets.UTF_8));
        return millisSince(startedAt);
    }

    private long timeQueued(int rows, String content) throws Exception {
        UUID userId = user().getId();
        long startedAt = System.nanoTime();
        ImportJob job = jobService.accept(userId, file(rows, content), StatementUpload.Format.CSV);
        worker.drainOnce();
        long elapsed = millisSince(startedAt);

        ImportJob done = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(done.getStatus())
                .as("measurement is meaningless if the job did not actually run: %s", done.getLastError())
                .isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(done.getRowsTotal())
                .as("both paths must stage the same statement for the comparison to mean anything")
                .isEqualTo(rows);
        return elapsed;
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /** Median rather than mean: one GC pause during a run should not move the reported figure. */
    private static long median(long[] runs) {
        long[] sorted = runs.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long min(long[] runs) { return java.util.Arrays.stream(runs).min().orElse(0); }

    private static long max(long[] runs) { return java.util.Arrays.stream(runs).max().orElse(0); }

    /** How much this path's own runs disagreed with each other — the bar a difference between the
     *  two paths has to clear before it means anything. */
    private static long spread(long[] runs) { return max(runs) - min(runs); }
}
