package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BH-041, now the verification rather than the measurement.
 *
 * <h2>What this used to say, and why it was wrong</h2>
 *
 * <p>This class was written to measure a cost before deciding what to do about it, and it recorded
 * a reason not to act: each section's {@code ConfirmResponse} reports {@code duplicatesDetected}
 * and {@code transfersIdentified} from {@code DuplicateDetector.tally}, which runs after
 * reconciliation — so reconciling once at the end "would make every per-section response report
 * zero", making the change a contract question rather than an optimisation.
 *
 * <p>That was inferred, and it was false. Every section writes its OWN {@code StatementImport}, and
 * {@code tally} is a post-hoc read of persisted flags scoped by that id. It does not care how many
 * passes ran or when. The counts survive a single shared pass untouched, no API changed, and the
 * deferral was unnecessary. The lesson is kept here rather than deleted: the measurement was sound
 * and the conclusion drawn from it was not.
 *
 * <h2>What it measures now, and a correction to the original headline</h2>
 *
 * <p>The pre-fix baseline was "+309 prepared statements, +136 queries, +132 ms for 3 sections over
 * 1". <b>Most of that was not reconciliation.</b> It compared three {@code confirm()} calls against
 * one, so it also counted two extra Account resolves, two extra StatementImport rows, two extra
 * transaction batches and two extra merchant-learning enqueues — per-section work that is real,
 * legitimate and unchanged by BH-041.
 *
 * <p>Measured properly — the same path, the same machine, only the reconciliation shape swapped —
 * a 3-section import costs:
 *
 * <pre>
 *   per-section + unbounded (shipped before)   994 statements   628 queries   ~514-629 ms
 *   once + windowed         (shipped now)      938 statements   616 queries   ~219-402 ms
 * </pre>
 *
 * <p>So the honest claim is: the repeated passes are gone (3 → 1, asserted by call count below),
 * worth ~56 prepared statements and ~12 queries here, and roughly a third to a half of wall-clock.
 * The elapsed saving is much larger than the statement saving because a reconciliation pass is only
 * a couple of queries — its cost is the in-memory O(n²) matching, which is exactly what repeats.
 *
 * <p>A whole-history pass being cheap in STATEMENTS is also why the second measurement in this
 * class exists: swapping the unbounded fetch for the windowed one changed nothing at all here
 * (994 vs 994), because every row in this fixture sits inside the ±180-day window. See
 * {@link #theCandidateSetExcludesHistoryOutsideTheWindow} for the case where it does pay.
 */
class MultiSectionReconciliationCostIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @SpyBean private ReconciliationService reconciliationService;
    @SpyBean private RecurringService recurringService;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TransactionRepository transactionRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    /**
     * Every confirm enqueues merchant-learning events, and the test profile disables the worker
     * ({@code app.learning.queue.enabled=false}) so nothing drains them. This class performs a lot
     * of confirms -- two 200-row histories plus four sections -- and left alone they accumulate in
     * a table every integration test in the JVM shares.
     *
     * <p>That is not theoretical: the first run of this class broke
     * {@code MerchantLearningQueueIT.twoConcurrentClaimsNeverReturnTheSameEvent}, which calls
     * {@code claimDueEvents} TABLE-WIDE and asserts the claim returns exactly one event. Any test
     * that leaves a pending event behind breaks it.
     *
     * <p>Scoped to this class's own users rather than truncating the table, so the cleanup is not
     * itself the cross-test coupling it exists to remove.
     */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private User user() {
        User user = new User();
        user.setEmail("multi-section-cost-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Multi Section Cost User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User owner, String name) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName(name);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return accountRepository.save(account);
    }

    private ConfirmedRow row(int i) {
        return new ConfirmedRow(LocalDate.of(2026, 7, (i % 28) + 1), "MERCHANT " + i + " STORE",
                new BigDecimal(100 + i + ".00"), "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private StagedRow stagedRow(int i) {
        return new StagedRow(LocalDate.of(2026, 7, (i % 28) + 1), "MERCHANT " + i + " STORE",
                new BigDecimal(100 + i + ".00"), "EXPENSE", "Other", "rule", null, false, null, null);
    }

    private List<ConfirmedRow> rows(int count, int offset) {
        List<ConfirmedRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(row(offset + i));
        return rows;
    }

    private List<StagedRow> stagedRows(int count, int offset) {
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(stagedRow(offset + i));
        return rows;
    }

    /**
     * An N-section import driven through the real {@code confirmMultiSection} path.
     *
     * <p>The old version of this class approximated it by calling the per-account {@code confirm()}
     * in a loop, which was faithful to the shape it was measuring precisely because that is what
     * the multi-section path then did. It no longer is, so measuring the proxy would now measure
     * something that does not happen.
     */
    private void importSections(User user, List<Account> targets, int rowsPerSection) {
        List<StagedAccountSection> staged = new ArrayList<>();
        List<SectionConfirm> confirms = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            int offset = i * rowsPerSection;
            staged.add(new StagedAccountSection(null, stagedRows(rowsPerSection, offset), rowsPerSection, 0, List.of()));
            confirms.add(new SectionConfirm(rows(rowsPerSection, offset), targets.get(i).getId(), null, null, null));
        }
        ImportSession session = importSessionService.createMultiSection(
                user.getId(), "statement.pdf", "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8), staged);
        importService.confirmMultiSection(user.getId(), new MultiAccountConfirmRequest(session.getId(), confirms));
    }

    /** Gives the user a history worth scanning, so the passes cost what they cost in real life. */
    private void seedHistory(User user, Account account, int rows) {
        importService.confirm(user.getId(), "history.csv",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(rows, 1000), account.getId(), null, null, null,
                null));
    }

    private record Cost(long statements, long queries, long elapsedMs) {}

    @Test
    @DisplayName("BH-041: a three-section import reconciles once, exactly as a one-section import does")
    void reconciliationCostNoLongerScalesWithSectionCount() {
        int history = 200;
        int totalRows = 60;

        // --- three sections, 20 rows each, through confirmMultiSection ---
        User multi = user();
        Account historyAccount = account(multi, "History");
        seedHistory(multi, historyAccount, history);
        List<Account> sections = List.of(
                account(multi, "Savings section"), account(multi, "Card section"), account(multi, "Deposit section"));

        // Discarded warm-up. The counters are deterministic and do not need it, but the elapsed
        // figure does: whichever block runs first pays for JIT compiling the whole confirm path,
        // and the first version of this measurement reported a 142 ms gap alongside a query delta
        // of exactly ZERO -- time that provably was not database work. Measuring warm keeps the
        // three numbers telling the same story.
        User warmup = user();
        Account warmupHistory = account(warmup, "History");
        seedHistory(warmup, warmupHistory, history);
        importSections(warmup, List.of(account(warmup, "Warm-up section")), totalRows);

        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        // Seeding the history is itself a confirm, so it reconciled once already. Cleared so both
        // the counters and the spies describe only the window being measured.
        clearInvocations(reconciliationService, recurringService);
        long startedAt = System.nanoTime();
        importSections(multi, sections, totalRows / sections.size());
        Cost threeSections = new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount(),
                (System.nanoTime() - startedAt) / 1_000_000);
        // Verified HERE, not at the end: the next measurement clears the spies, which would wipe
        // these records before an end-of-test assertion could read them.
        //
        // ONE, for three sections. This is the whole of BH-041.
        verify(reconciliationService, times(1)).reconcileForImport(eq(multi.getId()), any(), any());
        verify(recurringService, times(1)).detectForUser(multi.getId());

        // --- one section, all 60 rows, same history size ---
        User single = user();
        Account singleHistory = account(single, "History");
        seedHistory(single, singleHistory, history);
        Account only = account(single, "Only section");

        stats.clear();
        clearInvocations(reconciliationService, recurringService);
        startedAt = System.nanoTime();
        importSections(single, List.of(only), totalRows);
        Cost oneSection = new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount(),
                (System.nanoTime() - startedAt) / 1_000_000);
        verify(reconciliationService, times(1)).reconcileForImport(eq(single.getId()), any(), any());

        // Deliberately NOT printed against the old "+309 / +136 / +132" figures. Those came from
        // comparing three confirm() calls to one, so they counted two extra sections of persistence
        // as though it were reconciliation cost. Showing them in a column beside these numbers
        // would invite the same misreading the class comment exists to correct.
        System.out.printf(
                "%nBH-041 section-count sensitivity (history %d rows, %d imported rows either way)%n"
                + "                          3 sections    1 section     delta%n"
                + "  reconcile passes ....... %8d %12d %9d%n"
                + "  recurring passes ....... %8d %12d %9d%n"
                + "  prepared statements .... %8d %12d %9d%n"
                + "  query executions ....... %8d %12d %9d%n"
                + "  elapsed (ms) ........... %8d %12d %9d%n"
                + "  The pass counts are the result. The remaining statement/query delta is%n"
                + "  per-section persistence -- three Account resolves and three StatementImport%n"
                + "  rows against one -- which is real work that SHOULD scale with sections.%n"
                + "  For the actual before/after of the change itself, see the class comment:%n"
                + "  a 3-section import went 994 -> 938 statements, 628 -> 616 queries.%n%n",
                history, totalRows,
                1, 1, 0,
                1, 1, 0,
                threeSections.statements(), oneSection.statements(),
                threeSections.statements() - oneSection.statements(),
                threeSections.queries(), oneSection.queries(),
                threeSections.queries() - oneSection.queries(),
                threeSections.elapsedMs(), oneSection.elapsedMs(),
                threeSections.elapsedMs() - oneSection.elapsedMs());

        // The claim. Not "cheaper" -- INDIFFERENT. One pass either way, asserted above by call
        // count, and a database cost that no longer tracks section count.
        //
        // A ratchet rather than an exact figure. The pre-fix delta was +309 statements and +136
        // queries; anything near those means the per-section passes are back, which is the
        // regression worth catching. The measured delta is now single digits and can legitimately
        // sit either side of zero -- three 20-row batches and one 60-row batch flush differently,
        // so 3 sections came out 2 statements CHEAPER than 1 on the first run of this. Asserting
        // "3 > 1" looked obviously true and was false; asserting equality would flake on batch
        // boundaries. The bound is what actually matters.
        // THESE BOUNDS ARE A REGRESSION GUARD, NOT A PERFORMANCE TARGET. Nobody should read the
        // gap between the observed delta (~66-126) and the ceiling (200) as headroom that is
        // meant to be consumed, or as an expected value. The only thing they assert is "the old
        // repeated-work shape has not come back" -- that shape produced +309 statements and +136
        // queries, and any regression toward it trips these long before it reaches the old figure.
        // A tighter bound would encode today's per-section persistence cost as a contract, which it
        // is not: adding a legitimate per-section query would then fail a test about
        // reconciliation.
        //
        // Bounds chosen against MEASURED spread, not by eye. Across seven runs -- isolated and as
        // part of the full suite -- the statement delta landed between 66 and 126 and the query
        // delta between -5 and +29. The first bound written here was 100 and the full suite
        // promptly produced exactly 100, which is the whole argument for picking these from
        // observed data rather than from what looks like a round number.
        //
        // The deterministic signal is the pass count asserted above by call count. This is only the
        // backstop for a per-section query creeping in without changing it.
        long statementDelta = Math.abs(threeSections.statements() - oneSection.statements());
        long queryDelta = Math.abs(threeSections.queries() - oneSection.queries());
        assertThat(statementDelta)
                .as("section count must not drive prepared-statement count any more -- the pre-fix "
                        + "delta here was +309, and a number near that means whole-history "
                        + "reconciliation is running per section again")
                .isLessThan(200);
        assertThat(queryDelta)
                .as("same for query executions -- the pre-fix delta was +136")
                .isLessThan(80);
    }

    /**
     * The other half of BH-041, which the measurement above structurally cannot show.
     *
     * <p>Every row in that fixture sits in July 2026, and the candidate window is ±180 days around
     * the imported dates — so the window contains the entire history and narrowing it saves
     * nothing. Measured both ways, the 3-section import cost 994 prepared statements with the
     * unbounded fetch and 994 with the windowed one. Identical, because there was nothing outside
     * the window to leave behind.
     *
     * <p>Windowing only pays when history extends past the window, which is the case that actually
     * grows over a product's life. This spreads three years of history around a one-month import
     * and asserts on what the pass loaded.
     */
    @Test
    @DisplayName("BH-041: the windowed fetch loads the candidates, not the user's whole history")
    void theCandidateSetExcludesHistoryOutsideTheWindow() {
        User user = user();
        Account account = account(user, "Savings");

        // Three years of history, one row a fortnight, ending well before the import period.
        List<ConfirmedRow> spread = new ArrayList<>();
        for (int i = 0; i < 72; i++) {
            spread.add(new ConfirmedRow(LocalDate.of(2023, 1, 15).plusDays(i * 14L),
                    "OLD MERCHANT " + i, new BigDecimal(200 + i + ".00"), "EXPENSE", "Other",
                    true, "rule", null, false, null, null, false));
        }
        importService.confirm(user.getId(), "history.csv",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, spread, account.getId(), null, null, null,
                null));

        // A one-month statement, three years after that history starts.
        importSections(user, List.of(account(user, "July 2026")), 20);

        long totalRows = transactionRepository.findByUserId(user.getId()).size();
        Map<String, Object> lastRun = auditLogRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .filter(a -> "RECONCILIATION_RUN".equals(a.getAction()))
                .findFirst().orElseThrow(() -> new AssertionError("no RECONCILIATION_RUN was audited"))
                .getMetadata();

        long candidatesLoaded = ((Number) lastRun.get("candidatesLoaded")).longValue();
        System.out.printf(
                "%nBH-041 candidate window (3 years of history, 1 month imported)%n"
                + "  transactions on file .... %d%n"
                + "  candidates loaded ....... %d%n"
                + "  window .................. %s -> %s%n"
                + "  left unread ............. %d (%.0f%%)%n%n",
                totalRows, candidatesLoaded, lastRun.get("windowFrom"), lastRun.get("windowTo"),
                totalRows - candidatesLoaded, 100.0 * (totalRows - candidatesLoaded) / totalRows);

        assertThat(lastRun)
                .as("the windowed path reports its own scope -- transactionsProcessed belongs to "
                        + "the unbounded path and means something different")
                .containsKeys("candidatesLoaded", "windowFrom", "windowTo")
                .doesNotContainKey("transactionsProcessed");
        assertThat(candidatesLoaded)
                .as("history older than the widest matching window cannot pair with anything in "
                        + "this import, so loading it was always wasted work")
                .isLessThan(totalRows);
    }

    @Test
    @DisplayName("BH-041: the per-section counts a single shared pass was assumed to destroy")
    void perSectionResponseStillReportsItsOwnCountsAfterOneSharedPass() {
        User user = user();
        Account account = account(user, "Savings");

        // The counts are only ever non-zero when a section's rows duplicate rows ALREADY in the
        // SAME account -- the duplicate pass groups by accountId. On a first import of a composite
        // statement every section is a different account, so the counts are structurally 0. The
        // case that produces a real number is a RE-import, which is what this models.
        var firstImport = importService.confirm(user.getId(), "statement.pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(5, 0), account.getId(), null, null, null,
                null), 0);

        var reImport = importService.confirm(user.getId(), "statement.pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(5, 0), account.getId(), null, null, null,
                null), 0);

        assertThat(firstImport.duplicatesDetected())
                .as("nothing to duplicate on a first import")
                .isZero();
        assertThat(reImport.duplicatesDetected())
                .as("this is the number the deferral assumed would become 0 once reconciliation "
                        + "stopped running per section. It does not: tally() reads persisted flags "
                        + "scoped by this import's own statement_import_id, so it is indifferent to "
                        + "when the pass ran. MultiSectionSharedTransferIT proves the same thing on "
                        + "the real multi-section path.")
                .isEqualTo(5);
    }
}
