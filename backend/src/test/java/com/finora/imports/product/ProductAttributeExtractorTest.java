package com.finora.imports.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAttributeExtractorTest {

    private final ProductAttributeExtractor extractor = new ProductAttributeExtractor();

    private Map<String, String> row(String... kv) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) row.put(kv[i], kv[i + 1]);
        return row;
    }

    @Test
    void extractsAFixedDepositsOwnValues() {
        var rows = List.of(row("Principal Amount", "100000.00", "Maturity Date", "12/03/2027",
                "Rate of Interest", "7.10"));

        var attrs = extractor.extract(FinancialProductType.FIXED_DEPOSIT, rows);

        assertThat(attrs).hasSize(1);
        assertThat(attrs.get(0).principalAmount()).isEqualByComparingTo("100000.00");
        assertThat(attrs.get(0).interestRate()).isEqualByComparingTo("7.10");
        assertThat(attrs.get(0).maturityDate()).isEqualTo(LocalDate.of(2027, 3, 12));
    }

    @Test
    void twoRowsInAFixedDepositSectionAreTwoDifferentDeposits() {
        // A real FD section lists every deposit the customer holds, one row each. Two rows is two
        // products -- the common real-world shape, not an edge case.
        var rows = List.of(
                row("Principal Amount", "100000.00", "Maturity Date", "12/03/2027", "Rate of Interest", "7.10"),
                row("Principal Amount", "24053.00", "Maturity Date", "01/05/2027", "Rate of Interest", "6.90"));

        var attrs = extractor.extract(FinancialProductType.FIXED_DEPOSIT, rows);

        assertThat(attrs).hasSize(2);
        assertThat(attrs.get(0).principalAmount()).isEqualByComparingTo("100000.00");
        assertThat(attrs.get(1).principalAmount()).isEqualByComparingTo("24053.00");
        assertThat(attrs.get(0).maturityDate()).isNotEqualTo(attrs.get(1).maturityDate());
    }

    @Test
    void recurringDepositInstallmentsAreNeverSplitIntoSeparateProducts() {
        // Not a heuristic: RECURRING_DEPOSIT.hasTransactions() is already false because "an RD's
        // installments already appear on the savings account that funds it" -- the schedule was
        // always one product's payment history. Splitting these rows would silently multiply one
        // real deposit into several phantom accounts, a worse failure than the bug being fixed.
        var rows = List.of(
                row("Due Date", "05/05/2026", "Installment Paid", "5000.00", "Maturity Date", "05/05/2027",
                        "Rate of Interest", "6.75", "Status", "Paid"),
                row("Due Date", "05/06/2026", "Installment Paid", "5000.00", "Maturity Date", "05/05/2027",
                        "Rate of Interest", "6.75", "Status", "Paid"));

        var attrs = extractor.extract(FinancialProductType.RECURRING_DEPOSIT, rows);

        assertThat(attrs).as("one product, not one per installment row").hasSize(1);
        assertThat(attrs.get(0).installmentsPaid()).isEqualTo(2);
        assertThat(attrs.get(0).installmentsTotal()).isEqualTo(2);
        assertThat(attrs.get(0).interestRate()).isEqualByComparingTo("6.75");
        assertThat(attrs.get(0).maturityDate()).isEqualTo(LocalDate.of(2027, 5, 5));
    }

    @Test
    void aRecurringDepositsUnpaidInstallmentsAreNotCountedAsPaid() {
        var rows = List.of(
                row("Due Date", "05/05/2026", "Installment Paid", "5000.00", "Status", "Paid"),
                row("Due Date", "05/06/2026", "Installment Paid", "5000.00", "Status", "Due"));

        var attrs = extractor.extract(FinancialProductType.RECURRING_DEPOSIT, rows);

        assertThat(attrs.get(0).installmentsPaid()).isEqualTo(1);
        assertThat(attrs.get(0).installmentsTotal()).isEqualTo(2);
    }

    @Test
    void aScheduleWithNoStatusColumnCountsEveryRowAsPaidRatherThanAssumingFailure() {
        // Assuming unpaid with no evidence for it is the wrong direction to guess -- an installment
        // schedule with no status information should read as "this many rows were on the
        // statement," not as a customer behind on payments they may not be.
        var rows = List.of(
                row("Due Date", "05/05/2026", "Installment Paid", "5000.00"),
                row("Due Date", "05/06/2026", "Installment Paid", "5000.00"));

        var attrs = extractor.extract(FinancialProductType.RECURRING_DEPOSIT, rows);

        assertThat(attrs.get(0).installmentsPaid()).isEqualTo(2);
    }

    @Test
    void distinguishesPrincipalFromMaturityAmountByTheirTrailingAsterisk() {
        // A real HDFC-style layout uses "Amount(Rs)" for principal and "Amount(Rs)*" for the
        // maturity value on the same row -- normalizeHeaderCell only strips a trailing
        // parenthetical, so the asterisk after the closing paren keeps the two distinct.
        var rows = List.of(row("Amount(Rs)", "50000.00", "Amount(Rs)*", "58000.00"));

        var attrs = extractor.extract(FinancialProductType.FIXED_DEPOSIT, rows);

        assertThat(attrs.get(0).principalAmount()).isEqualByComparingTo("50000.00");
        assertThat(attrs.get(0).maturityAmount()).isEqualByComparingTo("58000.00");
    }

    @Test
    void aRecurringDepositsSharedTermsAreFoundOnWhicheverRowCarriesThem() {
        // These are the schedule's shared terms, so any row carrying them carries the right value --
        // but the first row is not reliably the one that does. Reading only row 0 silently lost the
        // whole product's rate and maturity date when a bucketing glitch blanked them on that one
        // row, even though every other row had them.
        var rows = List.of(
                row("Due Date", "05/05/2026", "Installment Paid", "5000.00"),
                row("Due Date", "05/06/2026", "Installment Paid", "5000.00",
                        "Rate of Interest", "6.75", "Maturity Date", "05/05/2027"));

        var attrs = extractor.extract(FinancialProductType.RECURRING_DEPOSIT, rows);

        assertThat(attrs.get(0).interestRate()).isEqualByComparingTo("6.75");
        assertThat(attrs.get(0).maturityDate()).isEqualTo(LocalDate.of(2027, 5, 5));
    }

    @Test
    void aProductWithNoStructuralVocabularyYieldsEmptyAttributesRatherThanGuessing() {
        var attrs = extractor.extract(FinancialProductType.MUTUAL_FUND,
                List.of(row("Folio", "12345")));

        assertThat(attrs).hasSize(1);
        assertThat(attrs.get(0).isEmpty()).isTrue();
    }

    @Test
    void noRowsYieldsOneEmptyAttributesRatherThanAnEmptyList() {
        var attrs = extractor.extract(FinancialProductType.FIXED_DEPOSIT, List.of());

        assertThat(attrs).hasSize(1);
        assertThat(attrs.get(0).isEmpty()).isTrue();
    }
}
