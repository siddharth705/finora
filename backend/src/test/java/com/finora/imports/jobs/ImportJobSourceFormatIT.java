package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.StatementUpload;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
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
 * BH-029. Which parser a queued job runs is a recorded fact on its row, not a function of its
 * filename re-evaluated wherever someone needs the answer.
 *
 * <p>Before this, {@code ImportJobService.formatOf(fileName)} was called twice: once at upload, to
 * choose what {@code StatementUpload} validated the bytes against, and once in
 * {@code ImportJobWorker.stage()}, minutes later, to choose the parser. They agreed — but by
 * construction, because both read the same string through the same function, which is a different
 * thing from the format having been decided. {@code statement_imports.source_format} (V36) exists
 * for exactly this reason on the confirmed import and says so in its own comment; the job that
 * produces it did not have one.
 *
 * <p><b>How these tests can tell the two designs apart.</b> A test that uploads a CSV named
 * {@code .csv} and a PDF named {@code .pdf} passes under either design and proves nothing. So the
 * first two cases deliberately store a row whose {@code file_name} and {@code source_format}
 * <em>disagree</em>, which is the only arrangement where the two implementations answer
 * differently, and assert that the column wins. Reverting the worker to
 * {@code formatOf(job.getFileName())} flips both.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-import-source-format-it",
        "app.import.queue.enabled=false"
})
class ImportJobSourceFormatIT extends AbstractIntegrationTest {

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            """;

    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private ImportJobStore jobStore;
    @Autowired private ImportJobService jobService;
    @Autowired private ImportJobWorker worker;

    /** BH-058. The suite shares one import_jobs table and leaves jobs QUEUED in it; drainOnce()
     *  claims only the oldest ImportJobStore.BATCH_SIZE of them, so without this the job each test
     *  enqueues below can fall outside the batch and never run. See ImportJobQueueBacklog. */
    @BeforeEach
    void emptyTheQueueTheRestOfTheSuiteLeftBehind() {
        ImportJobQueueBacklog.empty(worker);
    }
    @Autowired private StatementStorage storage;

    private User user() {
        User user = new User();
        user.setEmail("source-format-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Source Format IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A job over real CSV bytes, with the two fields set independently so a test can make them
     *  disagree. The filename is always ".csv" so that the FILENAME-derived answer is always CSV,
     *  and only the column varies. */
    private ImportJob queuedCsvBytes(User owner, StatementUpload.Format declaredFormat) {
        ContentAddress address = storage.store(CSV.getBytes(StandardCharsets.UTF_8));
        return jobStore.enqueue(owner.getId(), "statement.csv", address.hash(), address.key(), declaredFormat);
    }

    /**
     * The column decides, and here it decides wrongly on purpose.
     *
     * <p>CSV bytes, a {@code .csv} filename, and {@code source_format = PDF}. The worker must hand
     * these bytes to the PDF parser and fail, because that is what the row says to do. Under the
     * previous design the filename said CSV and this job would have imported cleanly — so a
     * COMPLETED here is precisely the old behaviour returning.
     *
     * <p>Asserted as "did not import, and recorded why" rather than as {@code FAILED}. The first
     * draft expected FAILED and got QUEUED, which is the worker being right: one parse failure is
     * retryable and dead-lettering waits for {@code MAX_ATTEMPTS}. Pinning the retry status here
     * would make this test fail the day that policy changes, for a reason that has nothing to do
     * with BH-029.
     */
    @Test
    void theWorkerParsesByTheStoredFormatEvenWhenTheFilenameDisagrees() {
        User owner = user();
        ImportJob queued = queuedCsvBytes(owner, StatementUpload.Format.PDF);

        worker.drainOnce();

        ImportJob after = jobRepository.findById(queued.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("PDF was recorded, so the PDF parser must have been given CSV bytes and failed. "
                        + "COMPLETED means the worker went back to reading the filename")
                .isNotEqualTo(ImportJob.Status.COMPLETED);
        assertThat(after.getAttemptCount())
                .as("and it must have actually been attempted, not skipped")
                .isEqualTo(1);
        assertThat(after.getLastError())
                .as("with the parser's own complaint recorded")
                .isNotBlank();
    }

    /**
     * The control, and it is load-bearing rather than decoration.
     *
     * <p>Without it, the case above is equally consistent with "this fixture cannot import at all"
     * — a broken CSV, missing storage, a worker that fails everything. Identical bytes, identical
     * filename, only the column changed, and the job completes. So the failure above is caused by
     * the format and by nothing else.
     */
    @Test
    void theSameBytesUnderTheStoredCsvFormatImportCleanly() {
        User owner = user();
        ImportJob queued = queuedCsvBytes(owner, StatementUpload.Format.CSV);

        worker.drainOnce();

        ImportJob after = jobRepository.findById(queued.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("same bytes, same name, CSV recorded -- if this fails the fixture is broken and "
                        + "the case above proves nothing")
                .isEqualTo(ImportJob.Status.COMPLETED);
    }

    /**
     * The write half: the format the upload was validated against is what lands on the row.
     *
     * <p>Asserted through {@code accept()} rather than by calling {@code formatOf} and comparing it
     * to itself, which would be a tautology. The value handed in is the one the endpoint used for
     * {@code StatementUpload.requireReadable}, so this pins that the accepted format and the stored
     * format are one evaluation rather than two that have to stay in agreement.
     */
    @Test
    void acceptPersistsTheFormatTheUploadWasValidatedAgainst() throws Exception {
        User owner = user();
        MockMultipartFile upload = new MockMultipartFile(
                "file", "statement.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));

        ImportJob accepted = jobService.accept(owner.getId(), upload, StatementUpload.Format.CSV);

        assertThat(jobRepository.findById(accepted.getId()).orElseThrow().getSourceFormat())
                .isEqualTo("CSV");
    }

    /** And the column is NOT NULL, so nothing can enqueue work whose parser is unknown -- the
     *  nullable-with-a-filename-fallback shape would have left the old behaviour alive on a branch
     *  nothing exercises. */
    @Test
    void everyQueuedJobCarriesAFormat() {
        User owner = user();
        ImportJob queued = queuedCsvBytes(owner, StatementUpload.Format.PDF);

        assertThat(jobRepository.findById(queued.getId()).orElseThrow().getSourceFormat())
                .isNotBlank();
    }
}
