package com.finora.imports.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What reaches the database when a verification finding is persisted, and — more importantly — what
 * does not.
 *
 * <p>{@code observability.md} requires scrubbing to live in pure static functions that are tested
 * directly, because <b>scrubbing that silently stops working looks exactly like scrubbing that
 * works</b>: no error, no failing test, rows still being written. The same applies here with one
 * extra edge — the destination is our own database rather than a third party, which makes it easier
 * to justify one more field each time and correspondingly harder to walk back. V59 says so
 * explicitly about the table this data hangs off.
 *
 * <p>Payloads below are the real shapes the four validators emit, not {@code "foo"}. A scrubber
 * tested on synthetic keys proves nothing about the keys it will actually meet.
 */
class ImportVerificationDetailAllowlistTest {

    /** Exactly what {@code BalanceChainValidator.report} puts in a finding's details. */
    private static Map<String, Object> balanceChainDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 124);
        details.put("rowsWithBalance", 124);
        details.put("anchoredOnOpeningBalance", true);
        details.put("discrepancies", List.of(
                Map.of("rowIndex", 17, "expectedBalance", new BigDecimal("48221.50"),
                        "actualBalance", new BigDecimal("47785.50"), "difference", new BigDecimal("436.00")),
                Map.of("rowIndex", 18, "expectedBalance", new BigDecimal("47785.50"),
                        "actualBalance", new BigDecimal("47349.50"), "difference", new BigDecimal("436.00"))));
        return details;
    }

    /** Exactly what {@code StatementTotalsValidator.check} puts in a FAILED finding's details. */
    private static Map<String, Object> statementTotalsDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("openingBalance", new BigDecimal("50000.00"));
        details.put("closingBalance", new BigDecimal("47349.50"));
        details.put("totalCredits", new BigDecimal("1200.00"));
        details.put("totalDebits", new BigDecimal("4286.50"));
        details.put("expectedClosingBalance", new BigDecimal("46913.50"));
        details.put("difference", new BigDecimal("436.00"));
        details.put("lastRowBalance", new BigDecimal("47349.50"));
        details.put("suspectedCause", "ROWS");
        details.put("explanation", "The statement's own closing balance disagrees with the rows.");
        return details;
    }

    @Test
    void noBalanceAmountOrTotalSurvives() {
        Map<String, Object> safe =
                ImportVerificationRecorder.structuralDetailsOf(statementTotalsDetails());

        // Asserted on the WHOLE result rather than field by field. A rebuild that RELOCATED a
        // balance -- into a differently-named key, or nested inside something that survived --
        // would pass every per-field absence check and still put money in the table.
        assertThat(safe)
                .as("only structural facts may be persisted; every monetary field must be absent "
                    + "by construction")
                .containsOnlyKeys("suspectedCause");
        assertThat(safe.get("suspectedCause")).isEqualTo("ROWS");
    }

    @Test
    void aDiscrepancyListBecomesACountAndNeverItsContents() {
        Map<String, Object> safe =
                ImportVerificationRecorder.structuralDetailsOf(balanceChainDetails());

        assertThat(safe).containsOnlyKeys(
                "rowsChecked", "rowsWithBalance", "anchoredOnOpeningBalance", "discrepanciesCount");
        assertThat(safe.get("discrepanciesCount"))
                .as("how many rows disagreed is the diagnostic; which balances they were is "
                    + "statement content")
                .isEqualTo(2);
        // The serialised form is what actually lands in the column, so the absence has to hold
        // there too -- a nested structure could survive a key check and still be written out.
        assertThat(safe.toString()).doesNotContain("48221.50").doesNotContain("436.00");
    }

    @Test
    void theBankSOwnTransactionCountsSurviveButItsTotalsDoNot() {
        // SUMMARY_TOTALS is the framework's strongest rule precisely because a printed COUNT cannot
        // be derived from our reading of the document. Dropping the counts alongside the amounts
        // would persist the rule's verdict while discarding the evidence that makes it worth
        // having.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("printedCreditTotal", new BigDecimal("1200.00"));
        details.put("parsedCreditTotal", new BigDecimal("1200.00"));
        details.put("printedDebitTotal", new BigDecimal("4722.50"));
        details.put("parsedDebitTotal", new BigDecimal("4286.50"));
        details.put("printedDebitCount", 3);
        details.put("parsedDebitCount", 2);
        details.put("mismatches", List.of("debitTotal", "debitCount"));

        Map<String, Object> safe = ImportVerificationRecorder.structuralDetailsOf(details);

        assertThat(safe).containsOnlyKeys("printedDebitCount", "parsedDebitCount", "mismatches");
        assertThat(safe.get("mismatches"))
                .as("which comparisons disagreed is bounded vocabulary, not figures")
                .isEqualTo(List.of("debitTotal", "debitCount"));
        assertThat(safe.toString()).doesNotContain("4722.50");
    }

    @Test
    void anAmbiguousCellSValueNeverLeavesTheRequest() {
        // COLUMN_AMBIGUITY reports the raw cell it could read two ways. That cell is a narration or
        // an amount copied verbatim out of the customer's statement -- the single most direct route
        // from a document into a telemetry table this codebase has.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 124);
        details.put("ambiguousRows", 1);
        details.put("ambiguities", List.of(Map.of(
                "rowIndex", 17, "kind", "BOTH_COLUMNS_POPULATED",
                "column", "Withdrawal", "value", "UPI/ACME STORES/paid")));
        details.put("explanation", "One row could be read as either a debit or a credit.");

        Map<String, Object> safe = ImportVerificationRecorder.structuralDetailsOf(details);

        assertThat(safe).containsOnlyKeys("rowsChecked", "ambiguousRows", "ambiguitiesCount");
        assertThat(safe.toString())
                .as("a narration must not reach the database through a diagnostics field")
                .doesNotContain("ACME");
    }

    @Test
    void aKeyNobodyHasReviewedIsAbsentRatherThanTrusted() {
        // The whole point of rebuilding from an allowlist instead of stripping known-bad fields: a
        // rule added later cannot leak by default. This is the test that fails if someone converts
        // the allowlist into a denylist.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 10);
        // Shaped like an account number and obviously synthetic (repeated-digit runs), which is
        // what check-fixture-hygiene.sh recognises as a deliberate placeholder.
        details.put("someFutureRuleField", "0000111122223333");

        Map<String, Object> safe = ImportVerificationRecorder.structuralDetailsOf(details);

        assertThat(safe).containsOnlyKeys("rowsChecked");
    }

    @Test
    void theFindingIsStillWorthKeepingAfterScrubbing() {
        // The guard observability.md requires: a scrubber that emptied everything would satisfy
        // every safety assertion above and be useless. A persisted BALANCE_CHAIN finding has to
        // still say how much was checked and how much disagreed, or the table answers nothing.
        Map<String, Object> safe =
                ImportVerificationRecorder.structuralDetailsOf(balanceChainDetails());

        assertThat(safe).isNotEmpty();
        assertThat(safe.get("rowsChecked")).isEqualTo(124);
        assertThat(safe.get("discrepanciesCount")).isEqualTo(2);
        assertThat(safe.get("anchoredOnOpeningBalance")).isEqualTo(true);
    }

    @Test
    void aRuleThatCouldNotRunKeepsItsReason() {
        // NOT_APPLICABLE without a reason is unactionable: "no balances printed" and "no rows
        // parsed" call for completely different responses, and the outcome alone cannot tell them
        // apart.
        Map<String, Object> safe = ImportVerificationRecorder.structuralDetailsOf(
                Map.of("reason", "The statement did not state an opening or closing balance."));

        assertThat(safe.get("reason"))
                .isEqualTo("The statement did not state an opening or closing balance.");
    }

    @Test
    void aReasonIsBoundedEvenThoughWeWroteIt() {
        Map<String, Object> safe = ImportVerificationRecorder.structuralDetailsOf(
                Map.of("reason", "x".repeat(500)));

        assertThat(((String) safe.get("reason")).length()).isLessThanOrEqualTo(201);
    }

    @Test
    void anEmptyOrAbsentDetailsMapIsNotAnError() {
        // The bare case. observability.md calls this out specifically: a real NPE shipped here once
        // because every fixture was fully populated.
        assertThat(ImportVerificationRecorder.structuralDetailsOf(null)).isEmpty();
        assertThat(ImportVerificationRecorder.structuralDetailsOf(Map.of())).isEmpty();
    }
}
