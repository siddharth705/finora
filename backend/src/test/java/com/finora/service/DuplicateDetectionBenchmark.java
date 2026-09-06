package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation accuracy benchmark, category 1 of 6: duplicate detection. See
 * ReconciliationBenchmarkSupport's own doc comment for why this class is deliberately excluded
 * from the default {@code mvn test} gate, and docs/proposals/reconciliation-benchmark/README.md
 * for how to run it and what to do with a red assertion here.
 *
 * <p>Each test's expectation is what a CORRECT verdict is for a realistic Indian-bank narration
 * pattern, established independently of {@link ReconciliationService}'s current code -- some of
 * these are already known-good (kept here as regression anchors so a future change can't silently
 * break them without this suite noticing) and some are known gaps this benchmark exists to surface.
 * Each test says which, in its own comment, BEFORE running it -- not written up after the fact from
 * whatever the assertion happened to do, which is exactly the guessing this project's evidence
 * rule (CLAUDE.md, "No guessing") forbids.
 */
class DuplicateDetectionBenchmark extends ReconciliationBenchmarkSupport {

    @Test
    @DisplayName("BASELINE (known-good): identical statement rows re-scraped twice are flagged DUPLICATE")
    void exactDuplicate_isFlagged() {
        Account card = cardAccount("5001");
        Transaction original = txn(card, LocalDate.of(2026, 7, 3), "486.00", Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR");
        Transaction reimported = txn(card, LocalDate.of(2026, 7, 3), "486.00", Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR");
        loadTransactions(original, reimported);

        run();

        assertThat(reimported.getIsDuplicateOf()).isEqualTo(original.getId());
        assertThat(reimported.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
    }

    @Test
    @DisplayName("GAP: the same UPI payment, described differently in its pending vs. settled row, is not caught")
    void nearDuplicate_reformattedNarrationAcrossPendingAndSettled_isNotCaught() {
        // Real UPI apps/banks routinely surface a transaction once as an app-side pending
        // notification narration and again, days later, as the bank's own settled-statement
        // narration for the exact same payment -- same rail reference, same money, two different
        // strings. DuplicateMatching.normalizeDescription only folds case and edge whitespace (see
        // its own doc comment); it has no notion of "these two strings describe the same UPI leg".
        Account wallet = account();
        Transaction pendingNotification = txn(wallet, LocalDate.of(2026, 7, 14), "1499.00",
                Transaction.Type.EXPENSE, "UPI-SWIGGY-9182xxxx@ybl-9876543210"); // synthetic-ok
        Transaction settledStatementRow = txn(wallet, LocalDate.of(2026, 7, 14), "1499.00",
                Transaction.Type.EXPENSE, "UPI/DR/9876543210/SWIGGY/ybl/Payment from Phone"); // synthetic-ok
        loadTransactions(pendingNotification, settledStatementRow);

        run();

        assertThat(settledStatementRow.getIsDuplicateOf())
                .as("GAP: both rows are the same real payment (identical amount, date, and the same "
                        + "UPI reference number embedded in both narrations) but the duplicate pass has "
                        + "no way to see that -- it currently leaves both rows OK, silently "
                        + "double-counting one real expense as two")
                .isEqualTo(pendingNotification.getId());
    }

    @Test
    @DisplayName("BASELINE (known-good): same amount, same day, different merchant is never flagged")
    void sameAmountSameDayDifferentMerchant_notFlagged() {
        Account card = cardAccount("5001");
        Transaction swiggy = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "SWIGGY ORDER 771");
        Transaction amazon = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "AMAZON ORDER 4521");
        loadTransactions(swiggy, amazon);

        run();

        assertThat(swiggy.getIsDuplicateOf()).isNull();
        assertThat(amazon.getIsDuplicateOf()).isNull();
    }

    @Test
    @DisplayName("BASELINE (known-good): monthly SIPs/EMIs recurring on different dates are never flagged")
    void recurringMerchant_differentMonths_notFlagged() {
        Account savings = account();
        Transaction julySip = txn(savings, LocalDate.of(2026, 7, 5), "5000.00", Transaction.Type.EXPENSE, "ACH SIP GROWW MUTUAL FUND");
        Transaction augustSip = txn(savings, LocalDate.of(2026, 8, 5), "5000.00", Transaction.Type.EXPENSE, "ACH SIP GROWW MUTUAL FUND");
        loadTransactions(julySip, augustSip);

        run();

        assertThat(julySip.getIsDuplicateOf()).isNull();
        assertThat(augustSip.getIsDuplicateOf()).isNull();
    }

    @Test
    @DisplayName("GAP: two different same-day SIP installments, identical amount/narration, no balance or reference column, are wrongly merged")
    void sipInstallments_sameDaySameAmountNoDiscriminator_wronglyMerged() {
        // Real, confirmed pattern (see ReconciliationService.splitByDiscriminator's own doc
        // comment: an HDFC statement with four same-day SIP installments, distinguished only by a
        // running balance). This scenario is the case that discriminator CANNOT rescue: a
        // Gmail-receipt-style or minimal-column import that captures neither balanceAfter nor
        // referenceNumber for either row. Two genuinely different SIP debits -- e.g. to two
        // different fund folios -- sharing the coincidence of equal amount is a real, not
        // hypothetical, retail pattern (round-number SIP amounts like 5000 are extremely common).
        Account savings = account();
        Transaction sipFolioA = txn(savings, LocalDate.of(2026, 7, 5), "5000.00", Transaction.Type.EXPENSE, "ACH SIP GROWW MUTUAL FUND");
        Transaction sipFolioB = txn(savings, LocalDate.of(2026, 7, 5), "5000.00", Transaction.Type.EXPENSE, "ACH SIP GROWW MUTUAL FUND");
        loadTransactions(sipFolioA, sipFolioB);

        run();

        assertThat(sipFolioB.getIsDuplicateOf())
                .as("GAP: two REAL, separate SIP debits with no balance/reference discriminator "
                        + "available fall back to splitByDiscriminator's undiscriminated case and are "
                        + "merged into one duplicate cluster -- one real ₹5,000 investment silently "
                        + "vanishes from the user's records")
                .isNull();
    }

    @Test
    @DisplayName("GAP: two different same-day EMI auto-debits, identical amount/narration, no balance or reference column, are wrongly merged")
    void emiPayments_sameDaySameAmountNoDiscriminator_wronglyMerged() {
        // Same root cause as the SIP case above, different domain: a person paying an auto-loan EMI
        // and a personal-loan EMI that happen to be equal (a common coincidence for someone with two
        // loans at round EMI amounts) on the same due date, through a bank that doesn't print a
        // per-row reference for standing-instruction debits.
        Account savings = account();
        Transaction autoLoanEmi = txn(savings, LocalDate.of(2026, 7, 7), "12500.00", Transaction.Type.EXPENSE, "ECS EMI AUTO DEBIT");
        Transaction personalLoanEmi = txn(savings, LocalDate.of(2026, 7, 7), "12500.00", Transaction.Type.EXPENSE, "ECS EMI AUTO DEBIT");
        loadTransactions(autoLoanEmi, personalLoanEmi);

        run();

        assertThat(personalLoanEmi.getIsDuplicateOf())
                .as("GAP: two real EMIs to different loan accounts, coincidentally equal, are merged "
                        + "into one duplicate -- one real EMI payment vanishes from spend totals")
                .isNull();
    }

    @Test
    @DisplayName("BASELINE (known-good): a genuine duplicate payroll credit is flagged")
    void salaryDuplicate_truePayrollErrorIsFlagged() {
        // The duplicate pass has no salary-specific carve-out (only the TRANSFER pass's
        // looksLikeSalary guard does) -- a true double-credit from a payroll error must still be
        // caught exactly like any other exact-key collision.
        Account savings = account();
        Transaction salary = txn(savings, LocalDate.of(2026, 7, 1), "85000.00", Transaction.Type.INCOME, "NEFT SALARY PAYMENT XYZ CORP");
        Transaction duplicateSalary = txn(savings, LocalDate.of(2026, 7, 1), "85000.00", Transaction.Type.INCOME, "NEFT SALARY PAYMENT XYZ CORP");
        loadTransactions(salary, duplicateSalary);

        run();

        assertThat(duplicateSalary.getIsDuplicateOf()).isEqualTo(salary.getId());
        assertThat(duplicateSalary.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
    }

    @Test
    @DisplayName("BASELINE (known-good): a whole statement re-imported a second time is fully deduplicated, row for row")
    void statementReimport_everyRowFlagged() {
        Account card = cardAccount("5001");
        Transaction row1 = txn(card, LocalDate.of(2026, 7, 3), "486.00", Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR");
        Transaction row2 = txn(card, LocalDate.of(2026, 7, 4), "1299.00", Transaction.Type.EXPENSE, "AMAZON ORDER 4521");
        Transaction row3 = txn(card, LocalDate.of(2026, 7, 6), "250.00", Transaction.Type.EXPENSE, "UBER TRIP");
        Transaction row1Reimport = txn(card, LocalDate.of(2026, 7, 3), "486.00", Transaction.Type.EXPENSE, "SWIGGY*ORDR9182 BLR");
        Transaction row2Reimport = txn(card, LocalDate.of(2026, 7, 4), "1299.00", Transaction.Type.EXPENSE, "AMAZON ORDER 4521");
        Transaction row3Reimport = txn(card, LocalDate.of(2026, 7, 6), "250.00", Transaction.Type.EXPENSE, "UBER TRIP");
        loadTransactions(row1, row2, row3, row1Reimport, row2Reimport, row3Reimport);

        run();

        assertThat(row1Reimport.getIsDuplicateOf()).isEqualTo(row1.getId());
        assertThat(row2Reimport.getIsDuplicateOf()).isEqualTo(row2.getId());
        assertThat(row3Reimport.getIsDuplicateOf()).isEqualTo(row3.getId());
    }

    @Test
    @DisplayName("GAP: a manually-entered transaction and its later bank-statement import of the same real payment are never linked")
    void manualEntryThenImport_sameRealTransaction_differentDescription_notCaught() {
        // The single most common real "duplicate" a user actually files a complaint about: they
        // type "Dinner with Raj" into the app the night of the expense, then import the card
        // statement at month-end, which carries the bank's own narration for the identical charge.
        // duplicateKey compares raw description text; there is no cross-check by amount+date alone
        // (deliberately -- ReconciliationService's own comment says over-matching on weaker keys is
        // not accepted), so this is invisible to the duplicate pass today.
        Account card = cardAccount("5001");
        Transaction manualEntry = txn(card, LocalDate.of(2026, 7, 12), "1850.00", Transaction.Type.EXPENSE, "Dinner with Raj");
        manualEntry.setSource(Transaction.Source.MANUAL);
        Transaction importedRow = txn(card, LocalDate.of(2026, 7, 12), "1850.00", Transaction.Type.EXPENSE, "BARBEQUE NATION BLR");
        importedRow.setSource(Transaction.Source.CSV_IMPORT);
        loadTransactions(manualEntry, importedRow);

        run();

        assertThat(importedRow.getIsDuplicateOf())
                .as("GAP: same real charge, same day, same amount, manual note vs. bank narration -- "
                        + "today this is two full, unlinked expenses; this project's own duplicate-key "
                        + "design (account+date+amount+description) trades this false negative for "
                        + "safety against over-matching, but it is a real, common user-facing gap")
                .isEqualTo(manualEntry.getId());
    }
}
