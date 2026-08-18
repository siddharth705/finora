package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.TextSource;

import java.util.List;
import java.util.Map;

/**
 * Computes {@link ImportReliabilityStatus} from evidence {@link ImportVerifier} already has, once
 * every validator has run. A static method rather than a Spring bean or an {@code ImportVerifier}
 * constructor parameter -- deliberately: it needs no state and no mocking, and {@code
 * ImportVerifier}'s constructor already has 14 direct call sites across this codebase's tests,
 * every one of which would need editing for no benefit if this became an 8th injected collaborator.
 *
 * <p><b>Rule-based, not weighted.</b> Each condition below is a named fact this pipeline already
 * computes -- a header-reconstruction finding existing, a specific dropped-row reason appearing, a
 * finding's own outcome -- combined with OR, in a fixed priority order. Nothing here is a score,
 * and nothing is invented: nothing is here that isn't already evidence somewhere else in the
 * report. See {@link ImportReliabilityStatus}'s own doc comment for why that distinction matters
 * in this codebase specifically.
 */
final class ImportReliabilityStatusDeriver {

    /** Key inside {@code ROW_ACCOUNTING}'s {@code droppedTransactionCandidateReasons} map (see
     *  {@link RowAccountingValidator}) that the {@code PRE_HEADER_ACTIVITY_CANDIDATE} evidence
     *  layer records -- the one dropped-row reason confirmed, via real-document verification, to
     *  correspond to an actual lost transaction rather than a merely-unexplained row. */
    private static final String PRE_HEADER_ACTIVITY_CANDIDATE = "PRE_HEADER_ACTIVITY_CANDIDATE";

    private ImportReliabilityStatusDeriver() {}

    static ImportReliabilityStatus derive(List<VerificationFinding> findings,
            boolean headerReconstructionUncertain, TextSource textSource) {
        if (headerReconstructionUncertain
                || hasPreHeaderActivityCandidate(findings)
                || hasOutcome(findings, "FAILED")) {
            return ImportReliabilityStatus.NEEDS_ATTENTION;
        }
        if (textSource == TextSource.OCR || textSource == TextSource.NATIVE_PLUS_OCR
                || hasOutcome(findings, "WARNING")) {
            return ImportReliabilityStatus.REVIEW_RECOMMENDED;
        }
        return ImportReliabilityStatus.CLEAN;
    }

    private static boolean hasOutcome(List<VerificationFinding> findings, String outcome) {
        return findings != null && findings.stream().anyMatch(f -> outcome.equals(f.outcome()));
    }

    /** Reads {@code ROW_ACCOUNTING}'s already-assembled details rather than taking a separate
     *  parameter -- a second field carrying the same fact is exactly the "second source of truth
     *  that could disagree" this codebase's own verification-report doc comment warns against. */
    private static boolean hasPreHeaderActivityCandidate(List<VerificationFinding> findings) {
        if (findings == null) return false;
        for (VerificationFinding finding : findings) {
            if (!RowAccountingValidator.RULE.equals(finding.rule())) continue;
            Object reasons = finding.details().get("droppedTransactionCandidateReasons");
            if (reasons instanceof Map<?, ?> map && map.containsKey(PRE_HEADER_ACTIVITY_CANDIDATE)) {
                return true;
            }
        }
        return false;
    }
}
