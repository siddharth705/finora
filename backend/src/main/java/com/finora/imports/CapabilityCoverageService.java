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
            // A genuinely headerless transaction table sitting BEFORE a completely unrelated table
            // further down the same document whose own header IS recognized -- found on a real
            // HSBC credit-card statement whose real ledger has no header at all, but a later page
            // carries an unrelated EMI "Loan Summary Table" with a genuine one. Once any header is
            // found, INFERRED_HEADERLESS_LAYOUT's own sections.isEmpty() gate never fires for the
            // earlier content; this tries headerless inference on just the pre-header slice,
            // independent of what the main loop found afterward. See
            // PdfTableLocator.locateAll's own firstHeaderRowIndex doc comment.
            "HEADERLESS_LAYOUT_BEFORE_LATER_HEADER",
            // The escape hatch INFERRED_HEADERLESS_LAYOUT's own row-count floor needed to recover
            // that same real HSBC document's genuine transaction: one real transaction, one short
            // of the floor, whose "signed" candidacy (an explicit CR/DR marker) and amount exactly
            // reconcile the document's own printed OPENING BALANCE against its own printed NET
            // OUTSTANDING BALANCE. See PdfTableLocator.corroboratedByPrintedBalanceReconciliation.
            "HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED",
            // A fictional worked-example table inside a real AU Small Finance Bank credit-card
            // statement's fee/interest-calculation appendix, indistinguishable from a real header
            // by vocabulary alone -- three of them opened three garbage sections and blocked real
            // transaction recovery entirely. See PdfTableLocator.ILLUSTRATIVE_EXAMPLE_MARKER.
            "ILLUSTRATIVE_BLOCK_SUPPRESSED",
            // A literal "**** End of Statement ****" line, found on a real Axis Bank Neo Rupay
            // credit-card statement -- everything from that line to the end of the document is
            // trailing boilerplate (a Minimum-Amount-Due illustration table structurally identical
            // to a real transaction table, an interest-calculation worked example, grievance/nodal-
            // officer contact tables), none of it caught by any other structural gate. Shares
            // ILLUSTRATIVE_EXAMPLE_MARKER's one-way suppression mechanism -- see
            // docs/architecture/system-design/transaction-boundary-phase2a-investigation.md for the
            // real-corpus evidence this closes. See PdfTableLocator.STATEMENT_CLOSING_MARKER.
            "TRANSACTION_TABLE_CLOSED",
            // Phase 2C. A real Kotak Mahindra Bank credit-card statement prints its own
            // "Total Purchase & Other Charges" column-total row directly beneath the last real
            // transaction, before its MITC/fees-and-charges legal schedule begins -- the same
            // failure shape as TRANSACTION_TABLE_CLOSED, evidenced from a different bank. See
            // PdfTableLocator.TRANSACTION_TABLE_TOTAL_MARKER.
            "TRANSACTION_TABLE_TOTAL_CLOSED",
            // Phase 2C. A real ICICI Bank credit-card statement opens its MITC/legal appendix with
            // an all-caps "MOST IMPORTANT TERMS AND CONDITIONS (MITC)" heading immediately after
            // the last real transaction and its rewards summary -- same failure shape again,
            // evidenced from a third bank. Deliberately case-sensitive; see
            // PdfTableLocator.MITC_SECTION_MARKER for why (two other real documents mention the
            // same phrase, mixed-case, mid-document, well before their own real content ends).
            "MITC_SECTION_CLOSED",
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
            // An account's identity stated only in a per-page product banner ("SAVINGS ACCOUNT -
            // <number>") on a document that never prints the phrase "Account Number" at all --
            // found on a real BOB savings statement, whose number reached the extractor all along
            // (PdfTableLocator keeps the first such banner in auxiliary text and discards only the
            // per-page repeats) and simply matched no pattern here. See
            // PdfMetadataExtractor.ACCOUNT_PRODUCT_BANNER.
            "ACCOUNT_PRODUCT_BANNER_IDENTITY",
            // A statement period stated inside an ordinary sentence rather than as a labelled
            // field -- "Statement for your credit card ending with <last4> (19 Mar - 18 Apr 2026)"
            // on a real AU Small Finance Bank statement, whose range is hyphen-separated and
            // states its year only once, on the end date. See
            // PdfMetadataExtractor.STATEMENT_PERIOD_IN_SENTENCE.
            "STATEMENT_PERIOD_IN_SENTENCE",
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
            "PHYSICAL_ROW_DEDUP_EVIDENCE",
            // Phase 2E.2. The header quality gate judged the row this document was about to accept
            // as its header too weak to explain its own upcoming rows (most of them carry more raw
            // values than the header has columns for), and the reconstruction engine recovered a
            // better one by composing it with the physical line immediately above -- a fragment
            // wrappedHeaderAt never reaches, because it only ever looks forward. Motivated by a real
            // SBI Credit Card statement's supplementary-cardholder section, whose header prints
            // "Transaction Details" one line above "Date | Amount ( ` )", the row that gets accepted
            // on its own. See PdfTableLocator.reconstructHeader and
            // docs/architecture/system-design/header-reconstruction-design.md.
            "HEADER_RECONSTRUCTED",
            // A legal/legend block (abbreviation key, late-payment-charges prose) printed at the
            // bottom of EVERY page, not once at the document's true end -- unlike
            // TRANSACTION_TABLE_CLOSED/MITC_SECTION_CLOSED's own markers. Found on the same real SBI
            // Credit Card statement HEADER_RECONSTRUCTED above is evidenced from: with no boundary
            // recognized for it, the block glued onto the last real transaction above each page
            // break via the ordinary trailing-continuation merge. Resets at the next header
            // (repeated or new), unlike the permanent TRAILING_CONTENT_TRIGGERS family -- see
            // PdfTableLocator.pageLegendBlockActive's own doc comment.
            "PAGE_LEGEND_BLOCK_SUPPRESSED",
            // A real Kotak Mahindra Bank credit-card statement groups its own ledger into
            // sub-categories mid-table with bare, dateless heading lines ("Payments and Other
            // Credits", "Primary Card Transactions- <masked card>", "Retail Purchases and Cash
            // Transactions") -- the ordinary leading/trailing narration merge had no notion of "this
            // is a category divider, not prose" and swept each one onto whichever real transaction
            // row it happened to sit closer to. See PdfTableLocator.CREDIT_CARD_CATEGORY_HEADER.
            "TRANSACTION_CATEGORY_HEADER_SUPPRESSED",
            // The same real Kotak statement states its transaction date range inside the table's
            // own repeated column-header row ("Transaction details from 16-Feb-2026 to
            // 15-Mar-2026") rather than any pre-table "Statement Period" field -- invisible to
            // PdfMetadataExtractor, which only ever sees auxiliaryText (the lines BEFORE a header is
            // recognized). See TransactionTableDateRangeExtractor.
            "PRINTED_TRANSACTION_TABLE_DATE_RANGE",
            // A real Kotak Mahindra Bank SAVINGS statement prints its period as a bare,
            // unlabeled date range directly beneath its own "Account Statement" title -- no
            // "Statement Period"/"From"/"To" label anywhere near it, so PdfMetadataExtractor's
            // line-based patterns can never match. Recognized only by its document-structure
            // relationship to the title (see StatementTitleDateRangeExtractor's own doc comment
            // for why this is not a general bare-date-range pattern).
            "PRINTED_TITLE_ADJACENT_DATE_RANGE",
            // Real Axis Bank and SBI credit-card statements each print their payment due date as
            // one column of a dense multi-column "Payment Summary" grid -- confirmed the label
            // text is dropped/detached from its value once PdfTableLocator's line-based
            // preTableLines view joins the panel's several visual rows into one text line, so no
            // line-based pattern can ever recover it. Recognized by reading the same raw
            // positioned-text grid CreditCardSummaryExtractor already reads for its own money
            // fields (see PaymentDueDateGridExtractor's own doc comment).
            "PRINTED_PAYMENT_DUE_DATE_GRID",
            // The same two real documents (Axis, SBI) scramble their own printed credit limit away
            // from its "Credit Limit" label the same way -- recovered by the same grid-reading
            // mechanism. Also the more reliable of two readings on a real IndusInd Bank statement:
            // PdfMetadataExtractor's own line-based GRID_CREDIT_LIMIT_LABEL fallback picks the first
            // amount-shaped text after the label, which on that document is an unrelated "Payments &
            // Other Credits" figure a stray word from the same corrupted line join happens to sit in
            // front of -- one line above the credit limit's own true value. Matching by the label's
            // own x-column instead of by first-match-wins gets the right one. See
            // CreditLimitGridExtractor's own doc comment.
            "PRINTED_CREDIT_LIMIT_GRID",
            // The same real Axis Bank credit-card statement prints its own account/card number in
            // this exact scrambled panel too, in two different real layouts (a stacked grid and a
            // same-row label/value pair) -- recovered the same "read raw positioned text directly"
            // way, reusing PdfMetadataExtractor's card-number label/value vocabulary. See
            // AccountNumberGridExtractor's own doc comment.
            "PRINTED_ACCOUNT_NUMBER_GRID",
            // A real ICICI credit-card statement prints its own account number with no label at
            // all, in a row positioned directly under the transaction table's own "Date" column
            // header, before the first real transaction row -- recognized by that position and by
            // its shape (a real transaction date never matches a masked-number pattern), not by any
            // adjacent label text. See AccountNumberTransactionHeaderExtractor's own doc comment.
            "PRINTED_ACCOUNT_NUMBER_ABOVE_TRANSACTIONS",
            // Two real, independently-uploaded savings-account statements (Central Bank of India,
            // PNB ONE) each close with a regulatory-boilerplate discrepancy-notification sentence
            // that sits before either document's own true end -- swept into the last real
            // transaction's trailing narration before any existing closing marker had a chance to
            // fire. See PdfTableLocator.ACCOUNT_DISCREPANCY_DISCLAIMER_MARKER.
            "ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED",
            // Two more real, independently-uploaded savings-account statements (HDFC, SBI) each
            // close with a "Statement Summary" balance-recap block after the last real transaction,
            // with no other recognized closing marker anywhere in either document -- its own header
            // row and trailing security disclaimer both swept into the last real transaction's
            // trailing narration before this trigger existed. See
            // PdfTableLocator.STATEMENT_SUMMARY_BLOCK_MARKER.
            "STATEMENT_SUMMARY_BLOCK_CLOSED",
            // A real Axis Bank credit-card statement's true end opens with "Your cheque should be
            // payable to..." followed by an ECS-registration sentence and an "IMPORTANT MESSAGE"
            // legal/GST disclaimer block, swept into the last real transaction's trailing narration
            // before this trigger existed. See PdfTableLocator.CHEQUE_PAYABLE_FOOTER_MARKER.
            "CHEQUE_PAYABLE_FOOTER_CLOSED",
            // A real HDFC "Tata Neu Plus" credit-card statement's transaction table ends with a
            // "Note:" footnote explaining how its "Base NeuCoins" rewards column is calculated,
            // directly beneath the last real transaction -- swept into that transaction's trailing
            // narration before this trigger existed. See PdfTableLocator.NEUCOINS_FOOTNOTE_MARKER.
            "NEUCOINS_FOOTNOTE_CLOSED",
            // A real SBI credit-card statement's supplementary-cardholder section closes its
            // transaction table with a "SAVINGS AND BENEFITS SECTION" heading, introducing a Cash
            // Back / Petrol Surcharge Waiver / Reward Points recap grid -- swept into the last real
            // transaction's trailing narration before this trigger existed. See
            // PdfTableLocator.SAVINGS_AND_BENEFITS_SECTION_MARKER.
            "SAVINGS_AND_BENEFITS_SECTION_CLOSED",
            // A real Canara Bank statement's own bare "Chq: <reference>" trailer line, past
            // MAX_TRAILING_CONTINUATION_ROWS's count cap, recovered as trailing content anyway by
            // content shape rather than count -- see PdfTableLocator.CHEQUE_REFERENCE_TRAILER.
            "CHEQUE_REFERENCE_TRAILER_RECOVERED",
            // A statement period stated as two separately colon-labeled fields on one row
            // ("From : <date>" / "To : <date>") rather than one combined "Period" label -- found
            // on real HDFC savings-account statements and a real Sanjay HDFC statement. See
            // PdfMetadataExtractor.FROM_TO_LABELED_PERIOD.
            "STATEMENT_PERIOD_FROM_TO_FIELDS",
            // A statement period labeled "Statement From" rather than "Statement Period"/
            // "Billing Period" -- found on real Manas_HDFC, Shivani_HDFC, and Sanjay SBI
            // statements. See PdfMetadataExtractor.STATEMENT_FROM_LABELED_PERIOD.
            "STATEMENT_PERIOD_STATEMENT_FROM_LABEL",
            // A statement period labeled "Statement of Account" rather than any "...Period"
            // vocabulary -- found on a real Central Bank of India statement. See
            // PdfMetadataExtractor.STATEMENT_OF_ACCOUNT_PERIOD.
            "STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL",
            // A statement period stated as plain prose ("...for the period <date> to <date>")
            // with no parentheses and no "Label:" shape at all -- found on real canara.pdf and
            // ICICI saving.pdf statements. See PdfMetadataExtractor.STATEMENT_PERIOD_PROSE.
            "STATEMENT_PERIOD_PROSE",
            // A statement period labeled "For Period:" -- found on a real PNB ONE savings
            // statement, whose own line also carries an unrelated "Statement of Account:<number>"
            // label before it. See PdfMetadataExtractor.FOR_PERIOD_LABELED.
            "STATEMENT_PERIOD_FOR_PERIOD_LABEL",
            // A header block whose column GROUPS wrap to unequal depths -- some columns print
            // across the top and bottom lines of a multi-line heading, skipping a middle line
            // entirely, while a second group of columns prints ONLY on that middle line. Found on
            // a real third-party-generated SBI (Indian Overseas Bank) savings statement and a real
            // Standard Chartered savings statement, both of which located a table with 2-3 garbled
            // columns instead of 6-7 and staged zero transaction rows. See
            // PdfTableLocator.mergeHeaderLinesAdmittingInteriorTierColumns.
            "WRAPPED_HEADER_INTERIOR_TIER_COLUMNS");

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
