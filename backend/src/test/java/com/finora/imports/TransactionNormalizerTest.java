package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers two real-world bugs found importing an actual PNB ONE bank statement (Date | Instrument
 * ID | Amount(INR) | Type | Balance | Remarks -- no separate Description or Debit/Credit
 * columns): every row staged with an empty description (CategoryRules.extractMerchant("") falls
 * back to the literal string "unknown", which is what showed up in the ledger), and every CR
 * (credit) row was silently misclassified as an EXPENSE because the old isIncome check only ever
 * looked for a separate Credit column or the literal word "income" in Type, neither of which this
 * layout has.
 */
class TransactionNormalizerTest {

    private final UUID userId = UUID.randomUUID();
    private TransactionNormalizer normalizer;

    @BeforeEach
    void setUp() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        // Staging calls the rule-set overload (rules hoisted out of the per-row loop);
        // stubbed alongside the loading one so either path returns a real suggestion.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        normalizer = new TransactionNormalizer(categorizationService, duplicateDetector, com.finora.imports.TestRuleEngines.empty());
    }

    private Map<String, String> rowOf(String... headerThenValuePairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < headerThenValuePairs.length; i += 2) {
            row.put(headerThenValuePairs[i], headerThenValuePairs[i + 1]);
        }
        return row;
    }

    // --- Description column recognition ---

    @Test
    void normalize_readsDescriptionFromARemarksColumn_noSeparateDescriptionColumn() {
        Map<String, String> row = rowOf(
                "Date", "31/07/2026",
                "Instrument ID", "",
                "Amount(INR)", "680.0",
                "Type", "DR",
                "Balance", "7025.86",
                "Remarks", "UPI/DR/900011112222/MERCHANT/UTIB/sample-billpay@ok/");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.description()).isEqualTo("UPI/DR/900011112222/MERCHANT/UTIB/sample-billpay@ok/");
    }

    @Test
    void normalize_readsDescriptionFromAParticularsColumn() {
        Map<String, String> row = rowOf(
                "Date", "01/07/2026",
                "Amount", "500",
                "Type", "DR",
                "Particulars", "ATM WDL MG ROAD");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.description()).isEqualTo("ATM WDL MG ROAD");
    }

    @Test
    void normalize_stillFallsBackToEmptyDescription_whenNoRecognizedColumnExists() {
        // Not a regression target so much as documenting the floor: an unrecognized layout still
        // stages (date + amount is enough), just with nothing to show for a description -- this
        // is the state that used to render as "unknown" in the ledger (Ledger.tsx's `t.description
        // || t.merchant` fallback), not something this class should paper over with a guess.
        Map<String, String> row = rowOf("Date", "01/07/2026", "Amount", "500", "Type", "DR", "Some Other Column", "text");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.description()).isEmpty();
    }

    /**
     * Regression test: this exact fallback existed, was accidentally dropped when
     * TransactionNormalizer.normalize() was later edited for the remarks/DR-CR fix above, and the
     * only thing that caught it was an unrelated PDF integration test's row count silently
     * dropping from 6 to 4 -- a confusing way to discover a one-line regression in a completely
     * different class. This test exists so a regression here fails loudly and specifically
     * instead.
     */
    @Test
    void normalize_resolvesAmountFromABalanceColumn_whenNoAmountDebitOrCreditColumnExistsAtAll() {
        // A PDF statement's OPENING BALANCE / CLOSING BALANCE row: no Debit, no Credit, no
        // Amount column at all -- only a Balance column and a date, exactly like
        // PdfPreviewGeneratorTest's golden fixture uses for those two rows.
        Map<String, String> row = rowOf("Date", "01/07/2026", "Description", "OPENING BALANCE", "Balance", "50000.00");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualByComparingTo("50000.00");
    }

    // --- DR/CR type-column recognition (unified Amount + Type layout) ---

    @Test
    void normalize_treatsATypeColumnValueOfCr_asIncome() {
        Map<String, String> row = rowOf(
                "Date", "18/07/2026",
                "Amount(INR)", "1057.0",
                "Type", "CR",
                "Remarks", "UPI/CR/900033334444/SAMPLE P/SBIN/sample11111@oksbi/");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
        assertThat(result.amount()).isEqualByComparingTo("1057.0");
    }

    @Test
    void normalize_treatsATypeColumnValueOfDr_asExpense() {
        Map<String, String> row = rowOf(
                "Date", "31/07/2026",
                "Amount(INR)", "680.0",
                "Type", "DR",
                "Remarks", "UPI/DR/900011112222/MERCHANT/UTIB/sample-billpay@ok/");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("EXPENSE");
    }

    @Test
    void normalize_typeColumnCheckIsCaseAndWhitespaceInsensitive() {
        Map<String, String> row = rowOf("Date", "18/07/2026", "Amount", "500", "Type", " cr ", "Remarks", "x");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
    }

    @Test
    void normalize_readsDirectionFromAColumnNamedForTheMarkerItself() {
        // Verified against a real Bandhan Bank statement, whose direction column is headed
        // literally "Dr / Cr" -- the marker as the column name, no "Type" anywhere. The exact
        // "type" hint never matched it, and with no Credit column in this layout either, every row
        // fell through to the final default and staged as an EXPENSE. This is the wrong-data
        // failure rather than the missing-data one: the amount, date and balance all looked right
        // in the review screen while the sign was inverted on the credits.
        Map<String, String> credit = rowOf(
                "Transaction Date", "July12, 2026",
                "Description", "UPI/CR/C210000000002/",
                "Amount", "INR15,000.00",
                "Dr / Cr", "Cr",
                "Balance", "INR35,728.84");

        StagedRow result = normalizer.normalize(userId, credit);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
        assertThat(result.amount()).isEqualByComparingTo("15000.00");

        Map<String, String> debit = rowOf(
                "Transaction Date", "July29, 2026",
                "Description", "UPI/DR/D650000000001/",
                "Amount", "INR16,281.00",
                "Dr / Cr", "Dr",
                "Balance", "INR19,447.84");

        assertThat(normalizer.normalize(userId, debit).type()).isEqualTo("EXPENSE");
    }

    @Test
    void normalize_readsDirectionFromAnUnspacedDrCrColumnName() {
        // normalizeHeaderCell strips a trailing parenthetical and trailing punctuation but leaves
        // interior spacing alone, so "Dr/Cr" and "Dr / Cr" arrive as genuinely different strings
        // and each has to be listed. Asserted so a future tidy-up of that list cannot drop one
        // spelling on the assumption the other covers it.
        Map<String, String> row = rowOf("Date", "12/07/2026", "Amount", "500", "Dr/Cr", "Cr", "Description", "x");

        assertThat(normalizer.normalize(userId, row).type()).isEqualTo("INCOME");
    }

    @Test
    void normalize_stillRecognizesTheLiteralWordIncomeInATypeColumn() {
        // Pre-existing behavior (some exports use a Type column with values like "Income"/
        // "Expense" rather than Dr/Cr) -- the new Dr/Cr check must not replace this, only add to it.
        Map<String, String> row = rowOf("Date", "01/07/2026", "Amount", "500", "Type", "Income", "Description", "Salary");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
    }

    @Test
    void normalize_stillRecognizesASeparateNonBlankCreditColumn_whenThereIsNoTypeColumnAtAll() {
        // Pre-existing behavior for a genuine separate Debit/Credit-column layout (no unified
        // Type column at all) -- must keep working exactly as before.
        Map<String, String> row = rowOf("Date", "05/07/2026", "Credit", "1000", "Description", "Salary credit");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
    }

    // Regression test for a serious real bug, not just a dropped-row bug: a real Kotak Mahindra
    // Bank statement uses column headers "Deposit (Cr.)" / "Withdrawal (Dr.)", which
    // CsvParser.normalizeHeaderCell reduces to the SINGULAR "deposit"/"withdrawal" (the
    // parenthesized "(Cr.)"/"(Dr.)" suffix strips the same way a currency suffix does) -- neither
    // of which used to be recognized by this class at all (only the plural "deposits"/
    // "withdrawals" was). Every row on that file silently fell through to the "balance" fallback
    // instead: the transaction's AMOUNT showed as the account's running BALANCE, and every row --
    // including genuine credits -- staged as an EXPENSE, with no error and no dropped-row signal
    // to reveal it. Caught only by directly inspecting staged amounts against the real file, not
    // by anything failing loudly.
    @Test
    void normalize_recognizesASingularDepositColumnHeader_asACreditSignal_notJustTheBalanceFallback() {
        Map<String, String> row = rowOf("Date", "01/07/2026", "Deposit (Cr.)", "10.00", "Balance", "24351.97",
                "Description", "UPI/SAMPLE PAYEE A");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("INCOME");
        assertThat(result.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void normalize_recognizesASingularWithdrawalColumnHeader_asTheAmount_notTheBalance() {
        Map<String, String> row = rowOf("Date", "01/07/2026", "Withdrawal (Dr.)", "1000.00", "Balance", "24361.97",
                "Description", "SentIMPS900055556666");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("EXPENSE");
        assertThat(result.amount()).isEqualByComparingTo("1000.00");
    }

    // Regression test: verified against a real Canara Bank statement whose closing-balance
    // summary row puts the literal label text "Closing Balance" in what's otherwise the Deposits
    // column real deposit amounts use on every other row -- a plain "first non-blank match" would
    // return that label as the "amount" and fail to parse it as a number, dropping a row that
    // actually carries a real, usable Withdrawals amount right alongside it.
    @Test
    void normalize_skipsAnAmountHintMatch_whoseValueIsntActuallyNumeric_andFallsThroughToARealAmount() {
        Map<String, String> row = rowOf("Date", "01/08/2026", "Deposits", "Closing Balance",
                "Withdrawals", "228.00", "Balance", "107279.08");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualByComparingTo("228.00");
        assertThat(result.type()).isEqualTo("EXPENSE");
    }

    @Test
    void normalize_defaultsToExpense_whenNeitherATypeColumnNorACreditColumnIndicatesIncome() {
        Map<String, String> row = rowOf("Date", "05/07/2026", "Debit", "1000", "Description", "Groceries");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo("EXPENSE");
    }

    @Test
    void normalize_amountIsAlwaysPositive_regardlessOfDirection() {
        Map<String, String> creditRow = rowOf("Date", "18/07/2026", "Amount", "1057.0", "Type", "CR", "Remarks", "x");
        Map<String, String> debitRow = rowOf("Date", "31/07/2026", "Amount", "680.0", "Type", "DR", "Remarks", "x");

        assertThat(normalizer.normalize(userId, creditRow).amount()).isEqualByComparingTo(new BigDecimal("1057.0"));
        assertThat(normalizer.normalize(userId, debitRow).amount()).isEqualByComparingTo(new BigDecimal("680.0"));
    }

    // --- Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // referenceNumber/balanceAfter -- previously computed nowhere, now captured whenever a
    // recognized column is present, and null otherwise (never guessed). ---

    @Test
    void normalize_capturesReferenceNumber_fromARecognizedColumn() {
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Amount", "500", "Type", "DR", "Remarks", "x",
                "Reference Number", "REF123456789");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.referenceNumber()).isEqualTo("REF123456789");
    }

    @Test
    void normalize_capturesReferenceNumber_fromAnInstrumentIdColumn_thePnbOneLayout() {
        // Same real PNB ONE layout this test class's own class doc describes -- "Instrument ID"
        // was already a header-detection hint (PdfTableLocator.HEADER_HINTS) but its value was
        // never captured until now.
        Map<String, String> row = rowOf(
                "Date", "31/07/2026", "Instrument ID", "UPI2607315823",
                "Amount(INR)", "680.0", "Type", "DR", "Balance", "7025.86",
                "Remarks", "UPI/DR/900011112222/MERCHANT/UTIB/sample-billpay@ok/");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.referenceNumber()).isEqualTo("UPI2607315823");
    }

    @Test
    void normalize_capturesReferenceNumber_fromTheRealCanaraBankColumnHeaderVariant() {
        // The exact header text a real Canara Bank statement uses -- verified directly against
        // the row map (not a rendered PDF) since PdfFixtureBuilder's synthetic PDFBox rendering
        // has its own column-width quirks unrelated to whether this hint match itself works.
        // (Cell values are synthetic per the Synthetic Fixture Policy -- see the engineering
        // principles doc -- only the column header phrasing itself is the real, structural fact
        // under test here.)
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Particulars", "UPI/DR/234567890123/GENERIC MERCHANT",
                "Reference / Cheque No.", "234567890123", "Amount", "-1000.00", "Balance", "49000.00");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.referenceNumber()).isEqualTo("234567890123");
    }

    @Test
    void normalize_capturesReferenceNumber_fromTheRealKotakBankColumnHeaderVariant() {
        // Bug fix: a real Kotak Mahindra Bank statement's column is "Chq/Ref. No." -- periods
        // after BOTH "Ref" and "No", unlike the "chq/ref no" (no periods) variant already
        // covered. normalizeHeaderCell only strips a TRAILING parenthetical, never internal
        // punctuation, so this real header normalized to a literal string nothing else matched.
        // (Cell values genericized per the Synthetic Fixture Policy -- only the header phrasing
        // is the real fact under test.)
        Map<String, String> row = rowOf(
                "Date", "02 Jul 2026", "Description", "CASHBACK EARNED",
                "Chq/Ref. No.", "REF1234567890ABC", "Deposit (Cr.)", "1.00", "Balance", "25000.00");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.referenceNumber()).isEqualTo("REF1234567890ABC");
    }

    @Test
    void normalize_fallsBackToTheTransactionIdColumn_whenNoRealDescriptionColumnHasAnyValue() {
        // Bug fix: verified against a real Union Bank of India statement. Its header row detects
        // a "Remarks" column, but PdfTableLocator's column-anchor bucketing never actually
        // produces a "Remarks" key on any real data row for this document -- the narration text
        // lands under "Transaction Id" instead. Every transaction staged with an empty
        // description, which then also broke categorization (nothing to match against) and
        // silently pushed every row to "low confidence" in the review UI, even though the real
        // narration text was present in the row the whole time, just under an unexpected key.
        // (Value genericized per the Synthetic Fixture Policy -- only the column shape, a short
        // ID token followed by narration text in one cell, is the real fact under test.)
        Map<String, String> row = rowOf(
                "Date", "01-05-2026", "Transaction Id", "Y00000000 UPIAB/000000000000/CR/GENERIC PAYER",
                "Amount(", "50000.00(Cr)", "Balance(", "58234.84(Cr)");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.description()).isEqualTo("Y00000000 UPIAB/000000000000/CR/GENERIC PAYER");
    }

    @Test
    void normalize_prefersARealDescriptionColumn_overTransactionIdWhenBothArePresent() {
        // "Transaction Id" is deliberately lowest priority -- a document where "Remarks"/
        // "Description"/etc. IS populated must never have that real description silently
        // replaced by a merely-present "Transaction Id" column.
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "Salary Credit", "Transaction Id", "TXN00012345",
                "Amount", "50000.00");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.description()).isEqualTo("Salary Credit");
    }

    @Test
    void normalize_referenceNumberIsNull_whenNoRecognizedColumnIsPresent() {
        Map<String, String> row = rowOf("Date", "01/07/2026", "Amount", "500", "Type", "DR", "Remarks", "x");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.referenceNumber()).isNull();
    }

    @Test
    void normalize_capturesBalanceAfter_fromARunningBalanceColumn() {
        Map<String, String> row = rowOf(
                "Date", "31/07/2026", "Amount(INR)", "680.0", "Type", "DR",
                "Balance", "7025.86", "Remarks", "x");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.balanceAfter()).isEqualByComparingTo(new BigDecimal("7025.86"));
    }

    @Test
    void normalize_balanceAfterIsNull_whenNoBalanceColumnIsPresent() {
        Map<String, String> row = rowOf("Date", "01/07/2026", "Amount", "500", "Type", "DR", "Remarks", "x");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result.balanceAfter()).isNull();
    }

    /** AMOUNT_HINTS' own existing fallback (an OPENING/CLOSING BALANCE summary row with no
     *  debit/credit column at all, so "balance" is the only usable amount) must keep working
     *  exactly as before -- balanceAfter is a second, independent read of the same column, not a
     *  replacement for it. */
    @Test
    void normalize_balanceColumnStillWorksAsTheAmountFallback_forASummaryRowWithNoDebitCreditColumn() {
        Map<String, String> row = rowOf("Date", "01/07/2026", "Balance", "50000.00", "Remarks", "OPENING BALANCE");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        // And the SAME column also populates balanceAfter now -- both readings of "balance" are
        // legitimate simultaneously for this row shape: it's this row's only usable amount AND
        // its post-transaction balance (they happen to be the same value for an opening-balance
        // summary row specifically).
        assertThat(result.balanceAfter()).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    // --- explainFailure() -- "Never lose information": a row normalize() rejects still gets a
    // specific, actionable reason surfaced to the user instead of just vanishing from the count. ---

    @Test
    void explainFailure_reportsNoDateColumn_whenNothingLooksLikeADate() {
        Map<String, String> row = rowOf("Amount", "500.00", "Description", "Groceries");

        assertThat(normalizer.explainFailure(row)).isEqualTo("No column recognized as a date");
    }

    @Test
    void explainFailure_reportsTheUnparseableDateValue_whenADateColumnExistsButDoesNotParse() {
        Map<String, String> row = rowOf("Date", "not-a-real-date", "Amount", "500.00");

        assertThat(normalizer.explainFailure(row)).contains("not-a-real-date").contains("didn't match any known date format");
    }

    @Test
    void explainFailure_reportsNoAmountColumn_whenDateParsesButNothingLooksLikeAnAmount() {
        Map<String, String> row = rowOf("Date", "05/07/2026", "Description", "Groceries");

        assertThat(normalizer.explainFailure(row)).isEqualTo("No column recognized as an amount or balance");
    }

    @Test
    void explainFailure_reportsTheUnparseableAmountValue_whenAnAmountColumnExistsButDoesNotParse() {
        Map<String, String> row = rowOf("Date", "05/07/2026", "Amount", "not-a-number");

        assertThat(normalizer.explainFailure(row)).contains("not-a-number").contains("didn't match any known numeric format");
    }

    // --- A zero in the unused column must not shadow the real amount ---------------------------
    //
    // Verified against a real HDFC savings statement. A separate Withdrawals/Deposits layout does
    // not leave the side that did not move blank -- it prints 0.00 there. AMOUNT_HINTS checks
    // "deposits" before "withdrawals", and 0.00 is neither blank nor unparseable, so every
    // withdrawal on that statement imported with amount 0 while the one genuine deposit imported
    // correctly. Silently wrong data rather than a dropped row, and the file looked like it worked.

    @Test
    void normalize_readsTheWithdrawal_whenTheDepositColumnHoldsAZero() {
        Map<String, String> row = rowOf(
                "Txn Date", "16/07/2026", "Narration", "JNS-PMJJBY PREMIUM",
                "Withdrawals", "436.00", "Deposits", "0.00", "Closing Balance", "24,544.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.amount()).isEqualByComparingTo("436.00");
    }

    @Test
    void normalize_classifiesAWithdrawalAsExpense_whenTheDepositColumnHoldsAZero() {
        // The same zero was wrong twice: CREDIT_HINTS matched it too, and the direction check only
        // asks whether a credit column had a value at all -- so these rows were EXPENSE only by
        // accident of their amount being zero, and would have flipped to INCOME once the amount
        // was fixed. A zero in the credit column means "not this side".
        Map<String, String> row = rowOf(
                "Txn Date", "16/07/2026", "Narration", "JNS-PMSBY PREMIUM",
                "Withdrawals", "20.00", "Deposits", "0.00", "Closing Balance", "24,980.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.type()).isEqualTo("EXPENSE");
        assertThat(staged.amount()).isEqualByComparingTo("20.00");
    }

    @Test
    void normalize_stillReadsTheDeposit_whenTheWithdrawalColumnHoldsAZero() {
        // The mirror case, so the fix cannot be "always prefer withdrawals".
        Map<String, String> row = rowOf(
                "Txn Date", "10/07/2026", "Narration", "UPI CREDIT",
                "Withdrawals", "0.00", "Deposits", "25,000.00", "Closing Balance", "25,000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.amount()).isEqualByComparingTo("25000.00");
        assertThat(staged.type()).isEqualTo("INCOME");
    }

    @Test
    void normalize_stillAcceptsAGenuinelyZeroAmount_whenNothingElseParses() {
        // Zero is not rejected outright -- only deprioritised. A row whose only parseable value is
        // zero must still normalize to zero rather than failing.
        Map<String, String> row = rowOf(
                "Date", "05/07/2026", "Description", "NIL CHARGE", "Amount", "0.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.amount()).isEqualByComparingTo("0.00");
        // No Balance-style column exists on this row at all, so there is no competing "real"
        // numeric content -- the zero amount stays a genuine transaction, not a marker.
        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
    }

    // --- Zero-padded balance-marker rows (second fix pass) -----------------------------------
    //
    // firstParseableAmount's fallback (used when no TRANSACTION_AMOUNT_HINTS column has a
    // NON-zero value) previously treated an explicit "0.00" in a real debit/credit column as
    // proof the row was a transaction -- exactly the shape a separate-columns layout uses to pad
    // a balance-marker row's unused sides ("0.00" printed on BOTH sides instead of leaving them
    // blank; see firstNonZeroAmount's own doc comment for the single-sided version of this same
    // layout convention). These tests prove the fix in both directions.

    @Test
    void normalize_classifiesAsBalanceMarker_whenBothTransactionalColumnsAreExplicitlyZeroPadded() {
        // The exact shape from the marker-row-pollution investigation: a separate-columns layout
        // that does not leave OPENING BALANCE's unused sides blank -- it prints "0.00" on BOTH,
        // with the real figure only in Balance. Must not classify TRANSACTION with amount
        // 50000.00 (the bug this test guards against).
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "OPENING BALANCE",
                "Withdrawals", "0.00", "Deposits", "0.00", "Balance", "50000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
        // Balance evidence is still preserved for opening/closing-balance derivation even though
        // the row is excluded from the transaction stream.
        assertThat(staged.balanceAfter()).isEqualByComparingTo("50000.00");
    }

    @Test
    void normalize_classifiesAsBalanceMarker_whenZeroPaddedRowHasNoBalanceWordingAtAll() {
        // Same structural shape as above, no recognizable marker wording and a blank description
        // -- proves the classifier reads column structure, not text, even for the zero-padded
        // case.
        Map<String, String> row = rowOf(
                "Date", "31/07/2026", "Description", "",
                "Withdrawals", "0.00", "Deposits", "0.00", "Closing Balance", "117209.50");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsTransaction_whenAGenuineZeroValueTransactionHasNoRealBalanceSignal() {
        // A genuine zero-value transaction (e.g. a fee waiver) on a separate-columns layout: both
        // sides are explicitly zero, exactly like a padded marker row, but there is no Balance
        // column at all on this statement/row to serve as the marker tiebreaker -- so it must
        // stay TRANSACTION rather than being swept up by the new zero-padding rule.
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "FEE WAIVER",
                "Withdrawals", "0.00", "Deposits", "0.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
        assertThat(staged.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void normalize_classifiesAsBalanceMarker_whenSingleAmountColumnIsExplicitlyZero_andBalanceColumnHasARealValue() {
        // A single-Amount-column layout (not separate debit/credit) with a genuine zero-value
        // transaction AND a running-balance column present. "amount" is itself a
        // TRANSACTION_AMOUNT_HINTS entry, so its zero value is what resolves transactionAmountRaw
        // -- this exercises the same tiebreaker from the single-column-layout angle, confirming
        // the fix isn't specific to the separate debit/credit shape.
        Map<String, String> row = rowOf(
                "Date", "06/07/2026", "Description", "NIL CHARGE", "Amount", "0.00",
                "Balance", "24980.00");

        StagedRow staged = normalizer.normalize(userId, row);

        // This is the documented, accepted ambiguity of the tiebreaker (see the fix's own
        // comment in TransactionNormalizer.normalize): a zero-padded transactional column paired
        // with a real balance figure is classified BALANCE_MARKER even though, in this specific
        // case, it is actually a genuine zero-value transaction. Structurally indistinguishable
        // from a true marker row without reading the description (which the classifier is
        // deliberately forbidden from doing) or adjacent-row context (which normalize() does not
        // have). Pinned here as documented, known behavior, not silently assumed.
        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    // --- RowKind classification (marker-row pollution fix) ---
    //
    // See docs/architecture/system-design/marker-row-pollution-scope-investigation.md and
    // RowKind's own doc comment. The classifier reads which COLUMN produced the amount -- a real
    // debit/credit/amount column vs. only a Balance-style column -- and never reads the
    // description text. These tests exercise several different bank phrasings for the SAME
    // underlying structural shape (no transactional-amount column, only a Balance column) to prove
    // the classifier is not a disguised string list, plus the mirror case: a real transaction whose
    // description happens to contain the word "balance" must not be misclassified.

    @Test
    void normalize_classifiesAsBalanceMarker_whenOnlyABalanceColumnHasAValue_openingBalanceWording() {
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "OPENING BALANCE",
                "Debit", "", "Credit", "", "Balance", "50000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsBalanceMarker_regardlessOfWording_beginningBalance() {
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "Beginning Balance",
                "Debit", "", "Credit", "", "Balance", "24818.22");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsBalanceMarker_regardlessOfWording_balanceForward() {
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "Balance Forward",
                "Withdrawals", "", "Deposits", "", "Closing Balance", "83413.31");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsBalanceMarker_regardlessOfWording_endingBalanceDifferentCasing() {
        Map<String, String> row = rowOf(
                "Date", "31/07/2026", "Description", "ENDING BALANCE",
                "Withdrawal Amt", "", "Deposit Amt", "", "Running Balance", "117209.50");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsBalanceMarker_evenWithNoBalanceWordingAtAll_blankDescription() {
        // The classifier never reads the description -- a row with NO recognizable "balance"
        // wording at all, or no description whatsoever, still classifies correctly purely from its
        // column structure. This is the strongest proof the fix generalizes rather than being a
        // string list wearing an enum's clothes.
        Map<String, String> row = rowOf(
                "Date", "01/07/2026", "Description", "",
                "Debit", "", "Credit", "", "Balance", "50000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
    }

    @Test
    void normalize_classifiesAsTransaction_whenARealDebitColumnHasAValue() {
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "Grocery Store",
                "Debit", "2000.00", "Credit", "", "Balance", "48000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
    }

    @Test
    void normalize_classifiesAsTransaction_whenARealCreditColumnHasAValue() {
        Map<String, String> row = rowOf(
                "Date", "03/07/2026", "Description", "Salary Credit",
                "Debit", "", "Credit", "75000.00", "Balance", "123000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
    }

    /**
     * The over-broad-filter risk the PM's scope explicitly calls out: a genuine transaction
     * immediately next to a balance marker, with the SAME date shape and amount format, whose
     * description happens to literally contain the word "balance" (a real narration a bank could
     * print, e.g. "Balance transfer to savings account"). Must classify TRANSACTION because it has
     * a real value in a transactional amount column -- the classifier never looks at the
     * description at all, so text overlap with a marker row's usual wording cannot trip it.
     */
    @Test
    void normalize_classifiesAsTransaction_whenARealTransactionsDescriptionMentionsBalance() {
        Map<String, String> row = rowOf(
                "Date", "04/07/2026", "Description", "Balance transfer to savings account",
                "Debit", "5000.00", "Credit", "", "Balance", "43000.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
        assertThat(staged.amount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void normalize_classifiesAsTransaction_whenTheOnlyAmountColumnIsGenericAmount() {
        // A single-column layout (Axis/HDFC "37.94 Dr" style) with no separate debit/credit/balance
        // split at all -- "amount" is itself a TRANSACTION_AMOUNT_HINTS entry, so an ordinary
        // transaction on this layout still classifies correctly.
        Map<String, String> row = rowOf("DATE", "24/06/2026", "TRANSACTION DETAILS", "UPI/SAMPLE VENDOR", "AMOUNT (Rs.)", "37.94 Dr");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
    }

    @Test
    void normalize_defaultsToTransaction_forEveryPreExistingStagedRowConstruction() {
        // Every StagedRow built directly (not through normalize()) -- the shape every existing test
        // and caller across the codebase already uses -- must keep defaulting to TRANSACTION so
        // none of them silently change behavior.
        StagedRow legacy = new StagedRow(java.time.LocalDate.now(), "x", new BigDecimal("1.00"), "EXPENSE",
                "Other", "default", null, false, null, null);
        assertThat(legacy.kind()).isEqualTo(RowKind.TRANSACTION);
    }

    // --- hasUnparseableRecognizedAmount (third fix pass) --------------------------------------
    //
    // An independent verifier found the second fix pass's hasUnrecognizedNonBlankColumn guard
    // covers only unrecognized COLUMN NAMES. A row with a recognized transactional column (e.g.
    // Debit) whose VALUE fails to parse (CsvParser.parseNumeric returns null) still classifies
    // BALANCE_MARKER via the same structural logic a real balance row does, and
    // hasUnrecognizedNonBlankColumn finds nothing wrong -- every column name IS recognized. The
    // row then silently vanishes: not staged (wrong kind), not reported unparseable (no
    // unrecognized column). These tests pin the new guard directly against
    // TransactionNormalizer, independent of which staging loop (CSV or PDF) wires it in.

    @Test
    void hasUnparseableRecognizedAmount_isTrue_forASlashDashSuffixedDebitValue() {
        // The exact row from the marker-row-pollution third-pass report: a real Debit column
        // holding "1500/-", a value CsvParser.parseNumeric cannot parse at all.
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "REAL RENT PAYMENT",
                "Debit", "1500/-", "Credit", "", "Balance", "48500.00");

        assertThat(normalizer.hasUnparseableRecognizedAmount(row)).isTrue();
    }

    @Test
    void hasUnparseableRecognizedAmount_isTrue_forTheDocumentedUnionBankParenthesizedDrCrFormat() {
        // Proves the fix generalizes rather than pattern-matching the literal "1500/-" string --
        // this is one of the three real bank numeric formats CsvParser.parseNumeric's own javadoc
        // documents historically choking on with this exact "row vanishes" failure mode, exercised
        // here as a malformed variant it still can't parse (a stray leading "X").
        Map<String, String> row = rowOf(
                "Date", "03/07/2026", "Description", "REAL DEBIT",
                "Debit", "X50000.00(Cr)", "Credit", "", "Balance", "48500.00");

        assertThat(normalizer.hasUnparseableRecognizedAmount(row)).isTrue();
    }

    @Test
    void hasUnparseableRecognizedAmount_isFalse_whenEveryRecognizedAmountColumnParsesOrIsBlank() {
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "REAL RENT PAYMENT",
                "Debit", "1500.00", "Credit", "", "Balance", "48500.00");

        assertThat(normalizer.hasUnparseableRecognizedAmount(row)).isFalse();
    }

    @Test
    void normalize_stillClassifiesTheUnparseableDebitRowAsBalanceMarker_soCallersMustRouteItToUnparseable() {
        // normalize() itself is unaffected by the fix -- the row still classifies BALANCE_MARKER
        // (its real Debit column never resolves a value). It is the staging loops
        // (PreviewGenerator/PdfPreviewGenerator), not normalize(), that now also check
        // hasUnparseableRecognizedAmount before excluding a BALANCE_MARKER row from `staged`.
        // Pinned here so a future change to the classifier itself can't silently defeat the guard.
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "REAL RENT PAYMENT",
                "Debit", "1500/-", "Credit", "", "Balance", "48500.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
        assertThat(normalizer.hasUnparseableRecognizedAmount(row)).isTrue();
    }

    // --- All-zero CLOSING BALANCE rows (third fix pass) ----------------------------------------
    //
    // A closed/emptied account's CLOSING BALANCE row can read Withdrawals=0.00/Deposits=0.00/
    // Balance=0.00 -- every side genuinely zero, not just the transactional sides. The prior
    // tiebreaker (balanceAfter.signum() != 0) treated a zero balance as if the Balance column
    // weren't present at all, misclassifying this as TRANSACTION. The fix reads presence
    // (balanceAfter != null) instead of magnitude.

    @Test
    void normalize_classifiesAsBalanceMarker_whenClosingBalanceRowIsEntirelyZero() {
        Map<String, String> row = rowOf(
                "Date", "31/07/2026", "Description", "CLOSING BALANCE",
                "Withdrawals", "0.00", "Deposits", "0.00", "Balance", "0.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.BALANCE_MARKER);
        assertThat(staged.balanceAfter()).isEqualByComparingTo("0.00");
    }

    @Test
    void normalize_stillClassifiesAsTransaction_whenGenuineZeroValueTransactionHasNoBalanceColumnAtAll() {
        // Regression guard: the presence-based tiebreaker must not become a blanket
        // "amount == 0 => marker" rule. A genuine zero-value transaction with NO Balance-style
        // column on the row at all (balanceAfter stays null) must still classify TRANSACTION.
        Map<String, String> row = rowOf(
                "Date", "02/07/2026", "Description", "FEE WAIVER",
                "Withdrawals", "0.00", "Deposits", "0.00");

        StagedRow staged = normalizer.normalize(userId, row);

        assertThat(staged).isNotNull();
        assertThat(staged.kind()).isEqualTo(RowKind.TRANSACTION);
        assertThat(staged.amount()).isEqualByComparingTo("0.00");
    }
}
