package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These specifically exercise behavior that only a real Postgres can validate — an H2 in-memory
 * DB would silently pass even if @SQLRestriction were misconfigured, because H2 doesn't enforce
 * the same JSONB/array/constraint semantics. This is the whole reason Testcontainers exists here.
 */
class TransactionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private EntityManager entityManager;

    private UUID userId;
    private UUID accountId;
    private UUID categoryId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        user = userRepository.save(user);
        userId = user.getId();

        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        account = accountRepository.save(account);
        accountId = account.getId();

        Category category = new Category();
        category.setUserId(userId);
        category.setName("Dining");
        category = categoryRepository.save(category);
        categoryId = category.getId();
    }

    private Transaction newTransaction(BigDecimal amount, LocalDate date, String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    @Test
    @Transactional
    void softDelete_removesFromNormalQueries_butRowStillExistsInDatabase() {
        Transaction t = newTransaction(BigDecimal.valueOf(500), LocalDate.of(2026, 7, 10), "Test expense");
        UUID txnId = t.getId();

        transactionRepository.delete(t);
        entityManager.flush();
        entityManager.clear(); // force a real reload from the DB, not the persistence context cache

        assertThat(transactionRepository.findById(txnId)).isEmpty();
        assertThat(transactionRepository.findByUserId(userId)).isEmpty();

        // The row must still physically exist — that's the entire point of soft delete.
        Long rawCount = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM transactions WHERE id = :id AND deleted_at IS NOT NULL")
                .setParameter("id", txnId)
                .getSingleResult();
        assertThat(rawCount).isEqualTo(1L);
    }

    @Test
    @Transactional
    void search_filtersByTypeAndAmountRange() {
        newTransaction(BigDecimal.valueOf(200), LocalDate.of(2026, 7, 1), "Small expense");
        newTransaction(BigDecimal.valueOf(5000), LocalDate.of(2026, 7, 2), "Large expense");

        var filtered = transactionRepository.search(
                userId, null, null, Transaction.Type.EXPENSE, null, null,
                BigDecimal.valueOf(1000), null, null, List.of("NONE"), List.of(accountId), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).hasSize(1);
        assertThat(filtered.getContent().get(0).getDescription()).isEqualTo("Large expense");
    }

    @Test
    @Transactional
    void search_filtersByKeyword_caseInsensitive() {
        newTransaction(BigDecimal.valueOf(486), LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR");
        newTransaction(BigDecimal.valueOf(320), LocalDate.of(2026, 7, 11), "Uber trip");

        var filtered = transactionRepository.search(
                userId, null, null, null, null, null, null, null, "swiggy", List.of("NONE"), List.of(accountId), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).hasSize(1);
        assertThat(filtered.getContent().get(0).getDescription()).contains("SWIGGY");
    }

    @Test
    @Transactional
    void search_matchesByBankOfficialName_evenWhenDescriptionDoesNotMentionIt() {
        // A second account, held with a different (recognized) bank, so this proves the match
        // comes from the bank name and not just an accidental description hit.
        Account pnbAccount = new Account();
        pnbAccount.setUserId(userId);
        pnbAccount.setName("Salary Account");
        pnbAccount.setAccountType(Account.Type.SAVINGS);
        pnbAccount.setBalance(BigDecimal.ZERO);
        pnbAccount.setBankId("PNB");
        pnbAccount = accountRepository.save(pnbAccount);

        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(pnbAccount.getId());
        t.setCategoryId(categoryId);
        t.setTxnDate(LocalDate.of(2026, 7, 12));
        t.setAmount(BigDecimal.valueOf(750));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Grocery run"); // deliberately no mention of the bank
        t.setSource(Transaction.Source.MANUAL);
        transactionRepository.save(t);

        // A transaction on the OTHER account (different bank) with an unrelated description --
        // must NOT show up for a "Punjab National" search.
        newTransaction(BigDecimal.valueOf(100), LocalDate.of(2026, 7, 12), "Unrelated expense");

        // bankIds is what TransactionService would have resolved from BankRegistry.search("Punjab
        // National") -- ["PNB"] -- passed here directly since this test exercises the repository
        // query in isolation. The keyword itself deliberately matches nothing in either
        // transaction's description/merchant, so a match here can only be coming from the
        // bankIds branch of the query, not an accidental text hit.
        var filtered = transactionRepository.search(
                userId, null, null, null, null, null, null, null, "zzz-no-text-match",
                List.of("PNB"), List.of(accountId, pnbAccount.getId()), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).extracting(Transaction::getDescription).containsExactly("Grocery run");
    }

    /**
     * Deleted-account leak fix: when the caller doesn't ask for one specific account
     * ({@code accountId == null}), {@code search} must still restrict results to the liveAccountIds
     * collection the caller passes -- otherwise a soft-deleted account's transactions would surface
     * in the Ledger's default "all accounts" search forever, since {@code Transaction.deleted_at}
     * is deliberately left unset when only the owning ACCOUNT is deleted. Verified against real
     * Postgres because the query's {@code (:accountId IS NOT NULL OR t.accountId IN :liveAccountIds)}
     * clause is exactly the kind of boolean-logic mistake a mock would never catch.
     */
    @Test
    @Transactional
    void search_withNoAccountIdFilter_scopesResultsToTheGivenLiveAccountIds() {
        newTransaction(BigDecimal.valueOf(100), LocalDate.of(2026, 7, 1), "On the live account");

        Account otherAccount = new Account();
        otherAccount.setUserId(userId);
        otherAccount.setName("Second Account");
        otherAccount.setAccountType(Account.Type.SAVINGS);
        otherAccount.setBalance(BigDecimal.ZERO);
        otherAccount = accountRepository.save(otherAccount);
        Transaction onOtherAccount = new Transaction();
        onOtherAccount.setUserId(userId);
        onOtherAccount.setAccountId(otherAccount.getId());
        onOtherAccount.setCategoryId(categoryId);
        onOtherAccount.setTxnDate(LocalDate.of(2026, 7, 2));
        onOtherAccount.setAmount(BigDecimal.valueOf(200));
        onOtherAccount.setTxnType(Transaction.Type.EXPENSE);
        onOtherAccount.setDescription("On the deleted-account stand-in");
        onOtherAccount.setSource(Transaction.Source.MANUAL);
        transactionRepository.save(onOtherAccount);

        // liveAccountIds simulates the caller's own account having been soft-deleted: only
        // `accountId` (not otherAccount's id) is passed as live, even though both transactions
        // physically exist and neither transaction itself is soft-deleted.
        var filtered = transactionRepository.search(
                userId, null, null, null, null, null, null, null, null,
                List.of("NONE"), List.of(accountId), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).extracting(Transaction::getDescription)
                .containsExactly("On the live account");
    }

    /**
     * The other half of the same clause: when the caller DOES supply a specific accountId, that
     * filter is trusted as-is and liveAccountIds is not consulted at all -- passing an empty
     * liveAccountIds collection here must not also exclude the explicitly-requested account.
     */
    @Test
    @Transactional
    void search_withAnExplicitAccountId_ignoresLiveAccountIds() {
        newTransaction(BigDecimal.valueOf(100), LocalDate.of(2026, 7, 1), "On the explicitly requested account");

        var filtered = transactionRepository.search(
                userId, accountId, null, null, null, null, null, null, null,
                List.of("NONE"), List.of(), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).extracting(Transaction::getDescription)
                .containsExactly("On the explicitly requested account");
    }

    /**
     * The scenario the whole fix exists for: a user whose only account was soft-deleted, running
     * the Ledger's default "all accounts" search (accountId == null). liveAccountIds is therefore
     * empty -- confirming Hibernate's IN-clause handling of an empty parameter list evaluates to
     * "match nothing" here rather than throwing (or, worse, silently matching everything).
     */
    @Test
    @Transactional
    void search_withNoAccountIdFilterAndNoLiveAccounts_returnsNothing() {
        newTransaction(BigDecimal.valueOf(100), LocalDate.of(2026, 7, 1), "On the now-deleted account");

        var filtered = transactionRepository.search(
                userId, null, null, null, null, null, null, null, null,
                List.of("NONE"), List.of(), PageRequest.of(0, 20));

        assertThat(filtered.getContent()).isEmpty();
    }

    @Test
    @Transactional
    void findPotentialDuplicates_matchesOnAccountDateAmountAndDescription() {
        Transaction original = newTransaction(BigDecimal.valueOf(486), LocalDate.of(2026, 7, 10), "SWIGGY*ORDR9182 BLR");

        List<Transaction> dupes = transactionRepository.findPotentialDuplicates(
                userId, accountId, LocalDate.of(2026, 7, 10), BigDecimal.valueOf(486), "SWIGGY*ORDR9182 BLR");

        assertThat(dupes).extracting(Transaction::getId).contains(original.getId());
    }

    /**
     * BH-042. {@code ReportService.availableMonths} used to load the user's ENTIRE history as
     * entities to derive a dropdown's worth of month strings. It reads distinct dates from the
     * database now, and this asserts the query against real Postgres rather than a mock -- a
     * mocked repository would happily return whatever the test handed it and prove nothing about
     * whether the JPQL is valid.
     *
     * <p>The soft-delete case is the one worth pinning: {@code DISTINCT} on a derived query still
     * has to inherit Transaction's {@code @SQLRestriction}, or the Reports dropdown would offer a
     * month whose only transaction the user had deleted.
     */
    @Test
    @Transactional
    void distinctTransactionDates_areScopedToTheUserAndExcludeSoftDeletedRows() {
        newTransaction(BigDecimal.valueOf(100), LocalDate.of(2026, 5, 4), "May A");
        newTransaction(BigDecimal.valueOf(200), LocalDate.of(2026, 5, 4), "May B -- same date");
        newTransaction(BigDecimal.valueOf(300), LocalDate.of(2026, 6, 11), "June");
        Transaction deleted = newTransaction(BigDecimal.valueOf(400), LocalDate.of(2026, 7, 1), "July, deleted");

        transactionRepository.delete(deleted);
        entityManager.flush();
        entityManager.clear();

        assertThat(transactionRepository.findDistinctTransactionDates(userId))
                .as("two rows share 4 May, so DISTINCT must collapse them; the deleted July row must not appear")
                .containsExactlyInAnyOrder(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 6, 11));

        assertThat(transactionRepository.findDistinctTransactionDates(UUID.randomUUID()))
                .as("another user's dates are not this user's")
                .isEmpty();
    }

    /**
     * C6.4. The JPQL here compares an enum literal by its fully-qualified Java path
     * ({@code com.finora.entity.Transaction.Type.EXPENSE}) rather than a bound parameter -- exactly
     * the kind of syntax a mock repository would never catch a typo in. Real Postgres via
     * Testcontainers is what proves the query even parses.
     */
    @Test
    @Transactional
    void findCandidatesForGmailReconciliation_matchesOnAmountAndDateWindow_excludingGmailSourcedRows() {
        Transaction inWindow = newTransaction(new BigDecimal("1299.00"), LocalDate.of(2026, 8, 9), "AMZN MKTPLACE");

        Transaction wrongAmount = newTransaction(new BigDecimal("50.00"), LocalDate.of(2026, 8, 9), "AMZN MKTPLACE");

        Transaction outsideWindow = newTransaction(new BigDecimal("1299.00"), LocalDate.of(2026, 7, 1), "AMZN MKTPLACE");

        Transaction gmailSourced = new Transaction();
        gmailSourced.setUserId(userId);
        gmailSourced.setAccountId(accountId);
        gmailSourced.setCategoryId(categoryId);
        gmailSourced.setTxnDate(LocalDate.of(2026, 8, 9));
        gmailSourced.setAmount(new BigDecimal("1299.00"));
        gmailSourced.setTxnType(Transaction.Type.EXPENSE);
        gmailSourced.setDescription("amazon.in");
        gmailSourced.setSource(Transaction.Source.GMAIL_IMPORT);
        transactionRepository.save(gmailSourced);

        List<Transaction> candidates = transactionRepository.findCandidatesForGmailReconciliation(
                userId, new BigDecimal("1299.00"), LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 13));

        assertThat(candidates).extracting(Transaction::getId).containsExactly(inWindow.getId());
        assertThat(candidates).extracting(Transaction::getId)
                .doesNotContain(wrongAmount.getId(), outsideWindow.getId(), gmailSourced.getId());
    }
}
