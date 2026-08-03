package com.finora.imports.product;

import com.finora.imports.CsvParser;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 2.5: given a section already classified as a deposit product, extracts what it actually
 * SAYS -- not just that a maturity field is present, but that it reads 12/03/2027.
 *
 * Reuses {@link CsvParser}'s header-matching and value-parsing directly rather than re-deriving
 * numeric/date parsing a second time; the only new logic here is knowing which column names hold
 * which deposit-specific value.
 *
 * <h2>Fixed deposits: one row is one product</h2>
 *
 * A real FD section lists every fixed deposit the customer holds, one row each, with its own
 * principal, its own maturity date. Two rows in an FD section are two different deposits.
 *
 * <h2>Recurring deposits: rows are installments of ONE product, never split</h2>
 *
 * This is not a heuristic -- it is what {@link FinancialProductType#RECURRING_DEPOSIT}'s own
 * routing already commits to: {@code hasTransactions()} is false because "an RD's installments
 * already appear on the savings account that funds it," meaning the schedule was always modelled
 * as one product's payment history, not as N accounts. Splitting an RD's rows the same way an
 * FD's are split would silently multiply one recurring deposit into several phantom accounts --
 * a worse failure than the empty-account bug this whole feature exists to fix. Its rows are
 * aggregated into one {@link ProductAttributes} instead: shared fields (rate, maturity) are read
 * once, and {@code installmentsPaid} counts the rows themselves.
 */
@Component
public class ProductAttributeExtractor {

    /**
     * @param type    what the section was classified as. Callers should only call this for a
     *                product where {@link FinancialProductType#hasTransactions()} is false --
     *                a ledger account's rows are transactions, not attribute rows, and have
     *                nothing for this method to extract.
     * @param rows    the section's own rows, header-keyed exactly like {@link CsvParser}/
     *                {@code PdfTableLocator} already produce them
     * @return one {@link ProductAttributes} per resulting product -- more than one only for
     *         {@link FinancialProductType#FIXED_DEPOSIT} with more than one row
     */
    public List<ProductAttributes> extract(FinancialProductType type, List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) return List.of(ProductAttributes.empty());

        if (type == FinancialProductType.RECURRING_DEPOSIT) {
            return List.of(aggregateInstallments(rows));
        }
        if (type == FinancialProductType.FIXED_DEPOSIT) {
            List<ProductAttributes> perDeposit = new ArrayList<>();
            for (Map<String, String> row : rows) perDeposit.add(fromRow(row));
            return perDeposit;
        }
        // Every other non-transacting product (PPF/EPF/NPS/mutual fund/demat/loan) has no
        // structural vocabulary defined for it yet (see ProductHypothesis) -- nothing to extract.
        return List.of(ProductAttributes.empty());
    }

    /** One fixed deposit, or one installment row, read as itself. */
    private ProductAttributes fromRow(Map<String, String> row) {
        // "amount", not "amount(rs)": CsvParser.normalizeHeaderCell strips a trailing parenthetical
        // that sits at the very end of the cell, so "Amount(Rs)" normalizes all the way down to
        // "amount" before this lookup ever runs. "Amount(Rs)*" does NOT strip -- the trailing "*"
        // after the closing paren breaks normalizeHeaderCell's end-of-string anchor -- which is
        // exactly the accidental signal that keeps principal and maturity amount distinguishable
        // on the same row.
        BigDecimal principal = amount(row, "principal amount", "principal", "deposit amount", "amount");
        BigDecimal rate = amount(row, "rate of interest", "interest rate", "roi", "rate");
        LocalDate maturityDate = date(row, "maturity date", "date of maturity");
        BigDecimal maturityAmount = amount(row, "maturity amount", "maturity value", "amount(rs)*");
        BigDecimal installmentAmount = amount(row, "installment paid", "installment amount",
                "amount paid", "monthly installment", "deposit(mnth)");
        return new ProductAttributes(principal, rate, maturityDate, maturityAmount, installmentAmount,
                null, null);
    }

    /**
     * Every row is one installment of the SAME recurring deposit. Rate and maturity are read once
     * (shared across the schedule, not per-installment); installmentsPaid counts rows whose status
     * column marks them paid, falling back to every row when there is no status column at all --
     * a schedule with no status information is read as "this many rows were on the statement," not
     * assumed unpaid, since assuming failure with no evidence for it is the wrong direction to
     * guess in.
     */
    private ProductAttributes aggregateInstallments(List<Map<String, String>> rows) {
        // Scanned across every row rather than read off row 0. These are the schedule's shared
        // terms, so any row carrying them carries the right value -- but the FIRST row is not
        // reliably the one that does: a real schedule may print the rate once on a later line, and
        // a bucketing glitch on one row would otherwise silently lose the whole product's rate and
        // maturity date even though the other rows had them.
        BigDecimal rate = firstAmountAcross(rows, "rate of interest", "interest rate", "roi", "rate");
        LocalDate maturityDate = firstDateAcross(rows, "maturity date", "date of maturity");
        BigDecimal installmentAmount = firstAmountAcross(rows, "installment paid", "installment amount",
                "amount paid", "monthly installment");

        boolean hasStatusColumn = rows.stream()
                .anyMatch(r -> CsvParser.firstNonBlank(r, "status") != null);
        int paidCount = 0;
        for (Map<String, String> row : rows) {
            String status = CsvParser.firstNonBlank(row, "status");
            if (!hasStatusColumn || (status != null && status.toLowerCase(java.util.Locale.ROOT).contains("paid"))) {
                paidCount++;
            }
        }

        return new ProductAttributes(null, rate, maturityDate, null, installmentAmount,
                paidCount, rows.size());
    }

    /** The first non-null value for these keys across every row -- for a schedule's SHARED terms
     *  only (rate, maturity, installment amount), never for a per-row value. */
    private BigDecimal firstAmountAcross(List<Map<String, String>> rows, String... keys) {
        for (Map<String, String> row : rows) {
            BigDecimal value = amount(row, keys);
            if (value != null) return value;
        }
        return null;
    }

    private LocalDate firstDateAcross(List<Map<String, String>> rows, String... keys) {
        for (Map<String, String> row : rows) {
            LocalDate value = date(row, keys);
            if (value != null) return value;
        }
        return null;
    }

    private BigDecimal amount(Map<String, String> row, String... keys) {
        return CsvParser.parseNumeric(CsvParser.firstNonBlank(row, keys));
    }

    private LocalDate date(Map<String, String> row, String... keys) {
        String raw = CsvParser.firstNonBlank(row, keys);
        return raw == null ? null : CsvParser.parseDate(raw);
    }
}
