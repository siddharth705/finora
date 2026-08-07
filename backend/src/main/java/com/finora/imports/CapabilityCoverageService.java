package com.finora.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.CapabilityActivation;
import com.finora.entity.StatementImport;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Phase 4, steps 11-12: turns per-import facts that were already being recorded into numbers that
 * can fail a build.
 *
 * The engine has recorded {@code activated_capabilities_json} on every import since Phase 1 and
 * nothing has ever read it. This aggregates it into two things the roadmap keeps asserting without
 * evidence:
 *
 * <ul>
 *   <li>a <b>coverage map</b> -- which capabilities actually fire on real documents, and, more
 *       usefully, which ones exist in the registry and have never fired at all. A capability with
 *       no activations is either dead code or a gap in the corpus, and both are worth knowing;</li>
 *   <li>a <b>capability backlog with real frequencies</b> -- which failure reasons and which
 *       column shapes cost the most rows. The backlog table in the engineering principles doc
 *       currently says things like "1 statement" and "6 of 7 real statements in the Aug 2026
 *       validation pass", because counting meant someone debugging documents by hand.</li>
 * </ul>
 *
 * <h2>Data before dashboards</h2>
 *
 * This deliberately produces numbers and nothing else -- no scoring, no thresholds, no auto-review
 * decisions. The principles doc's own sequencing is collect, store, VALIDATE, then build dashboards,
 * then decide from them; a dashboard on unvalidated metrics looks authoritative and is not, which is
 * worse than no dashboard. These counts have to be checked against known cases before anything acts
 * on them.
 */
@Service
public class CapabilityCoverageService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityCoverageService.class);

    /**
     * Every capability the engine can currently activate.
     *
     * Hand-maintained, and that is the point: the difference between this list and what actually
     * fires is the coverage gap. Deriving it from observed activations instead would make the map
     * unable to report the one thing it exists to report -- a capability that has never fired
     * would simply be absent rather than flagged.
     */
    static final List<String> KNOWN_CAPABILITIES = List.of(
            "RUNNING_BALANCE", "DR_CR_SUFFIX", "LEADING_PLUS_CREDIT", "DATE_TIME_COLUMN",
            "WRAPPED_DESCRIPTION", "REPEATED_HEADER", "REPEATED_ACCOUNT_BANNER",
            "PAGE_BOUNDARY_ISOLATION", "COMPOSITE_STATEMENT", "CREDIT_CARD_SUMMARY_SIGNAL",
            "OFFSET_COLUMN_ANCHORS", "GRID_METADATA_FALLBACK", "GRID_METADATA_TRAILING_LABEL",
            "LEADING_NAME_LINE", "LEADING_NARRATION_CONTINUATION",
            "FINANCIAL_PRODUCT_CLASSIFICATION",
            // Added once CapabilityCorpusCoverageTest found the engine recording them with the
            // registry unaware -- so they appeared in neither activations nor neverActivated, which
            // is the one gap this map exists to report. RIGHT_ALIGNED_AMOUNTS is among the most
            // frequently exercised capabilities there is, so the map had never reported on the
            // thing it most often does.
            "PRINTED_SUMMARY_TOTALS", "RIGHT_ALIGNED_AMOUNTS");

    /**
     * @param importsAnalysed    how many imports these counts are drawn from -- a coverage figure
     *                           from three documents means something very different from one drawn
     *                           from three hundred, and omitting it invites reading the first as
     *                           the second
     * @param activations        capability name to the number of imports it fired on
     * @param neverActivated     registry capabilities with no activations at all
     * @param unparseableReasons failure reason to total rows lost across all imports
     * @param unparseableShapes  column signature to total rows lost
     * @param rowsLost           total unparseable rows across all imports
     */
    public record CoverageMap(int importsAnalysed, Map<String, Integer> activations,
                              List<String> neverActivated, Map<String, Integer> unparseableReasons,
                              Map<String, Integer> unparseableShapes, int rowsLost) {

        /** Share of registry capabilities that have fired at least once, 0..1. The headline number,
         *  and the one that can fail a build once it has been validated against known cases. */
        public double coverageRatio() {
            if (KNOWN_CAPABILITIES.isEmpty()) return 0;
            return (double) (KNOWN_CAPABILITIES.size() - neverActivated.size()) / KNOWN_CAPABILITIES.size();
        }
    }

    private final StatementImportRepository statementImportRepository;
    private final ObjectMapper objectMapper;

    public CapabilityCoverageService(StatementImportRepository statementImportRepository,
                                      ObjectMapper objectMapper) {
        this.statementImportRepository = statementImportRepository;
        this.objectMapper = objectMapper;
    }

    /** Coverage across one user's own imports. */
    public CoverageMap forUser(UUID userId) {
        return aggregate(statementImportRepository.findByUserIdOrderByImportedAtDesc(userId));
    }

    CoverageMap aggregate(List<StatementImport> imports) {
        Map<String, Integer> activations = new TreeMap<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Integer> shapes = new LinkedHashMap<>();
        int rowsLost = 0;

        for (StatementImport si : imports) {
            for (String capability : capabilitiesOf(si)) {
                activations.merge(capability, 1, Integer::sum);
            }
            UnparseableRowSummary summary = unparseableOf(si);
            if (summary != null) {
                summary.reasons().forEach((reason, n) -> reasons.merge(reason, n, Integer::sum));
                summary.columnSignatures().forEach((sig, n) -> shapes.merge(sig, n, Integer::sum));
                rowsLost += summary.total();
            }
        }

        List<String> neverActivated = new ArrayList<>();
        for (String capability : KNOWN_CAPABILITIES) {
            if (!activations.containsKey(capability)) neverActivated.add(capability);
        }

        return new CoverageMap(imports.size(), activations, neverActivated,
                sortedByCountDescending(reasons), sortedByCountDescending(shapes), rowsLost);
    }

    /** Highest count first: a backlog is only useful in priority order, and the whole reason for
     *  counting was to stop prioritising by whichever document someone looked at most recently. */
    private Map<String, Integer> sortedByCountDescending(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private List<String> capabilitiesOf(StatementImport si) {
        String json = si.getActivatedCapabilitiesJson();
        if (json == null || json.isBlank()) return List.of();
        try {
            List<CapabilityActivation> parsed =
                    objectMapper.readValue(json, new TypeReference<List<CapabilityActivation>>() {});
            // Distinct per import: a capability firing forty times in one document is one document's
            // worth of evidence that it works, not forty. Counting raw activations would make a
            // capability that runs per-row look enormously better covered than one that runs once.
            return parsed.stream().map(CapabilityActivation::capability).distinct().toList();
        } catch (Exception e) {
            // A metrics read must never break on one malformed row -- these are observations, not
            // the ledger. Logged rather than swallowed so a persistent parse failure is visible.
            log.warn("Skipping unreadable activated_capabilities_json on import {}", si.getId(), e);
            return List.of();
        }
    }

    private UnparseableRowSummary unparseableOf(StatementImport si) {
        String json = si.getUnparseableSummaryJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, UnparseableRowSummary.class);
        } catch (Exception e) {
            log.warn("Skipping unreadable unparseable_summary_json on import {}", si.getId(), e);
            return null;
        }
    }
}
