package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2, item 1 — the corpus gate.
 *
 * <p>The charter's gate has two halves: <em>the list of layouts we claim to support exists in
 * writing</em>, and <em>every one of them has a trace</em>. The first half already existed —
 * {@link CapabilityCoverageService#KNOWN_CAPABILITIES}, hand-maintained on purpose. Nothing
 * connected it to the second half, so "coverage" was an impression rather than a number.
 *
 * <p><b>What the number is, precisely.</b> How many declared capabilities have at least one
 * committed trace that exercises them — a statement about the corpus, not about the parser. A
 * capability with no trace may work perfectly; what it lacks is anything that would notice if it
 * stopped. Reading it as "9 of 16 are broken" is the misreading this test is most likely to cause,
 * so the console output says so explicitly on every run.
 *
 * <p>Two things are held here, and they fail in opposite directions on purpose.
 *
 * <p><b>The registry matches the engine.</b> A capability the engine records but the registry does
 * not know is invisible to the coverage map. A capability the registry declares but nothing records
 * is worse: it reports as never-activated forever, and never-activated is the one signal that map
 * exists to produce. Both make the map lie quietly, which is the failure mode the Evidence Rule
 * names.
 *
 * <p><b>Every declared capability is exercised by a committed trace.</b> Measured by running the
 * locator over the trace and reading what actually fired — not by reading the trace's own metadata
 * claim, which would let the corpus grade its own homework. The v3 traces do declare capabilities
 * now, which makes the distinction sharper rather than moot: a declaration is what the capture
 * intended, and this gate reports what the locator actually did. The one v1 trace left declares
 * nothing at all, so a metadata-based gate would still report perfect coverage of nothing.
 *
 * <p>Both use an explicit named accept-list rather than a tolerated count, following
 * {@code LayerDependencyDirectionTest} and {@code check-dependency-advisories.py}. The property
 * that matters is that a <em>stale</em> entry fails too: the day a trace covers one of these, this
 * test goes red until the entry is deleted, so the ratchet tightens by itself instead of waiting for
 * someone to remember.
 */
class CapabilityCorpusCoverageTest {

    /**
     * Recorded by the engine, absent from the registry.
     *
     * <p>Empty. Both original entries -- PRINTED_SUMMARY_TOTALS and RIGHT_ALIGNED_AMOUNTS -- were
     * real capabilities and are now registered. The third, UNANCHORED_ROWS_ABANDONED, was not a
     * capability at all and moved to the diagnostics channel.
     *
     * <p>Kept as a field rather than deleted, because an empty accept-list is the assertion: a
     * capability the engine records and the registry has never heard of appears in neither
     * activations nor neverActivated, so the coverage map cannot report the one gap it exists for.
     */
    private static final Map<String, String> RECORDED_BUT_UNDECLARED = Map.of();

    /**
     * Declared in the registry, recorded nowhere.
     *
     * <p>Empty, and that is the point of leaving it here rather than deleting the field: this was
     * the more damaging of the two drifts. A capability nothing records reports as never-activated
     * forever, which is indistinguishable from "no document has needed it" -- and never-activated is
     * the one signal the coverage map exists to produce.
     *
     * <p>Both original entries turned out to be Case A, live capabilities. LEADING_PLUS_CREDIT
     * always recorded and the scan missed it; LEADING_NAME_LINE had always implemented the
     * behaviour and was simply never wired. Neither was obsolete, which is the outcome worth
     * noting: the registry was right and the evidence was missing.
     */
    private static final Map<String, String> DECLARED_BUT_UNRECORDED = Map.of();

    /**
     * Declared capabilities that no committed trace exercises yet.
     *
     * <p>This is the corpus shortfall, named. Ten of nineteen, from three traces --
     * RIGHT_ALIGNED_AMOUNTS came off this list when the two HDFC documents were re-captured with
     * widths intact.
     *
     * <p>It has corrected me twice, in both directions, which is the argument for measuring rather
     * than listing. First run: three capabilities I had listed as uncovered were in fact exercised,
     * because the traces are v1 and declare nothing, so the corpus covered more than it could say
     * for itself. Then, on registering RIGHT_ALIGNED_AMOUNTS, the reverse — I had called it one of
     * the most commonly exercised capabilities and no committed trace activates it at all.
     *
     * <p>Every line removed from here is a layout that stops being a claim and starts being
     * evidence.
     *
     * <p>Deliberately not a count. A count would let one trace be swapped for another with no
     * visible change; a name makes each gap something a person decided to leave open.
     */
    private static final Map<String, String> DECLARED_WITHOUT_A_TRACE = new LinkedHashMap<>();
    static {
        DECLARED_WITHOUT_A_TRACE.put("RUNNING_BALANCE", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("DR_CR_SUFFIX", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("DATE_TIME_COLUMN", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_SIGNAL", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("GRID_METADATA_FALLBACK", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("GRID_METADATA_TRAILING_LABEL", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("FINANCIAL_PRODUCT_CLASSIFICATION", "no trace");
        DECLARED_WITHOUT_A_TRACE.put("PRINTED_SUMMARY_TOTALS", "no trace; newly registered");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_TOTALS",
                "no trace yet -- CreditCardSummaryExtractorTest exercises the GRID strategy on "
                        + "synthetic fixtures reproducing real observed shapes (a clean stacked grid, "
                        + "and the row-merge recovery motivated by a real Axis statement), but no "
                        + "committed trace fixture exists for either yet. See the architecture doc's "
                        + "Credit Card Direction Evidence Study addendum for the measured real-corpus "
                        + "fire rate.");
        DECLARED_WITHOUT_A_TRACE.put("CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE",
                "no trace yet -- same situation as CREDIT_CARD_SUMMARY_TOTALS above, for the "
                        + "INLINE_LABEL_VALUE strategy motivated by a real AU statement's label-left/value-right "
                        + "layout. Covered by synthetic fixtures in CreditCardSummaryExtractorTest, "
                        + "not yet by a committed real-document trace.");
        DECLARED_WITHOUT_A_TRACE.put("TRANSACTION_TABLE_TOTAL_CLOSED",
                "no trace yet -- motivated by a real Kotak Mahindra Bank credit-card statement's "
                        + "own \"Total Purchase & Other Charges\" column-total row; real-corpus verified "
                        + "directly against the unredacted document (CorpusProbe/PdfPipelineDiagnostic, "
                        + "not just a synthetic reproduction) in the Phase 2A/2C investigation. Capturing "
                        + "a redacted trace from this specific document was attempted and refused by "
                        + "TraceValidator (zero sections survive redaction on this layout) -- an "
                        + "unrelated pre-existing gap in trace capture for this document's shape, not "
                        + "something this change is scoped to fix. Covered by a synthetic fixture in "
                        + "StatementClosingMarkerPdfPreviewGeneratorTest instead, mutation-checked "
                        + "against the pre-fix code.");
        DECLARED_WITHOUT_A_TRACE.put("MITC_SECTION_CLOSED",
                "no trace yet -- motivated by a real ICICI Bank credit-card statement's own all-caps "
                        + "\"MOST IMPORTANT TERMS AND CONDITIONS (MITC)\" section heading; real-corpus "
                        + "verified directly against the unredacted document in the Phase 2A/2C "
                        + "investigation. A redacted trace WAS captured from this document, but the "
                        + "heading sits on page 2 and the captured trace's own text does not reach that "
                        + "far, so it exercises this document's other capabilities without exercising "
                        + "this one -- not committed, since a trace that cannot exercise the capability "
                        + "it would be cited for is not real coverage. Covered by a synthetic fixture in "
                        + "StatementClosingMarkerPdfPreviewGeneratorTest instead, mutation-checked "
                        + "against the pre-fix code.");
        // RIGHT_ALIGNED_AMOUNTS was here, with the note "either the three traces genuinely avoid
        // right-aligned amount columns, or the recording sits on a path they do not take. Measure
        // before capturing." It was measured, and the answer was a third thing: the two HDFC
        // traces DO carry right-aligned amount columns and DO take the path, but every committed
        // trace was width-blind -- v1 has no width column, and the v3 captures made before the
        // redactor fix zeroed every width -- so the guard at PdfTableLocator's right-edge redirect
        // (`t.width() > 0`) could never be true on the corpus. The capability was unreachable on
        // the evidence, not unexercised by it. Recapturing both documents with the width-preserving
        // redactor activates it on both.
        DECLARED_WITHOUT_A_TRACE.put("LEADING_PLUS_CREDIT",
                "no trace, and nothing records it -- see DECLARED_BUT_UNRECORDED. A trace cannot "
                        + "cover a capability nothing emits, so this one is blocked on that first.");
        DECLARED_WITHOUT_A_TRACE.put("LEADING_NAME_LINE",
                "no trace, and nothing records it -- same as LEADING_PLUS_CREDIT.");
        DECLARED_WITHOUT_A_TRACE.put("INFERRED_HEADERLESS_LAYOUT",
                "no trace, and none is planned -- the one real document that motivates it is a "
                        + "genuinely headerless statement, so a trace captured from it would need widths "
                        + "recorded from real dates and amounts to reproduce the balance-chain scoring "
                        + "this depends on, which the Synthetic Fixture Policy requires be synthesized, "
                        + "not preserved, for exactly this kind of fixture. Covered instead by "
                        + "HeaderlessLayoutInferenceTest's fully hand-synthesized fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("ILLUSTRATIVE_BLOCK_SUPPRESSED",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT. "
                        + "Covered instead by IllustrativeBlockSuppressionTest's fully hand-synthesized "
                        + "fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("INFERRED_TWO_LINE_DATE_BLOCK",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT. "
                        + "Covered instead by TwoLineDateBlockInferenceTest's fully hand-synthesized "
                        + "fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("PHYSICAL_ROW_DEDUP_EVIDENCE",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT: the "
                        + "one real document known to exercise the headerless path (a real SBI savings "
                        + "statement) contains no repeated physical row for this to remove, so no real "
                        + "trace has ever activated it, and a synthesized trace would need to reproduce "
                        + "the same balance-chain-scoring geometry INFERRED_HEADERLESS_LAYOUT's own "
                        + "entry explains. Covered instead by HeaderlessLayoutInferenceTest's fully "
                        + "hand-synthesized reprinted-row fixture.");
        DECLARED_WITHOUT_A_TRACE.put("CARD_ENDING_DIGITS_IDENTITY",
                "no trace, and none is planned -- same reasoning as INFERRED_HEADERLESS_LAYOUT: the "
                        + "one real document that motivates it (a real AU Small Finance Bank "
                        + "credit-card statement) would need its actual card-ending sentence "
                        + "preserved for a trace to exercise this, which the Synthetic Fixture Policy "
                        + "requires be synthesized, not preserved. Covered instead by "
                        + "PdfMetadataExtractorTest's fully hand-synthesized fixtures.");
        // BLANK_COLUMN_NAME_QUALIFIED and RECOVERED_MISSING_DESCRIPTION_COLUMN are deliberately
        // NOT listed here: the already-committed sbi-credit-card-statement trace turns out to
        // exercise both for real (its own "( ` )" blank-currency cell, and a genuine missing-
        // description recovery elsewhere in its composite structure) -- found by
        // theCorpusShortfallOnlyEverShrinks the moment these were added, exactly the ratchet it
        // exists to enforce. Motivated by a real ICICI savings e-statement either way; also
        // covered by HeaderColumnRecoveryTest's fully hand-synthesized fixtures.
        DECLARED_WITHOUT_A_TRACE.put("RECOVERED_MISSING_SERIAL_NUMBER_COLUMN",
                "no trace, and none is planned -- same reasoning as BLANK_COLUMN_NAME_QUALIFIED, "
                        + "same real document. Covered instead by HeaderColumnRecoveryTest's fully "
                        + "hand-synthesized fixtures.");
        DECLARED_WITHOUT_A_TRACE.put("HEADER_RECONSTRUCTED",
                "no trace, deliberately -- motivated by a real SBI Credit Card statement's "
                        + "supplementary-cardholder section (Phase 2E.1/2E.2), whose header prints one "
                        + "column alone on the physical line above the row that gets accepted on its "
                        + "own. Real-corpus verified directly against the committed "
                        + "sbi-credit-card-statement.trace during development, and does not fire on it, "
                        + "for two independent reasons found while widening this gate. First: this "
                        + "engine is deliberately scoped to exactly ONE orphaned single-cell fragment "
                        + "(PdfTableLocator.reconstructHeader's nonBlankCount(above) != 1 guard) -- a "
                        + "real ICICI savings statement in the same corpus has a genuine THREE-cell "
                        + "second tier one line above ITS accepted header, and composing all three in "
                        + "unresolved recovered some real transactions but left the statement's own "
                        + "aggregate balance-total check failing, a partial result this phase is not "
                        + "scoped to ship (the general, multi-tier composition case is explicitly "
                        + "deferred, design doc §8). Second, independently: even within scope, this "
                        + "engine's row-compatibility validation (does the candidate's date column "
                        + "bucket cleanly, with no two raw values colliding into one cell, across the "
                        + "section's real rows) correctly refuses to prefer a reconstruction it cannot "
                        + "verify improves anything, and the SBI trace's own redacted dates are not "
                        + "parseable at all -- the same refusal a genuinely bad candidate would get, "
                        + "which is the point: the engine cannot and must not tell those two cases "
                        + "apart from evidence alone. Covered instead by HeaderReconstructionEngineTest's "
                        + "fully hand-synthesized fixtures, which reproduce the single-fragment shape "
                        + "with realistic, parseable dates.");
    }

    /**
     * {@code ctx.record(...)} — the capability channel, capturing the whole argument expression so
     * every SCREAMING_SNAKE literal inside it is seen, not only a bare string argument.
     *
     * <p>The first version matched {@code .record("NAME")} exactly and missed
     * {@code ctx.record(x.startsWith("+") ? "LEADING_PLUS_CREDIT" : "DR_CR_SUFFIX")}, which made a
     * live capability look unwired and sent me hunting for a fix that was not needed. A scan that
     * under-reports is the worse failure of the two directions this test checks: it can also miss a
     * capability recorded through a ternary and never registered, which is exactly the drift the
     * test exists to catch.
     *
     * <p>Scoped to a {@code ctx.} receiver deliberately. {@code auditService.record(...)} is an
     * unrelated method whose second argument is a SCREAMING_SNAKE audit action.
     */
    private static final Pattern RECORD_CALL = Pattern.compile("\\bctx\\.record\\(([^;]*?)\\)\\s*;");

    /** A capability name inside that call. Four characters minimum, so a stray "PDF" or "OK" in the
     *  same expression is not mistaken for one. */
    private static final Pattern CAPABILITY_LITERAL = Pattern.compile("\"([A-Z][A-Z_]{3,})\"");

    @Test
    void everyCapabilityTheEngineRecordsIsInTheRegistry() {
        Set<String> undeclared = new TreeSet<>(recordedByTheEngine());
        undeclared.removeAll(CapabilityCoverageService.KNOWN_CAPABILITIES);
        undeclared.removeAll(RECORDED_BUT_UNDECLARED.keySet());

        assertThat(undeclared)
                .as("""
                        The engine records a capability the registry has never heard of, so \
                        CapabilityCoverageService cannot report on it -- it will not appear in \
                        activations and will not appear in neverActivated either. Add it to \
                        KNOWN_CAPABILITIES, or decide it is not a capability and stop recording it \
                        through this channel.""")
                .isEmpty();
    }

    @Test
    void everyRegisteredCapabilityIsActuallyRecordedSomewhere() {
        Set<String> phantom = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        phantom.removeAll(recordedByTheEngine());
        phantom.removeAll(DECLARED_BUT_UNRECORDED.keySet());

        assertThat(phantom)
                .as("""
                        The registry declares a capability nothing records. It will report as \
                        never-activated forever, which is indistinguishable from "no document has \
                        needed it" -- and never-activated is the one signal the coverage map exists \
                        to produce. Wire the detection to ctx.record(), or remove the name.""")
                .isEmpty();
    }

    @Test
    void theAcceptListsDoNotOutliveTheProblemsTheyDescribe() {
        Set<String> recorded = recordedByTheEngine();
        List<String> stale = new ArrayList<>();

        RECORDED_BUT_UNDECLARED.keySet().stream()
                .filter(c -> CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c) || !recorded.contains(c))
                .forEach(c -> stale.add("RECORDED_BUT_UNDECLARED: " + c));
        DECLARED_BUT_UNRECORDED.keySet().stream()
                .filter(c -> recorded.contains(c) || !CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c))
                .forEach(c -> stale.add("DECLARED_BUT_UNRECORDED: " + c));

        assertThat(stale)
                .as("""
                        An accept-list entry no longer describes anything real -- the drift it \
                        documented was resolved. Delete it, so the list keeps meaning what it says \
                        and this test gets correspondingly stricter.""")
                .isEmpty();
    }

    @Test
    void everyDeclaredCapabilityIsExercisedByACommittedTrace() {
        Set<String> covered = capabilitiesTheCorpusExercises();

        Set<String> uncovered = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        uncovered.removeAll(covered);
        uncovered.removeAll(DECLARED_WITHOUT_A_TRACE.keySet());

        assertThat(uncovered)
                .as("""
                        A capability is claimed with nothing in the corpus that exercises it, so a \
                        change that breaks it fails on a customer's statement rather than on this \
                        build. Capture a trace, or add it to DECLARED_WITHOUT_A_TRACE with the \
                        reason it is being left uncovered.""")
                .isEmpty();
    }

    /** The ratchet. Every line deleted from the shortfall is a claim that became evidence, and this
     *  is what stops one being added back quietly to make room for another. */
    @Test
    void theCorpusShortfallOnlyEverShrinks() {
        Set<String> covered = capabilitiesTheCorpusExercises();
        List<String> resolved = DECLARED_WITHOUT_A_TRACE.keySet().stream().filter(covered::contains).toList();

        assertThat(resolved)
                .as("""
                        A trace now exercises a capability still listed as uncovered. Delete the \
                        entry -- leaving it there means the shortfall stops describing the corpus \
                        and the number stops being worth reporting.""")
                .isEmpty();

        List<String> unknown = DECLARED_WITHOUT_A_TRACE.keySet().stream()
                .filter(c -> !CapabilityCoverageService.KNOWN_CAPABILITIES.contains(c)).toList();
        assertThat(unknown)
                .as("the shortfall names a capability the registry no longer declares")
                .isEmpty();
    }

    /**
     * Reports coverage rather than asserting it, so the number is visible on every run.
     *
     * <p>Not a soft assertion — the gate above is the assertion. This exists because a shortfall
     * nobody sees is a shortfall nobody closes, and a figure printed on every build is the cheapest
     * form of pressure there is.
     */
    @Test
    void reportsWhereTheCorpusStands() {
        Set<String> declared = new TreeSet<>(CapabilityCoverageService.KNOWN_CAPABILITIES);
        Set<String> covered = capabilitiesTheCorpusExercises();
        Set<String> declaredAndCovered = new TreeSet<>(declared);
        declaredAndCovered.retainAll(covered);

        // Worded carefully, because this line is what gets quoted. "7 of 16 covered" reads as "9 do
        // not work", and that is not what was measured -- a capability can be correct and simply
        // have no committed trace. What this number says is how much of the engine a parser change
        // cannot silently break, which is a statement about the CORPUS, not about the parser.
        System.out.printf(
                "%n[corpus] %d of %d declared capabilities have at least one regression trace "
                        + "exercising them (%.0f%%), from %d committed trace(s).%n"
                        + "[corpus] This measures evidence, not correctness: an unexercised "
                        + "capability may work perfectly and simply lack a trace.%n",
                declaredAndCovered.size(), declared.size(),
                100.0 * declaredAndCovered.size() / declared.size(),
                PdfTrace.committedTraceNames().size());
        System.out.println("[corpus] exercised by a trace: " + String.join(", ", declaredAndCovered));
        Set<String> remaining = new TreeSet<>(declared);
        remaining.removeAll(declaredAndCovered);
        System.out.println("[corpus] no trace yet:        " + String.join(", ", remaining));

        assertThat(PdfTrace.committedTraceNames())
                .as("the corpus is empty -- every claim about parser coverage is unevidenced")
                .isNotEmpty();
    }

    /**
     * What the corpus actually exercises, by running the locator over each trace and reading what
     * fired.
     *
     * <p>Deliberately not the traces' own {@code capabilities} metadata. A corpus that grades itself
     * on what it claims to cover is not a gate. The two v3 traces now carry capability metadata and
     * the remaining v1 carries none, so a metadata-based measure would grade the corpus on its own
     * claims for two documents and report nothing at all for the third.
     */
    private static Set<String> capabilitiesTheCorpusExercises() {
        Set<String> covered = new LinkedHashSet<>();
        for (String name : PdfTrace.committedTraceNames()) {
            DocumentContext ctx = new DocumentContext("PDF", "CapabilityCorpusCoverageTest");
            new PdfTableLocator().locateAll(PdfTrace.load(name), ctx);
            ctx.capabilities().stream().map(ImportDto.CapabilityActivation::capability).forEach(covered::add);
        }
        return covered;
    }

    /**
     * Every capability name the production sources pass to {@code ctx.record(...)}.
     *
     * <p>Read from source rather than from a constant because there is no enum to read — the names
     * are string literals at their call sites. Scanning is what makes this test able to notice a
     * name that was added without being registered, which is the whole point; the same idiom is
     * already used by {@code MoneyComparisonUsageTest}.
     */
    private static Set<String> recordedByTheEngine() {
        Set<String> recorded = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Matcher call = RECORD_CALL.matcher(Files.readString(p));
                    while (call.find()) {
                        Matcher literal = CAPABILITY_LITERAL.matcher(call.group(1));
                        while (literal.find()) recorded.add(literal.group(1));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(recorded)
                .as("no capability recording found in the sources at all -- the scan is broken, "
                        + "and a broken scan makes every assertion in this class pass vacuously")
                .isNotEmpty();
        return recorded;
    }
}
