package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.integrations.google.merchant.GmailReconciliationMatcher;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Reconciliation accuracy benchmark, category 5 of 6: Gmail cross-source matching. See
 * ReconciliationBenchmarkSupport's own doc comment for how to run this and what a red assertion
 * means.
 *
 * <p>Two different things are under test here, and each scenario says which:
 * <ul>
 *   <li>Scenarios 1-5 call {@link GmailReconciliationMatcher#findMatchAmongTransactions} directly,
 *       with real repositories mocked out (that method takes its candidate list as a plain
 *       parameter and never queries them) -- these are about the MATCHER's own merchant-similarity
 *       quality, independent of {@link ReconciliationService}.
 *   <li>Scenarios 6-7 go through the full mocked {@link ReconciliationService} (this class's
 *       inherited harness) -- these are about how the SERVICE wires the matcher in: which
 *       candidates it ever offers it, and how the ordinary duplicate pass interacts with
 *       Gmail-sourced rows.
 * </ul>
 */
class GmailMatchingBenchmark extends ReconciliationBenchmarkSupport {

    private GmailReconciliationMatcher realMatcher() {
        return new GmailReconciliationMatcher(mock(TransactionRepository.class), mock(AccountRepository.class));
    }

    @Test
    @DisplayName("BASELINE (known-good): an identical merchant name is matched")
    void perfectMatch_isFound() {
        Account card = cardAccount("5001");
        Transaction gmailTxn = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "Amazon");
        Transaction bankTxn = txn(card, LocalDate.of(2026, 7, 11), "499.00", Transaction.Type.EXPENSE, "AMAZON ORDER 4521");

        var match = realMatcher().findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match).contains(bankTxn);
    }

    @Test
    @DisplayName("BASELINE (known-good): a common abbreviation (\"AMZN\" for \"Amazon\") is matched via edit-distance similarity")
    void merchantNameVariation_abbreviationIsFound() {
        Account card = cardAccount("5001");
        Transaction gmailTxn = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "Amazon");
        Transaction bankTxn = txn(card, LocalDate.of(2026, 7, 11), "499.00", Transaction.Type.EXPENSE, "AMZN MKTPLACE 4521");

        var match = realMatcher().findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match)
                .as("\"amazon\" vs \"amzn\" is the exact pair GmailReconciliationMatcher's own "
                        + "SIMILARITY_THRESHOLD doc comment cites as the case it was tuned to pass")
                .contains(bankTxn);
    }

    @Test
    @DisplayName("GAP: a known brand-to-legal-entity alias (Swiggy / Bundl Technologies) is not matched")
    void merchantAlias_brandToLegalEntityName_isNotFound() {
        // This project already has a mechanism for exactly this problem -- MerchantAlias /
        // MerchantNormalizationEngine, a curated alias table used elsewhere in categorization. This
        // matcher does not consult it at all: it is pure Levenshtein edit-distance over raw text,
        // and "swiggy" vs "bundl"/"technologies" share almost no characters, so no edit-distance
        // threshold could bridge them without a curated mapping.
        Account card = cardAccount("5001");
        Transaction gmailTxn = txn(card, LocalDate.of(2026, 7, 10), "650.00", Transaction.Type.EXPENSE, "Swiggy");
        Transaction bankTxn = txn(card, LocalDate.of(2026, 7, 11), "650.00", Transaction.Type.EXPENSE, "BUNDL TECHNOLOGIES PVT LTD");

        var match = realMatcher().findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match)
                .as("GAP: same real Swiggy order, same amount, one day apart -- invisible to this "
                        + "matcher because it never looks up the merchant-alias table this project "
                        + "already maintains. If the user also has a bank-side import of this same "
                        + "charge, it is counted twice: once via the Gmail receipt, once via the bank "
                        + "row, with no cross-source link between them.")
                .contains(bankTxn);
    }

    @Test
    @DisplayName("BASELINE (known-good): a statement-truncated merchant name is still matched")
    void partialMerchantMatch_truncatedNarration_isFound() {
        // Real bank statements truncate merchant names to fit a fixed column width -- "BIGBASKET"
        // printed as "BIGBASKE" is a realistic, not contrived, truncation.
        Account card = cardAccount("5001");
        Transaction gmailTxn = txn(card, LocalDate.of(2026, 7, 10), "1250.00", Transaction.Type.EXPENSE, "Bigbasket");
        Transaction bankTxn = txn(card, LocalDate.of(2026, 7, 11), "1250.00", Transaction.Type.EXPENSE, "BIGBASKE ONLINE");

        var match = realMatcher().findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match).contains(bankTxn);
    }

    @Test
    @DisplayName("GAP (false positive): two unrelated short brand names that happen to be one edit apart are wrongly matched")
    void falsePositive_shortTokenEditDistanceCoincidence_wronglyMatched() {
        // "zoom" and "room" are both exactly 4 characters, one substitution apart -- similarity
        // 0.75, comfortably over the 0.6 threshold. MIN_BRAND_TOKEN_LENGTH (3) only screens out
        // tokens SHORTER than 3 characters; it does nothing for two same-length, unrelated 4-letter
        // words. A Zoom subscription receipt and an unrelated room-rent payment of the same amount,
        // a day apart, is not a contrived coincidence -- round subscription/rent amounts collide
        // often.
        Account savings = account();
        Transaction gmailTxn = txn(savings, LocalDate.of(2026, 7, 10), "1500.00", Transaction.Type.EXPENSE, "Zoom");
        Transaction bankTxn = txn(savings, LocalDate.of(2026, 7, 11), "1500.00", Transaction.Type.EXPENSE, "ROOM RENT PAYMENT");

        var match = realMatcher().findMatchAmongTransactions(gmailTxn, List.of(bankTxn));

        assertThat(match)
                .as("GAP: an unrelated rent payment gets linked to an unrelated Zoom subscription "
                        + "receipt purely because \"zoom\" and \"room\" are one Levenshtein edit apart")
                .isEmpty();
    }

    @Test
    @DisplayName("BASELINE (known-good): a candidate 4 days away, just outside the 3-day date window, is never even offered to the matcher")
    void dateMismatch_beyondThreeDayWindow_neverOfferedToMatcher() {
        Account card = cardAccount("5001");
        Transaction bankTxn = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "AMAZON ORDER 4521");
        bankTxn.setSource(Transaction.Source.CSV_IMPORT);
        Transaction gmailTxn = txn(card, LocalDate.of(2026, 7, 14), "499.00", Transaction.Type.EXPENSE, "Amazon");
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);
        loadTransactions(bankTxn, gmailTxn);

        run();

        assertThat(capturedEdges()).isEmpty();
        verifyNoInteractions(gmailReconciliationMatcher);
    }

    @Test
    @DisplayName("BASELINE (known-good): the same Gmail receipt imported twice (a forwarded duplicate email) is caught by the ordinary duplicate pass, not the cross-source pass")
    void duplicateEmailReceipts_sameReceiptTwice_caughtByOrdinaryDuplicatePass() {
        Account card = cardAccount("5001");
        Transaction receipt1 = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "Amazon");
        receipt1.setSource(Transaction.Source.GMAIL_IMPORT);
        Transaction receipt2 = txn(card, LocalDate.of(2026, 7, 10), "499.00", Transaction.Type.EXPENSE, "Amazon");
        receipt2.setSource(Transaction.Source.GMAIL_IMPORT);
        loadTransactions(receipt1, receipt2);

        run();

        assertThat(receipt2.getIsDuplicateOf())
                .as("identical account+date+amount+description -- pass 1 (exact duplicates) already "
                        + "handles this regardless of source")
                .isEqualTo(receipt1.getId());
    }
}
