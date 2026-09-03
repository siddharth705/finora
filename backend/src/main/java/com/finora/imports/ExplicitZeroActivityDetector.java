package com.finora.imports;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recognises when a statement's own printed table states, in so many words, that nothing happened
 * during the period it covers -- as opposed to the pipeline simply failing to read a table it
 * found. The two look identical to {@link ExtractionCheck}, which only ever sees "zero staged
 * rows"; this is the thing that lets it tell them apart.
 *
 * <h2>The evidence this is built on</h2>
 *
 * A real HSBC composite statement in the corpus prints a "Transaction Count" line inside its
 * savings ledger, with a zero-shaped figure on both the deposit and the withdrawal side, alongside
 * an identical BALANCE BROUGHT FORWARD / CLOSING BALANCE pair. {@code PdfTableLocator} locates this
 * as an ordinary row (it has no opinion on what a row MEANS -- see its own class doc), and none of
 * {@code TransactionNormalizer}'s classifications turn it into a transaction, so it vanishes
 * silently: the document is genuinely, provably inactive, and {@code ExtractionCheck} had no way to
 * know that and reported it as an unreadable table instead.
 *
 * <h2>Why this is a document-level fact, not a capability or a diagnostic</h2>
 *
 * {@link DocumentContext}'s own doc comment draws exactly two categories: a capability is a parser
 * behaviour that improves extraction, a diagnostic is a measurement of parse quality. This is
 * neither -- it is a positive claim the DOCUMENT ITSELF makes about its own content, true or false
 * independent of how well anything downstream reads it. See
 * {@link DocumentContext#recordExplicitZeroActivityDeclared()}.
 *
 * <h2>Why the label is matched as a substring, and why both directions are required</h2>
 *
 * On the real evidencing document, this exact row's OTHER columns are glued to unrelated
 * boilerplate by an unrelated row-formation quirk ({@code PdfPreviewGenerator.buildLedgerSection}
 * keeps every located row, garbled or not, specifically so nothing is silently dropped -- see its
 * own comment). An exact-equals match on the label would never fire on the document this class
 * exists for. The cost of the looser label match is paid for by requiring BOTH the deposit and the
 * withdrawal figure to be present and exactly zero: a bank that only ever prints a single combined
 * running total has not stated zero on both sides, and inferring it from a partial figure would be
 * exactly the kind of guess {@link CsvParser} already refuses to make for less consequential
 * fields.
 */
public final class ExplicitZeroActivityDetector {

    private ExplicitZeroActivityDetector() {
    }

    private static final Pattern TRANSACTION_COUNT_LABEL =
            Pattern.compile("(?i)\\btransaction\\s+count\\b");

    // Shared with TransactionNormalizer, deliberately: this needs to recognise the same narration
    // column that class does, and every bank-specific synonym added there over time (each with its
    // own evidence comment -- see that field's own doc comment) is exactly the vocabulary this
    // needs too. A duplicated copy would silently drift the first time either class gained a
    // synonym the other didn't.
    private static final String[] DESCRIPTION_HINTS = TransactionNormalizer.DESCRIPTION_HINTS;
    private static final String[] DEPOSIT_HINTS =
            {"deposit amt", "deposit amount", "deposit", "deposits"};
    private static final String[] WITHDRAWAL_HINTS =
            {"withdrawal amt", "withdrawal amount", "withdrawal", "withdrawals"};

    /** True when some located row states, on both sides at once, that this statement's transaction
     *  count for its own covered period is zero. */
    public static boolean anyRowDeclaresZeroTransactionCount(List<Map<String, String>> rows) {
        for (Map<String, String> row : rows) {
            String narration = CsvParser.firstNonBlank(row, DESCRIPTION_HINTS);
            if (narration == null || !TRANSACTION_COUNT_LABEL.matcher(narration).find()) continue;

            BigDecimal deposits = CsvParser.parseNumeric(CsvParser.firstNonBlank(row, DEPOSIT_HINTS));
            BigDecimal withdrawals = CsvParser.parseNumeric(CsvParser.firstNonBlank(row, WITHDRAWAL_HINTS));
            if (isZero(deposits) && isZero(withdrawals)) return true;
        }
        return false;
    }

    private static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }
}
