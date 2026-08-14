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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-018. {@code ImportJobService}'s class comment states the order of operations as
 * "<b>Store the bytes</b> — outside the transaction. Object storage cannot participate in one, and
 * holding a database transaction open across a network upload would tie up a connection from a
 * pool capped at 10 for the duration." The method was {@code @Transactional} over its whole body
 * and stored the bytes inside it.
 *
 * <p>An inline comment conceded that and argued it was harmless because the store is a network
 * write that touches no database. That is true only while no JDBC statement has been issued
 * first — it rests on Hibernate's delayed connection acquisition, which is a property of every
 * caller above the method and of Hibernate's configuration, not of anything the method controls.
 * The claim was correct about what should happen and wrong about what did; nothing anywhere
 * enforced the difference.
 *
 * <p>This asserts the ordering itself rather than its consequence. Counting pool connections would
 * be measuring the symptom, and it would pass against the old code too — delayed acquisition means
 * no connection had been taken yet on the path the endpoint actually uses. The defect was never
 * that a connection was held today; it was that whether one was held depended on configuration and
 * on callers, with nothing saying so. "Is a transaction open while the upload runs" is the property
 * the comment promises, and it is the one that answers differently.
 *
 * <p><b>What this does not guarantee</b>, stated because the test would otherwise imply it: a
 * <em>caller</em> that wraps {@code accept()} in its own transaction still holds one across the
 * upload. Removing {@code @Transactional} means this method no longer opens one; it cannot stop
 * someone above it from doing so. No caller does today — {@code ImportJobController.submit} is not
 * transactional — and the case is covered here as a negative so the distinction is on the record
 * rather than implied.
 */
@TestPropertySource(properties = "app.import.queue.enabled=false")
@Import(ImportJobStoreOutsideTransactionIT.ObservingStorageConfig.class)
class ImportJobStoreOutsideTransactionIT extends AbstractIntegrationTest {

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            """;

    /**
     * Replaces the real storage so the assertion can be taken <em>during</em> the upload rather
     * than inferred afterwards. Nothing else about the path is simulated: the service, the
     * repository, the transaction manager and Postgres are all real.
     */
    static class ObservingStorage implements StatementStorage {
        final AtomicBoolean transactionActiveDuringStore = new AtomicBoolean();
        final AtomicInteger storeCalls = new AtomicInteger();

        // BH-018. accept() calls the streaming overload directly (it no longer holds the upload
        // as a byte[] at all), so this is the one that must observe -- overriding store(byte[])
        // instead would silently stop exercising the real call path this test asserts about.
        @Override
        public ContentAddress store(java.io.InputStream content, long contentLength) {
            transactionActiveDuringStore.set(TransactionSynchronizationManager.isActualTransactionActive());
            storeCalls.incrementAndGet();
            try {
                return ContentAddress.forContent(content.readAllBytes());
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override public byte[] retrieve(ContentAddress address) { return new byte[0]; }
        @Override public boolean exists(ContentAddress address) { return true; }
        @Override public void delete(String objectKey) { /* not exercised by this test */ }
    }

    @TestConfiguration
    static class ObservingStorageConfig {
        @Bean @Primary ObservingStorage observingStorage() { return new ObservingStorage(); }
    }

    @Autowired private ObservingStorage storage;
    @Autowired private ImportJobService jobService;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private User owner;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("store-outside-tx-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Store Outside Tx IT User");
        user.setPhoneVerified(true);
        owner = userRepository.save(user);
        storage.transactionActiveDuringStore.set(false);
        storage.storeCalls.set(0);
    }

    private MockMultipartFile upload() {
        return new MockMultipartFile("file", "statement-" + UUID.randomUUID() + ".csv", "text/csv",
                (CSV + UUID.randomUUID() + ",PAD,1.00,DEBIT\n").getBytes(StandardCharsets.UTF_8));
    }

    /** The documented step 2. Fails the moment {@code @Transactional} returns to {@code accept}. */
    @Test
    void theUploadToObjectStorageRunsWithNoTransactionOpen() throws Exception {
        jobService.accept(owner.getId(), upload(), StatementUpload.Format.CSV);

        assertThat(storage.storeCalls.get())
                .as("the observation has to have been taken at all")
                .isEqualTo(1);
        assertThat(storage.transactionActiveDuringStore.get())
                .as("class comment, step 2: the bytes are stored OUTSIDE the transaction")
                .isFalse();
    }

    /** The documented step 3, which the change must not have cost. The job row is still written,
     *  and still written transactionally — the boundary moved, it did not disappear. */
    @Test
    void theJobRowIsStillWrittenAndTheDedupCheckStillRuns() throws Exception {
        MockMultipartFile file = upload();

        ImportJob first = jobService.accept(owner.getId(), file, StatementUpload.Format.CSV);
        ImportJob second = jobService.accept(owner.getId(), file, StatementUpload.Format.CSV);

        assertThat(jobRepository.findById(first.getId())).isPresent();
        assertThat(second.getId())
                .as("BH-019's live-job dedup reads and writes in one transaction; splitting the "
                        + "store out must not have split that too")
                .isEqualTo(first.getId());
    }

    /**
     * The honest negative. A caller that opens its own transaction still holds one across the
     * upload, and this records that rather than leaving the class comment sounding unconditional.
     *
     * <p>It is not a defect today: nothing calls {@code accept()} transactionally, and making the
     * guarantee unconditional would mean suspending the caller's transaction for the store, which
     * is a larger change than the finding asks for and would need its own reasoning about the
     * orphaned-object trade.
     */
    @Test
    void aTransactionalCallerStillHoldsOneAcrossTheUploadAndThatIsUnchanged() {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                jobService.accept(owner.getId(), upload(), StatementUpload.Format.CSV);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(storage.transactionActiveDuringStore.get())
                .as("removing @Transactional stops this method opening one; it cannot stop a caller")
                .isTrue();
    }
}
