package com.finora.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto.CapabilityActivation;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.CapabilityData;
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
            "PRINTED_SUMMARY_TOTALS", "RIGHT_ALIGNED_AMOUNTS",
            // A header split across two or three visual lines, found on a real HDFC combined
            // statement whose fixed-deposit schedule was invisible to table location entirely
            // because neither half of its header scored as one. See
            // PdfTableLocator.HEADER_WRAP_MAX_GAP.
            "WRAPPED_HEADER",
            // Two header cells normalizing to the same column name -- found on a real ICICI
            // savings statement whose stacked heading names both amount columns "Amount (INR)".
            // See PdfTableLocator.resolveDuplicateColumnNames.
            "DUPLICATE_COLUMN_NAMES",
            // A transaction table with no header row anywhere in the document -- found on a real
            // SBI savings statement whose column vocabulary never appears as text at all. Columns
            // are inferred from data-row geometry and content shape instead of a label. See
            // PdfTableLocator.inferHeaderlessSection.
            "INFERRED_HEADERLESS_LAYOUT",
            // A fictional worked-example table inside a real AU Small Finance Bank credit-card
            // statement's fee/interest-calculation appendix, indistinguishable from a real header
            // by vocabulary alone -- three of them opened three garbage sections and blocked real
            // transaction recovery entirely. See PdfTableLocator.ILLUSTRATIVE_EXAMPLE_MARKER.
            "ILLUSTRATIVE_BLOCK_SUPPRESSED",
            // A transaction printed as a two-physical-line visual block (day-of-month + narration
            // + amount, then month/year + a bare Cr/Dr marker) rather than a table row at all --
            // found on the same AU statement, once the illustrative sections above stopped
            // blocking recovery. See PdfTableLocator.inferTwoLineDateBlockSection.
            "INFERRED_TWO_LINE_DATE_BLOCK",
            // A credit card's identity stated inside an ordinary sentence ("...credit card ending
            // with <4 digits>") rather than any "Label: Value" or grid shape -- found on the same
            // AU statement once INFERRED_TWO_LINE_DATE_BLOCK stopped discarding the auxiliary text
            // this reads. See PdfMetadataExtractor.CARD_ENDING_DIGITS.
            "CARD_ENDING_DIGITS_IDENTITY",
            // A header cell whose printed text is real but normalizes to blank (a bare currency
            // unit like "(INR)") -- found on a real ICICI savings e-statement whose Balance column
            // heading is invisible to every downstream recognizer as a result. See
            // PdfTableLocator.resolveBlankColumnNames.
            "BLANK_COLUMN_NAME_QUALIFIED",
            // A narration/remarks column with no representation at all on the accepted header
            // line -- found on the same real ICICI statement, whose three-tier heading puts
            // "Transaction Remarks" on a tier mergeHeaderLines correctly refuses to fold in
            // wholesale. See PdfTableLocator.recoverMissingDescriptionColumn.
            "RECOVERED_MISSING_DESCRIPTION_COLUMN",
            // An "S No." column recovered the same way, not for its own sake but because leaving
            // it unnamed let its digit values collide with and corrupt the Date column (nearestColumn
            // has no maximum-distance cap). See PdfTableLocator.recoverMissingSerialNumberColumn.
            "RECOVERED_MISSING_SERIAL_NUMBER_COLUMN",
            // A credit card's own payment-summary panel (Total/Minimum Payment Due, Available
            // Credit/Cash Limit, ...) satisfying looksLikeHeaderRow exactly like a real transaction
            // table -- found on two real credit-card statements (Axis, HDFC) with otherwise
            // unrelated layouts, each producing a one-row phantom section immediately superseded by
            // the real ledger's header. See PdfTableLocator.looksLikePaymentSummaryPanel.
            "PAYMENT_SUMMARY_PANEL_SUPPRESSED",
            // A credit-card statement's own billing-summary panel read via one of two independent
            // strategies -- see CreditCardSummaryExtractor's own class doc comment for why they are
            // kept separate rather than merged into one extractor. CREDIT_CARD_SUMMARY_TOTALS is the
            // stacked label-row/value-row GRID strategy (real evidence: a real Axis statement's
            // Total Payment Due figure, once a row-merge edge case in shared grid-reading logic was
            // fixed). CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE is the label-left/value-right SAME_ROW strategy
            // (real evidence: a real AU statement's "Bill summary" widget). See the architecture
            // doc's Credit Card Direction Evidence Study addendum for the measured fire rate against
            // the real 6-document corpus.
            "CREDIT_CARD_SUMMARY_TOTALS", "CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE",
            // Fires only when the headerless-inference path actually removes a repeated physical
            // row (see PdfTableLocator.bucketHeaderlessRowsWithContinuation's own doc comment for
            // the real page-boundary-reprint artifact this protects against), never merely when
            // that path runs -- so this answers "how many documents relied on this safety net",
            // not "how many documents took this code path". Distinct from INFERRED_HEADERLESS_LAYOUT
            // itself, which fires on every document that path accepts regardless of whether a
            // duplicate was present to remove.
            "PHYSICAL_ROW_DEDUP_EVIDENCE");

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
        return aggregate(statementImportRepository.findCapabilityDataByUserId(userId));
    }

    CoverageMap aggregate(List<CapabilityData> imports) {
        Map<String, Integer> activations = new TreeMap<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Integer> shapes = new LinkedHashMap<>();
        int rowsLost = 0;

        for (CapabilityData si : imports) {
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

    private List<String> capabilitiesOf(CapabilityData si) {
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

    private UnparseableRowSummary unparseableOf(CapabilityData si) {
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
