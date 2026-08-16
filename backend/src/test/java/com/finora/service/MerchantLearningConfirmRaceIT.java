package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * BH-053. {@code MerchantLearningService.confirm}'s check-then-act race, closed. This class both
 * proves the fix and pins the propagation contract any future fix here must keep honouring.
 *
 * <p>Three properties are pinned, and they are separate on purpose:
 *
 * <ol>
 *   <li><b>The race is closed: two concurrent first-ever confirmations both survive.</b> Neither
 *       caller's transaction fails, and the merchant ends up with one pair at
 *       {@code confirmationCount = 2} — not one winner and one lost update. See this test's own
 *       history in git blame for what this class asserted before the fix landed: the loser's
 *       whole transaction failing on V7's {@code UNIQUE(user_id, merchant_id, category_id)}. The
 *       synchronization point below had to move for exactly that reason — the old check-then-act
 *       window doesn't exist to pause inside anymore, because closing the race removed it. What
 *       serializes the two callers now is a real Postgres row lock inside
 *       {@code ensurePairExists}'s {@code INSERT ... ON CONFLICT}, not application code, so this
 *       test proves it by holding one caller's transaction open past its insert and showing the
 *       other's call — genuinely blocked at the database, not simulated — resolves correctly once
 *       the first commits.</li>
 *   <li><b>{@code confirm()} joins the caller's transaction, and must keep doing so.</b> The
 *       remaining tests fail if anyone applies the {@code Propagation.REQUIRES_NEW} that the class
 *       comment used to prescribe and {@code confirm()}'s own Javadoc now warns against.</li>
 * </ol>
 *
 * <p><b>Why the propagation tests are the ones that catch the tempting wrong fix.</b>
 * {@code REQUIRES_NEW} looks like it isolates the race. It does not — it converts a rare collision
 * into a constant failure, because {@code merchant_category_learning} carries {@code NOT NULL}
 * foreign keys into {@code merchants} and {@code categories}, and on the real call path
 * ({@code CategorizationService.learn}) both parent rows are routinely created in the caller's
 * still-uncommitted transaction. A suspended-and-restarted inner transaction cannot see them. That
 * is invisible to a mocked test and to any test whose fixture commits its merchant first, which is
 * why these run against a real Postgres and commit nothing before calling.
 */
class MerchantLearningConfirmRaceIT extends AbstractIntegrationTest {

    @Autowired private MerchantLearningService learningService;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private MerchantLearningAuditRepository auditRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * Real behaviour by default. Spied only to get a hook at the one instruction that matters:
     * {@code topCategory} is called between {@code confirm()}'s read and its write, so blocking it
     * parks a caller exactly inside the check-then-act window. A {@code CyclicBarrier} on the two
     * {@code confirm()} calls would be racing the race — it would pass or fail on scheduler luck.
     */
    @MockitoSpyBean private ConfidenceEngine confidenceEngine;

    private record Fixture(User user, Merchant merchant, Category category) {}

    /** Committed before the test body runs — the race test needs both callers to see the same
     *  already-visible parents, so that the only thing they collide over is the learning row. */
    private Fixture committedFixture() {
        User user = newUser();
        userRepository.save(user);
        Merchant merchant = newMerchant(user.getId());
        merchantRepository.save(merchant);
        Category category = newCategory(user.getId());
        categoryRepository.save(category);
        return new Fixture(user, merchant, category);
    }

    private User newUser() {
        User user = new User();
        user.setEmail("confirm-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirm Race IT User");
        user.setPhoneVerified(true);
        return user;
    }

    private Merchant newMerchant(UUID userId) {
        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setCanonicalName("Race Merchant " + UUID.randomUUID());
        return merchant;
    }

    private Category newCategory(UUID userId) {
        Category category = new Category();
        category.setUserId(userId);
        category.setName("Race Category " + UUID.randomUUID());
        return category;
    }

    // --- 1. The race, closed -----------------------------------------------------------------

    /**
     * Two first-ever confirmations of the same (user, merchant, category), genuinely overlapping.
     * Both survive; the merchant ends up with one pair carrying both confirmations.
     *
     * <p>The FIRST caller is parked with its {@code ensurePairExists} insert already issued but
     * its transaction still open -- the row lock behind V7's
     * {@code UNIQUE(user_id, merchant_id, category_id)} is held for real, not simulated. The
     * SECOND caller is started on its own thread specifically because it is expected to block
     * inside the database for as long as that lock is held; calling it on the test thread would
     * hang the test on the same block. Releasing the first caller lets its transaction commit,
     * which is what unblocks the second's {@code INSERT ... ON CONFLICT} to see the now-committed
     * row and take the {@code DO NOTHING} branch -- at which point it reads the up-to-date count
     * and increments it correctly, rather than colliding with it.
     */
    @Test
    void twoCallersRacingOnTheSameBrandNewPairBothSucceed_confirmationCountEndsAtTwo() throws Exception {
        Fixture f = committedFixture();

        CountDownLatch firstHasInsertedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        // topCategory is still the right hook -- still called right after ensurePairExists, just
        // no longer before the write (there is no more check-then-act window to sit inside); this
        // pauses the first caller after its write, before its transaction commits.
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasInsertedButNotCommitted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(confidenceEngine).topCategory(anyList());

        Thread first = new Thread(() -> {
            try {
                learningService.confirm(f.user().getId(), f.merchant().getId(), f.category().getId());
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasInsertedButNotCommitted.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked with its insert in place, uncommitted")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                learningService.confirm(f.user().getId(), f.merchant().getId(), f.category().getId());
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, SECOND_THREAD);
        second.start();

        // No signal available from this side of the process for "the second caller is now
        // blocked inside the database" -- give it a moment to actually reach and issue its
        // INSERT before releasing the first caller, so the two genuinely overlap rather than
        // running sequentially by accident.
        Thread.sleep(500);

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).as("the first caller must have finished, not hung").isFalse();
        assertThat(second.isAlive()).as("the second caller must have finished, not hung").isFalse();

        assertThat(firstFailure.get()).as("neither caller's transaction may fail").isNull();
        assertThat(secondFailure.get()).as("neither caller's transaction may fail").isNull();

        List<MerchantCategoryLearning> distribution =
                learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId());
        assertThat(distribution).singleElement()
                .as("one pair, both confirmations counted -- not a winner and a lost update")
                .satisfies(pair -> assertThat(pair.getConfirmationCount()).isEqualTo(2));

        List<MerchantLearningAudit> audit =
                auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(f.user().getId(), f.merchant().getId());
        assertThat(audit).as("both confirmations produced their own audit entry").hasSize(2);
    }

    private static final String FIRST_THREAD = "bh-053-first";
    private static final String SECOND_THREAD = "bh-053-second";

    // --- 2. The propagation contract ---------------------------------------------------------

    /**
     * The reason {@code REQUIRES_NEW} is not available as a fix, expressed as a test.
     *
     * <p>The merchant and the category are written but <b>not committed</b> when {@code confirm()}
     * is called — which is not a contrived arrangement, it is what
     * {@code CategorizationService.learn} does on every first-time merchant. Joining the caller's
     * transaction is what lets the foreign keys resolve. A suspended inner transaction would see
     * neither parent and fail on {@code merchant_category_learning_merchant_id_fkey}.
     *
     * <p>They are flushed rather than merely persisted so the test does not quietly depend on
     * Hibernate's insert ordering: under the current behaviour the rows are already in the
     * database (invisible to anyone else), so the only thing that can make the child insert fail
     * is being issued from a different transaction.
     */
    @Test
    void confirmSeesParentRowsTheCallerHasNotCommittedYet() {
        User user = userRepository.save(newUser());

        UUID learningRowId = transactionTemplate.execute(status -> {
            Merchant merchant = merchantRepository.saveAndFlush(newMerchant(user.getId()));
            Category category = categoryRepository.saveAndFlush(newCategory(user.getId()));

            MerchantLearningService.LearningResult result =
                    learningService.confirm(user.getId(), merchant.getId(), category.getId());

            assertThat(result.distribution())
                    .as("a first-time merchant's very first confirmation, from inside the "
                            + "transaction that created the merchant")
                    .singleElement()
                    .satisfies(pair -> assertThat(pair.getCategoryId()).isEqualTo(category.getId()));
            return result.distribution().get(0).getId();
        });

        assertThat(learningRepository.findById(learningRowId))
                .as("and it is durable once the caller commits")
                .isPresent();
    }

    /**
     * The other half of "joins the caller's transaction": the caller's rollback must take the
     * learning write with it.
     *
     * <p>Under {@code REQUIRES_NEW} the inner transaction commits independently, so a merchant
     * would be recorded as confirmed for an import that then failed and wrote no transactions at
     * all — learning drawn from evidence that does not exist. The audit row is asserted alongside
     * it because it is written in the same method and would survive the same way.
     */
    @Test
    void aCallerRollbackAfterConfirmTakesTheLearningAndItsAuditRowWithIt() {
        Fixture f = committedFixture();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                learningService.confirm(f.user().getId(), f.merchant().getId(), f.category().getId());
                throw new IllegalStateException("the caller fails after learning was recorded");
            });
        } catch (IllegalStateException expected) {
            // the point of the test
        }

        assertThat(learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId()))
                .as("no learning may survive the transaction whose evidence it came from")
                .isEmpty();
        List<MerchantLearningAudit> audit =
                auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(f.user().getId(), f.merchant().getId());
        assertThat(audit)
                .as("nor may the audit entry that claims it happened")
                .isEmpty();
    }
}
