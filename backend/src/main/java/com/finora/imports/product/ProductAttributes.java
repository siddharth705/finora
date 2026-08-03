package com.finora.imports.product;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What makes a deposit a DEPOSIT, as opposed to a name and a balance.
 *
 * {@link ProductEvidenceCollector} answers "is a maturity field present" for classification;
 * this answers "what does it actually say" -- 7.10%, 12/03/2027, principal 100000.00. Routing a
 * fixed deposit into the Investments module without these is only half of what "persist as an
 * investment, with its attributes" means: a customer looking at their FD sees a balance and a name,
 * with no way to tell it apart from a savings account that happens to sit in the same module.
 *
 * Every field is nullable and genuinely IS null when the row didn't carry it -- same convention
 * {@link com.finora.dto.ImportDto.DetectedAccountInfo} already holds to for its own best-effort
 * fields. Fields not relevant to a product's type are simply never populated for it (a fixed
 * deposit has no installmentAmount; a recurring deposit usually has no principalAmount, since its
 * value builds up over the schedule rather than starting as a lump sum).
 */
public record ProductAttributes(
        BigDecimal principalAmount,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal maturityAmount,
        BigDecimal installmentAmount,
        Integer installmentsPaid,
        Integer installmentsTotal
) {
    private static final ProductAttributes EMPTY =
            new ProductAttributes(null, null, null, null, null, null, null);

    public static ProductAttributes empty() { return EMPTY; }

    public boolean isEmpty() { return this.equals(EMPTY); }
}
