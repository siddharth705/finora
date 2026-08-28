package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures what audit findings BUG-17 and BUG-19/20 assert, instead of arguing about it.
 *
 * <p><b>Why this exists rather than a fix.</b> Those findings are complexity observations, not
 * demonstrated defects — the audit that raised them says so itself, and says the counts were never
 * measured against a running system. {@code docs/engineering/scaling-triggers.md} sets the bar for
 * acting: "a measured, synchronous bottleneck — a request-handling thread blocked long enough,
 * often enough, to affect other users. Not 'imports feel like they could be slow one day.'"
 * Optimising {@code reconcileForUser} on the strength of reading it would be exactly the
 * build-ahead-of-evidence this repository has written down a rule against. So: measure first,
 * decide second.
 *
 * <p><b>Not part of the normal suite.</b> The class name ends in Benchmark, which matches none of
 * surefire's include patterns (see backend/pom.xml), so it never runs in CI and never slows the
 * build. Run it deliberately:
 *
 * <pre>
 *   ./mvnw -o test -Dtest=ReconciliationScalingBenchmark -DfailIfNoTests=false
 * </pre>
 *
 * <p><b>What it measures, and what it does not.</b> The repository is mocked, so this isolates the
 * in-memory comparison cost — the quadratic pass itself, which is the actual claim. It does NOT
 * measure query counts, connection-pool contention, or Hibernate hydration of a real result set;
 * those need a database and a profiler, and hydration of 50k managed entities is plausibly the
 * larger cost of the two. Treat these numbers as a lower bound on the real thing.
 *
 * <p><b>It asserts nothing.</b> A timing assertion on a shared CI runner is a flaky test, and this
 * does not run in CI anyway. It prints a table; a human reads it against the trigger.
 */
class ReconciliationScalingBenchmark {

    private static final int[] SIZES = {1_000, 10_000, 50_000};

    private final UUID userId = UUID.randomUUID();

    /**
     * Seeded, so two runs are comparable and a change in the numbers means a change in the code.
     * Descriptions and amounts are drawn from small pools deliberately: identical amounts within
     * the date window are what make the transfer and refund passes do their inner-loop work, so a
     * corpus of all-distinct values would measure the cheap path and report a reassuring number
     * that means nothing.
     */
    private List<Transaction> syntheticHistory(int count) {
        Random random = new Random(20260805L);
        List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String[] descriptions = {
                "UPI PAYMENT", "CARD PURCHASE", "SALARY CREDIT", "ATM WITHDRAWAL",
                "NEFT TRANSFER", "REFUND RECEIVED", "BILL PAYMENT", "SUBSCRIPTION",
        };
        BigDecimal[] amounts = {
                new BigDecimal("199.00"), new BigDecimal("450.50"), new BigDecimal("1200.00"),
                new BigDecimal("85000.00"), new BigDecimal("2499.99"), new BigDecimal("75.00"),
        };

        LocalDate start = LocalDate.of(2024, 1, 1);
        List<Transaction> history = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            t.setUserId(userId);
            t.setAccountId(accounts.get(random.nextInt(accounts.size())));
            // Spread across ~2 years. The transfer pass only compares within a 4-day window, so
            // clustering matters: a corpus spanning a decade would understate the inner loop and a
            // single day would overstate it. Two years of daily activity is ordinary usage.
            t.setTxnDate(start.plusDays(random.nextInt(730)));
            t.setAmount(amounts[random.nextInt(amounts.length)]);
            t.setTxnType(random.nextInt(4) == 0 ? Transaction.Type.INCOME : Transaction.Type.EXPENSE);
            t.setDescription(descriptions[random.nextInt(descriptions.length)] + " " + random.nextInt(10_000));
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            history.add(t);
        }
        return history;
    }

    private ReconciliationService reconciliationService(List<Transaction> history) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.findByUserId(any())).thenReturn(history);
        return new ReconciliationService(repository, mock(RelationshipService.class), mock(AuditService.class),
                mock(TransactionGraphService.class));
    }

    private RecurringService recurringService(List<Transaction> history) {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.findByUserId(any())).thenReturn(history);
        return new RecurringService(repository, mock(RuleEngineService.class), mock(AuditService.class),
                mock(FeatureFlagService.class));
    }

    @Test
    void measureReconcileAndDetectAcrossHistorySizes() {
        System.out.println();
        System.out.println("Scaling measurement -- one synchronous call, per scaling-triggers.md");
        System.out.println("In-memory comparison cost only; repository mocked. See class doc.");
        System.out.println();
        System.out.printf("%10s  %18s  %18s%n", "txns", "reconcileForUser", "detectForUser");
        System.out.printf("%10s  %18s  %18s%n", "----", "----------------", "-------------");

        for (int size : SIZES) {
            // One untimed pass first: the JIT has not compiled these paths on the first call, and
            // reporting interpreter-speed numbers would overstate the cost by an order of
            // magnitude. This is a rough warmup, not JMH -- good enough to answer "is this seconds
            // or milliseconds", which is the question the trigger actually asks.
            //
            // The warmup gets its OWN history, and this matters more than it looks. reconcileForUser
            // MUTATES the transactions it classifies, and every pass skips what a previous run
            // already settled -- duplicates skip a non-null isDuplicateOf, the transfer candidate
            // list filters out isTransfer(), the refund pass skips any income whose status is no
            // longer OK. Warming up on the same list therefore timed a SECOND run over
            // already-reconciled data, which does a fraction of the work. The first version of this
            // benchmark did exactly that, and the numbers it published were correspondingly too
            // low. Fresh data per timed run is what makes this measure the case that actually
            // happens: a real import, reconciling rows nothing has classified yet.
            reconciliationService(syntheticHistory(size)).reconcileForUser(userId);
            recurringService(syntheticHistory(size)).detectForUser(userId);

            List<Transaction> forReconcile = syntheticHistory(size);
            List<Transaction> forDetect = syntheticHistory(size);
            long reconcileMs = timeOf(() -> reconciliationService(forReconcile).reconcileForUser(userId));
            long detectMs = timeOf(() -> recurringService(forDetect).detectForUser(userId));

            System.out.printf("%10d  %16d ms  %16d ms%n", size, reconcileMs, detectMs);
        }

        System.out.println();
        System.out.println("Read against scaling-triggers.md's background-worker trigger: a");
        System.out.println("request-handling thread blocked long enough, often enough, to affect");
        System.out.println("other users. Note this is reachable by ordinary usage rather than by");
        System.out.println("growth -- one account with a long import history is enough.");
        System.out.println();
    }

    private long timeOf(Runnable work) {
        long startedAt = System.nanoTime();
        work.run();
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    // --- Would bucketing refund candidates by account be worth it? ----------------------------

    /** Account counts to sweep. Real users hold a handful, not dozens -- the whole question is
     *  whether a partition that can only ever divide by "how many accounts you have" earns its
     *  complexity, and quoting a number for 50 accounts would flatter it dishonestly. */
    private static final int[] ACCOUNT_COUNTS = {2, 3, 5, 10};

    private List<Transaction> syntheticHistory(int count, int accountCount) {
        List<Transaction> history = syntheticHistory(count);
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) accounts.add(UUID.randomUUID());
        Random random = new Random(20260805L);
        for (Transaction t : history) t.setAccountId(accounts.get(random.nextInt(accountCount)));
        return history;
    }

    /**
     * Measures the opportunity, not a prototype.
     *
     * <p>The refund pass already narrows to a 180-day slice (shipped in 33cf944) and then rejects
     * every candidate on a different account, one at a time, inside the loop. Bucketing by account
     * would move that rejection out of the loop. What that saves is exactly the count of
     * cross-account candidates examined, so counting them answers the question without building
     * anything — and a count is deterministic, where a timing on a shared runner is not.
     *
     * <p>Deliberately re-implements only the CANDIDATE SELECTION, not the matching rules. The
     * predicates that decide a refund are untouched by bucketing and reproducing them here would
     * risk measuring a divergent copy rather than the real pass.
     */
    @Test
    void measureRefundCandidateReductionFromAccountBucketing() {
        System.out.println();
        System.out.println("Refund pass: cross-account candidates examined per run, at 50k transactions");
        System.out.println("(the work account bucketing would remove -- see class doc)");
        System.out.println();
        System.out.printf("%9s  %16s  %16s  %10s%n", "accounts", "examined now", "after bucketing", "removed");
        System.out.printf("%9s  %16s  %16s  %10s%n", "--------", "------------", "---------------", "-------");

        // 10k rather than 50k: the ratio is structural (accounts are assigned uniformly, so the
        // reduction is 1 - 1/accounts at any size) and 50k across four account counts took nearly
        // four minutes, which is a benchmark nobody re-runs. The absolute cost at 50k is measured
        // separately below, where it matters.
        for (int accountCount : ACCOUNT_COUNTS) {
            List<Transaction> candidates = sortedCandidates(syntheticHistory(10_000, accountCount));

            long examinedNow = 0;
            long examinedBucketed = 0;
            for (Transaction income : candidates) {
                if (income.getTxnType() != Transaction.Type.INCOME) continue;
                LocalDate from = income.getTxnDate().minusDays(ReconciliationPolicy.REFUND_WINDOW_DAYS);
                for (Transaction expense : candidates) {
                    if (expense.getTxnDate().isBefore(from) || expense.getTxnDate().isAfter(income.getTxnDate())) continue;
                    examinedNow++;
                    if (expense.getAccountId().equals(income.getAccountId())) examinedBucketed++;
                }
            }

            double removedPct = examinedNow == 0 ? 0 : 100.0 * (examinedNow - examinedBucketed) / examinedNow;
            System.out.printf("%9d  %16d  %16d  %9.1f%%%n",
                    accountCount, examinedNow, examinedBucketed, removedPct);
        }

        // The number that actually decides it: what the whole pass costs today, against what the
        // candidates it would stop examining cost. A count is a ceiling on the benefit; this is the
        // benefit's actual size relative to the thing being optimised.
        System.out.println();
        System.out.println("Cost in context, 50k transactions, 3 accounts (a realistic holding):");
        // Fresh history for the timed run, warmed on a separate copy -- see the note in the other
        // benchmark method for why sharing it silently measures a second run instead of a first.
        reconciliationService(syntheticHistory(50_000, 3)).reconcileForUser(userId);
        List<Transaction> forTiming = syntheticHistory(50_000, 3);
        long wholeRunMs = timeOf(() -> reconciliationService(forTiming).reconcileForUser(userId));

        List<Transaction> candidates = sortedCandidates(syntheticHistory(50_000, 3));
        long crossAccountScanMs = timeOf(() -> {
            long sink = 0;
            for (Transaction income : candidates) {
                if (income.getTxnType() != Transaction.Type.INCOME) continue;
                LocalDate from = income.getTxnDate().minusDays(ReconciliationPolicy.REFUND_WINDOW_DAYS);
                for (Transaction expense : between(candidates, from, income.getTxnDate())) {
                    // The two predicates the real pass applies before anything expensive, in the
                    // same order. Bucketing removes exactly the iterations where the second fails.
                    if (expense.getTxnType() != Transaction.Type.EXPENSE) continue;
                    if (!expense.getAccountId().equals(income.getAccountId())) { sink++; continue; }
                }
            }
            if (sink < 0) throw new IllegalStateException(); // keep the loop from being optimised away
        });

        System.out.printf("  whole reconcileForUser run   : %d ms%n", wholeRunMs);
        System.out.printf("  cross-account iterations only: %d ms  (what bucketing could remove)%n",
                crossAccountScanMs);

        System.out.println();
        System.out.println("Read the percentages as a ceiling, not a saving. Each removed candidate");
        System.out.println("removes one getAccountId().equals() -- among the cheapest predicates in");
        System.out.println("the pass -- while bucketing adds a grouping pass and a per-account");
        System.out.println("structure to build and keep correct. The reduction has to be large");
        System.out.println("enough to pay for that, which is what the two timings above decide.");
        System.out.println();
    }

    /** The (txnDate, id) ordering the real pass builds, so the windowed lookups behave identically. */
    private static List<Transaction> sortedCandidates(List<Transaction> history) {
        return history.stream()
                .sorted(Comparator.comparing(Transaction::getTxnDate).thenComparing(Transaction::getId))
                .toList();
    }

    /** Mirrors ReconciliationService's own date-window slice (private there). */
    private static List<Transaction> between(List<Transaction> sortedByDate, LocalDate from, LocalDate to) {
        int start = 0, end = sortedByDate.size();
        int low = 0, high = sortedByDate.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedByDate.get(mid).getTxnDate().isBefore(from)) low = mid + 1; else high = mid;
        }
        start = low;
        low = 0; high = sortedByDate.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedByDate.get(mid).getTxnDate().isAfter(to)) high = mid; else low = mid + 1;
        }
        end = low;
        return start >= end ? List.of() : sortedByDate.subList(start, end);
    }
}
