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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
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
 * The rollback test uses @SpyBean on TransactionRepository to force a failure at one specific
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
    @SpyBean private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private UUID userId;
    private UUID accountId;
    private UUID shoppingCategoryId;

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

        var result = merchantService.merge(userId, surviving.getId(), absorbed.getId());

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

        assertThatThrownBy(() -> merchantService.merge(userId, m.getId(), m.getId()))
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

        assertThatThrownBy(() -> merchantService.merge(userId, surviving.getId(), absorbed.getId()))
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

        merchantLearningService.undo(userId, merchant.getId());

        assertThat(learningRepository.findByUserIdAndMerchantId(userId, merchant.getId())).isEmpty();
        List<MerchantLearningAudit> history = auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchant.getId());
        assertThat(history).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(MerchantLearningAudit.Action.UNDONE));
    }
}
