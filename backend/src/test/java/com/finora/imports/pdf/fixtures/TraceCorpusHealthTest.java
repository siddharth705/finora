package com.finora.imports.pdf.fixtures;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus checking itself, so an allowlist change identifies the traces it invalidated instead
 * of relying on someone connecting the two.
 *
 * <h2>The incident this exists to prevent recurring</h2>
 *
 * Three traces were captured while {@link PdfTraceRedactor}'s allowlist had no deposit vocabulary.
 * "Maturity Date" was masked to "Xxxxxxxx Date" and "Deposit(Mnth)" to "Deposit(Xxxx)" -- the exact
 * column headers product classification reads. The allowlist was later fixed, and nothing anywhere
 * connected that fix to the fixtures it had already damaged. Every test kept passing. The traces
 * had simply stopped being evidence, and it was found only by dumping a fixture by hand.
 *
 * A trace now records the allowlist fingerprint it was captured under, so the moment that
 * fingerprint changes this test names every affected trace. It reports rather than fails: a stale
 * trace is not broken, it is a re-capture that needs the original document, and the person holding
 * that document may not be the person running the build. Failing the build for something only
 * someone else can fix trains people to ignore it.
 */
class TraceCorpusHealthTest {

    @Test
    void everyCommittedTraceIsSafeToHaveCommitted() {
        // The one thing that IS a hard failure. A trace containing unmasked customer data is not a
        // maintenance item -- it is the thing the whole redaction pipeline exists to prevent, and
        // it must fail loudly wherever it is noticed.
        List<String> offenders = new ArrayList<>();
        for (String name : PdfTrace.committedTraceNames()) {
            TraceValidator.Result result = TraceValidator.validate(name, PdfTrace.read(name));
            result.blockers().stream()
                    .filter(f -> f.check().equals("pii"))
                    .forEach(f -> offenders.add(name + ": " + f.detail()));
        }

        assertThat(offenders)
                .as("a committed trace contains unmasked customer data")
                .isEmpty();
    }

    @Test
    void staleTracesAreNamedSoAnAllowlistChangeCannotSilentlyOrphanThem() {
        String current = PdfTraceRedactor.allowlistFingerprint();
        List<String> stale = new ArrayList<>();

        for (String name : PdfTrace.committedTraceNames()) {
            TraceMetadata metadata = PdfTrace.metadata(name);
            if (metadata.isStaleAgainst(current)) {
                stale.add("  " + name + " (captured under allowlist " + metadata.allowlistFingerprint()
                        + ", current is " + current + ")");
            }
        }

        if (!stale.isEmpty()) {
            System.out.println("""

                    STALE TRACES — captured under a different redaction allowlist than the one in
                    force now, so their redaction may have removed evidence the current allowlist
                    would preserve. Re-capture from the original documents when available:

                    """ + String.join("\n", stale) + """


                      ./scripts/trace-capture.sh <name> <path-to-original.pdf>

                    See docs/engineering/trace-lifecycle.md. This is a report, not a failure --
                    re-capture needs the original document, which the build machine does not have.
                    """);
        }

        // Asserting the mechanism, not the outcome: the check itself must keep working even while
        // every trace is legitimately stale (which is true today -- all three predate metadata).
        assertThat(current).as("the allowlist fingerprint must be computable").hasSize(8);
    }

    @Test
    void aTraceThatClaimsToProtectACapabilityActuallyExercisesIt() {
        // Stops a trace from accumulating capability claims it does not earn -- which is how a
        // corpus comes to look like it covers more than it does.
        List<String> unearned = new ArrayList<>();
        for (String name : PdfTrace.committedTraceNames()) {
            TraceValidator.Result result = TraceValidator.validate(name, PdfTrace.read(name));
            result.findings().stream()
                    .filter(f -> f.check().equals("capability-evidence"))
                    .forEach(f -> unearned.add(name + ": " + f.detail()));
        }

        assertThat(unearned)
                .as("a trace claims to protect a capability that does not activate on it")
                .isEmpty();
    }

    // P-002 Fix 2 (commit pending) makes zero located sections the CORRECT reading of this one
    // trace: every one of its eight sections was a prose paragraph (a fee schedule, MITC text)
    // misread as a table header, not a genuine layout. See HeaderProseRejectionTest. The general
    // rule below -- a trace that yields no sections is corruption, not evidence -- still holds for
    // every other trace in the corpus; this is a deliberate, named exception, not a loosened check.
    private static final String ALL_PROSE_NO_GENUINE_TABLE = "kotak-credit-card-ledger-validation";

    @Test
    void everyTraceStillParsesIntoATable() {
        // A trace that yields no sections is a file, not evidence of a layout. Catches a trace
        // corrupted by an editor, a bad merge, or a line-ending rewrite.
        for (String name : PdfTrace.committedTraceNames()) {
            if (name.equals(ALL_PROSE_NO_GENUINE_TABLE)) continue;
            TraceValidator.Result result = TraceValidator.validate(name, PdfTrace.read(name));
            assertThat(result.sections())
                    .as("%s parses into no sections at all", name)
                    .isGreaterThan(0);
        }
    }

    @Test
    void theCorpusIsNotEmpty() {
        // Guards the guard: every check above passes trivially against zero traces, so a resource
        // path change or a build layout change would silently disable the whole file.
        assertThat(PdfTrace.committedTraceNames())
                .as("no traces found — the corpus health checks are testing nothing")
                .isNotEmpty();
    }
}
