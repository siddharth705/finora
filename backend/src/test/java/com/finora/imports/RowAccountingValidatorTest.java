package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.imports.pdf.PdfTableLocator.DroppedCandidateRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one rule that asks whether every physical row a document offered has an accounted-for fate
 * -- not whether the numbers add up (every other validator already asks that), but whether
 * something was silently left behind on the way to those checks ever running. See the class-level
 * doc comment on {@link RowAccountingValidator} for the exact gap this closes: a statement with
 * 100 real transactions, 70 extracted, all 70 individually correct, still reports VERIFIED
 * everywhere else.
 */
class RowAccountingValidatorTest {

    private final RowAccountingValidator validator = new RowAccountingValidator();

    private StagedRow row(String description, String amount) {
        return new StagedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), "EXPENSE",
                "Other", "default", null, false, null, null);
    }

    @Test
    void verifiesWhenEveryLocatedRowHasAnAccountedForFate() {
        var finding = validator.check(List.of(row("Coffee Shop", "50.00")), List.of(), List.of(), 1);

        assertThat(finding.rule()).isEqualTo("ROW_ACCOUNTING");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("stagedTransactionCount", 1);
        assertThat(finding.details()).containsEntry("droppedTransactionCandidateCount", 0);
    }

    @Test
    void reportsNotApplicableWhenNothingWasFoundAtAll() {
        var finding = validator.check(List.of(), List.of(), List.of(), 0);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void reportsWarningWhenTransactionShapedRowsWereDropped() {
        List<DroppedCandidateRow> dropped = List.of(
                new DroppedCandidateRow("PAGE_FOOTER_OR_CLOSING_MARKER", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("PAGE_FOOTER_OR_CLOSING_MARKER", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")));

        var finding = validator.check(List.of(row("Coffee Shop", "50.00")), List.of(), dropped, 4);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsEntry("droppedTransactionCandidateCount", 3);
        @SuppressWarnings("unchecked")
        Map<String, Long> reasons = (Map<String, Long>) finding.details().get("droppedTransactionCandidateReasons");
        assertThat(reasons).containsEntry("PAGE_FOOTER_OR_CLOSING_MARKER", 2L).containsEntry("BUCKET_EMPTY", 1L);
    }

    /** Never {@code FAILED}: a dropped transaction-shaped row is not proof anything is wrong -- see
     *  {@link DroppedCandidateRow}'s own doc comment. Only WARNING (review-worthy) or VERIFIED. */
    @Test
    void neverReturnsFailed_evenWithManyDroppedCandidates() {
        List<DroppedCandidateRow> manyDropped = List.of(
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")),
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")));

        var finding = validator.check(List.of(row("Coffee Shop", "50.00")), List.of(), manyDropped, 6);

        assertThat(finding.outcome()).isNotEqualTo("FAILED");
    }

    /** Wording guard: this rule only ever sees a row's SHAPE, never confirms it was really a
     *  transaction -- the explanation must say "candidate"/"discarded"/"require review", never
     *  claim outright that transactions are "missing". */
    @Test
    void explanationNeverClaimsTransactionsAreMissing() {
        List<DroppedCandidateRow> dropped = List.of(
                new DroppedCandidateRow("BUCKET_EMPTY", Set.of("DATE_PRESENT", "AMOUNT_PRESENT")));

        var finding = validator.check(List.of(row("Coffee Shop", "50.00")), List.of(), dropped, 2);

        String explanation = finding.details().get("explanation").toString().toLowerCase(java.util.Locale.ROOT);
        assertThat(explanation).doesNotContain("missing transaction").doesNotContain("transactions went missing");
        assertThat(explanation).contains("candidate");
    }

    @Test
    void includesUnparseableRowCountAsContext_withoutAffectingTheVerdict() {
        List<UnparseableRow> unparseable = List.of(new UnparseableRow(Map.of("Date", "bad"), "no amount"));

        var finding = validator.check(List.of(row("Coffee Shop", "50.00")), unparseable, List.of(), 2);

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("unparseableRowCount", 1);
    }
}
