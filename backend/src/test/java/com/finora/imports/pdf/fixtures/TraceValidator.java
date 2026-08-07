package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PositionedText;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a trace is safe to commit and still worth committing.
 *
 * Those are two separate questions and both were previously answered by "the person capturing it
 * should read the file first". That is not a check, it is a hope: the redactor's own doc comment
 * already said the capturer is the last reviewer standing between a customer's statement and the
 * repository, and a customer's name and account number reached the repository anyway.
 *
 * <h2>Four checks</h2>
 *
 * <ol>
 *   <li><b>No unmasked PII.</b> The same patterns {@code scripts/check-fixture-hygiene.sh} blocks
 *       commits on, applied to the trace's own text. A trace that trips this must never be
 *       committed, regardless of how useful its structure is.</li>
 *   <li><b>Required structural evidence preserved.</b> Declared per trace in its own metadata. This
 *       is the check that would have caught the incident that motivated all of this: the deposit
 *       traces were captured with "Maturity Date" masked to "Xxxxxxxx Date", and every test kept
 *       passing because nothing asserted the evidence was still there.</li>
 *   <li><b>The trace still parses into a table.</b> A trace that yields no sections is not evidence
 *       of a layout, it is a file.</li>
 *   <li><b>Capability evidence.</b> The capabilities the trace claims to protect actually activate
 *       when it is run through the locator -- so a trace cannot claim to guard a capability it
 *       does not exercise.</li>
 * </ol>
 *
 * A trace is evidence, not test data: it exists to preserve the real-world document structure that
 * motivated a capability. Checks 2 and 4 are what keep that true over time.
 */
public final class TraceValidator {

    private TraceValidator() {}

    // Deliberately the same shapes check-fixture-hygiene.sh blocks on, so the two cannot drift into
    // disagreeing about what counts as customer data.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern INDIAN_MOBILE = Pattern.compile("(\\+?91[-. ]?)?[6-9][0-9]{9}\\b");
    private static final Pattern IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(.)\\1{3,}|example|sample|test|dummy|fake|placeholder|redacted|noreply|localhost");

    public record Finding(Severity severity, String check, String detail) {
        public enum Severity { BLOCKER, REVIEW }

        @Override
        public String toString() { return severity + " [" + check + "] " + detail; }
    }

    public record Result(String traceName, TraceMetadata metadata, List<Finding> findings,
                         int sections, int tables, int rows, List<String> preservedHeaders,
                         List<String> missingHeaders, List<String> activatedCapabilities) {

        /** True when nothing blocks this trace from being committed. */
        public boolean isCommittable() {
            return findings.stream().noneMatch(f -> f.severity() == Finding.Severity.BLOCKER);
        }

        public List<Finding> blockers() {
            return findings.stream().filter(f -> f.severity() == Finding.Severity.BLOCKER).toList();
        }
    }

    /** Validates a trace's contents without needing it committed first -- the capture path calls
     *  this before writing, so a failing trace is never written to disk in the first place. */
    public static Result validate(String traceName, String content) {
        TraceMetadata metadata = TraceMetadata.parse(content);
        List<PositionedText> runs = PdfTrace.parse(content);
        List<Finding> findings = new ArrayList<>();

        findings.addAll(scanForPii(runs));

        String allText = runs.stream().map(PositionedText::text)
                .reduce(new StringBuilder(), (sb, t) -> sb.append(t).append('\n'), StringBuilder::append)
                .toString();

        List<String> preserved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String required : metadata.requiredHeaders()) {
            if (allText.toLowerCase().contains(required.toLowerCase())) {
                preserved.add(required);
            } else {
                missing.add(required);
                findings.add(new Finding(Finding.Severity.BLOCKER, "structural-evidence",
                        "required header \"" + required + "\" did not survive redaction -- this trace "
                                + "no longer contains the evidence it exists to preserve"));
            }
        }

        var ctx = new com.finora.imports.DocumentContext("PDF", "TraceValidator");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);
        List<String> activated = ctx.capabilities().stream()
                .map(com.finora.dto.ImportDto.CapabilityActivation::capability).distinct().sorted().toList();

        int rowCount = doc.sections().stream().mapToInt(s -> s.rows().size()).sum();
        if (doc.sections().isEmpty()) {
            findings.add(new Finding(Finding.Severity.BLOCKER, "parses",
                    "the trace yields no table sections at all -- it is not evidence of a layout"));
        }

        for (String claimed : metadata.capabilities()) {
            if (!activated.contains(claimed)) {
                findings.add(new Finding(Finding.Severity.REVIEW, "capability-evidence",
                        "claims to protect " + claimed + ", which did not activate on it"));
            }
        }

        if (metadata.hasNoProvenance()) {
            findings.add(new Finding(Finding.Severity.REVIEW, "provenance",
                    "no metadata block -- redaction provenance cannot be established, so this trace "
                            + "cannot be shown to still contain its evidence"));
        }

        if (metadata.hasNoWidths()) {
            // Separate from provenance on purpose: a trace can have impeccable provenance and still
            // be unable to exercise width-dependent bucketing, and reporting both as "legacy" hides
            // which of the two problems a recapture would fix.
            findings.add(new Finding(Finding.Severity.REVIEW, "widths",
                    "rows carry no width, so every run has endX == x. Any capability guarded on a "
                            + "measured width -- RIGHT_ALIGNED_AMOUNTS, and the column bucketing it "
                            + "corrects -- cannot activate on this trace however good its content "
                            + "is. Recapture at trace v3 to fix."));
        }

        return new Result(traceName, metadata, findings, doc.sections().size(), doc.sections().size(),
                rowCount, preserved, missing, activated);
    }

    /**
     * PII scan over the trace's own text.
     *
     * Placeholder-shaped values are allowed by the same {@code is_placeholder} rule the commit hook
     * uses -- a redacted email is still email-shaped, and blocking those would make every correctly
     * redacted trace unpublishable.
     */
    private static List<Finding> scanForPii(List<PositionedText> runs) {
        List<Finding> findings = new ArrayList<>();
        for (PositionedText run : runs) {
            String text = run.text();
            if (text == null || text.isBlank()) continue;
            checkPattern(findings, EMAIL, text, "email address");
            checkPattern(findings, INDIAN_MOBILE, text, "phone number");
            checkIfsc(findings, text);
        }
        return findings;
    }

    private static void checkPattern(List<Finding> findings, Pattern pattern, String text, String what) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            String hit = matcher.group();
            if (PLACEHOLDER.matcher(hit).find()) continue;
            findings.add(new Finding(Finding.Severity.BLOCKER, "pii",
                    "unmasked " + what + " survived redaction: " + hit));
        }
    }

    /** An IFSC's 4-letter bank prefix is deliberately preserved (it is what lets a trace test bank
     *  detection); only a real-looking BRANCH code is a finding. */
    private static void checkIfsc(List<Finding> findings, String text) {
        var matcher = IFSC.matcher(text);
        while (matcher.find()) {
            String branch = matcher.group().substring(5, 11);
            if (branch.matches("^(.)\\1{5}$")) continue;
            findings.add(new Finding(Finding.Severity.BLOCKER, "pii",
                    "unmasked IFSC branch code survived redaction: " + matcher.group()));
        }
    }
}
