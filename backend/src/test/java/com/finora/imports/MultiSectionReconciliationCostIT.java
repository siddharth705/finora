package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
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
import org.springframework.mock.web.MockMultipartFile;

import jakarta.persistence.EntityManagerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * BH-041, the measurement — not the fix.
 *
 * <h2>The question this answers</h2>
 *
 * <p>{@code ImportService.confirm} ends by running {@code reconcileForUser} and
 * {@code detectForUser}, each of which loads the user's ENTIRE transaction history.
 * {@code confirmMultiSection} calls {@code confirm} once per account section, so an N-section
 * composite statement performs 2N whole-history passes where 2 would do.
 *
 * <p>Deduplicating that looked mechanical and is not, because each section's
 * {@code ConfirmResponse} reports {@code duplicatesDetected} and {@code transfersIdentified},
 * and both are computed from {@code DuplicateDetector.tally} AFTER reconciliation has run. Running
 * reconciliation once at the end would make every per-section response report zero. Whether that
 * matters is a question about consumers, not about code, and it was not something to decide
 * silently inside a performance change.
 *
 * <h2>Why this is a test and not a paragraph in a document</h2>
 *
 * <p>Same reason {@code ImportQueryCountIT} gives: the last performance document in this repository
 * was stale within forty minutes. A number nobody can re-derive in one command is a number that
 * stops being true without anybody noticing. This asserts the SHAPE of the cost (that it scales
 * with section count) rather than a wall-clock figure, so it cannot rot into a flaky machine-speed
 * assertion — and it prints the figures so the decision can be made against real ones.
 */
class MultiSectionReconciliationCostIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @SpyBean private ReconciliationService reconciliationService;
    @SpyBean private RecurringService recurringService;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

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

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "statement.pdf", "application/pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row(int i) {
        return new ConfirmedRow(LocalDate.of(2026, 7, (i % 28) + 1), "MERCHANT " + i + " STORE",
                new BigDecimal(100 + i + ".00"), "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private List<ConfirmedRow> rows(int count, int offset) {
        List<ConfirmedRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(row(offset + i));
        return rows;
    }

    /** One section confirmed through the same per-account entry point confirmMultiSection uses. */
    private void confirmSection(User user, Account account, int rowCount, int offset) {
        importService.confirm(user.getId(), "statement.pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(rowCount, offset), account.getId(), null, null, null),
                0);
    }

    /** Gives the user a history worth scanning, so the passes cost what they cost in real life. */
    private void seedHistory(User user, Account account, int rows) {
        importService.confirm(user.getId(), "history.csv",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(rows, 1000), account.getId(), null, null, null));
    }

    private record Cost(long statements, long queries, long elapsedMs) {}

    @Test
    @DisplayName("BH-041: three sections cost three reconciliation passes; one section costs one")
    void reconciliationCostScalesWithSectionCountNotWithTheStatement() {
        int history = 200;
        int totalRows = 60;

        // --- three sections, 20 rows each ---
        User multi = user();
        Account historyAccount = account(multi, "History");
        seedHistory(multi, historyAccount, history);
        List<Account> sections = List.of(
                account(multi, "Savings section"), account(multi, "Card section"), account(multi, "Deposit section"));

        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        // Seeding the history is itself a confirm, so it reconciled once already. Cleared so both
        // the counters and the spies describe only the window being measured.
        org.mockito.Mockito.clearInvocations(reconciliationService, recurringService);
        long startedAt = System.nanoTime();
        for (int i = 0; i < sections.size(); i++) {
            confirmSection(multi, sections.get(i), totalRows / sections.size(), i * (totalRows / sections.size()));
        }
        Cost threeSections = new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount(),
                (System.nanoTime() - startedAt) / 1_000_000);
        // Verified HERE, not at the end: the next measurement clears the spies, which would wipe
        // these records before an end-of-test assertion could read them.
        verify(reconciliationService, org.mockito.Mockito.times(sections.size())).reconcileForUser(multi.getId());
        verify(recurringService, org.mockito.Mockito.times(sections.size())).detectForUser(multi.getId());

        // --- one section, all 60 rows, same history size: the shape a single reconciliation pass
        //     at the end would produce, for the same amount of imported data ---
        User single = user();
        Account singleHistory = account(single, "History");
        seedHistory(single, singleHistory, history);
        Account only = account(single, "Only section");

        stats.clear();
        org.mockito.Mockito.clearInvocations(reconciliationService, recurringService);
        startedAt = System.nanoTime();
        confirmSection(single, only, totalRows, 0);
        Cost oneSection = new Cost(stats.getPrepareStatementCount(), stats.getQueryExecutionCount(),
                (System.nanoTime() - startedAt) / 1_000_000);
        verify(reconciliationService, org.mockito.Mockito.times(1)).reconcileForUser(single.getId());

        System.out.printf(
                "%nBH-041 multi-section confirm cost (history %d rows, %d imported rows either way)%n"
                + "                          3 sections    1 section     delta%n"
                + "  reconcile passes ....... %8d %12d %9d%n"
                + "  recurring passes ....... %8d %12d %9d%n"
                + "  prepared statements .... %8d %12d %9d%n"
                + "  query executions ....... %8d %12d %9d%n"
                + "  elapsed (ms) ........... %8d %12d %9d%n%n",
                history, totalRows,
                sections.size(), 1, sections.size() - 1,
                sections.size(), 1, sections.size() - 1,
                threeSections.statements(), oneSection.statements(),
                threeSections.statements() - oneSection.statements(),
                threeSections.queries(), oneSection.queries(),
                threeSections.queries() - oneSection.queries(),
                threeSections.elapsedMs(), oneSection.elapsedMs(),
                threeSections.elapsedMs() - oneSection.elapsedMs());

        // The claim -- that reconciliation and recurring detection each run once PER SECTION, and
        // each loads the whole history -- is asserted inline above, next to the measurement each
        // one belongs to.

        assertThat(threeSections.statements())
                .as("three sections must cost strictly more than one for the same imported rows -- "
                        + "if this ever stops holding, the per-section passes have been removed and "
                        + "this measurement is obsolete")
                .isGreaterThan(oneSection.statements());
    }

    @Test
    @DisplayName("BH-041: what the per-section response actually reports, on the case where it is non-zero")
    void perSectionResponseReportsCountsComputedAfterReconciliation() {
        User user = user();
        Account account = account(user, "Savings");

        // The counts are only ever non-zero when a section's rows duplicate rows ALREADY in the
        // SAME account -- the duplicate pass groups by accountId. On a first import of a composite
        // statement every section is a different account, so the counts are structurally 0. The
        // case that produces a real number is a RE-import, which is what this models.
        var firstImport = importService.confirm(user.getId(), "statement.pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(5, 0), account.getId(), null, null, null), 0);

        var reImport = importService.confirm(user.getId(), "statement.pdf",
                "rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8),
                new ConfirmRequest(null, rows(5, 0), account.getId(), null, null, null), 0);

        System.out.printf(
                "BH-041 per-section response%n"
                + "  first import  -> imported %d, duplicatesDetected %d, transfersIdentified %d%n"
                + "  re-import     -> imported %d, duplicatesDetected %d, transfersIdentified %d%n"
                + "  Both fields are read from DuplicateDetector.tally, which runs AFTER%n"
                + "  reconciliation. Defer reconciliation to the end of confirmMultiSection and%n"
                + "  every per-section response reports 0 here instead of %d.%n%n",
                firstImport.imported(), firstImport.duplicatesDetected(), firstImport.transfersIdentified(),
                reImport.imported(), reImport.duplicatesDetected(), reImport.transfersIdentified(),
                reImport.duplicatesDetected());

        assertThat(firstImport.duplicatesDetected())
                .as("nothing to duplicate on a first import")
                .isZero();
        assertThat(reImport.duplicatesDetected())
                .as("this is the number that would become 0 if reconciliation were deferred -- "
                        + "which is what makes deferring a contract change rather than an optimisation")
                .isPositive();
    }
}
