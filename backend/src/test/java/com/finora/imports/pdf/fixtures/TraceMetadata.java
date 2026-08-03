package com.finora.imports.pdf.fixtures;

import java.util.List;
import java.util.Map;

/**
 * What a trace is, where it came from, and which capability it protects.
 *
 * <h2>A trace is evidence, not test data</h2>
 *
 * This distinction is the reason this record exists. A trace preserves the real-world document
 * structure that MOTIVATED a capability -- the exact fragmentation, the exact coordinates, the
 * exact column headers a real bank's PDF generator produced. If a trace no longer contains that
 * evidence, the capability loses its grounding: the test still runs, still passes, and no longer
 * demonstrates anything about the document it was captured from.
 *
 * That is not hypothetical. Three traces were captured while the redactor's allowlist had no
 * deposit vocabulary, so "Maturity Date" was masked to "Xxxxxxxx Date" -- the precise header
 * product classification reads. The fixtures kept passing. They had simply stopped being evidence.
 *
 * <h2>Why the provenance fields</h2>
 *
 * {@code redactorVersion} and {@code allowlistFingerprint} let a trace state which redactor
 * produced it, so a later change to either can identify every trace it invalidated instead of
 * relying on someone connecting the two. {@code capabilities}, {@code regressions} and
 * {@code motivation} answer "why is this file in the repository" six months later, when the person
 * who captured it is not the person reading it.
 *
 * @param traceVersion         format version of the trace file itself
 * @param redactorVersion      {@link PdfTraceRedactor#REDACTOR_VERSION} at capture time
 * @param allowlistFingerprint {@link PdfTraceRedactor#allowlistFingerprint()} at capture time
 * @param capturedAt           ISO date of capture
 * @param generatedFrom        human description of the source document -- never a file path, which
 *                             would point at a customer statement someone still has
 * @param capabilities         the capability names this trace exists to protect
 * @param regressions          issue/regression identifiers, free-form
 * @param motivation           one sentence on what this document taught the engine
 * @param requiredHeaders      structural tokens that MUST survive redaction for this trace to still
 *                             be evidence -- see {@link TraceValidator}, which enforces them
 */
public record TraceMetadata(int traceVersion, int redactorVersion, String allowlistFingerprint,
                            String capturedAt, String generatedFrom, List<String> capabilities,
                            List<String> regressions, String motivation, List<String> requiredHeaders) {

    /** The format version written by the current capture path. */
    public static final int CURRENT_TRACE_VERSION = 2;

    /**
     * What a v1 trace reports.
     *
     * v1 carried no metadata at all, so nothing about its provenance can be recovered -- which is
     * itself the finding. Rather than inventing plausible values, every field is explicitly unknown
     * and {@code allowlistFingerprint} is {@code "UNKNOWN"}, which never matches the current
     * fingerprint and therefore always reports as stale. That is the correct answer: a trace whose
     * redaction provenance cannot be established cannot be trusted to still contain its evidence.
     */
    public static TraceMetadata legacyV1() {
        return new TraceMetadata(1, 0, "UNKNOWN", "unknown", "unknown (captured before trace v2)",
                List.of(), List.of(), "unknown (captured before trace metadata existed)", List.of());
    }

    public boolean isLegacy() { return traceVersion < CURRENT_TRACE_VERSION; }

    /** True when this trace was captured under a different allowlist than the one in force now, so
     *  its redaction may have removed evidence the current allowlist would preserve. */
    public boolean isStaleAgainst(String currentFingerprint) {
        return !currentFingerprint.equals(allowlistFingerprint);
    }

    /** The metadata block as trace-file comment lines, in a stable order. */
    public String toHeaderLines() {
        StringBuilder sb = new StringBuilder();
        sb.append("# traceVersion: ").append(traceVersion).append('\n');
        sb.append("# redactorVersion: ").append(redactorVersion).append('\n');
        sb.append("# allowlistFingerprint: ").append(allowlistFingerprint).append('\n');
        sb.append("# capturedAt: ").append(capturedAt).append('\n');
        sb.append("# generatedFrom: ").append(generatedFrom).append('\n');
        sb.append("# capabilities: ").append(String.join(", ", capabilities)).append('\n');
        sb.append("# regressions: ").append(String.join(", ", regressions)).append('\n');
        sb.append("# requiredHeaders: ").append(String.join(", ", requiredHeaders)).append('\n');
        sb.append("# motivation: ").append(motivation).append('\n');
        return sb.toString();
    }

    /** Reads a metadata block back out of a trace file's comment lines, falling back to
     *  {@link #legacyV1()} when the file predates metadata entirely. */
    public static TraceMetadata parse(String content) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (String line : content.split("\n", -1)) {
            if (!line.startsWith("#")) break; // metadata is a contiguous header block
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            fields.put(line.substring(1, colon).trim(), line.substring(colon + 1).trim());
        }
        if (!fields.containsKey("traceVersion")) return legacyV1();

        return new TraceMetadata(
                intOr(fields.get("traceVersion"), 1),
                intOr(fields.get("redactorVersion"), 0),
                fields.getOrDefault("allowlistFingerprint", "UNKNOWN"),
                fields.getOrDefault("capturedAt", "unknown"),
                fields.getOrDefault("generatedFrom", "unknown"),
                csv(fields.get("capabilities")),
                csv(fields.get("regressions")),
                fields.getOrDefault("motivation", ""),
                csv(fields.get("requiredHeaders")));
    }

    private static int intOr(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
}
