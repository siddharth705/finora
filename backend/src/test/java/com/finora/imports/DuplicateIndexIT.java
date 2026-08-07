package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index must agree with the query it replaced, on every row, always.
 *
 * <p>This is a correctness test wearing a performance change's clothes. Duplicate detection decides
 * whether a user is warned that a row repeats something already in their ledger; a divergence here
 * does not fail, it silently changes which duplicates get surfaced. The profile's own warning about
 * these paths is that a silent regression corrupts user data rather than merely slowing things
 * down.
 *
 * <p>So the assertions below are equivalence assertions: the same inputs, through both paths,
 * producing the same answer -- run against a real Postgres, because the divergence that matters
 * most is between Java semantics and SQL semantics.
 */
class DuplicateIndexIT extends AbstractIntegrationTest {

    @Autowired private DuplicateDetector duplicateDetector;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;

    private User user() {
        User user = new User();
        user.setEmail("duplicate-index-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Duplicate Index IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);

        // transactions.account_id is NOT NULL, so a user without an account cannot hold one.
        Account account = new Account();
        account.setUserId(saved.getId());
        account.setName("Duplicate Index IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        accounts.put(saved.getId(), accountRepository.save(account).getId());
        return saved;
    }

    /** userId -> that user's account, so existing() can satisfy the NOT NULL. */
    private final java.util.Map<UUID, UUID> accounts = new java.util.HashMap<>();

    private Transaction existing(UUID userId, LocalDate date, BigDecimal amount, String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accounts.get(userId));
        t.setTxnDate(date);
        t.setAmount(amount);
        t.setDescription(description);
        t.setTxnType(Transaction.Type.EXPENSE);
        return transactionRepository.save(t);
    }

    /** Both paths, same inputs -- the property every test here asserts. */
    private void assertBothPathsAgree(UUID userId, LocalDate date, BigDecimal amount, String description) {
        var viaQuery = duplicateDetector.findMatch(userId, date, amount, description);
        var viaIndex = duplicateDetector.findMatch(duplicateDetector.indexFor(userId), date, amount, description);

        assertThat(viaIndex.isPresent())
                .as("index and query disagree about whether %s on %s is a duplicate", amount, date)
                .isEqualTo(viaQuery.isPresent());
        if (viaQuery.isPresent()) {
            assertThat(viaIndex.get().existingTransactionId()).isEqualTo(viaQuery.get().existingTransactionId());
            assertThat(viaIndex.get().matchCount()).isEqualTo(viaQuery.get().matchCount());
        }
    }

    @Test
    void anExactRepeatIsFoundByBothPaths() {
        User user = user();
        LocalDate date = LocalDate.of(2026, 7, 10);
        existing(user.getId(), date, new BigDecimal("486.00"), "SWIGGY ORDER 4471");

        assertBothPathsAgree(user.getId(), date, new BigDecimal("486.00"), "SWIGGY ORDER 4471");
    }

    @Test
    void amountsThatDifferOnlyInScaleStillMatch() {
        // THE bug this index could have introduced. Postgres NUMERIC equality is by value, so
        // 486.0 = 486.00 in the query it replaces. BigDecimal.equals is by value AND scale, so a
        // naive HashMap key would treat them as different transactions -- and users would quietly
        // stop being warned about duplicates whenever a CSV wrote one scale and a PDF the other.
        User user = user();
        LocalDate date = LocalDate.of(2026, 7, 11);
        existing(user.getId(), date, new BigDecimal("486.0"), "BLINKIT GROCERIES");

        assertBothPathsAgree(user.getId(), date, new BigDecimal("486.00"), "BLINKIT GROCERIES");
        assertThat(duplicateDetector.findMatch(duplicateDetector.indexFor(user.getId()),
                date, new BigDecimal("486.00"), "BLINKIT GROCERIES"))
                .as("scale must not decide identity")
                .isPresent();
    }

    @Test
    void trailingZeroesOnTheStoredSideAlsoMatch() {
        // The mirror of the case above -- the stored row carries the extra zeros this time.
        User user = user();
        LocalDate date = LocalDate.of(2026, 7, 12);
        existing(user.getId(), date, new BigDecimal("1200.0000"), "ZEPTO DAILY");

        assertBothPathsAgree(user.getId(), date, new BigDecimal("1200"), "ZEPTO DAILY");
    }

    @Test
    void aDifferentDateIsNotADuplicate() {
        User user = user();
        existing(user.getId(), LocalDate.of(2026, 7, 10), new BigDecimal("486.00"), "SWIGGY ORDER");

        assertBothPathsAgree(user.getId(), LocalDate.of(2026, 7, 11), new BigDecimal("486.00"), "SWIGGY ORDER");
    }

    @Test
    void aDifferentAmountOrDescriptionIsNotADuplicate() {
        User user = user();
        LocalDate date = LocalDate.of(2026, 7, 10);
        existing(user.getId(), date, new BigDecimal("486.00"), "SWIGGY ORDER");

        assertBothPathsAgree(user.getId(), date, new BigDecimal("487.00"), "SWIGGY ORDER");
        assertBothPathsAgree(user.getId(), date, new BigDecimal("486.00"), "ZOMATO ORDER");
    }

    @Test
    void anotherUsersTransactionIsNeverADuplicate() {
        // Tenant isolation, asserted on the new path specifically: the index is keyed by user at
        // construction, and getting that wrong would leak one user's ledger into another's
        // duplicate warnings.
        User mine = user();
        User theirs = user();
        LocalDate date = LocalDate.of(2026, 7, 10);
        existing(theirs.getId(), date, new BigDecimal("486.00"), "SWIGGY ORDER");

        assertThat(duplicateDetector.findMatch(duplicateDetector.indexFor(mine.getId()),
                date, new BigDecimal("486.00"), "SWIGGY ORDER")).isEmpty();
        assertBothPathsAgree(mine.getId(), date, new BigDecimal("486.00"), "SWIGGY ORDER");
    }

    @Test
    void theMatchCountSurvives_becauseMoreThanOneMeansSomethingDifferent() {
        // Two matches usually means the user genuinely transacts this amount on this date
        // repeatedly -- a commute fare, a split bill -- which is exactly when skipping is wrong.
        // The count is the signal that lets the review screen say so.
        User user = user();
        LocalDate date = LocalDate.of(2026, 7, 10);
        existing(user.getId(), date, new BigDecimal("60.00"), "METRO FARE");
        existing(user.getId(), date, new BigDecimal("60.00"), "METRO FARE");

        assertBothPathsAgree(user.getId(), date, new BigDecimal("60.00"), "METRO FARE");
        assertThat(duplicateDetector.findMatch(duplicateDetector.indexFor(user.getId()),
                date, new BigDecimal("60.00"), "METRO FARE").orElseThrow().matchCount()).isEqualTo(2);
    }

    @Test
    void oneIndexServesManyDatesWithoutReloadingAnyOfThem() {
        // The whole point: an index is a cache per date, so a statement spanning a month costs one
        // query per date rather than one per row. Asserted behaviourally -- repeated lookups keep
        // returning the right answers, including for a date first seen after others were cached.
        User user = user();
        for (int day = 1; day <= 5; day++) {
            existing(user.getId(), LocalDate.of(2026, 7, day), new BigDecimal("100.00"), "DAILY " + day);
        }

        DuplicateIndex index = duplicateDetector.indexFor(user.getId());
        for (int pass = 0; pass < 2; pass++) {
            for (int day = 1; day <= 5; day++) {
                assertThat(duplicateDetector.findMatch(index, LocalDate.of(2026, 7, day),
                        new BigDecimal("100.00"), "DAILY " + day))
                        .as("pass %d, day %d", pass, day)
                        .isPresent();
            }
        }
    }

    @Test
    void nullsAreNotDuplicates_ratherThanThrowing() {
        // Staging parses whatever the file contained; a row with an unparseable date or amount
        // reaches here as null and must not take the parse down.
        User user = user();
        DuplicateIndex index = duplicateDetector.indexFor(user.getId());

        assertThat(duplicateDetector.findMatch(index, null, new BigDecimal("1.00"), "X")).isEmpty();
        assertThat(duplicateDetector.findMatch(index, LocalDate.of(2026, 7, 10), null, "X")).isEmpty();
        assertThat(duplicateDetector.findMatch(index, LocalDate.of(2026, 7, 10), new BigDecimal("1.00"), null)).isEmpty();
    }
}
