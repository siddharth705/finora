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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;
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
 * BH-053. {@code MerchantLearningService.confirm}'s check-then-act race is described precisely by
 * two comments and asserted by nothing. This class is the missing assertion.
 *
 * <p>Two separate properties are pinned here, and they are separate on purpose:
 *
 * <ol>
 *   <li><b>The race is real and it takes the caller down.</b> {@code confirm()} reads the
 *       merchant's pairs, decides in Java whether one exists, and inserts if not — against V7's
 *       {@code UNIQUE(user_id, merchant_id, category_id)}. The first test drives two callers
 *       through that window and shows the loser's whole transaction failing, not just its
 *       learning write.</li>
 *   <li><b>{@code confirm()} joins the caller's transaction, and must keep doing so.</b> The
 *       remaining tests fail if anyone applies the {@code Propagation.REQUIRES_NEW} that the class
 *       comment used to prescribe and {@code confirm()}'s own Javadoc now warns against.</li>
 * </ol>
 *
 * <p><b>The first test asserts a defect, deliberately.</b> It is not a guard against a regression;
 * it is the reproduction the finding asked for, and it will need rewriting the day the race is
 * actually closed — at which point it should assert that the loser's confirmation is *not* lost,
 * which is the behaviour a fix has to produce. Its value until then is that the exposure is a
 * checked fact rather than a claim in a comment, and that anyone attempting a fix has something to
 * run against it.
 *
 * <p><b>Why the other two tests are the ones that catch the tempting wrong fix.</b>
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
    @SpyBean private ConfidenceEngine confidenceEngine;

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

    // --- 1. The race itself ------------------------------------------------------------------

    /**
     * Two first-ever confirmations of the same (user, merchant, category), interleaved so that
     * both read before either writes. One of them loses.
     *
     * <p>The harm is not that a confirmation is dropped — it is *whose* failure it is.
     * {@code confirm()} joins the caller's transaction, so the unique violation poisons everything
     * the caller had done. On {@code ImportService.confirm} that is every transaction insert for a
     * statement the user has already reviewed, discarded because two categorizations of one
     * merchant happened to overlap.
     */
    @Test
    void twoCallersInsideTheCheckThenActWindowCollideAndTheLoserLosesItsWholeTransaction() throws Exception {
        Fixture f = committedFixture();

        CountDownLatch loserHasRead = new CountDownLatch(1);
        CountDownLatch winnerHasCommitted = new CountDownLatch(1);
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        // Only the thread parked inside the window is held; the winner runs through the same spy
        // untouched, so nothing about its path is simulated.
        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals(LOSER_THREAD)) {
                loserHasRead.countDown();
                assertThat(winnerHasCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(confidenceEngine).topCategory(anyList());

        Thread loser = new Thread(() -> {
            try {
                learningService.confirm(f.user().getId(), f.merchant().getId(), f.category().getId());
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        }, LOSER_THREAD);
        loser.start();

        assertThat(loserHasRead.await(30, TimeUnit.SECONDS))
                .as("the loser must actually be parked between its read and its write")
                .isTrue();

        // The winner runs start to finish and commits while the loser is still holding the empty
        // answer its decision was based on.
        learningService.confirm(f.user().getId(), f.merchant().getId(), f.category().getId());
        winnerHasCommitted.countDown();
        loser.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(loser.isAlive()).isFalse();

        assertThat(loserFailure.get())
                .as("the lost race surfaces to the caller -- it is neither swallowed nor retried")
                .isInstanceOf(DataAccessException.class);
        assertThat(rootCauseOf(loserFailure.get()).getMessage())
                .as("and it is V7's unique constraint that rejects it, not something else")
                .contains("merchant_category_learning");

        // The loser's confirmation is gone, not merged. This is the assertion that has to change
        // when the race is closed: a fix must make this 2.
        List<MerchantCategoryLearning> distribution =
                learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId());
        assertThat(distribution).singleElement()
                .satisfies(pair -> assertThat(pair.getConfirmationCount()).isEqualTo(1));
    }

    private static final String LOSER_THREAD = "bh-053-loser";

    private static Throwable rootCauseOf(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

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
