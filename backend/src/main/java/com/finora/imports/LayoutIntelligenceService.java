package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.FinancialDocumentMetadata;
import com.finora.entity.RegisteredLayout;
import com.finora.entity.StatementImport;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the layout data the import pipeline has been writing since V39 and answers questions about
 * it. The read side of {@code DocumentContext.buildFingerprint()}, which until now had no reader at
 * all -- every import computed and stored a layout key that nothing ever looked up.
 *
 * <h2>Curated first, aggregated second (Milestone 2 item 2)</h2>
 * The overview is now a join of two sources rather than one {@code GROUP BY}. {@code layout_registry}
 * (V68) supplies what a layout <em>is</em> -- its name, its status, the parser that handles it, and
 * a first/last-seen that survives statement deletion. The aggregate over
 * {@code statement_imports} still supplies what has <em>happened</em> to it: usage, capability
 * stability, unknown headers, durations. Neither can answer the other's questions, which is why
 * both are here.
 *
 * <p>The join is deliberately outer in both directions, and each side means something:
 * <ul>
 *   <li>A registry row with no surviving imports still appears, with a usage count of zero. Its
 *       statements were deleted; the layout was still encountered, and dropping it is exactly the
 *       history loss the registry exists to prevent.</li>
 *   <li>An imported fingerprint with no registry row still appears, marked
 *       {@value #UNREGISTERED}. After V68 backfilled every fingerprint in history and every
 *       confirmed import registers its own, this should never occur -- which is what makes it worth
 *       showing rather than hiding. One of these on the overview means an observation was dropped.</li>
 * </ul>
 *
 * <h2>What this deliberately does NOT do</h2>
 * No layout reuse, no mapping cache, no parser shortcut, no confidence adjustment, no skipping
 * discovery. Nothing here runs during an import; it is a reporting service over rows that already
 * exist. The fingerprint is derived from the header set, and the header set is the OUTPUT of
 * structural discovery, so it could not skip that work even if we wanted it to -- see
 * docs/engineering/layout-intelligence-proposal.md.
 *
 * <h2>Anonymised by construction</h2>
 * Every record this returns is keyed by fingerprint and carries counts, durations and header names
 * only. No user id, no account, no transaction, no bank, no file name, and no balance ever reaches
 * a result type -- not as a convenience field, not "just for debugging". That is what makes
 * platform-wide aggregation operational telemetry rather than cross-user learning, and it is a
 * property of these records rather than of the caller, so it cannot be lost by adding an endpoint.
 *
 * <h2>Counts and nothing else</h2>
 * Same discipline as {@link CapabilityCoverageService}: no scoring, no thresholds, no automatic
 * decisions. `Confidence` as a live metric does not exist yet and is gated behind Phase 3 of the
 * principles doc. A drift signal here says "this changed", never "this is wrong".
 */
@Service
public class LayoutIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(LayoutIntelligenceService.class);

    /**
     * Status reported for a fingerprint that history knows about and the registry does not.
     *
     * <p>Not a {@code RegisteredLayout.Status} value, on purpose: it describes the absence of a row
     * rather than a state a row can be in, and adding it to the persisted enum would make "not
     * registered" storable in the registry.
     */
    public static final String UNREGISTERED = "UNREGISTERED";

    /**
     * One layout: what the registry says it is, and what the imports say has happened to it.
     *
     * <p>{@code name}, {@code status} and {@code parser} come from {@code layout_registry};
     * everything else is aggregated. {@code status} is a String rather than the persisted enum so
     * this record can carry {@value #UNREGISTERED}, and so a reporting type never puts an entity
     * type on the wire.
     */
    public record LayoutSummary(
            String fingerprint,
            /** Null until an operator names it -- never a generated placeholder, so "how many
             *  layouts have we identified" stays answerable. */
            String name,
            /** OBSERVED / UNDER_REVIEW / SUPPORTED / UNSUPPORTED, or {@value #UNREGISTERED}. */
            String status,
            String sourceFormat,
            /** The extractor last observed producing this layout. Null on a layout registered
             *  before any import recorded readable metadata for it. */
            String parser,
            int columns,
            /** Imports of this layout that still exist. Zero for a layout whose statements have all
             *  been deleted -- the registry remembers it, the ledger does not. */
            int usageCount,
            /** From the registry when it has a row, which is what makes these survive the deletion
             *  of every statement that produced them. */
            Instant firstSeen,
            Instant lastSeen,
            /** Capabilities that fired on EVERY import of this layout -- the stable core. */
            List<String> stableCapabilities,
            /** Fired on some imports but not others: either the documents genuinely differ, or the
             *  layout is drifting. This is the set worth looking at. */
            List<String> unstableCapabilities,
            List<String> unknownHeaders,
            /** Null when no import of this layout has a recorded duration (everything before V53). */
            Long medianDurationMs,
            int totalRowsImported,
            int totalRowsSkipped
    ) {
        /** A layout seen once teaches nothing about stability -- callers should say so rather than
         *  presenting a single observation as a trend. */
        public boolean isRecurring() { return usageCount > 1; }
    }

    /** One header the hint lists do not recognise, aggregated across every layout it appears in. */
    public record UnknownHeaderSummary(
            String header,
            int importCount,
            /** How many DISTINCT layouts contain it. Greater than one is the strong signal: a
             *  header spanning several layouts is a gap in the hint lists, not a quirk of one
             *  bank's export. */
            int layoutCount,
            List<String> fingerprints,
            Instant firstSeen,
            Instant lastSeen
    ) {}

    /** One import of one layout, oldest first -- the raw material for a timeline view. */
    public record LayoutTimelinePoint(
            Instant importedAt,
            List<String> capabilities,
            List<String> unknownHeaders,
            Long durationMs,
            int rowsImported,
            int rowsSkipped,
            /** True when this import's capability or unknown-header set differs from the import
             *  before it. Surfaces that something changed; says nothing about whether it is bad. */
            boolean changedFromPrevious
    ) {}

    /**
     * The report that decides whether layout reuse is ever worth building.
     *
     * Every field is nullable-by-absence: when there is not enough data to answer, the answer is
     * omitted rather than defaulted to zero. A report that silently reads "0 ms faster" when it
     * actually means "nothing measured" is worse than no report, because it would close the
     * question with a number nobody earned.
     */
    public record EvidenceReport(
            int totalImportsAnalysed,
            int distinctLayouts,
            int recurringLayouts,
            int importsOnRecurringLayouts,
            Long medianDurationFirstEncounter,
            Long medianDurationRecurring,
            Double avgUnknownHeadersFirstEncounter,
            Double avgUnknownHeadersRecurring,
            Double avgSkippedRowsFirstEncounter,
            Double avgSkippedRowsRecurring,
            /** Plain-language statement of what the numbers do and do not support. */
            String verdict
    ) {}

    private final StatementImportRepository statementImportRepository;
    private final LayoutRegistryService layoutRegistryService;
    private final ObjectMapper objectMapper;

    public LayoutIntelligenceService(StatementImportRepository statementImportRepository,
                                      LayoutRegistryService layoutRegistryService,
                                      ObjectMapper objectMapper) {
        this.statementImportRepository = statementImportRepository;
        this.layoutRegistryService = layoutRegistryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Every layout across the platform, most-used first.
     *
     * <p>Registry rows and import aggregates, outer-joined on the fingerprint -- see the class
     * comment for why neither side may drop the other's rows.
     */
    public List<LayoutSummary> layoutOverview() {
        return withRegistry(summarise(statementImportRepository.findAllWithLayoutFingerprint()),
                layoutRegistryService.all());
    }

    /**
     * Overlays the curated record on the aggregate, and adds the layouts the aggregate cannot see.
     *
     * <p>Where a registry row exists it wins on identity (name, status, parser) and on
     * first/last-seen. The aggregate's own first/last-seen are computed from surviving statement
     * imports only, so they silently move forward as old statements are deleted; the registry's do
     * not, and a "first seen" that drifts later every time somebody tidies their uploads is worse
     * than no answer.
     */
    private List<LayoutSummary> withRegistry(List<LayoutSummary> aggregated,
                                              List<RegisteredLayout> registered) {
        Map<String, RegisteredLayout> byFingerprint = new LinkedHashMap<>();
        for (RegisteredLayout layout : registered) byFingerprint.put(layout.getFingerprint(), layout);

        List<LayoutSummary> merged = new ArrayList<>();
        for (LayoutSummary summary : aggregated) {
            RegisteredLayout layout = byFingerprint.remove(summary.fingerprint());
            merged.add(layout == null ? summary : new LayoutSummary(
                    summary.fingerprint(), layout.getName(), layout.getStatus().name(),
                    // COALESCE, not override: a registry row backfilled by V68 has no parser and
                    // may have no source format, and blanking a value the aggregate does know
                    // would make the merge lose information rather than add it.
                    layout.getSourceFormat() != null ? layout.getSourceFormat() : summary.sourceFormat(),
                    layout.getParser(),
                    summary.columns(), summary.usageCount(),
                    layout.getFirstSeen(), layout.getLastSeen(),
                    summary.stableCapabilities(), summary.unstableCapabilities(),
                    summary.unknownHeaders(), summary.medianDurationMs(),
                    summary.totalRowsImported(), summary.totalRowsSkipped()));
        }

        // Whatever is left has no surviving imports. Reported with an empty capability picture
        // rather than an invented one -- there is genuinely nothing left to read it from.
        byFingerprint.values().forEach(layout -> merged.add(new LayoutSummary(
                layout.getFingerprint(), layout.getName(), layout.getStatus().name(),
                layout.getSourceFormat(), layout.getParser(), 0, 0,
                layout.getFirstSeen(), layout.getLastSeen(),
                List.of(), List.of(), List.of(), null, 0, 0)));

        merged.sort(Comparator.comparingInt(LayoutSummary::usageCount).reversed()
                .thenComparing(LayoutSummary::fingerprint));
        return merged;
    }

    /** Every unrecognised header across the platform, most-seen first. */
    public List<UnknownHeaderSummary> unknownHeaders() {
        return aggregateUnknownHeaders(statementImportRepository.findAllWithLayoutFingerprint());
    }

    /** One layout's history, oldest first. */
    public List<LayoutTimelinePoint> timeline(String fingerprint) {
        List<StatementImport> imports = statementImportRepository.findAllWithLayoutFingerprint().stream()
                .filter(si -> fingerprint.equals(si.getLayoutFingerprint()))
                .sorted(Comparator.comparing(StatementImport::getImportedAt))
                .toList();

        List<LayoutTimelinePoint> points = new ArrayList<>();
        List<String> previousCapabilities = null;
        List<String> previousUnknown = null;
        for (StatementImport si : imports) {
            List<String> capabilities = capabilitiesOf(si);
            List<String> unknown = unknownHeadersOf(si);
            boolean changed = previousCapabilities != null
                    && (!capabilities.equals(previousCapabilities) || !unknown.equals(previousUnknown));
            points.add(new LayoutTimelinePoint(si.getImportedAt(), capabilities, unknown,
                    si.getImportDurationMs(), si.getTransactionsImported(), si.getTransactionsSkipped(), changed));
            previousCapabilities = capabilities;
            previousUnknown = unknown;
        }
        return points;
    }

    /**
     * Layouts whose most recent import differs structurally from the pattern established by the
     * ones before it. Surfaces the change; does not attempt to explain or fix it.
     *
     * Requires at least three prior imports, so "the second import of a layout looks different from
     * the first" is not reported as a regression -- with one prior observation there is no
     * established pattern to diverge from.
     */
    public List<LayoutSummary> driftingLayouts() {
        List<LayoutSummary> drifting = new ArrayList<>();
        for (LayoutSummary summary : layoutOverview()) {
            if (summary.usageCount() < 4) continue;
            List<LayoutTimelinePoint> points = timeline(summary.fingerprint());
            if (!points.isEmpty() && points.get(points.size() - 1).changedFromPrevious()) {
                drifting.add(summary);
            }
        }
        return drifting;
    }

    public EvidenceReport evidenceReport() {
        List<StatementImport> all = statementImportRepository.findAllWithLayoutFingerprint();
        List<LayoutSummary> layouts = summarise(all);

        Set<String> recurringFingerprints = new LinkedHashSet<>();
        for (LayoutSummary l : layouts) if (l.isRecurring()) recurringFingerprints.add(l.fingerprint());

        // "First encounter" = the earliest import of each fingerprint; every later one is a
        // recurrence. Splitting this way is the whole comparison -- if recurrence brought no
        // benefit, the two halves look the same.
        Map<String, Instant> earliest = new LinkedHashMap<>();
        for (StatementImport si : all) {
            earliest.merge(si.getLayoutFingerprint(), si.getImportedAt(),
                    (a, b) -> a.isBefore(b) ? a : b);
        }

        List<StatementImport> firstEncounters = new ArrayList<>();
        List<StatementImport> recurrences = new ArrayList<>();
        for (StatementImport si : all) {
            if (si.getImportedAt().equals(earliest.get(si.getLayoutFingerprint()))) firstEncounters.add(si);
            else recurrences.add(si);
        }

        Long medianFirst = medianDuration(firstEncounters);
        Long medianRecurring = medianDuration(recurrences);

        return new EvidenceReport(
                all.size(), layouts.size(), recurringFingerprints.size(), recurrences.size(),
                medianFirst, medianRecurring,
                averageUnknownHeaders(firstEncounters), averageUnknownHeaders(recurrences),
                averageSkipped(firstEncounters), averageSkipped(recurrences),
                verdict(all.size(), recurringFingerprints.size(), recurrences.size(), medianFirst, medianRecurring));
    }

    /**
     * States what the numbers support in words, because a table of figures invites whoever reads it
     * to supply their own conclusion -- and the conclusion this report most often licenses is "no
     * evidence for reuse", which is a perfectly good outcome that a bare table does not convey.
     */
    private String verdict(int imports, int recurringLayouts, int recurrences, Long medianFirst, Long medianRecurring) {
        if (imports == 0) return "No imports carry a layout fingerprint yet. Nothing to conclude.";
        if (recurringLayouts == 0) {
            return "No layout has been seen more than once across " + imports + " imports. "
                    + "Layout reuse has nothing to reuse; do not build it on this evidence.";
        }
        if (medianFirst == null || medianRecurring == null) {
            return recurrences + " recurring imports across " + recurringLayouts + " layouts, but too few "
                    + "have a recorded duration to compare (durations are only stored from V53 onward). "
                    + "Re-run once more imports have accumulated.";
        }
        long delta = medianFirst - medianRecurring;
        if (Math.abs(delta) * 10 < medianFirst) {
            return "Recurring layouts import at effectively the same speed as first encounters ("
                    + medianRecurring + "ms vs " + medianFirst + "ms). No performance case for layout reuse.";
        }
        return "Recurring layouts import " + delta + "ms " + (delta > 0 ? "faster" : "slower")
                + " at the median (" + medianRecurring + "ms vs " + medianFirst + "ms). Worth investigating "
                + "why before drawing conclusions -- this is a correlation across differing documents, "
                + "not a controlled measurement.";
    }

    List<LayoutSummary> summarise(List<StatementImport> imports) {
        Map<String, List<StatementImport>> byFingerprint = new LinkedHashMap<>();
        for (StatementImport si : imports) {
            byFingerprint.computeIfAbsent(si.getLayoutFingerprint(), k -> new ArrayList<>()).add(si);
        }

        List<LayoutSummary> summaries = new ArrayList<>();
        byFingerprint.forEach((fingerprint, group) -> {
            Map<String, Integer> capabilityCounts = new TreeMap<>();
            Set<String> unknown = new LinkedHashSet<>();
            Instant first = null, last = null;
            int rowsImported = 0, rowsSkipped = 0;
            String sourceFormat = null;
            int columns = 0;

            for (StatementImport si : group) {
                for (String c : capabilitiesOf(si)) capabilityCounts.merge(c, 1, Integer::sum);
                unknown.addAll(unknownHeadersOf(si));
                if (first == null || si.getImportedAt().isBefore(first)) first = si.getImportedAt();
                if (last == null || si.getImportedAt().isAfter(last)) last = si.getImportedAt();
                rowsImported += si.getTransactionsImported();
                rowsSkipped += si.getTransactionsSkipped();
                FinancialDocumentMetadata metadata = metadataOf(si);
                if (metadata != null) {
                    if (sourceFormat == null) sourceFormat = metadata.sourceFormat();
                    if (columns == 0) columns = metadata.columns();
                }
            }

            List<String> stable = new ArrayList<>();
            List<String> unstable = new ArrayList<>();
            capabilityCounts.forEach((capability, count) -> {
                if (count == group.size()) stable.add(capability);
                else unstable.add(capability);
            });

            // name/status/parser are the registry's to supply; this method only sees imports.
            // UNREGISTERED is therefore the honest default here, and withRegistry() replaces it for
            // every fingerprint that has a row.
            summaries.add(new LayoutSummary(fingerprint, null, UNREGISTERED, sourceFormat, null,
                    columns, group.size(), first, last,
                    stable, unstable, new ArrayList<>(unknown), medianDuration(group), rowsImported, rowsSkipped));
        });

        summaries.sort(Comparator.comparingInt(LayoutSummary::usageCount).reversed()
                .thenComparing(LayoutSummary::fingerprint));
        return summaries;
    }

    List<UnknownHeaderSummary> aggregateUnknownHeaders(List<StatementImport> imports) {
        Map<String, Integer> importCounts = new LinkedHashMap<>();
        Map<String, Set<String>> fingerprints = new LinkedHashMap<>();
        Map<String, Instant> firstSeen = new LinkedHashMap<>();
        Map<String, Instant> lastSeen = new LinkedHashMap<>();

        for (StatementImport si : imports) {
            for (String header : unknownHeadersOf(si)) {
                importCounts.merge(header, 1, Integer::sum);
                fingerprints.computeIfAbsent(header, k -> new LinkedHashSet<>()).add(si.getLayoutFingerprint());
                firstSeen.merge(header, si.getImportedAt(), (a, b) -> a.isBefore(b) ? a : b);
                lastSeen.merge(header, si.getImportedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }

        List<UnknownHeaderSummary> result = new ArrayList<>();
        importCounts.forEach((header, count) -> {
            Set<String> layouts = fingerprints.get(header);
            result.add(new UnknownHeaderSummary(header, count, layouts.size(), new ArrayList<>(layouts),
                    firstSeen.get(header), lastSeen.get(header)));
        });

        // Headers spanning several layouts first: those are hint-list gaps rather than one export's
        // quirk, which makes them the most actionable thing in this whole report.
        result.sort(Comparator.comparingInt(UnknownHeaderSummary::layoutCount)
                .thenComparingInt(UnknownHeaderSummary::importCount).reversed()
                .thenComparing(UnknownHeaderSummary::header));
        return result;
    }

    /** Median, not mean: one pathological 30-second import should not move the number that decides
     *  whether an optimisation is worth building. Null when nothing in the group was measured. */
    private Long medianDuration(List<StatementImport> imports) {
        List<Long> durations = imports.stream()
                .map(StatementImport::getImportDurationMs)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (durations.isEmpty()) return null;
        return durations.get(durations.size() / 2);
    }

    private Double averageUnknownHeaders(List<StatementImport> imports) {
        if (imports.isEmpty()) return null;
        return imports.stream().mapToInt(si -> unknownHeadersOf(si).size()).average().orElse(0);
    }

    private Double averageSkipped(List<StatementImport> imports) {
        if (imports.isEmpty()) return null;
        return imports.stream().mapToInt(StatementImport::getTransactionsSkipped).average().orElse(0);
    }

    private List<String> capabilitiesOf(StatementImport si) {
        if (si.getActivatedCapabilitiesJson() == null) return List.of();
        try {
            List<String> parsed = objectMapper.readValue(si.getActivatedCapabilitiesJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return parsed.stream().sorted().toList();
        } catch (Exception e) {
            // One malformed row must not blank the whole report -- it is telemetry, not a ledger.
            log.warn("Unreadable activated capabilities on statement import {}", si.getId(), e);
            return List.of();
        }
    }

    private List<String> unknownHeadersOf(StatementImport si) {
        FinancialDocumentMetadata metadata = metadataOf(si);
        if (metadata == null || metadata.unknownHeaders() == null) return List.of();
        return metadata.unknownHeaders().stream().sorted().toList();
    }

    private FinancialDocumentMetadata metadataOf(StatementImport si) {
        if (si.getLayoutMetadataJson() == null) return null;
        try {
            return objectMapper.readValue(si.getLayoutMetadataJson(), FinancialDocumentMetadata.class);
        } catch (Exception e) {
            log.warn("Unreadable layout metadata on statement import {}", si.getId(), e);
            return null;
        }
    }
}
