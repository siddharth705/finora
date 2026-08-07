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
 * claim, which would let the corpus grade its own homework. It also has to work today: every
 * committed trace is v1 and declares no capabilities at all, so a metadata-based gate would report
 * perfect coverage of nothing.
 *
 * <p>Both use an explicit named accept-list rather than a tolerated count, following
 * {@code LayerDependencyDirectionTest} and {@code check-dependency-advisories.py}. The property
 * that matters is that a <em>stale</em> entry fails too: the day a trace covers one of these, this
 * test goes red until the entry is deleted, so the ratchet tightens by itself instead of waiting for
 * someone to remember.
 */
class CapabilityCorpusCoverageTest {

    /**
     * Recorded by the engine, absent from the registry — so the coverage map cannot see them.
     *
     * <p>Each needs a decision rather than an entry: is it a capability the map should report, or
     * is it something else wearing a capability's clothes? {@code UNANCHORED_ROWS_ABANDONED} looks
     * like the latter — a failure signal, not a layout the engine handles — and a registry that
     * counts it would report a coverage figure improved by parsing badly.
     *
     * <p>Nothing may be added here without that decision being made.
     */
    private static final Map<String, String> RECORDED_BUT_UNDECLARED = new LinkedHashMap<>(Map.of(
            "PRINTED_SUMMARY_TOTALS",
            "recorded when a statement prints its own debit/credit totals. Genuinely a capability; "
                    + "belongs in the registry once someone confirms the name is the one we want.",
            "RIGHT_ALIGNED_AMOUNTS",
            "recorded by PdfTableLocator's column geometry. Genuinely a capability, and one of the "
                    + "most commonly exercised — its absence from the registry means the map has "
                    + "never reported on the thing it most often does.",
            "UNANCHORED_ROWS_ABANDONED",
            "a failure signal, not a capability. Probably should stop being recorded through the "
                    + "capability channel rather than being added to the registry — counting it "
                    + "would let coverage improve by parsing worse."));

    /**
     * Declared in the registry, recorded nowhere — so they can only ever report as never-activated.
     *
     * <p>This is the more damaging direction. The coverage map's stated purpose is to distinguish
     * "the engine can do this and no document has needed it" from "the engine cannot do this". For
     * these two it reports the first while the truth is unknowable, because no code path would emit
     * them even on a document that exercised them.
     */
    private static final Map<String, String> DECLARED_BUT_UNRECORDED = new LinkedHashMap<>(Map.of(
            "LEADING_PLUS_CREDIT",
            "no .record() call anywhere. Either the detection was never wired to the capability "
                    + "channel, or the capability was renamed and the registry kept the old name.",
            "LEADING_NAME_LINE",
            "same shape as LEADING_PLUS_CREDIT. Both are worth resolving before the corpus grows, "
                    + "because a trace cannot be shown to cover a capability nothing emits."));

    /**
     * Declared capabilities that no committed trace exercises yet.
     *
     * <p>This is the corpus shortfall, named. Nine of sixteen today, from three traces — and the
     * first run of this test corrected the list downward, because the traces exercise three
     * capabilities nobody had recorded them as covering. That is what a measured gate buys over an
     * assumed one: coverage was better than the corpus could say for itself, since every committed
     * trace is v1 and declares nothing.
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
        DECLARED_WITHOUT_A_TRACE.put("LEADING_PLUS_CREDIT",
                "no trace, and nothing records it -- see DECLARED_BUT_UNRECORDED. A trace cannot "
                        + "cover a capability nothing emits, so this one is blocked on that first.");
        DECLARED_WITHOUT_A_TRACE.put("LEADING_NAME_LINE",
                "no trace, and nothing records it -- same as LEADING_PLUS_CREDIT.");
    }

    /** {@code ctx.record("NAME")} — how a capability activation reaches the document context. */
    private static final Pattern RECORD_CALL = Pattern.compile("\\.record\\(\"([A-Z_]+)\"\\)");

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

        System.out.printf(
                "%n[corpus] %d/%d declared capabilities exercised by %d committed trace(s) — %.0f%%%n",
                declaredAndCovered.size(), declared.size(),
                PdfTrace.committedTraceNames().size(),
                100.0 * declaredAndCovered.size() / declared.size());
        System.out.println("[corpus] covered:   " + String.join(", ", declaredAndCovered));
        Set<String> remaining = new TreeSet<>(declared);
        remaining.removeAll(declaredAndCovered);
        System.out.println("[corpus] uncovered: " + String.join(", ", remaining));

        assertThat(PdfTrace.committedTraceNames())
                .as("the corpus is empty -- every claim about parser coverage is unevidenced")
                .isNotEmpty();
    }

    /**
     * What the corpus actually exercises, by running the locator over each trace and reading what
     * fired.
     *
     * <p>Deliberately not the traces' own {@code capabilities} metadata. A corpus that grades itself
     * on what it claims to cover is not a gate, and every committed trace is v1 today — no metadata
     * at all — so a metadata-based measure would report perfect coverage of nothing.
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
                    Matcher m = RECORD_CALL.matcher(Files.readString(p));
                    while (m.find()) recorded.add(m.group(1));
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
