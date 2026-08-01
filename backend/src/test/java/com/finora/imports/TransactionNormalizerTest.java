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
        when(categorizationService.suggest(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Other", "default", null, null, null));
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        DuplicateDetector duplicateDetector = new DuplicateDetector(transactionRepository);
        normalizer = new TransactionNormalizer(categorizationService, duplicateDetector);
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
                "Remarks", "UPI/DR/657880538392/Google I/UTIB/gpay-utility@ok/");

        StagedRow result = normalizer.normalize(userId, row);

        assertThat(result).isNotNull();
        assertThat(result.description()).isEqualTo("UPI/DR/657880538392/Google I/UTIB/gpay-utility@ok/");
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
                "Remarks", "UPI/CR/619968934901/AMAN KUM/SBIN/aks199747@oksbi/");

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
                "Remarks", "UPI/DR/657880538392/Google I/UTIB/gpay-utility@ok/");

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
}
