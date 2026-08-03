package com.finora.imports.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which financial product a located section of a statement describes.
 *
 * Runs BEFORE transactions are parsed, because what a section is determines how it should be read:
 * a savings account is a ledger, a term deposit is a balance plus a maturity date, a recurring
 * deposit is an installment schedule. Treating all three as ledgers is what produced empty accounts
 * from a combined statement.
 *
 * Signals are column names and nearby text, never the bank's identity. A recurring deposit is
 * recognised by having installments and a maturity date -- true of every bank's RD, and of no
 * bank's savings account. That is what makes this one capability rather than forty parsers.
 *
 * DETECTION, NOT TRUTH. Every result carries a confidence and the evidence behind it, and when the
 * evidence does not clear the bar the answer is {@link FinancialProductType#UNKNOWN} -- which is a
 * successful outcome, not a failure. An unidentified product is shown to the user to name once;
 * it is never guessed into an account, because a wrong product silently creates wrong data in the
 * customer's net worth.
 */
@Component
public class FinancialProductClassifier {

    /**
     * Column-name and text markers per product, matched case-insensitively as substrings of the
     * section's normalised evidence. Ordered most-specific first: a recurring deposit also mentions
     * "deposit" and "maturity", so it has to be tested before the plainer fixed-deposit markers.
     */
    private static final Map<FinancialProductType, List<String>> MARKERS = new LinkedHashMap<>();
    static {
        MARKERS.put(FinancialProductType.RECURRING_DEPOSIT, List.of(
                "recurring deposit", "rd number", "rd account", "installment frequency",
                "installments paid", "monthly installment", "deposit(mnth)", "no of installments"));
        MARKERS.put(FinancialProductType.FIXED_DEPOSIT, List.of(
                "fixed deposit", "term deposit", "fd number", "fd account", "deposit number",
                "maturity amount", "maturity date", "principal amount", "rate of interest"));
        MARKERS.put(FinancialProductType.CREDIT_CARD, List.of(
                "credit card", "card number", "minimum amount due", "minimum due", "total payment due",
                "credit limit", "available credit", "statement date payment due date"));
        MARKERS.put(FinancialProductType.LOAN, List.of(
                "loan account", "loan number", "emi", "principal outstanding", "disbursed amount",
                "repayment schedule", "tenure"));
        MARKERS.put(FinancialProductType.OVERDRAFT, List.of("overdraft", "od limit", "od account"));
        MARKERS.put(FinancialProductType.PPF, List.of("public provident fund", "ppf account"));
        MARKERS.put(FinancialProductType.EPF, List.of("employees provident fund", "epf account", "uan"));
        MARKERS.put(FinancialProductType.NPS, List.of("national pension", "nps account", "pran"));
        MARKERS.put(FinancialProductType.DEMAT, List.of("demat", "isin", "dp id", "holding statement"));
        MARKERS.put(FinancialProductType.MUTUAL_FUND, List.of("mutual fund", "folio", "nav", "units held"));
        MARKERS.put(FinancialProductType.WALLET, List.of("wallet", "prepaid instrument"));
        MARKERS.put(FinancialProductType.CURRENT, List.of("current account"));
        MARKERS.put(FinancialProductType.SAVINGS, List.of("savings account", "savings a/c", "sb account"));
    }

    /**
     * Markers that NAME the product outright, as opposed to describing a field it happens to have.
     * These outweigh any number of supporting markers, because a document saying "Recurring
     * Deposit" is stating what it is, while "Maturity Date" merely narrows it -- and both FDs and
     * RDs have maturity dates. Without this split, two weak shared fields outvoted an explicit
     * name. Same principle as preferring a labelled IFSC over a bank name found loose in the text:
     * an explicit statement of identity beats an inference drawn from an attribute.
     */
    private static final List<String> DECISIVE_MARKERS = List.of(
            "recurring deposit", "fixed deposit", "term deposit", "credit card", "loan account",
            "overdraft", "savings account", "savings a/c", "sb account", "current account",
            "public provident fund", "employees provident fund", "national pension", "demat",
            "mutual fund", "wallet", "rd account", "fd account", "rd number", "fd number");

    /** Columns that mean "this section is a ledger of money moving in and out". Their presence is
     *  what separates a transaction account from a deposit summary that merely mentions amounts. */
    private static final List<String> LEDGER_COLUMNS = List.of(
            "withdrawal", "withdrawals", "deposit", "deposits", "debit", "credit",
            "narration", "particulars", "description", "closing balance", "running balance");

    /**
     * What the classifier concluded, why, and how sure it is.
     *
     * {@code evidence} exists so a wrong answer can be argued with. Without it "this is a recurring
     * deposit, 0.8" is unfalsifiable, and the only way to debug a misclassification is to re-read
     * the PDF -- which is exactly the position the engine was in before.
     */
    public record ProductClassification(FinancialProductType type, double confidence, List<String> evidence) {

        public boolean isConfident() { return confidence >= CONFIDENCE_THRESHOLD; }

        static ProductClassification unknown(List<String> evidence) {
            return new ProductClassification(FinancialProductType.UNKNOWN, 0.0, evidence);
        }
    }

    /**
     * Below this, the answer is UNKNOWN and the user is asked. Set where a single unambiguous
     * marker ("recurring deposit") is enough on its own but a lone weak signal is not -- getting
     * this wrong in the permissive direction creates silent bad data in someone's net worth, while
     * getting it wrong in the strict direction costs one question on the review screen.
     */
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    /** How much a product-naming marker outweighs one that merely describes a field. Three, so a
     *  single explicit name beats two shared attributes but not an overwhelming pile of them. */
    private static final int DECISIVE_WEIGHT = 3;

    /**
     * A product-naming marker found only in nearby prose, not in the section's own columns. Worth
     * more than an attribute but less than a name in the structure, because auxiliary text is not
     * reliably section-scoped.
     *
     * KNOWN GAP, and the reason this sits between the two rather than at either end: a combined
     * statement prints "Savings Accounts" once in its relationship summary near the top, and that
     * phrase then appears in the nearby text of the DEPOSIT sections further down. There is no
     * signal available here to tell a name that belongs to this section from one that leaked into
     * it -- that has to be fixed where auxiliary text is assigned to sections, not by weighting it
     * more cleverly. Until then a deposit section in a combined statement can still be misread as a
     * savings account, and no constant in this file fixes that.
     */
    private static final int NAMED_IN_TEXT_WEIGHT = 2;

    /**
     * @param columnNames the section's detected table columns
     * @param nearbyText  the section's auxiliary/heading text (letterhead, titles, summary blocks)
     * @param hasRows     whether the section produced parseable transaction rows
     */
    public ProductClassification classify(List<String> columnNames, List<String> nearbyText, boolean hasRows) {
        String columnEvidence = normalize(columnNames, null);
        String haystack = normalize(columnNames, nearbyText);
        List<String> evidence = new ArrayList<>();

        FinancialProductType best = null;
        int bestScore = 0;
        int bestHits = 0;
        for (Map.Entry<FinancialProductType, List<String>> candidate : MARKERS.entrySet()) {
            List<String> hits = new ArrayList<>();
            int score = 0;
            for (String marker : candidate.getValue()) {
                if (!mentions(haystack, marker)) continue;
                // A product-naming marker only counts as decisive when it appears in this
                // section's own COLUMNS. Auxiliary text is not reliably section-scoped -- a
                // combined statement's relationship summary says "Savings Accounts" once, near the
                // top, and that phrase then sits in the nearby text of the deposit sections too.
                // Trusting it there classified a term-deposit table as a savings account. Columns
                // belong to the section that owns them; prose in the vicinity does not.
                boolean namesTheProduct = DECISIVE_MARKERS.contains(marker);
                boolean decisive = namesTheProduct && mentions(columnEvidence, marker);
                score += decisive ? DECISIVE_WEIGHT : namesTheProduct ? NAMED_IN_TEXT_WEIGHT : 1;
                hits.add(decisive ? marker + "\" (names the product, in its own columns)"
                        : namesTheProduct ? marker + "\" (named only in nearby text)" : marker + "\"");
            }
            // Strictly greater keeps the LinkedHashMap's most-specific-first ordering meaningful:
            // a tie goes to whichever product was declared earlier, so an RD beats an FD on the
            // "maturity date" they share rather than the other way round.
            if (score > bestScore) {
                bestScore = score;
                bestHits = hits.size();
                best = candidate.getKey();
                List<String> fresh = new ArrayList<>();
                for (String hit : hits) fresh.add("matched \"" + hit);
                evidence = fresh;
            }
        }

        boolean looksLikeALedger = LEDGER_COLUMNS.stream().anyMatch(c -> mentions(haystack, c)) && hasRows;

        // A ledger with no product marker at all is a plain transaction account. This is the common
        // case -- most statements never say the words "savings account" anywhere near their table --
        // so it must not fall through to UNKNOWN and interrogate the user about every ordinary file.
        if (best == null) {
            if (looksLikeALedger) {
                return new ProductClassification(FinancialProductType.SAVINGS, 0.7,
                        List.of("transaction columns present and rows parsed, no other product markers"));
            }
            return ProductClassification.unknown(List.of("no recognised product markers"));
        }

        double confidence = confidenceFor(best, bestHits, looksLikeALedger, evidence);
        if (confidence < CONFIDENCE_THRESHOLD) {
            evidence.add("below confidence threshold " + CONFIDENCE_THRESHOLD + ", asking the user");
            return new ProductClassification(FinancialProductType.UNKNOWN, confidence, evidence);
        }
        return new ProductClassification(best, confidence, evidence);
    }

    private double confidenceFor(FinancialProductType type, int hits, boolean looksLikeALedger,
                                  List<String> evidence) {
        // Each corroborating marker adds, and the first one carries most of the weight -- two
        // markers is meaningfully better than one, ten is not meaningfully better than three.
        double confidence = Math.min(0.5 + 0.15 * hits, 0.95);

        // Structure agreeing with vocabulary is stronger evidence than either alone, and structure
        // CONTRADICTING vocabulary is the case worth catching: a section calling itself a deposit
        // while carrying a full ledger is more likely a savings account whose narration happens to
        // mention a deposit than it is a term deposit with transactions.
        if (type.hasTransactions() && looksLikeALedger) {
            confidence = Math.min(confidence + 0.1, 0.95);
            evidence.add("transaction columns corroborate a ledger product");
        } else if (!type.hasTransactions() && looksLikeALedger) {
            confidence -= 0.25;
            evidence.add("WARNING: markers say " + type + " but the section carries a transaction ledger");
        }
        return Math.max(confidence, 0.0);
    }

    /**
     * Lowercased, every run of non-alphanumerics collapsed to a single space, and padded with a
     * space at each end so a marker can be tested with whole-word boundaries.
     *
     * The padding is not cosmetic. Matching markers as raw substrings meant "Card Number" contained
     * "rd number" -- caRD NUMBER -- and a credit card statement classified as a recurring deposit.
     * That is the identical failure that made "Rewards Bill" detect as SBI in bank detection, which
     * is reason enough to never match a domain term without boundaries anywhere in this pipeline.
     */
    private String normalize(List<String> columnNames, List<String> nearbyText) {
        StringBuilder sb = new StringBuilder();
        if (columnNames != null) columnNames.forEach(c -> sb.append(' ').append(c));
        if (nearbyText != null) nearbyText.forEach(t -> sb.append(' ').append(t));
        return " " + sb.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }

    /** Whole-word containment: both sides are normalised the same way and space-padded, so a marker
     *  can only match complete words. */
    private boolean mentions(String haystack, String marker) {
        String normalizedMarker = " " + marker.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        return haystack.contains(normalizedMarker);
    }
}
