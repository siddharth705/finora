package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.MerchantLearningEventWorker;
import com.finora.service.MerchantLearningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * WI1, end to end: merchant learning no longer shares a transaction with the import that earns it.
 *
 * <p>This is the test that makes Deliverable 0 more than infrastructure. A queue that nothing puts
 * work into passes every one of its own tests and changes nothing in production, so the assertions
 * here are deliberately about the IMPORT's outcome rather than the queue's internals: what survives
 * when learning fails is the question Bug 02 was really about.
 *
 * <p>Bug 02, restated as the thing being prevented: {@code MerchantLearningService.confirm} does a
 * check-then-act against {@code UNIQUE(user_id, merchant_id, category_id)}. It used to run inside
 * the import transaction, once per confirmed row, so one lost race threw a constraint violation
 * that rolled back every transaction in a statement the user had already reviewed and approved.
 */
@TestPropertySource(properties = "app.learning.queue.enabled=false")
class MerchantLearningImportIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantLearningEventRepository eventRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    /** Real by default; told to throw only where a learning failure is the subject. See
     *  MerchantLearningQueueIT for why the failure cannot be induced by deleting the category. */
    @SpyBean private MerchantLearningService learningService;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("learning-import-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Import IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Import IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return new Fixture(savedUser, accountRepository.save(account));
    }

    /** Two rows for the same merchant and category -- a shape that produces real learning rather
     *  than an unresolved guess, which recordDecision correctly refuses to learn from. */
    private ConfirmRequest twoRowStatement(UUID accountId) {
        ConfirmedRow first = new ConfirmedRow(LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR",
                new BigDecimal("486.00"), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        ConfirmedRow second = new ConfirmedRow(LocalDate.of(2026, 7, 12), "SWIGGY*ORDR7710 BLR",
                new BigDecimal("212.00"), "EXPENSE", "Dining", true, "rule", null, false, null, null);
        return new ConfirmRequest(null, List.of(first, second), accountId, null, null, null);
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    // --- 1. Successful import, successful learning --------------------------------------------

    @Test
    void aSuccessfulImportQueuesItsLearningAndTheWorkerAppliesIt() throws Exception {
        Fixture f = fixture();

        importService.confirm(f.user().getId(), statementFile(), twoRowStatement(f.account().getId()));

        // Nothing applied yet: the import transaction has committed, but the worker has not run.
        // That gap IS the fix -- learning is no longer part of the import's unit of work.
        assertThat(transactionsFor(f)).hasSize(2);
        assertThat(eventsFor(f)).hasSize(2).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING));
        assertThat(learningRowsFor(f)).isEmpty();

        worker.drainOnce();

        assertThat(learningRowsFor(f)).isNotEmpty();
        assertThat(eventsFor(f)).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED));
    }

    /** Each event carries the statement that earned it, so the admin queue (WI2) can answer "which
     *  import produced this" without a join through the merchant. */
    @Test
    void eachQueuedEventIsAttributedToTheStatementThatEarnedIt() throws Exception {
        Fixture f = fixture();

        importService.confirm(f.user().getId(), statementFile(), twoRowStatement(f.account().getId()));

        UUID statementImportId = transactionsFor(f).get(0).getStatementImportId();
        assertThat(statementImportId).isNotNull();
        assertThat(eventsFor(f)).allSatisfy(e ->
                assertThat(e.getSourceStatementImportId()).isEqualTo(statementImportId));
    }

    // --- 2. Successful import, FAILED learning ------------------------------------------------

    /**
     * The whole point of the milestone, asserted the only way that means anything: the
     * transactions are still there.
     *
     * <p>Under the old design this exact scenario destroyed them. The constraint violation
     * propagated out of {@code confirm()} through the per-row loop and rolled back the entire
     * import — every transaction in a statement the user had reviewed, discarded because one
     * merchant-learning row lost a race.
     */
    @Test
    void aLearningFailureLeavesEveryImportedTransactionIntact() throws Exception {
        Fixture f = fixture();
        importService.confirm(f.user().getId(), statementFile(), twoRowStatement(f.account().getId()));

        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(learningService).confirm(any(), any(), any());
        worker.drainOnce();

        // The transactions survive a total failure of learning. Previously: zero.
        assertThat(transactionsFor(f)).hasSize(2);
        // And the failure is not lost either -- it is recorded, and scheduled.
        assertThat(eventsFor(f)).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
            assertThat(e.getAttemptCount()).isEqualTo(1);
            assertThat(e.getLastError()).contains("DataIntegrityViolationException");
            assertThat(e.getNextAttemptAt()).isAfter(Instant.now());
        });
    }

    // --- 3. A retry succeeds ------------------------------------------------------------------

    @Test
    void anEventThatFailedOnceSucceedsWhenTheRetryRuns() throws Exception {
        Fixture f = fixture();
        importService.confirm(f.user().getId(), statementFile(), twoRowStatement(f.account().getId()));

        doThrow(new DataIntegrityViolationException("transient")).when(learningService).confirm(any(), any(), any());
        worker.drainOnce();
        assertThat(learningRowsFor(f)).isEmpty();

        // Whatever was wrong is now fixed, and the backoff has elapsed.
        reset(learningService);
        makeAllDueNow(f);
        worker.drainOnce();

        assertThat(learningRowsFor(f)).isNotEmpty();
        assertThat(eventsFor(f)).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED));
        // The retry cleared the error rather than leaving a stale one on a completed row.
        assertThat(eventsFor(f)).allSatisfy(e -> assertThat(e.getLastError()).isNull());
    }

    // --- 4. Repeated failures become terminal -------------------------------------------------

    @Test
    void anEventThatKeepsFailingEndsUpFailedWithTheImportStillIntact() throws Exception {
        Fixture f = fixture();
        importService.confirm(f.user().getId(), statementFile(), twoRowStatement(f.account().getId()));

        doThrow(new DataIntegrityViolationException("permanently broken"))
                .when(learningService).confirm(any(), any(), any());
        for (int attempt = 0; attempt < MerchantLearningEvent.MAX_ATTEMPTS; attempt++) {
            makeAllDueNow(f);
            worker.drainOnce();
        }

        assertThat(eventsFor(f)).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.FAILED);
            assertThat(e.getAttemptCount()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
        });
        // Learning is permanently lost for this statement, and that is the correct trade: the
        // user's transactions are all still here, and a human can see the failure in the queue.
        assertThat(transactionsFor(f)).hasSize(2);
        assertThat(learningRowsFor(f)).isEmpty();
    }

    // --- helpers ------------------------------------------------------------------------------

    private List<Transaction> transactionsFor(Fixture f) {
        return transactionRepository.findByUserId(f.user().getId());
    }

    private List<MerchantLearningEvent> eventsFor(Fixture f) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getUserId().equals(f.user().getId()))
                .toList();
    }

    private List<?> learningRowsFor(Fixture f) {
        return learningRepository.findByUserId(f.user().getId());
    }

    private void makeAllDueNow(Fixture f) {
        List<MerchantLearningEvent> events = eventsFor(f);
        events.forEach(e -> ReflectionTestUtils.setField(
                e, "nextAttemptAt", Instant.now().minus(1, ChronoUnit.MINUTES)));
        eventRepository.saveAll(events);
    }
}
