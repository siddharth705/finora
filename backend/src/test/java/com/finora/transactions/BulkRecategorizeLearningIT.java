package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.MerchantLearningEventWorker;
import com.finora.service.MerchantLearningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

/**
 * WI1A, end to end: bulk recategorization no longer shares a transaction with the learning it earns.
 *
 * <p>{@code TransactionService.bulkRecategorize} was the last synchronous batch learning path. It
 * called {@code CategorizationService.learn} inline, once per id, up to
 * {@code TransactionDto.MAX_BULK_IDS} (500) times inside one transaction — the import path's exact
 * pre-WI1 shape. {@code MerchantLearningService.confirm} does a check-then-act against
 * {@code UNIQUE(user_id, merchant_id, category_id)}, so one lost race threw a constraint violation
 * that poisoned the transaction and discarded all 500 recategorizations, including the 499 that had
 * nothing to do with the merchant that lost.
 *
 * <p><b>An integration test rather than a unit test, for the reason the milestone's own testing
 * section gives.</b> Everything here — that a poisoned transaction rolls back, that the event row
 * shares the caller's unit of work, that the failure is recorded outside it — is invisible to a
 * mock by construction. {@code BudgetServiceTest} and {@code BootstrapServiceTest} both asserted
 * transaction-boundary behaviour that could not happen and passed for as long as they existed. A
 * mocked version of this class would pass against the code it replaces.
 *
 * @see com.finora.imports.MerchantLearningImportIT the same properties for the import path (WI1)
 */
@TestPropertySource(properties = "app.learning.queue.enabled=false")
class BulkRecategorizeLearningIT extends AbstractIntegrationTest {

    // The scheduler and the async nudge are off so these tests drive the queue deterministically
    // through drainOnce(), which deliberately does not consult the flag. Left on, the nudge fired
    // after bulkRecategorize commits drains the events on another thread before the assertions run
    // -- which is the queue working correctly, and makes the test meaningless.

    @Autowired private TransactionService transactionService;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantLearningEventRepository eventRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    /** Real by default; told to throw only where a learning failure is the subject. See
     *  {@code MerchantLearningQueueIT} for why the failure cannot be induced by deleting the
     *  category — V62 cascades the event away instead of making its apply fail. */
    @SpyBean private MerchantLearningService learningService;

    /** Five distinct merchants, one shared target category — the shape where "one row's learning
     *  failed" and "the batch survived" can be told apart. All five tokens differ, so
     *  {@code MerchantNormalizationEngine} resolves five separate merchants rather than collapsing
     *  them onto one. */
    private static final List<String> DESCRIPTIONS = List.of(
            "SWIGGY BANGALORE", "ZOMATO BANGALORE", "AMAZON RETAIL",
            "FLIPKART INTERNET", "RETAILER DESIGNS");

    private static final String TARGET_CATEGORY = "Groceries";

    private record Fixture(User user, Account account, Category startingCategory,
                            List<UUID> transactionIds) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("bulk-recat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Bulk Recategorize IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Bulk Recategorize IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        Account savedAccount = accountRepository.save(account);

        Category starting = new Category();
        starting.setUserId(savedUser.getId());
        starting.setName("Shopping " + UUID.randomUUID());
        Category savedCategory = categoryRepository.save(starting);

        // Saved directly rather than through TransactionService.create(), which learns
        // synchronously on its explicit-category branch -- seeding through it would put learning
        // rows in the database before the action under test has run, and "nothing was applied
        // inline" is one of the things being asserted.
        List<UUID> ids = DESCRIPTIONS.stream().map(description -> {
            Transaction t = new Transaction();
            t.setUserId(savedUser.getId());
            t.setAccountId(savedAccount.getId());
            t.setCategoryId(savedCategory.getId());
            t.setTxnDate(LocalDate.of(2026, 7, 10));
            t.setDescription(description);
            t.setAmount(new BigDecimal("486.00"));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setNeedsCategoryReview(true);
            return transactionRepository.save(t).getId();
        }).toList();

        return new Fixture(savedUser, savedAccount, savedCategory, ids);
    }

    // --- 1. The batch commits; learning is queued, not applied ---------------------------------

    @Test
    void bulkRecategorizeQueuesItsLearningAndAppliesNothingInline() {
        Fixture f = fixture();

        transactionService.bulkRecategorize(f.user().getId(), f.transactionIds(), TARGET_CATEGORY);

        // Recategorized and committed. The gap between here and the drain below IS the fix:
        // learning is no longer part of the bulk action's unit of work.
        assertThat(transactionsFor(f)).hasSize(DESCRIPTIONS.size())
                .allSatisfy(t -> assertThat(t.getCategoryId()).isEqualTo(targetCategoryId(f)));
        assertThat(eventsFor(f)).hasSize(DESCRIPTIONS.size()).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING));
        assertThat(learningRowsFor(f)).isEmpty();

        drainUntilSettled(f);

        assertThat(learningRowsFor(f)).hasSize(DESCRIPTIONS.size());
        assertThat(eventsFor(f)).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED));
    }

    /**
     * A bulk recategorization is not an import and had no staging session, so both source ids are
     * null — stated rather than invented. The admin queue's projection LEFT JOINs both, so the row
     * still renders; an operator following a link to a statement that never existed would
     * reasonably conclude the row is corrupt.
     */
    @Test
    void aQueuedBulkEventClaimsNoStatementAndNoSession() {
        Fixture f = fixture();

        transactionService.bulkRecategorize(f.user().getId(), f.transactionIds(), TARGET_CATEGORY);

        assertThat(eventsFor(f)).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getSourceStatementImportId()).isNull();
            assertThat(e.getSourceImportSessionId()).isNull();
        });
    }

    // --- 2. One lost race no longer discards the whole batch -----------------------------------

    /**
     * Bug 02's shape, reproduced against this path and shown to be gone.
     *
     * <p>Every other test here injects the failure AFTER the batch has committed, which measures
     * the queue. This one injects it BEFORE, which measures the batch: {@code confirm} is armed to
     * throw for every merchant, and then {@code bulkRecategorize} is called. Under the old shape
     * that violation was raised from inside the batch's own transaction on the very first row, and
     * the user's five recategorizations became zero. Now the batch never calls {@code confirm} at
     * all — it queues — so an armed failure cannot reach it.
     *
     * <p>Asserted as an outcome rather than as {@code verify(never())}, deliberately. A
     * "confirm was not called" assertion passes if the call is merely moved somewhere else that
     * still shares the transaction; "the rows are all recategorized despite confirm being broken"
     * only passes if the transaction boundary is genuinely where this work item put it.
     */
    @Test
    void aLearningFailureArmedBeforeTheBatchCannotReachIt() {
        Fixture f = fixture();
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(learningService).confirm(any(), any(), any());

        transactionService.bulkRecategorize(f.user().getId(), f.transactionIds(), TARGET_CATEGORY);

        // Previously: an exception out of bulkRecategorize and zero rows changed.
        assertThat(transactionsFor(f)).hasSize(DESCRIPTIONS.size())
                .allSatisfy(t -> assertThat(t.getCategoryId()).isEqualTo(targetCategoryId(f)));
        assertThat(eventsFor(f)).hasSize(DESCRIPTIONS.size()).allSatisfy(e ->
                assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING));
    }

    /**
     * The defect this work item exists to remove, in its most direct form.
     *
     * <p>Exactly one of the five merchants' confirmations fails, the way a single lost race against
     * {@code UNIQUE(user_id, merchant_id, category_id)} fails. Under the old shape that violation
     * was raised inside {@code bulkRecategorize}'s own transaction, which marks it rollback-only —
     * so all five recategorizations were discarded and the user's bulk action silently did nothing.
     *
     * <p>Now the failure is confined to the one event that caused it: the other four are applied,
     * all five recategorizations stand, and the failed one is recorded and scheduled for retry
     * rather than lost.
     */
    @Test
    void oneMerchantsLearningFailingLeavesEveryRecategorizationIntact() {
        Fixture f = fixture();
        transactionService.bulkRecategorize(f.user().getId(), f.transactionIds(), TARGET_CATEGORY);

        UUID doomedMerchantId = eventsFor(f).get(0).getMerchantId();
        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(learningService).confirm(any(), eq(doomedMerchantId), any());

        drainUntilSettled(f);

        // Previously: zero. Every row the user asked to recategorize is still recategorized.
        assertThat(transactionsFor(f)).hasSize(DESCRIPTIONS.size())
                .allSatisfy(t -> assertThat(t.getCategoryId()).isEqualTo(targetCategoryId(f)));

        // The four unaffected merchants learned normally -- a failure is contained to its own
        // event, not to the pass that happened to contain it.
        assertThat(learningRowsFor(f)).hasSize(DESCRIPTIONS.size() - 1);

        // And the one that failed is recorded, not lost: it survives at all only because the
        // failure was written in a transaction the violation had not poisoned.
        assertThat(eventsFor(f)).filteredOn(e -> e.getMerchantId().equals(doomedMerchantId))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
                    assertThat(e.getAttemptCount()).isEqualTo(1);
                    assertThat(e.getLastError()).contains("DataIntegrityViolationException");
                    assertThat(e.getNextAttemptAt()).isAfter(Instant.now());
                });
        assertThat(eventsFor(f)).filteredOn(e -> !e.getMerchantId().equals(doomedMerchantId))
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.COMPLETED));
    }

    /** Total failure of learning is survivable too, and is still the correct trade: the user's
     *  recategorizations all stand and a human can see the failures in the admin queue. */
    @Test
    void everyMerchantsLearningFailingStillLeavesEveryRecategorizationIntact() {
        Fixture f = fixture();
        transactionService.bulkRecategorize(f.user().getId(), f.transactionIds(), TARGET_CATEGORY);

        doThrow(new DataIntegrityViolationException("everything lost its race"))
                .when(learningService).confirm(any(), any(), any());
        drainUntilSettled(f);

        assertThat(transactionsFor(f)).hasSize(DESCRIPTIONS.size())
                .allSatisfy(t -> assertThat(t.getCategoryId()).isEqualTo(targetCategoryId(f)));
        assertThat(learningRowsFor(f)).isEmpty();
        assertThat(eventsFor(f)).hasSize(DESCRIPTIONS.size()).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
            assertThat(e.getAttemptCount()).isEqualTo(1);
        });
    }

    // --- 3. The other direction: a rolled-back batch leaves no queued work ----------------------

    /**
     * The mirror property, and the one that makes joining the caller's transaction load-bearing
     * rather than incidental.
     *
     * <p>The event rows are written INSIDE {@code bulkRecategorize}'s transaction, so a batch that
     * fails part-way takes its queued learning with it. Written outside, a worker would later apply
     * confirmations for recategorizations that never happened — a stronger failure than the one
     * this design replaces, because nothing downstream would ever notice it.
     *
     * <p>Driven by an id the caller does not own, which is the realistic way this batch aborts:
     * {@code getOwned} throws on the second id after the first has already been queued.
     */
    @Test
    void aBatchThatAbortsPartWayLeavesNoQueuedLearningAndNoRecategorization() {
        Fixture f = fixture();
        UUID ownedId = f.transactionIds().get(0);
        UUID notTheirs = UUID.randomUUID();

        assertThatThrownBy(() -> transactionService.bulkRecategorize(
                f.user().getId(), List.of(ownedId, notTheirs), TARGET_CATEGORY))
                .isInstanceOf(ApiException.class);

        assertThat(eventsFor(f)).isEmpty();
        // Nothing half-applied either: the first row kept the category it had.
        assertThat(transactionsFor(f)).allSatisfy(t ->
                assertThat(t.getCategoryId()).isEqualTo(f.startingCategory().getId()));
        // Nor did the merchant resolution the queueing needed survive on its own -- resolve()
        // creates a merchant on a miss, and that write is part of the same rolled-back unit.
        assertThat(merchantRepository.findByUserId(f.user().getId())).isEmpty();

        // Scoped to this fixture rather than asserted as "the queue drained nothing at all":
        // drainOnce() works across the whole table, so a sibling class's leftover event would make
        // a global assertion fail for a reason unrelated to rollback.
        worker.drainOnce();
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

    /** The category {@code bulkRecategorize} resolved or created for this user. Looked up rather
     *  than seeded, because resolving it is part of the behaviour under test. */
    private UUID targetCategoryId(Fixture f) {
        return categoryRepository.findByUserIdAndName(f.user().getId(), TARGET_CATEGORY)
                .orElseThrow(() -> new AssertionError("bulkRecategorize did not create " + TARGET_CATEGORY))
                .getId();
    }

    /**
     * Drains until THIS fixture's events are no longer pending, or gives up.
     *
     * <p>{@code drainOnce()} claims a bounded batch (50) across the whole table, ordered by
     * {@code next_attempt_at}. Run alone, one call covers five rows; run in the full suite, sibling
     * classes' events fill the batch first and this fixture's newest rows are left behind. The
     * batch bound is correct — it is what stops a backlog holding a connection — so the test has to
     * drain until its own work is done rather than assume one pass suffices.
     */
    private void drainUntilSettled(Fixture f) {
        for (int pass = 0; pass < 20; pass++) {
            boolean anyPending = eventsFor(f).stream()
                    .anyMatch(e -> e.getStatus() == MerchantLearningEvent.Status.PENDING
                            && !e.getNextAttemptAt().isAfter(Instant.now()));
            if (!anyPending) return;
            if (worker.drainOnce() == 0) return;
        }
    }
}
