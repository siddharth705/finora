package com.finora.imports.evidence;

import com.finora.dto.ImportDto;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.pdf.TextSource;

/**
 * The existing verifier output {@link DimensionAssessor#assessFinancialValidation} reads --
 * design §5's validator-to-scope mapping, referenced here, not recomputed. Both fields are
 * nullable: a statement may not have had one or the other validator run against it (e.g. no
 * opening/closing balance printed, or no running-balance column at all).
 *
 * @param balanceChain {@link BalanceChainValidator}'s row-level result, consulted only for
 *        {@link MaterialField#TRANSACTION_AMOUNT}/{@link MaterialField#TRANSACTION_DIRECTION} --
 *        design §3.3's round-3 tightening excludes every aggregate validator from those two
 *        fields' assessment, regardless of what the aggregate validator itself read
 * @param statementTotals {@code StatementTotalsValidator}'s finding, consulted only for
 *        {@link MaterialField#OPENING_BALANCE}/{@link MaterialField#CLOSING_BALANCE}
 * @param sectionIndex which section this validation ran against -- carried into the resulting
 *        {@link DimensionResult}'s provenance as a {@link ProvenanceNode.SectionAttribution}, so
 *        that a financial-validation dimension sharing a section with a structural-evidence
 *        dimension (the ICICI shape both trace back to) is correctly refused as independent
 * @param fromSource which acquisition produced the row data the validators ran against
 */
public record FinancialValidationContext(
        BalanceChainValidator.Result balanceChain,
        ImportDto.VerificationFinding statementTotals,
        int sectionIndex,
        TextSource fromSource) {
}
