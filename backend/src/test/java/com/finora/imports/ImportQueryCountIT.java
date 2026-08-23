package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how database work scales with statement size, and fails when it scales linearly.
 *
 * <h2>Why this exists as a test rather than a document</h2>
 *
 * <p>{@code docs/engineering/performance/import-pipeline-profile-2026-08-07.md} established the N+1
 * patterns by running two imports with {@code org.hibernate.SQL: DEBUG} and parsing 56,818 log
 * lines by hand. That was the right way to find the problem and the wrong way to keep knowing about
 * it: <b>the document was stale within forty minutes.</b> It records {@code category_rules} at 2.00
 * queries per row; commit b7aab9d fixed exactly that, the same morning, thirty-nine minutes after
 * the profile was committed.
 *
 * <p>Nobody did anything wrong. A measurement that takes a manual log-parsing session to repeat is
 * a measurement that gets repeated approximately never, and a performance document that cannot
 * cheaply re-verify itself decays into folklore -- confidently describing a system that has moved
 * on. The standing rule is to measure before AND after; this is what makes the "after" affordable.
 *
 * <h2>The method: marginal cost, not absolute count</h2>
 *
 * <p>Import N rows, then 2N rows, and look at the difference. Fixed setup -- loading the user,
 * their accounts, their rules once -- appears in both runs and cancels out. What remains is the
 * true per-row cost, which is the number that decides whether a 5,000-row statement is viable.
 *
 * <p>Asserting the marginal cost rather than a total is also what makes this robust: a total would
 * need updating every time any unrelated fixed query is added, and a test that needs constant
 * updating is one people update without reading.
 *
 * <h2>This is a ratchet, not a target</h2>
 *
 * <p>The ceiling below is set just above the current measured value. It exists to catch a
 * regression, not to declare the current number acceptable. All three recommendations from the
 * original profile -- batched {@code category_rules}, batched duplicate detection, batched
 * merchant resolution -- have now landed, and the ceiling has been lowered each time to match;
 * lowering it is the point at which an improvement becomes permanent.
 */
class ImportQueryCountIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    /**
     * Marginal statements allowed per imported row.
     *
     * <p><b>Measured at 0.00.</b> The profile's original was ~6.3; b7aab9d removed the 2.00/row
     * {@code category_rules} lookups and {@link DuplicateIndex} removed the 1.00/row duplicate
     * query. Recommendation 3, merchant resolution, was the last one outstanding at 2.00/row --
     * not from {@code TransactionNormalizer}'s own merchant lookup (that was ALREADY indexed, see
     * {@link MerchantIndex}), but from {@code CategorizationService.suggestReadOnly} running its
     * own separate, un-indexed {@code MerchantNormalizationEngine.resolveReadOnly(UUID, String)}
     * call per row for rule-context matching -- a second, independent merchant resolution the
     * first indexing pass missed entirely. Threading the same {@link MerchantIndex} into that call
     * too (see {@code CategorizationService.suggestReadOnly(List, UUID, String, BigDecimal, String,
     * MerchantIndex)}) removed it.
     *
     * <p>0.5, not 0.0, for the same reason the previous ceiling was set half a point below the
     * cheapest known regression rather than exactly on the measurement: <b>the ceiling must be
     * strictly below measured plus the smallest known regression</b>. The cheapest regression
     * available from here is duplicate detection or merchant resolution returning to a query per
     * row, either worth +1.00 on its own -- a ceiling of 1.0 would still let a single-row regression
     * through undetected until it compounded with another. 0.5 catches either alone.
     */
    private static final double MAX_MARGINAL_STATEMENTS_PER_ROW = 0.5;

    /**
     * Marginal JPQL/HQL query executions allowed per row.
     *
     * <p>Tracked alongside prepared statements because the two catch different regressions: a
     * repository method called in a loop moves this one, while a lazily-initialised association
     * moves only the statement count. Measured at 0.00, same reasoning as above for the ceiling.
     */
    private static final double MAX_MARGINAL_QUERIES_PER_ROW = 0.5;

    /**
     * Marginal statements/queries allowed per CONFIRMED row -- {@link #confirmDatabaseWorkDoesNotScaleLinearlyWithStatementSize}'s
     * ceiling, tracked separately from the staging ceilings above because confirm() does genuine
     * per-row writes (the transaction insert itself, merchant/category resolution's own writes,
     * merchant-learning event queueing) that staging never does, so it can never reach staging's
     * near-zero floor.
     *
     * <p>Measured at 4.98 statements/row and 4.48 queries/row after hoisting
     * {@code CategorizationService.ruleSetFor(userId)} out of the confirm loop (see
     * {@link #confirmDatabaseWorkDoesNotScaleLinearlyWithStatementSize}'s own doc comment). The
     * cheapest known regression from here is the same rule-set reload returning, worth +2.00 on
     * each; same "strictly below measured plus smallest known regression" rule as
     * {@link #MAX_MARGINAL_STATEMENTS_PER_ROW}, so the ceiling sits below measured+2.00 (6.98/6.48)
     * with headroom above the measurement itself for run-to-run noise.
     */
    private static final double MAX_MARGINAL_CONFIRM_STATEMENTS_PER_ROW = 5.5;
    private static final double MAX_MARGINAL_CONFIRM_QUERIES_PER_ROW = 5.0;

    private static final int SMALL = 40;
    private static final int LARGE = 80;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private User user() {
        User user = new User();
        user.setEmail("query-count-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Query Count IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /**
     * A statement of {@code rows} rows with {@code rows / 2} distinct merchant descriptions.
     *
     * <p>Distinct descriptions matter: identical rows would be answered from the alias cache and
     * hide the merchant-resolution cost entirely, which is precisely the mistake that would make
     * this test pass while the pipeline stayed N+1. The profile used the same technique for the
     * same reason.
     */
    private MockMultipartFile statement(int rows) {
        StringBuilder csv = new StringBuilder("Date,Description,Amount,Type\n");
        for (int i = 0; i < rows; i++) {
            csv.append(String.format("2026-07-%02d,MERCHANT %d STORE,%d.00,DEBIT%n",
                    (i % 28) + 1, i % Math.max(1, rows / 2), 100 + i));
        }
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Both counters after staging one statement of the given size, for a fresh user. */
    private record Cost(long statements, long queries) {}

    private Cost costToStage(int rows) throws Exception {
        User user = user();
        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        importService.parseAndStageWithSession(user.getId(), statement(rows));

        return new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount());
    }

    @Test
    void databaseWorkDoesNotScaleLinearlyWithStatementSize() throws Exception {
        Cost small = costToStage(SMALL);
        Cost large = costToStage(LARGE);

        int extraRows = LARGE - SMALL;
        double marginalStatements = (double) (large.statements() - small.statements()) / extraRows;
        double marginalQueries = (double) (large.queries() - small.queries()) / extraRows;

        // Printed unconditionally: this is the measurement the profile document used to carry, and
        // it is most useful when the test PASSES -- that is when someone is deciding whether an
        // optimisation is worth doing, or checking whether one worked.
        System.out.printf("%n=== Import query cost ===%n"
                        + "  %d rows : %d statements, %d queries%n"
                        + "  %d rows : %d statements, %d queries%n"
                        + "  marginal: %.2f statements/row (ceiling %.2f)%n"
                        + "            %.2f queries/row    (ceiling %.2f)%n%n",
                SMALL, small.statements(), small.queries(),
                LARGE, large.statements(), large.queries(),
                marginalStatements, MAX_MARGINAL_STATEMENTS_PER_ROW,
                marginalQueries, MAX_MARGINAL_QUERIES_PER_ROW);

        assertThat(marginalStatements)
                .as("""
                        Import database work is scaling with statement size beyond the agreed
                        ceiling, which means an N+1 has been introduced or reintroduced. The
                        ceiling is set strictly below measured-plus-smallest-known-regression, so
                        even duplicate detection returning to one query per row (+1.00) fails
                        here. See
                        docs/engineering/performance/import-pipeline-profile-2026-08-07.md, and run
                        with org.hibernate.SQL at DEBUG to see which query is repeating.""")
                .isLessThanOrEqualTo(MAX_MARGINAL_STATEMENTS_PER_ROW);

        assertThat(marginalQueries)
                .as("JPQL executions are scaling per row beyond the ceiling -- a repository method "
                        + "is being called inside a row loop.")
                .isLessThanOrEqualTo(MAX_MARGINAL_QUERIES_PER_ROW);
    }

    private Account account(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Query Count IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    /** {@code rows} confirmed rows with {@code rows / 2} distinct descriptions, for the same
     *  reason {@link #statement} uses distinct descriptions: an identical description on every row
     *  would be answered from a cache and hide the per-row cost {@code applySideEffectRules} used
     *  to have. */
    private ConfirmRequest confirmRequestOf(UUID accountId, int rows) {
        List<ConfirmedRow> confirmed = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            confirmed.add(new ConfirmedRow(LocalDate.of(2026, 7, (i % 28) + 1),
                    "MERCHANT " + (i % Math.max(1, rows / 2)) + " STORE",
                    new BigDecimal(100 + i), "EXPENSE", "Shopping", true, "rule", null, false, null, null));
        }
        return new ConfirmRequest(null, confirmed, accountId, null, null, null, null);
    }

    private Cost costToConfirm(int rows) {
        User user = user();
        Account account = account(user.getId());
        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        importService.confirm(user.getId(), "statement.csv",
                "dummy content".getBytes(StandardCharsets.UTF_8), confirmRequestOf(account.getId(), rows));

        return new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount());
    }

    /**
     * Confirm-time counterpart of {@link #databaseWorkDoesNotScaleLinearlyWithStatementSize}.
     *
     * <p>Regression test for the confirm-time sibling of the staging-time bug this file already
     * guards: {@code ImportService.persistSection} called
     * {@code CategorizationService.applySideEffectRules(UUID, Transaction)} -- the loading overload
     * -- once per confirmed row, re-querying {@code category_rules} twice on every call. Fixed by
     * hoisting {@code CategorizationService.ruleSetFor(userId)} once before the confirm loop and
     * passing it to the new {@code applySideEffectRules(UUID, Transaction, List)} overload -- see
     * that method's own doc comment.
     */
    @Test
    void confirmDatabaseWorkDoesNotScaleLinearlyWithStatementSize() {
        Cost small = costToConfirm(SMALL);
        Cost large = costToConfirm(LARGE);

        int extraRows = LARGE - SMALL;
        double marginalStatements = (double) (large.statements() - small.statements()) / extraRows;
        double marginalQueries = (double) (large.queries() - small.queries()) / extraRows;

        System.out.printf("%n=== Confirm query cost ===%n"
                        + "  %d rows : %d statements, %d queries%n"
                        + "  %d rows : %d statements, %d queries%n"
                        + "  marginal: %.2f statements/row%n"
                        + "            %.2f queries/row%n%n",
                SMALL, small.statements(), small.queries(),
                LARGE, large.statements(), large.queries(),
                marginalStatements, marginalQueries);

        assertThat(marginalStatements)
                .as("Confirm database work is scaling with statement size beyond the agreed ceiling "
                        + "-- category_rules is very likely being reloaded per confirmed row again.")
                .isLessThanOrEqualTo(MAX_MARGINAL_CONFIRM_STATEMENTS_PER_ROW);
        assertThat(marginalQueries)
                .as("Confirm JPQL executions are scaling per row beyond the ceiling -- a repository "
                        + "method is being called inside the confirm row loop.")
                .isLessThanOrEqualTo(MAX_MARGINAL_CONFIRM_QUERIES_PER_ROW);
    }
}
