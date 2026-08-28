package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Final-branch review, finding 1: category delete threw a foreign-key violation for its own
 * primary use case.
 *
 * <p>The design's FK audit found {@code transactions}, {@code budgets} and
 * {@code category_rules.action_value} and missed the entire Learning Engine —
 * {@code merchant_learning_audit.previous_category_id} / {@code new_category_id} (V7) are
 * {@code REFERENCES categories(id)} with no explicit {@code ON DELETE}, i.e. {@code NO ACTION},
 * so Postgres refuses the DELETE. An audit row is written by
 * {@code TransactionService.updateCategory -> CategorizationService.learn ->
 * MerchantLearningService.confirm} on every manual recategorization, so the categories this made
 * undeletable were precisely the ones a real user had actually used.
 *
 * <p><b>Why this test is an IT and not a unit test.</b> {@code CategoryServiceTest} mocks every
 * repository, so {@code categoryRepository.delete(category)} is a no-op that cannot fail — the
 * bug is structurally invisible to it, and was. Only a real Postgres with the real Flyway schema
 * enforces the foreign key that this whole fix exists to satisfy.
 */
class CategoryDeleteLearningReferencesIT extends AbstractIntegrationTest {

    @Autowired private CategoryService categoryService;
    @Autowired private TransactionService transactionService;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningAuditRepository auditRepository;
    @Autowired private MerchantCategoryLearningRepository learningRepository;

    private record Fixture(User user, Account account, Category source, Category target, Transaction txn) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("category-delete-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Category Delete IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Category Delete IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        Account savedAccount = accountRepository.save(account);

        Category source = categoryService.create(savedUser.getId(), "Weekend Groceries", null, null);
        Category target = categoryService.create(savedUser.getId(), "Groceries Rollup", null, null);

        Transaction txn = new Transaction();
        txn.setUserId(savedUser.getId());
        txn.setAccountId(savedAccount.getId());
        txn.setTxnDate(LocalDate.of(2026, 8, 12));
        txn.setDescription("BLINKIT MARKETPLACE BLR");
        txn.setMerchant("Blinkit");
        txn.setAmount(new BigDecimal("742.00"));
        txn.setTxnType(Transaction.Type.EXPENSE);
        Transaction savedTxn = transactionRepository.save(txn);

        return new Fixture(savedUser, savedAccount, source, target, savedTxn);
    }

    /**
     * The regression test proper. Before the fix this failed with a
     * {@code DataIntegrityViolationException} on
     * {@code merchant_learning_audit_previous_category_id_fkey} / {@code ..._new_category_id_fkey}.
     */
    @Test
    void deletingACategoryThatMerchantLearningHasWrittenAuditRowsAgainstSucceeds() {
        Fixture f = fixture();

        // The real path a user takes: recategorize a transaction, which learns the merchant and
        // writes the merchant_learning_audit row that used to make this category undeletable.
        transactionService.updateCategory(f.user().getId(), f.txn().getId(), f.source().getName());

        List<MerchantLearningAudit> auditRows = auditRepository.findByUserId(f.user().getId());
        assertThat(auditRows)
                .as("the fixture must actually produce the audit row the bug depended on")
                .anySatisfy(a -> assertThat(a.getNewCategoryId()).isEqualTo(f.source().getId()));

        assertThatCode(() -> categoryService.delete(f.user().getId(), f.source().getId(), f.target().getId()))
                .doesNotThrowAnyException();

        assertThat(categoryRepository.findById(f.source().getId())).isEmpty();
        // Nulled, not repointed: the audit trail must not claim the user picked a category they
        // never picked. See MerchantLearningAuditRepository.clearNewCategoryReferences.
        assertThat(auditRepository.findByUserId(f.user().getId()))
                .allSatisfy(a -> {
                    assertThat(a.getNewCategoryId()).isNotEqualTo(f.source().getId());
                    assertThat(a.getPreviousCategoryId()).isNotEqualTo(f.source().getId());
                });
    }

    /**
     * The second half of finding 1: {@code merchant_category_learning.category_id} is
     * {@code ON DELETE CASCADE}, so the delete never failed on it — it silently threw away the
     * merchant's training data instead of moving it to the category the user chose to move
     * everything else to.
     */
    @Test
    void merchantTrainingDataIsRepointedAtTheReassignmentTargetRatherThanCascadeDeleted() {
        Fixture f = fixture();
        transactionService.updateCategory(f.user().getId(), f.txn().getId(), f.source().getName());

        List<MerchantCategoryLearning> before = learningRepository.findByUserIdAndCategoryId(
                f.user().getId(), f.source().getId());
        assertThat(before).as("learning row for the merchant must exist before the delete").hasSize(1);
        UUID merchantId = before.get(0).getMerchantId();

        categoryService.delete(f.user().getId(), f.source().getId(), f.target().getId());

        assertThat(learningRepository.findByUserIdAndCategoryId(f.user().getId(), f.source().getId()))
                .isEmpty();
        assertThat(learningRepository.findByUserIdAndMerchantIdAndCategoryId(
                f.user().getId(), merchantId, f.target().getId()))
                .as("the merchant now trains toward the reassignment target, not nothing")
                .isPresent();
    }

    /**
     * The merge branch: both the deleted category and the target already know the same merchant,
     * so a plain UPDATE would violate {@code UNIQUE(user_id, merchant_id, category_id)}. The
     * source's confirmations are folded into the target rather than discarded — those transactions
     * are being reassigned to it, so its evidence genuinely grows.
     */
    @Test
    void confirmationsMergeIntoAnExistingTargetRowWhenBothCategoriesKnowTheMerchant() {
        Fixture f = fixture();

        // Same merchant string both times, so both learning rows hang off one merchant: first
        // confirmed under the target, then corrected to the category we are about to delete.
        transactionService.updateCategory(f.user().getId(), f.txn().getId(), f.target().getName());
        transactionService.updateCategory(f.user().getId(), f.txn().getId(), f.source().getName());

        UUID merchantId = learningRepository.findByUserIdAndCategoryId(f.user().getId(), f.source().getId())
                .get(0).getMerchantId();
        int targetCountBefore = learningRepository
                .findByUserIdAndMerchantIdAndCategoryId(f.user().getId(), merchantId, f.target().getId())
                .orElseThrow().getConfirmationCount();
        int sourceCountBefore = learningRepository
                .findByUserIdAndMerchantIdAndCategoryId(f.user().getId(), merchantId, f.source().getId())
                .orElseThrow().getConfirmationCount();

        categoryService.delete(f.user().getId(), f.source().getId(), f.target().getId());

        MerchantCategoryLearning merged = learningRepository
                .findByUserIdAndMerchantIdAndCategoryId(f.user().getId(), merchantId, f.target().getId())
                .orElseThrow();
        assertThat(merged.getConfirmationCount()).isEqualTo(targetCountBefore + sourceCountBefore);
        assertThat(learningRepository.findByUserIdAndCategoryId(f.user().getId(), f.source().getId()))
                .isEmpty();
    }
}
