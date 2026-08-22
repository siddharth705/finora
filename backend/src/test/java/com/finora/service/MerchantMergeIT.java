package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

/**
 * Merchant merge/undo against a real Postgres instance (via AbstractIntegrationTest's
 * Testcontainers setup), not mocked repositories -- per
 * docs/financial-intelligence-engine-spec.md Milestone C: "Integration tests... cover merge and
 * undo against a real Postgres instance, not mocks -- these two operations are exactly the kind
 * of multi-table consistency logic that's easy to get subtly wrong."
 *
 * The rollback test uses @MockitoSpyBean on TransactionRepository to force a failure at one specific
 * step (step 2, transaction repointing) while every other repository involved is the real bean
 * talking to the real containerized Postgres -- this is what makes it possible to assert "the
 * @Transactional boundary genuinely rolled back everything already written by step 1," which a
 * fully-mocked unit test can't prove (a mocked repository was never going to commit anything in
 * the first place, so there's nothing real to roll back) and a naturally-occurring Postgres
 * constraint violation can't be reliably targeted at this exact step on demand.
 */
class MerchantMergeIT extends AbstractIntegrationTest {

    @Autowired private MerchantService merchantService;
    @Autowired private MerchantLearningService merchantLearningService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private MerchantAliasRepository merchantAliasRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private MerchantLearningAuditRepository auditRepository;
    @Autowired private CategoryRepository categoryRepository;
    @MockitoSpyBean private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private UUID userId;
    private UUID accountId;
    private UUID shoppingCategoryId;
    private final UUID actingAdminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("merchant-merge-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Merge Test User");
        user = userRepository.save(user);
        userId = user.getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(50000));
        account = accountRepository.save(account);
        accountId = account.getId();

        Category shopping = new Category();
        shopping.setUserId(userId);
        shopping.setName("Shopping");
        shopping = categoryRepository.save(shopping);
        shoppingCategoryId = shopping.getId();
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        m.setUserId(userId);
        m.setCanonicalName(name);
        return merchantRepository.save(m);
    }

    private MerchantAlias alias(UUID merchantId, String normalized) {
        MerchantAlias a = new MerchantAlias();
        a.setMerchantId(merchantId);
        a.setUserId(userId);
        a.setNormalizedAlias(normalized);
        return merchantAliasRepository.save(a);
    }

    private Transaction transaction(UUID merchantId, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(shoppingCategoryId);
        t.setMerchantId(merchantId);
        t.setTxnDate(LocalDate.of(2026, 7, 1));
        t.setDescription("Test transaction");
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        return transactionRepository.save(t);
    }

    private MerchantCategoryLearning learningPair(UUID merchantId, UUID categoryId, int confirmationCount, int confidence) {
        MerchantCategoryLearning p = new MerchantCategoryLearning();
        p.setUserId(userId);
        p.setMerchantId(merchantId);
        p.setCategoryId(categoryId);
        p.setConfirmationCount(confirmationCount);
        p.setConfidence(confidence);
        return learningRepository.save(p);
    }

    @Test
    void merge_repointsAliasesTransactionsAndAuditHistory_sumsDistribution_deletesAbsorbedMerchant() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        MerchantAlias absorbedAlias = alias(absorbed.getId(), "amazon seller services");
        Transaction t1 = transaction(absorbed.getId(), new BigDecimal("999.00"));
        Transaction t2 = transaction(absorbed.getId(), new BigDecimal("500.00"));
        learningPair(surviving.getId(), shoppingCategoryId, 147, 100);
        learningPair(absorbed.getId(), shoppingCategoryId, 23, 100);

        MerchantLearningAudit priorHistory = new MerchantLearningAudit();
        priorHistory.setMerchantId(absorbed.getId());
        priorHistory.setUserId(userId);
        priorHistory.setAction(MerchantLearningAudit.Action.LEARNED);
        priorHistory.setNewCategoryId(shoppingCategoryId);
        priorHistory = auditRepository.save(priorHistory);

        var result = merchantService.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        // Distribution summed (147 + 23 = 170), not replaced -- spec §5.4 step 3.
        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).confirmationCount()).isEqualTo(170);

        // Aliases repointed -- spec step 1.
        MerchantAlias reloadedAlias = merchantAliasRepository.findById(absorbedAlias.getId()).orElseThrow();
        assertThat(reloadedAlias.getMerchantId()).isEqualTo(surviving.getId());

        // Transactions repointed -- spec step 2.
        assertThat(transactionRepository.findById(t1.getId()).orElseThrow().getMerchantId()).isEqualTo(surviving.getId());
        assertThat(transactionRepository.findById(t2.getId()).orElseThrow().getMerchantId()).isEqualTo(surviving.getId());

        // A single MERGED audit entry on the SURVIVING merchant -- spec step 6.
        List<MerchantLearningAudit> survivingAudit = auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, surviving.getId());
        assertThat(survivingAudit).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(MerchantLearningAudit.Action.MERGED));

        // The absorbed merchant's PRE-merge history is preserved, repointed onto the surviving
        // merchant -- NOT cascade-deleted (merchant_learning_audit.merchant_id has ON DELETE
        // CASCADE, V7 migration) when the absorbed merchant row is deleted below.
        MerchantLearningAudit reloadedPriorHistory = auditRepository.findById(priorHistory.getId()).orElseThrow();
        assertThat(reloadedPriorHistory.getMerchantId()).isEqualTo(surviving.getId());

        // The absorbed merchant row itself is gone -- spec step 7.
        assertThat(merchantRepository.findById(absorbed.getId())).isEmpty();
    }

    @Test
    void merge_intoItself_throwsAndChangesNothing() {
        Merchant m = merchant("Amazon");
        alias(m.getId(), "amazon");

        assertThatThrownBy(() -> merchantService.merge(userId, m.getId(), m.getId(), actingAdminId))
                .isInstanceOf(ApiException.class);

        assertThat(merchantRepository.findById(m.getId())).isPresent();
        assertThat(merchantAliasRepository.findByMerchantId(m.getId())).hasSize(1);
    }

    @Test
    void merge_rollsBackEverything_whenAFailureOccursPartwayThrough() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        MerchantAlias absorbedAlias = alias(absorbed.getId(), "amazon seller services");
        Transaction t1 = transaction(absorbed.getId(), new BigDecimal("999.00"));
        learningPair(surviving.getId(), shoppingCategoryId, 147, 100);
        learningPair(absorbed.getId(), shoppingCategoryId, 23, 100);

        // Force a failure exactly at step 2 (transaction repointing) -- step 1 (alias
        // repointing) has already run and, if @Transactional genuinely rolls back, must not be
        // visible afterward either.
        doThrow(new DataAccessException("Simulated failure during transaction repointing") {})
                .when(transactionRepository).saveAll(anyList());

        assertThatThrownBy(() -> merchantService.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId))
                .isInstanceOf(DataAccessException.class);

        // Clear the persistence context so the assertions below hit the real DB state, not
        // Hibernate's in-memory session cache from before the (rolled-back) merge attempt.
        entityManager.clear();

        // Nothing from step 1 onward is visible -- not a partial merge.
        MerchantAlias reloadedAlias = merchantAliasRepository.findById(absorbedAlias.getId()).orElseThrow();
        assertThat(reloadedAlias.getMerchantId())
                .as("alias repointing from step 1 must have been rolled back along with everything else")
                .isEqualTo(absorbed.getId());

        assertThat(transactionRepository.findById(t1.getId()).orElseThrow().getMerchantId()).isEqualTo(absorbed.getId());

        List<MerchantCategoryLearning> survivingPairs = learningRepository.findByUserIdAndMerchantId(userId, surviving.getId());
        assertThat(survivingPairs).hasSize(1);
        assertThat(survivingPairs.get(0).getConfirmationCount())
                .as("distribution must not have been summed if the merge as a whole didn't commit")
                .isEqualTo(147);

        // The absorbed merchant row must still exist -- step 7 (delete) never should have run.
        assertThat(merchantRepository.findById(absorbed.getId())).isPresent();
        // No MERGED audit entry should have been committed either.
        assertThat(auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, surviving.getId()))
                .noneMatch(a -> a.getAction() == MerchantLearningAudit.Action.MERGED);
    }

    @Test
    void undo_afterFirstConfirmation_removesThePairAndWritesAuditEntry_againstRealPostgres() {
        Merchant merchant = merchant("Swiggy");

        merchantLearningService.confirm(userId, merchant.getId(), shoppingCategoryId);
        assertThat(learningRepository.findByUserIdAndMerchantId(userId, merchant.getId())).hasSize(1);

        merchantLearningService.undo(userId, merchant.getId(), actingAdminId);

        assertThat(learningRepository.findByUserIdAndMerchantId(userId, merchant.getId())).isEmpty();
        List<MerchantLearningAudit> history = auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchant.getId());
        assertThat(history).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(MerchantLearningAudit.Action.UNDONE));
    }

    /**
     * Bug 54 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md), CORRECTED: the report claimed
     * repeated confirm/undo cycles after a merge can erode a pair's count below what the merge
     * absorbed, eventually deleting the row and destroying the absorbed merchant's evidence.
     * Reproduced against real Postgres before attempting a fix, under both readings of the
     * report's repro: neither erodes the baseline.
     *
     * <ul>
     *   <li>confirm+undo, repeated: each undo reverts exactly its own paired confirm (mostRecent
     *       is always that specific LEARNED entry), so the count returns to the merge baseline
     *       every round -- never below it.</li>
     *   <li>confirm x4 then undo x4 in a row: only the FIRST undo succeeds. The second finds an
     *       UNDONE entry as mostRecent and is rejected by the existing "can't undo an undo"
     *       guard -- the same guard already hardened for RESET (see undo()'s own doc comment).
     *       Consecutive, un-interspersed undos were never reachable through this API to begin
     *       with.</li>
     * </ul>
     *
     * <p>No code change made. Left as a passing regression test pinning the actual (safe)
     * behaviour, since nothing here was previously covered by a merge+undo interaction test.
     */
    @Test
    void undo_afterMergeAndRepeatedConfirmation_neverErodesTheMergedBaselineBelowWhatWasAbsorbed() {
        Merchant a = merchant("A");
        Merchant b = merchant("B");
        learningPair(a.getId(), shoppingCategoryId, 1, 100);
        learningPair(b.getId(), shoppingCategoryId, 3, 100);

        merchantService.merge(userId, a.getId(), b.getId(), actingAdminId);
        int baseline = learningRepository.findByUserIdAndMerchantId(userId, a.getId()).get(0).getConfirmationCount();
        assertThat(baseline).as("1 (A's own) + 3 (absorbed from B)").isEqualTo(4);

        for (int i = 0; i < 4; i++) {
            merchantLearningService.confirm(userId, a.getId(), shoppingCategoryId);
            merchantLearningService.undo(userId, a.getId(), actingAdminId);
            List<MerchantCategoryLearning> pairs = learningRepository.findByUserIdAndMerchantId(userId, a.getId());
            assertThat(pairs)
                    .as("round %d: confirm+undo must be exactly symmetric, never touching the merge baseline", i)
                    .hasSize(1);
            assertThat(pairs.get(0).getConfirmationCount()).isEqualTo(baseline);
        }

        for (int i = 0; i < 4; i++) {
            merchantLearningService.confirm(userId, a.getId(), shoppingCategoryId);
        }
        assertThat(learningRepository.findByUserIdAndMerchantId(userId, a.getId()).get(0).getConfirmationCount())
                .isEqualTo(baseline + 4);

        merchantLearningService.undo(userId, a.getId(), actingAdminId);
        assertThat(learningRepository.findByUserIdAndMerchantId(userId, a.getId()).get(0).getConfirmationCount())
                .as("exactly one undo succeeds, reverting exactly one of the four confirms")
                .isEqualTo(baseline + 3);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> merchantLearningService.undo(userId, a.getId(), actingAdminId))
                    .as("a second, un-interspersed undo is rejected -- it would find its own "
                            + "UNDONE entry as mostRecent, never one of the still-pending confirms")
                    .isInstanceOf(ApiException.class);
        }
        assertThat(learningRepository.findByUserIdAndMerchantId(userId, a.getId()).get(0).getConfirmationCount())
                .as("the rejected undo attempts changed nothing -- the row survives with evidence intact")
                .isEqualTo(baseline + 3);
    }

    /**
     * Self-review catch, surfaced while investigating Bug 54 above: {@code
     * MerchantLearningAuditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc} -- which
     * {@code undo()} reads as "the most recent action" -- had no tiebreaker on {@code createdAt}.
     * {@code MerchantLearningAudit.createdAt} is a plain {@code Instant.now()} field initializer,
     * not a DB sequence, so two entries for DIFFERENT categories landing in the same clock tick
     * (a worker or an admin bulk action confirming several categories for one merchant in a tight
     * loop) left which one "most recent" meant unspecified -- {@code undo()} could revert the
     * wrong category. Fixed by adding {@code id DESC} as a tiebreaker, the same fix (and the same
     * reasoning) {@code findByUserIdOrderByCreatedAtAscIdAsc}'s own comment already applies to the
     * sibling audit query in this repository.
     *
     * <p>The tie is forced directly -- {@code createdAt} is {@code updatable = false}, so setting
     * it via reflection AFTER the initial save (as a real timing collision would require) would
     * silently not persist; both entries are built with the tied timestamp already in place
     * before their first (and only) save, exactly like the existing {@code priorHistory}
     * construction earlier in this file. Real back-to-back {@code confirm()} calls are also far
     * too slow (each does multiple DB round trips) to reliably collide within one test run.
     *
     * <p>Deliberately does not assert WHICH category wins the tiebreak -- id is a random UUID, so
     * that has no meaning to assert on, and Java's {@code UUID.compareTo} ordering is not
     * guaranteed to agree with Postgres's own UUID sort order anyway. What must hold is that
     * {@code undo()}'s internal choice agrees with what a fresh call to the ordering query itself
     * returns as element zero -- i.e. the choice is deterministic, not left to query-plan
     * happenstance, which is the actual property a missing tiebreaker put at risk.
     */
    @Test
    void undo_breaksACreatedAtTieDeterministically_whenTwoDifferentCategoriesTie() {
        Merchant merchant = merchant("Tied Merchant");
        Category other = new Category();
        other.setUserId(userId);
        other.setName("Other");
        other = categoryRepository.save(other);
        UUID otherCategoryId = other.getId();

        learningPair(merchant.getId(), shoppingCategoryId, 1, 100);
        learningPair(merchant.getId(), otherCategoryId, 1, 100);

        Instant tiedInstant = Instant.now();
        MerchantLearningAudit forShopping = new MerchantLearningAudit();
        forShopping.setUserId(userId);
        forShopping.setMerchantId(merchant.getId());
        forShopping.setAction(MerchantLearningAudit.Action.LEARNED);
        forShopping.setNewCategoryId(shoppingCategoryId);
        ReflectionTestUtils.setField(forShopping, "createdAt", tiedInstant);
        auditRepository.save(forShopping);

        MerchantLearningAudit forOther = new MerchantLearningAudit();
        forOther.setUserId(userId);
        forOther.setMerchantId(merchant.getId());
        forOther.setAction(MerchantLearningAudit.Action.LEARNED);
        forOther.setNewCategoryId(otherCategoryId);
        ReflectionTestUtils.setField(forOther, "createdAt", tiedInstant);
        auditRepository.save(forOther);

        List<MerchantLearningAudit> tied =
                auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchant.getId());
        assertThat(tied).hasSize(2);
        // Not compared against `tiedInstant` itself: Postgres's timestamptz column truncates to
        // microseconds, while Instant.now() on some JVM/OS combinations (observed in CI, not
        // locally) carries full nanosecond precision -- the round-tripped value and the original
        // in-memory one can legitimately differ by sub-microsecond digits even though both rows
        // came from the exact same `tiedInstant`. What the test actually needs is that both
        // ROUND-TRIPPED values are equal to EACH OTHER, which is the tie the fix is about.
        assertThat(tied.get(0).getCreatedAt()).isEqualTo(tied.get(1).getCreatedAt());

        UUID categoryExpectedToBeReverted = tied.get(0).getNewCategoryId();
        UUID categoryExpectedToSurvive =
                categoryExpectedToBeReverted.equals(shoppingCategoryId) ? otherCategoryId : shoppingCategoryId;

        merchantLearningService.undo(userId, merchant.getId(), actingAdminId);

        List<MerchantCategoryLearning> pairs = learningRepository.findByUserIdAndMerchantId(userId, merchant.getId());
        assertThat(pairs)
                .as("only the query's own first result (the tiebreak-selected entry) should have "
                        + "been reverted -- its pair had count 1, so undo() deletes it")
                .noneMatch(p -> p.getCategoryId().equals(categoryExpectedToBeReverted));
        assertThat(pairs)
                .filteredOn(p -> p.getCategoryId().equals(categoryExpectedToSurvive))
                .as("the OTHER category's confirmation must survive untouched -- reverting it "
                        + "instead is exactly what a missing tiebreaker could get wrong")
                .hasSize(1);
    }
}
