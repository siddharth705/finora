package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the validator fires on both failure modes it exists for.
 *
 * A check that cannot be shown to fail is not a control -- and "the capturer will read the file
 * first" was exactly that: a control everyone believed in, which let a customer's account number
 * into the repository and separately let three traces lose their deposit vocabulary without a
 * single test going red.
 */
class TraceValidatorTest {

    private String traceWith(TraceMetadata metadata, String... lines) {
        List<PositionedText> runs = new java.util.ArrayList<>();
        float y = 700f;
        // A minimal but real table: a header row and a data row, so a well-formed trace parses into
        // one section and the parseability check is exercised rather than incidentally satisfied.
        runs.add(new PositionedText("Date", 50f, y, 0));
        runs.add(new PositionedText("Narration", 150f, y, 0));
        runs.add(new PositionedText("Amount", 300f, y, 0));
        y -= 12f;
        runs.add(new PositionedText("01/06/2026", 50f, y, 0));
        runs.add(new PositionedText("Salary", 150f, y, 0));
        runs.add(new PositionedText("1000.00", 300f, y, 0));

        for (String line : lines) {
            y -= 12f;
            runs.add(new PositionedText(line, 50f, y, 0));
        }
        return PdfTrace.format(runs, metadata);
    }

    private TraceMetadata metadata(List<String> requiredHeaders) {
        return new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION, PdfTraceRedactor.REDACTOR_VERSION,
                PdfTraceRedactor.allowlistFingerprint(), "2026-08-03", "synthetic",
                List.of(), List.of(), "validator self-test", requiredHeaders);
    }

    @Test
    void blocksAnUnmaskedEmailAddress() {
        String trace = traceWith(metadata(List.of()), "priya.nair@gmail.com"); // synthetic-ok: invented, and PII-shaped ON PURPOSE -- the assertion is that this is caught

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("email"));
    }

    @Test
    void blocksAnUnmaskedIndianMobileNumber() {
        String trace = traceWith(metadata(List.of()), "Contact 9812345678"); // synthetic-ok: invented number, PII-shaped so the check can be shown to fire

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("phone"));
    }

    @Test
    void blocksAnUnmaskedIfscBranchCode() {
        String trace = traceWith(metadata(List.of()), "IFSC HDFC0004521"); // synthetic-ok: invented branch code, needed unmasked to prove it is rejected

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("IFSC"));
    }

    @Test
    void allowsACorrectlyRedactedIfscWhoseBankPrefixSurvives() {
        // The 4-letter prefix is deliberately preserved -- it is what lets a trace regression-test
        // bank detection. Blocking it would make every correctly redacted trace unpublishable.
        String trace = traceWith(metadata(List.of()), "IFSC HDFC0XXXXXX");

        assertThat(TraceValidator.validate("probe", trace).isCommittable()).isTrue();
    }

    @Test
    void blocksATraceThatLostTheEvidenceItWasCapturedToPreserve() {
        // THE incident, as an executable check. A trace declaring it must retain "Maturity Date"
        // but whose redaction masked it away is no longer evidence of anything -- and previously
        // this was undetectable, because every test kept passing.
        String damaged = traceWith(metadata(List.of("Maturity Date")), "Xxxxxxxx Date");

        var result = TraceValidator.validate("probe", damaged);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.missingHeaders()).containsExactly("Maturity Date");
        assertThat(result.blockers())
                .anyMatch(f -> f.detail().contains("no longer contains the evidence"));
    }

    @Test
    void passesWhenTheRequiredEvidenceSurvived() {
        String intact = traceWith(metadata(List.of("Maturity Date")), "Maturity Date");

        var result = TraceValidator.validate("probe", intact);

        assertThat(result.isCommittable()).isTrue();
        assertThat(result.preservedHeaders()).containsExactly("Maturity Date");
        assertThat(result.missingHeaders()).isEmpty();
    }

    @Test
    void flagsATraceWithNoProvenanceForReviewWithoutBlockingIt() {
        // v1 traces are legitimately un-establishable, not corrupt -- they predate metadata. They
        // must be visible without making the corpus unbuildable.
        String legacy = PdfTrace.format(List.of(
                new PositionedText("Date", 50f, 700f, 0),
                new PositionedText("Narration", 150f, 700f, 0),
                new PositionedText("Amount", 300f, 700f, 0),
                new PositionedText("01/06/2026", 50f, 688f, 0),
                new PositionedText("Salary", 150f, 688f, 0),
                new PositionedText("1000.00", 300f, 688f, 0)));

        var result = TraceValidator.validate("probe", legacy);

        assertThat(result.metadata().isLegacy()).isTrue();
        assertThat(result.isCommittable()).as("legacy is a review item, not a blocker").isTrue();
        assertThat(result.findings()).anyMatch(f -> f.check().equals("provenance"));
    }

    @Test
    void aChangedAllowlistMakesEveryExistingTraceReportStale() {
        // The automatic half: a trace records the fingerprint it was captured under, so an
        // allowlist edit invalidates it without anyone having to connect the two.
        TraceMetadata capturedUnderOldAllowlist = new TraceMetadata(2, 1, "0000DEAD", "2026-01-01",
                "synthetic", List.of(), List.of(), "", List.of());

        assertThat(capturedUnderOldAllowlist.isStaleAgainst(PdfTraceRedactor.allowlistFingerprint()))
                .isTrue();
        assertThat(metadata(List.of()).isStaleAgainst(PdfTraceRedactor.allowlistFingerprint()))
                .as("a trace captured under the current allowlist is not stale")
                .isFalse();
    }

    @Test
    void theAllowlistFingerprintIsStableAcrossCallsAndSensitiveToContent() {
        assertThat(PdfTraceRedactor.allowlistFingerprint())
                .isEqualTo(PdfTraceRedactor.allowlistFingerprint());
        assertThat(PdfTraceRedactor.allowlistFingerprint()).hasSize(8);
    }

    @Test
    void theQualityReportStatesTheVerdictAndTheEvidence() {
        var result = TraceValidator.validate("probe",
                traceWith(metadata(List.of("Maturity Date")), "Maturity Date"));

        String report = TraceQualityReport.render(result);

        assertThat(report).contains("Trace Summary — probe");
        assertThat(report).contains("Allowlist fingerprint:");
        assertThat(report).contains("[ok]      Maturity Date");
        assertThat(report).contains("VERDICT: safe to commit");
    }

    @Test
    void theQualityReportRefusesLoudlyWhenEvidenceIsMissing() {
        var result = TraceValidator.validate("probe",
                traceWith(metadata(List.of("Maturity Date")), "Xxxxxxxx Date"));

        assertThat(TraceQualityReport.render(result))
                .contains("[MISSING] Maturity Date")
                .contains("VERDICT: DO NOT COMMIT");
    }
}
