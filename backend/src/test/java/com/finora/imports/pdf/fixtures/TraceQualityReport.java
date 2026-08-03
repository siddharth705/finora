package com.finora.imports.pdf.fixtures;

import java.util.List;

/**
 * A capture's result as something a human can approve in a few seconds.
 *
 * The alternative -- and what this replaces -- is "read the regenerated trace before committing".
 * A trace is thousands of coordinate lines; nobody reads one, and asking them to is how a
 * customer's account number reaches a repository while everyone believes it was reviewed. The
 * report states what was checked, what survived, and what still needs eyes, so review is a decision
 * rather than a scan.
 */
public final class TraceQualityReport {

    private TraceQualityReport() {}

    public static String render(TraceValidator.Result result) {
        StringBuilder out = new StringBuilder();
        out.append("Trace Summary — ").append(result.traceName()).append('\n');
        out.append("=".repeat(60)).append('\n');

        TraceMetadata meta = result.metadata();
        out.append("\nProvenance\n");
        out.append("  Trace version:         ").append(meta.traceVersion()).append('\n');
        out.append("  Redactor version:      ").append(meta.redactorVersion()).append('\n');
        out.append("  Allowlist fingerprint: ").append(meta.allowlistFingerprint()).append('\n');
        out.append("  Captured:              ").append(meta.capturedAt()).append('\n');
        out.append("  Source:                ").append(meta.generatedFrom()).append('\n');

        out.append("\nStructure\n");
        out.append("  Sections: ").append(result.sections()).append('\n');
        out.append("  Rows:     ").append(result.rows()).append('\n');

        out.append("\nCapabilities protected\n");
        appendList(out, meta.capabilities(), "  (none declared)");

        out.append("\nCapabilities that actually activated\n");
        appendList(out, result.activatedCapabilities(), "  (none)");

        out.append("\nRequired evidence preserved\n");
        if (result.preservedHeaders().isEmpty() && result.missingHeaders().isEmpty()) {
            out.append("  (none declared — this trace asserts nothing about what must survive)\n");
        } else {
            result.preservedHeaders().forEach(h -> out.append("  [ok]      ").append(h).append('\n'));
            result.missingHeaders().forEach(h -> out.append("  [MISSING] ").append(h).append('\n'));
        }

        long blockers = result.blockers().size();
        long review = result.findings().size() - blockers;

        out.append("\nPII scan\n");
        if (blockers == 0) {
            out.append("  [ok] no unmasked email, phone or IFSC branch code found\n");
        } else {
            result.blockers().forEach(f -> out.append("  [BLOCKER] ").append(f.detail()).append('\n'));
        }

        out.append("\nReview items: ").append(review).append('\n');
        result.findings().stream()
                .filter(f -> f.severity() == TraceValidator.Finding.Severity.REVIEW)
                .forEach(f -> out.append("  - ").append(f.detail()).append('\n'));

        out.append('\n').append("=".repeat(60)).append('\n');
        out.append(result.isCommittable()
                ? "VERDICT: safe to commit (" + blockers + " blockers, " + review + " review items)\n"
                : "VERDICT: DO NOT COMMIT — " + blockers + " blocker(s)\n");
        if (!meta.motivation().isBlank()) {
            out.append("\nWhy this trace exists: ").append(meta.motivation()).append('\n');
        }
        return out.toString();
    }

    private static void appendList(StringBuilder out, List<String> items, String emptyText) {
        if (items.isEmpty()) {
            out.append(emptyText).append('\n');
            return;
        }
        items.forEach(i -> out.append("  • ").append(i).append('\n'));
    }
}
