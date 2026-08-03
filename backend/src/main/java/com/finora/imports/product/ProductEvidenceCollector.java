package com.finora.imports.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1 of Financial Product Discovery: record what the document contains, decide nothing.
 *
 * This class has no notion of what a savings account or a deposit looks like. It reports that a
 * maturity field is present, that there are separate debit and credit columns, that the words
 * "Recurring Deposit" appeared in a heading -- and stops there. Every judgement lives in Stage 2
 * ({@link FinancialProductClassifier}), and every proof obligation in Stage 3
 * ({@link ProductValidator}).
 *
 * Separating the two is not ceremony. Collection and scoring used to be one pass, and the result
 * was that fixing a misclassification meant editing the same method that decided what counted as
 * evidence -- so each fix moved the failure to a different statement instead of removing it. Facts
 * that are recorded independently of the conclusion can be re-scored without being re-gathered, and
 * can be stored and argued with after the fact.
 *
 * Nothing here is bank-specific. A recurring deposit has installments at every bank; that is what
 * makes this one capability instead of forty parsers.
 */
@Component
public class ProductEvidenceCollector {

    /**
     * Structural vocabulary per signal. Matched as WHOLE WORDS against normalised text -- never as
     * raw substrings.
     *
     * The boundary requirement is load-bearing, not tidiness: matching "rd number" as a substring
     * found it inside "Card Number" (ca-RD NUMBER) and classified a credit card as a recurring
     * deposit. The identical failure made "Rewards Bill" detect as the bank SBI. Any domain term
     * matched without boundaries anywhere in this pipeline is a bug waiting for the right document.
     */
    private static final Map<ProductSignal, List<String>> VOCABULARY = new LinkedHashMap<>();
    static {
        VOCABULARY.put(ProductSignal.DATE_COLUMN, List.of("date", "dt"));
        VOCABULARY.put(ProductSignal.DESCRIPTION_COLUMN, List.of(
                "narration", "particulars", "description", "remarks", "details",
                "transaction description"));
        VOCABULARY.put(ProductSignal.RUNNING_BALANCE_COLUMN, List.of(
                "balance", "closing balance", "running balance", "available balance"));
        VOCABULARY.put(ProductSignal.OPENING_BALANCE_FIELD, List.of("opening balance", "b f", "brought forward"));
        VOCABULARY.put(ProductSignal.CLOSING_BALANCE_FIELD, List.of("closing balance", "c f", "carried forward"));

        VOCABULARY.put(ProductSignal.MATURITY_FIELD, List.of(
                "maturity", "maturity date", "maturity amount", "maturity value", "date of maturity"));
        VOCABULARY.put(ProductSignal.INTEREST_RATE_FIELD, List.of(
                "rate of interest", "interest rate", "roi", "interest"));
        VOCABULARY.put(ProductSignal.PRINCIPAL_FIELD, List.of(
                "principal", "principal amount", "deposit amount"));
        VOCABULARY.put(ProductSignal.INSTALLMENT_FIELD, List.of(
                "installment", "instalment", "installments", "instalments", "installment frequency",
                "installments paid", "monthly installment", "no of installments", "amount paid"));

        VOCABULARY.put(ProductSignal.MINIMUM_DUE_FIELD, List.of(
                "minimum amount due", "minimum due", "min amount due", "minimum payment due"));
        VOCABULARY.put(ProductSignal.TOTAL_DUE_FIELD, List.of(
                "total payment due", "total amount due", "total due"));
        VOCABULARY.put(ProductSignal.CREDIT_LIMIT_FIELD, List.of(
                "credit limit", "available credit", "cash limit"));
        VOCABULARY.put(ProductSignal.CARD_NUMBER_FIELD, List.of("card number", "card no"));

        VOCABULARY.put(ProductSignal.EMI_FIELD, List.of("emi", "equated monthly instalment"));
        VOCABULARY.put(ProductSignal.OUTSTANDING_FIELD, List.of(
                "outstanding", "principal outstanding", "outstanding balance", "amount outstanding"));
        VOCABULARY.put(ProductSignal.TENURE_FIELD, List.of("tenure", "repayment schedule", "disbursed amount"));
    }

    /** Money-out and money-in column vocabulary, kept apart because
     *  {@link ProductSignal#DEBIT_CREDIT_COLUMNS} requires BOTH sides. One "Deposit(Mnth)" column on
     *  a deposit schedule is not a credit column; a withdrawal column opposite it would be. */
    private static final List<String> DEBIT_WORDS = List.of("withdrawal", "withdrawals", "debit", "debits", "dr");
    private static final List<String> CREDIT_WORDS = List.of("deposit", "deposits", "credit", "credits", "cr");
    private static final List<String> AMOUNT_WORDS = List.of("amount", "amt", "value");

    /** Words that NAME a product. Plurals are listed explicitly rather than stemmed -- whole-word
     *  matching means "savings accounts" does not contain "savings account", and a stemmer is a
     *  large amount of machinery to avoid writing eight extra strings. */
    private static final Map<String, FinancialProductType> PRODUCT_NAMES = new LinkedHashMap<>();
    static {
        PRODUCT_NAMES.put("recurring deposit", FinancialProductType.RECURRING_DEPOSIT);
        PRODUCT_NAMES.put("recurring deposits", FinancialProductType.RECURRING_DEPOSIT);
        PRODUCT_NAMES.put("rd account", FinancialProductType.RECURRING_DEPOSIT);
        PRODUCT_NAMES.put("rd number", FinancialProductType.RECURRING_DEPOSIT);
        PRODUCT_NAMES.put("fixed deposit", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("fixed deposits", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("term deposit", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("term deposits", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("fd account", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("fd number", FinancialProductType.FIXED_DEPOSIT);
        PRODUCT_NAMES.put("credit card", FinancialProductType.CREDIT_CARD);
        PRODUCT_NAMES.put("savings account", FinancialProductType.SAVINGS);
        PRODUCT_NAMES.put("savings accounts", FinancialProductType.SAVINGS);
        PRODUCT_NAMES.put("savings a c", FinancialProductType.SAVINGS);
        PRODUCT_NAMES.put("sb account", FinancialProductType.SAVINGS);
        PRODUCT_NAMES.put("current account", FinancialProductType.CURRENT);
        PRODUCT_NAMES.put("current accounts", FinancialProductType.CURRENT);
        PRODUCT_NAMES.put("overdraft", FinancialProductType.OVERDRAFT);
        PRODUCT_NAMES.put("loan account", FinancialProductType.LOAN);
        PRODUCT_NAMES.put("loan number", FinancialProductType.LOAN);
        PRODUCT_NAMES.put("public provident fund", FinancialProductType.PPF);
        PRODUCT_NAMES.put("ppf account", FinancialProductType.PPF);
        PRODUCT_NAMES.put("employees provident fund", FinancialProductType.EPF);
        PRODUCT_NAMES.put("epf account", FinancialProductType.EPF);
        PRODUCT_NAMES.put("national pension", FinancialProductType.NPS);
        PRODUCT_NAMES.put("nps account", FinancialProductType.NPS);
        PRODUCT_NAMES.put("demat", FinancialProductType.DEMAT);
        PRODUCT_NAMES.put("mutual fund", FinancialProductType.MUTUAL_FUND);
        PRODUCT_NAMES.put("wallet", FinancialProductType.WALLET);
    }

    /**
     * One section as Stage 1 sees it.
     *
     * @param columnNames  the section's own detected table headers
     * @param sectionText  free text scoped to this section
     * @param documentText free text known to describe the whole document (letterhead, relationship
     *                     summary). Nullable -- when a caller can't yet separate the two, pass null
     *                     and let {@link #demoteEnumeratedNames} find the summary blocks in
     *                     {@code sectionText} instead.
     * @param rowCount     how many rows the section produced
     */
    public record Section(List<String> columnNames, List<String> sectionText,
                          List<String> documentText, int rowCount) {

        public static Section of(List<String> columnNames, List<String> sectionText, int rowCount) {
            return new Section(columnNames, sectionText, null, rowCount);
        }
    }

    /** Records every fact the section exhibits. Never returns empty-handed: a section with nothing
     *  recognisable yields an empty fact list, which is itself the evidence that produces UNKNOWN. */
    public SectionEvidence collect(Section section) {
        List<ObservedFact> facts = new ArrayList<>();

        String columns = normalize(section.columnNames());
        String sectionText = normalize(section.sectionText());
        String documentText = normalize(section.documentText());

        collectStructural(facts, columns, EvidenceSource.COLUMN_HEADERS);
        collectStructural(facts, sectionText, EvidenceSource.SECTION_TEXT);
        collectStructural(facts, documentText, EvidenceSource.DOCUMENT_TEXT);

        collectAmountColumns(facts, columns, EvidenceSource.COLUMN_HEADERS);

        if (section.rowCount() > 0) {
            facts.add(ObservedFact.of(ProductSignal.TRANSACTION_ROWS, EvidenceSource.ROW_DATA,
                    section.rowCount() + " rows"));
        }

        collectProductNames(facts, columns, EvidenceSource.COLUMN_HEADERS);
        collectProductNames(facts, documentText, EvidenceSource.DOCUMENT_TEXT);

        List<ObservedFact> inSectionText = new ArrayList<>();
        collectProductNames(inSectionText, sectionText, EvidenceSource.SECTION_TEXT);
        facts.addAll(demoteEnumeratedNames(inSectionText));

        return SectionEvidence.of(facts);
    }

    /**
     * Free text naming SEVERAL different products is a document-level summary, not a description of
     * the section it happens to sit next to.
     *
     * This is the fix for the misclassification that motivated the whole stage split. A combined
     * statement opens with an account summary enumerating everything the customer holds -- savings,
     * then two kinds of deposit -- and that block lands in the auxiliary text of whichever section
     * comes first. Read as section-scoped, it asserts "savings account" over a table that may be
     * nothing of the sort.
     *
     * The general rule needs no list of summary headings and no bank knowledge: one section is one
     * product, so text naming two or more distinct products cannot be describing one section. Such
     * namings are kept (they are real facts, and a document-level fact is still worth recording)
     * but demoted to {@link EvidenceSource#DOCUMENT_TEXT}, where the classifier already knows not
     * to let them outvote structure.
     */
    private List<ObservedFact> demoteEnumeratedNames(List<ObservedFact> sectionNames) {
        Set<FinancialProductType> distinct = new LinkedHashSet<>();
        for (ObservedFact f : sectionNames) distinct.add(f.named());
        if (distinct.size() < 2) return sectionNames;

        List<ObservedFact> demoted = new ArrayList<>(sectionNames.size());
        for (ObservedFact f : sectionNames) {
            demoted.add(ObservedFact.productName(f.named(), EvidenceSource.DOCUMENT_TEXT,
                    f.observed() + " (one of " + distinct.size()
                            + " products named together -- a document-level summary, not this section)"));
        }
        return demoted;
    }

    private void collectStructural(List<ObservedFact> facts, String haystack, EvidenceSource source) {
        if (haystack.isBlank()) return;
        for (Map.Entry<ProductSignal, List<String>> entry : VOCABULARY.entrySet()) {
            for (String phrase : entry.getValue()) {
                if (mentions(haystack, phrase)) {
                    facts.add(ObservedFact.of(entry.getKey(), source, phrase));
                    break; // one fact per signal per source; which synonym matched is detail enough
                }
            }
        }
    }

    /** Debit/credit columns are only claimed when BOTH sides are present -- see
     *  {@link #DEBIT_WORDS}. A single amount column is recorded only when that pair is absent, so
     *  the two signals never both fire and inflate a score. */
    private void collectAmountColumns(List<ObservedFact> facts, String columns, EvidenceSource source) {
        String debit = firstMentioned(columns, DEBIT_WORDS);
        String credit = firstMentioned(columns, CREDIT_WORDS);
        if (debit != null && credit != null) {
            facts.add(ObservedFact.of(ProductSignal.DEBIT_CREDIT_COLUMNS, source,
                    debit + " + " + credit));
            return;
        }
        String amount = firstMentioned(columns, AMOUNT_WORDS);
        if (amount != null) {
            facts.add(ObservedFact.of(ProductSignal.SINGLE_AMOUNT_COLUMN, source, amount));
        }
    }

    private void collectProductNames(List<ObservedFact> facts, String haystack, EvidenceSource source) {
        if (haystack.isBlank()) return;
        Set<FinancialProductType> alreadyNamed = new LinkedHashSet<>();
        for (Map.Entry<String, FinancialProductType> entry : PRODUCT_NAMES.entrySet()) {
            if (!mentions(haystack, entry.getKey())) continue;
            if (!alreadyNamed.add(entry.getValue())) continue; // one naming per product per source
            facts.add(ObservedFact.productName(entry.getValue(), source, entry.getKey()));
        }
    }

    private String firstMentioned(String haystack, List<String> phrases) {
        for (String phrase : phrases) if (mentions(haystack, phrase)) return phrase;
        return null;
    }

    /** Lowercased, every run of non-alphanumerics collapsed to one space, padded at both ends so a
     *  phrase can be tested with whole-word boundaries. */
    private String normalize(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : parts) if (part != null) sb.append(' ').append(part);
        String collapsed = sb.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return collapsed.isEmpty() ? "" : " " + collapsed + " ";
    }

    private boolean mentions(String haystack, String phrase) {
        String needle = " " + phrase.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        return haystack.contains(needle);
    }
}
