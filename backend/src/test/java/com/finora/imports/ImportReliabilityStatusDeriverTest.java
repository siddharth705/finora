package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.TextSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImportReliabilityStatusDeriver}: a deterministic OR over facts this pipeline already
 * computes, never a weighted score -- see that class's own doc comment for why. Every test here
 * builds only the specific evidence its own rule reads, to keep each case's cause legible.
 */
class ImportReliabilityStatusDeriverTest {

    private static VerificationFinding finding(String rule, String outcome) {
        return new VerificationFinding(rule, outcome, Map.of());
    }

    private static VerificationFinding rowAccountingWarningWithReason(String reason) {
        Map<String, Long> reasons = new TreeMap<>();
        reasons.put(reason, 1L);
        return new VerificationFinding(RowAccountingValidator.RULE, "WARNING",
                Map.of("droppedTransactionCandidateReasons", reasons));
    }

    @Test
    void cleanWhenEveryFindingIsHealthyAndTextWasNative() {
        List<VerificationFinding> findings = List.of(
                finding("BALANCE_CHAIN", "VERIFIED"), finding("SUMMARY_TOTALS", "NOT_APPLICABLE"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.CLEAN);
    }

    @Test
    void reviewRecommendedWhenAFindingWarns() {
        List<VerificationFinding> findings = List.of(finding("SUMMARY_TOTALS", "WARNING"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    @Test
    void reviewRecommendedWhenOcrWasUsedEvenWithNoWarnings() {
        List<VerificationFinding> findings = List.of(finding("BALANCE_CHAIN", "VERIFIED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.OCR);

        assertThat(status).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    @Test
    void reviewRecommendedWhenTextWasNativePlusOcr() {
        List<VerificationFinding> findings = List.of(finding("BALANCE_CHAIN", "VERIFIED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PLUS_OCR);

        assertThat(status).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    @Test
    void needsAttentionWhenHeaderReconstructionIsUncertain() {
        List<VerificationFinding> findings = List.of(finding("BALANCE_CHAIN", "VERIFIED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, true, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    @Test
    void needsAttentionWhenPreHeaderActivityCandidateIsAmongTheDroppedReasons() {
        List<VerificationFinding> findings = List.of(
                rowAccountingWarningWithReason("PRE_HEADER_ACTIVITY_CANDIDATE"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    /** A dropped-row reason that is NOT {@code PRE_HEADER_ACTIVITY_CANDIDATE} still WARNs (via
     *  ROW_ACCOUNTING's own outcome) but must not reach NEEDS_ATTENTION on that reason alone --
     *  only the specific reason confirmed, via real-document verification, to correspond to
     *  actual lost transactions escalates past REVIEW_RECOMMENDED. */
    @Test
    void otherDroppedRowReasonsStayAtReviewRecommended_notNeedsAttention() {
        List<VerificationFinding> findings = List.of(rowAccountingWarningWithReason("BUCKET_EMPTY"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    @Test
    void needsAttentionWhenAnyFindingFailed() {
        List<VerificationFinding> findings = List.of(
                finding("BALANCE_CHAIN", "VERIFIED"), finding("STATEMENT_TOTALS", "FAILED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.NATIVE_PDF);

        assertThat(status).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    /** Precedence: NEEDS_ATTENTION conditions must win even when a REVIEW_RECOMMENDED condition
     *  (OCR) is simultaneously true -- the one case a wrong if/else ordering, or two independent
     *  ifs instead of if/else-if, would silently get wrong. */
    @Test
    void needsAttentionTakesPrecedenceOverAnIndependentlyTrueReviewCondition() {
        List<VerificationFinding> findings = List.of(finding("STATEMENT_TOTALS", "FAILED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, TextSource.OCR);

        assertThat(status).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    @Test
    void needsAttentionTakesPrecedenceOverHeaderReconstructionAndOcrCombined() {
        List<VerificationFinding> findings = List.of(finding("SUMMARY_TOTALS", "WARNING"));

        var status = ImportReliabilityStatusDeriver.derive(findings, true, TextSource.OCR);

        assertThat(status).isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }

    @Test
    void nullTextSourceIsTreatedLikeNativeOnlyAffectingNothing() {
        List<VerificationFinding> findings = List.of(finding("BALANCE_CHAIN", "VERIFIED"));

        var status = ImportReliabilityStatusDeriver.derive(findings, false, null);

        assertThat(status).isEqualTo(ImportReliabilityStatus.CLEAN);
    }
}
