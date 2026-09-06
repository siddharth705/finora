package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation accuracy benchmark, category 3 of 6: investment transfers. See
 * ReconciliationBenchmarkSupport's own doc comment for how to run this and what a red assertion
 * means.
 *
 * <p>Unlike every other pass, the investment-transfer pass has no pairing logic at all: it is a
 * pure category gate. An EXPENSE row is excluded from cash flow (status INVESTMENT_TRANSFER, no
 * graph edge) purely because {@code CategoryRules.suggestCategory(description)} returns
 * "Investments" -- which means every failure mode here is really a {@code CategoryRules}
 * keyword-matching gap wearing a reconciliation hat, not a matching-logic defect the way the
 * transfer/refund passes have.
 */
class InvestmentTransferBenchmark extends ReconciliationBenchmarkSupport {

    @Test
    @DisplayName("BASELINE (known-good): a Groww outflow, real corpus narration shape, is excluded from cash flow")
    void grooveOutflow_isExcludedFromCashFlow() {
        Account savings = account();
        Transaction outflow = txn(savings, LocalDate.of(2026, 7, 5), "10000.00", Transaction.Type.EXPENSE, "UPI-GROWW INVEST TECH");
        loadTransactions(outflow);

        run();

        assertThat(outflow.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    @DisplayName("BASELINE (known-good): a Zerodha outflow is excluded from cash flow")
    void zerodhaOutflow_isExcludedFromCashFlow() {
        Account savings = account();
        Transaction outflow = txn(savings, LocalDate.of(2026, 7, 6), "25000.00", Transaction.Type.EXPENSE, "ZERODHA BROKING LTD");
        loadTransactions(outflow);

        run();

        assertThat(outflow.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    @DisplayName("BASELINE (known-good): a mutual fund SIP debit is excluded from cash flow")
    void mutualFundSip_isExcludedFromCashFlow() {
        Account savings = account();
        Transaction outflow = txn(savings, LocalDate.of(2026, 7, 7), "5000.00", Transaction.Type.EXPENSE, "ACH SIP HDFC MUTUAL FUND");
        loadTransactions(outflow);

        run();

        assertThat(outflow.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    @DisplayName("BASELINE (known-good): an NPS contribution is excluded from cash flow")
    void npsContribution_isExcludedFromCashFlow() {
        Account savings = account();
        Transaction outflow = txn(savings, LocalDate.of(2026, 7, 8), "6000.00", Transaction.Type.EXPENSE, "NPS TIER 1 CONTRIBUTION");
        loadTransactions(outflow);

        run();

        assertThat(outflow.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }

    @Test
    @DisplayName("BASELINE (known-good): multiple investment platforms funded in the same run are each independently excluded")
    void multiplePlatformsInOneRun_allIndependentlyExcluded() {
        Account savings = account();
        Transaction groww = txn(savings, LocalDate.of(2026, 7, 10), "10000.00", Transaction.Type.EXPENSE, "UPI-GROWW INVEST TECH");
        Transaction zerodha = txn(savings, LocalDate.of(2026, 7, 11), "15000.00", Transaction.Type.EXPENSE, "ZERODHA BROKING LTD");
        Transaction sip = txn(savings, LocalDate.of(2026, 7, 12), "5000.00", Transaction.Type.EXPENSE, "ACH SIP HDFC MUTUAL FUND");
        Transaction ordinarySpend = txn(savings, LocalDate.of(2026, 7, 13), "1200.00", Transaction.Type.EXPENSE, "BIG BAZAAR");
        loadTransactions(groww, zerodha, sip, ordinarySpend);

        run();

        assertThat(groww.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(zerodha.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(sip.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(ordinarySpend.getReconciliationStatus())
                .as("must not over-fire on unrelated spend in the same run")
                .isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    @DisplayName("GAP: a broker-funding narration with the keyword fused into a longer token (no word boundary) is not recognized")
    void brokerFundingWithFusedKeyword_noWordBoundary_notRecognized() {
        // CategoryRules' own keyword patterns match on word boundaries (see its RULES map's own
        // comment on why bare 3-letter keywords like "emi"/"ngo" are excluded for exactly this
        // fusion risk). "groww" inside "icclgrowwpay" has no boundary on either side -- it is one
        // contiguous alphanumeric run after normalize() -- so it is invisible to suggestCategory,
        // even though the real corpus narration this project's own investment-transfer pass cites
        // as its motivating example ("UPI-ICCLGROWW-GROWW-BSE") only worked because "GROWW"
        // happened to also appear a second time as its own hyphen-delimited token. A payment
        // gateway that fuses the broker's aggregator ID into one token with no repeated standalone
        // mention -- a realistic, not contrived, narration shape -- falls through this crack.
        Account savings = account();
        Transaction outflow = txn(savings, LocalDate.of(2026, 7, 14), "8000.00", Transaction.Type.EXPENSE, "UPI-ICCLGROWWPAY-9876543210-BSE"); // synthetic-ok
        loadTransactions(outflow);

        run();

        assertThat(outflow.getReconciliationStatus())
                .as("GAP: a genuine Groww investment outflow is left as ordinary, uncategorized "
                        + "spend (status OK) instead of being excluded from cash flow -- it inflates "
                        + "the user's reported spending by the full investment amount")
                .isEqualTo(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
    }
}
