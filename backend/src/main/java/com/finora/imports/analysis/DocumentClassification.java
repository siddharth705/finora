package com.finora.imports.analysis;

import java.util.Map;

/**
 * One operational verdict per document, derived from signals the pipeline already produces.
 *
 * <h2>What this is for</h2>
 *
 * The three existing validators ({@code BALANCE_CHAIN}, {@code STATEMENT_TOTALS},
 * {@code COLUMN_AMBIGUITY}) each answer one narrow question and report {@code VERIFIED} when their
 * question does not arise. Nothing combined them into "what happened to this document", so a
 * statement could pass every applicable rule while extracting one transaction from four pages --
 * which is exactly what HSBC does today. `verified` has never meant "complete".
 *
 * <h2>One label, several signals</h2>
 *
 * <p>The value returned is the <b>highest-severity condition the evidence can prove</b>, not the
 * root cause and not the only thing true about the document. Where more than one condition holds,
 * severity decides -- so a statement that both fails reconciliation and looks under-extracted is
 * labelled by the reconciliation failure. The other observations do not disappear: they stay
 * readable on {@link Signals}, via {@link Signals#suspectedIncompleteByPageRatio()} and the raw
 * counts. Report the label with the signals, never the label alone.
 *
 * <h2>Operational, NOT a root cause</h2>
 *
 * {@link #PARSED_INCOMPLETE} means <em>the available evidence indicates incomplete extraction</em>.
 * It does not claim to know why, and it must not be read as a diagnosis. Two documents can land on
 * the same classification for unrelated reasons -- ICICI Saving and CBI both extract zero rows, and
 * the causes are not the same one (positioned-run extraction versus row parsing). Root cause stays a
 * separate investigation.
 *
 * <h2>When the evidence is thin, classify less specifically</h2>
 *
 * Every rule below is ordered most-specific-first, and each one requires evidence that actually
 * distinguishes it from the alternatives. Where it does not, the classification falls through to a
 * broader bucket rather than inventing certainty. A wrong-but-confident classification sends someone
 * to fix the wrong layer, which is a worse outcome than a vague one.
 *
 * <h2>Deliberately NOT used: image count</h2>
 *
 * The first draft of this class treated image density as a scanned-document proxy. It is not one.
 * HSBC carries 24 image XObjects per page and 3,190 characters per page -- banks embed a logo and
 * decorations on every page. That proxy classified two text-rich statements as needing OCR and would
 * have pointed the fix at an OCR pipeline for what is a parser defect. Only text density says
 * anything about whether text can be read.
 */
public enum DocumentClassification {

    /** The document could not be opened or reports no pages. Nothing downstream is meaningful. */
    DOCUMENT_INVALID,

    /**
     * Pages exist and yield NO extractable text whatsoever.
     *
     * <p>The threshold here is deliberately zero, and that is a definition rather than a tuned
     * number: a document with no extractable characters cannot be a layout problem, because there
     * is nothing to lay out. Anything above zero falls through to the layout and completeness rules
     * below.
     *
     * <p><b>No statement in the current corpus reaches this state</b>, and that is why the bar is
     * where it is. The lowest text density measured is canara at 993 characters per page, which
     * parses 58 rows correctly -- so the corpus provides no evidence for any non-zero cutoff.
     * Picking one now would mean choosing a number that happens to sort today's files, which is the
     * same mistake the image-count proxy already made. Establishing a real threshold needs a genuine
     * scanned statement to measure; until one exists this path is exercised only by a synthetic
     * fixture in the test.
     */
    SCANNED_OCR_REQUIRED,

    /**
     * Text was extracted and positioned, but no transaction rows came out of it.
     *
     * <p>Deliberately broad. CBI (1,799 chars/page, 1,735 positioned runs, 0 rows) and ICICI Saving
     * (1,545 chars/page, 158 positioned runs, 0 rows) both land here, and their causes differ --
     * CBI positions its text fine and fails at row parsing, while ICICI Saving loses most of its
     * text before positioning. Splitting them would require asserting which stage failed, and the
     * signals available here cannot carry that weight. The {@code positionedRuns} figure is recorded
     * so the distinction is visible to whoever investigates, without this enum pretending to make it.
     */
    LAYOUT_UNSUPPORTED,

    /** Rows were extracted, but a column or value could not be read unambiguously. */
    COLUMNS_AMBIGUOUS,

    /**
     * Rows were extracted and the money does not add up: a balance chain that does not reconcile, or
     * stated totals that disagree with the summed rows.
     *
     * <p>Ranked above {@link #PARSED_INCOMPLETE} on severity, not confidence. A statement that
     * imports 360 rows with totals that do not reconcile puts wrong numbers in front of a user, which
     * is worse than one that visibly imports too few.
     */
    PARSED_RECONCILIATION_FAILED,

    /**
     * The evidence indicates rows are missing.
     *
     * <p>Reached two ways, and they are not equally strong:
     *
     * <ol>
     *   <li><b>Ground truth.</b> {@code expectedTransactions} is known and exceeds the extracted
     *       count. Definitive.
     *   <li><b>Fewer rows than pages.</b> A suspicion, and the only heuristic in this class. It comes
     *       from a property of the documents rather than from the corpus: a page of a transaction
     *       table holds more than one transaction, so extracting fewer rows than there are pages
     *       means most of the document produced nothing. It is not tuned to today's files -- it
     *       leaves HDFC credit (2 rows / 2 pages) and Manas_HDFC (4 rows / 2 pages) alone, both of
     *       which are plausibly complete, while catching HSBC (1/4), ICICI CC (3/9) and Bandhan
     *       (3/7). The boundary is tested.
     * </ol>
     */
    PARSED_INCOMPLETE,

    /**
     * Rows were extracted, every applicable rule passed, and nothing suggests missing data.
     *
     * <p><b>This is not proof of correctness.</b> Without {@code expectedTransactions} it means only
     * that no available signal contradicts completeness -- which is a much weaker claim, and the
     * reason {@code GroundTruthStatus} exists alongside this enum rather than inside it. Read the two
     * together or this value will be over-trusted exactly as {@code verified} has been.
     */
    PARSED_COMPLETE;

    /** Outcome string the three validators emit when their check passed. */
    private static final String VERIFIED = "VERIFIED";
    private static final String FAILED = "FAILED";
    private static final String WARNING = "WARNING";

    /**
     * Everything the classification depends on, named explicitly so a test can pin each one and no
     * caller can smuggle in a convenient proxy.
     *
     * @param pages                page count as the PDF reports it
     * @param extractedChars       non-whitespace characters a plain text extraction yields; the only
     *                             evidence about whether text is readable at all
     * @param positionedRuns       runs the positioning stage produced; recorded for investigators,
     *                             deliberately not used to classify (see {@link #LAYOUT_UNSUPPORTED})
     * @param sections             table sections located
     * @param rows                 transaction rows extracted across all sections
     * @param ruleOutcomes         validator rule name to outcome, exactly as the validators emit it
     * @param expectedTransactions ground truth when established, {@code null} when not. Never
     *                             derived from parser output -- that would make today's behaviour
     *                             the definition of correct.
     */
    public record Signals(
            int pages,
            int extractedChars,
            int positionedRuns,
            int sections,
            int rows,
            Map<String, String> ruleOutcomes,
            Integer expectedTransactions
    ) {
        public int charsPerPage() {
            return pages <= 0 ? 0 : extractedChars / pages;
        }

        /**
         * The completeness suspicion, exposed as its own signal rather than only consumed by
         * {@link DocumentClassification#of}.
         *
         * <p>This exists because the classification is a single value and the evidence is not.
         * Bandhan extracts 3 rows from 7 pages <em>and</em> fails its balance chain; severity
         * ordering picks {@link #PARSED_RECONCILIATION_FAILED}, which is the right label and would
         * also be the last anyone hears of the row/page problem if this method did not exist. The
         * two are independent observations and collapsing them into one label loses the second --
         * plausibly the one that explains the first, since totals will not reconcile if rows are
         * missing.
         *
         * <p>Reported alongside the classification, never instead of it. See the class comment on
         * {@link #PARSED_INCOMPLETE} for why fewer-rows-than-pages is a suspicion and not a proof.
         */
        public boolean suspectedIncompleteByPageRatio() {
            return pages > 0 && rows > 0 && rows < pages;
        }

        /** Rows per page, for reporting. Integer division; use the ratio predicate for decisions. */
        public int rowsPerPage() {
            return pages <= 0 ? 0 : rows / pages;
        }

        /** True once ground truth has been established, whichever way it came out. */
        public boolean hasGroundTruth() {
            return expectedTransactions != null;
        }

        boolean ruleIs(String rule, String outcome) {
            return outcome.equals(ruleOutcomes.get(rule));
        }
    }

    /** Derives the single operational verdict. Pure; no I/O, no pipeline state. */
    public static DocumentClassification of(Signals s) {
        if (s.pages() <= 0) return DOCUMENT_INVALID;

        // Zero extractable text: definitionally not a layout question. See the enum constant.
        if (s.extractedChars() == 0) return SCANNED_OCR_REQUIRED;

        // No rows at all. Broad on purpose -- the failing stage is not knowable from here.
        if (s.rows() == 0) return LAYOUT_UNSUPPORTED;

        // Ground truth outranks every heuristic below it, in either direction.
        if (s.expectedTransactions() != null && s.rows() < s.expectedTransactions()) {
            return PARSED_INCOMPLETE;
        }

        // Wrong money before too little money: a reconciled-but-short import is less dangerous than
        // a complete-looking import whose totals disagree.
        if (s.ruleIs("BALANCE_CHAIN", FAILED) || s.ruleIs("STATEMENT_TOTALS", FAILED)) {
            return PARSED_RECONCILIATION_FAILED;
        }

        if (s.ruleIs("COLUMN_AMBIGUITY", WARNING) || s.ruleIs("COLUMN_AMBIGUITY", FAILED)) {
            return COLUMNS_AMBIGUOUS;
        }

        // Suspicion only, and only when ground truth has not already answered.
        if (!s.hasGroundTruth() && s.suspectedIncompleteByPageRatio()) {
            return PARSED_INCOMPLETE;
        }

        return PARSED_COMPLETE;
    }
}
