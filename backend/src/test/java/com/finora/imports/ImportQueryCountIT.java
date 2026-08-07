package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
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
 * regression, not to declare the current number acceptable -- it is not. As the remaining
 * optimisations land (batch duplicate detection, batch merchant resolution) the ceiling should be
 * lowered to match, and lowering it is the point at which an improvement becomes permanent.
 */
class ImportQueryCountIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    /**
     * Marginal statements allowed per imported row.
     *
     * <p><b>Measured at 2.00.</b> The profile's original was ~6.3; b7aab9d removed the 2.00/row
     * {@code category_rules} lookups and {@link DuplicateIndex} removed the 1.00/row duplicate
     * query. What remains is merchant resolution -- recommendation 3, still outstanding.
     *
     * <p>2.5, not 3.0, and the half point matters. The cheapest regression available from here is
     * duplicate detection returning to a query per row, worth +1.00; a ceiling of 3.0 would sit
     * exactly ON that and let it through. This is the same trap an earlier revision of this file
     * fell into at 5.0 against a 3.00 measurement, so the rule is now explicit: <b>the ceiling must
     * be strictly below measured plus the smallest known regression</b>, not merely above the
     * measurement.
     *
     * <p>Lower it again when recommendation 3 lands. Lowering it is the point at which an
     * improvement stops being a number in a commit message and becomes something the build defends.
     */
    private static final double MAX_MARGINAL_STATEMENTS_PER_ROW = 2.5;

    /**
     * Marginal JPQL/HQL query executions allowed per row.
     *
     * <p>Tracked alongside prepared statements because the two catch different regressions: a
     * repository method called in a loop moves this one, while a lazily-initialised association
     * moves only the statement count. Measured at 2.00, same reasoning as above for the ceiling.
     */
    private static final double MAX_MARGINAL_QUERIES_PER_ROW = 2.5;

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
}
