package com.finora.imports.product;

import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.finora.imports.product.ProductEvidenceCollector.Section;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialProductClassifierTest {

    private final ProductEvidenceCollector collector = new ProductEvidenceCollector();
    private final FinancialProductClassifier classifier = new FinancialProductClassifier(collector);
    private final ProductValidator validator = new ProductValidator();
    private final ProductDiscovery discovery = new ProductDiscovery(collector, classifier, validator);

    @Test
    void aTransactionLedgerWithNoProductMarkersIsASavingsAccount() {
        // The common case by far -- most statements never print the words "savings account" near
        // their table. If this fell through to UNKNOWN the review screen would interrogate the user
        // about every ordinary file they ever upload.
        var result = classifier.classify(Section.of(
                List.of("Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance"),
                List.of("Statement of account"), 42));

        assertThat(result.type()).isEqualTo(FinancialProductType.SAVINGS);
        assertThat(result.isConfident()).isTrue();
    }

    @Test
    void anInstallmentScheduleIsARecurringDepositAndRoutesToInvestments() {
        var result = classifier.classify(Section.of(
                List.of("Number", "Due Date", "Amount Paid", "Due", "Status", "Running balance"),
                List.of("Installment Frequency Monthly", "Rate of Interest", "Maturity Date"), 12));

        assertThat(result.type()).isEqualTo(FinancialProductType.RECURRING_DEPOSIT);
        assertThat(result.type().domain()).isEqualTo(FinancialProductType.Domain.INVESTMENT);
        assertThat(result.type().investmentKind()).isEqualTo("RD");
        assertThat(result.type().hasTransactions())
                .as("an RD's installments already appear on the savings account that funds it")
                .isFalse();
    }

    @Test
    void aMaturitySummaryIsAFixedDepositAndRoutesToInvestments() {
        var result = classifier.classify(Section.of(
                List.of("Principal Amount", "Start Date", "Maturity Date", "Rate of Interest"),
                List.of("Deposit Number"), 3));

        assertThat(result.type()).isEqualTo(FinancialProductType.FIXED_DEPOSIT);
        assertThat(result.type().domain()).isEqualTo(FinancialProductType.Domain.INVESTMENT);
        assertThat(result.type().investmentKind()).isEqualTo("FD");
    }

    @Test
    void aRecurringDepositBeatsAFixedDepositOnTheMaturityDateTheyShare() {
        // The installment field is the only thing separating the two, which is why RD is declared
        // first in ProductHypothesis -- declaration order breaks the tie.
        var result = classifier.classify(Section.of(
                List.of("Due Date", "Installment Paid", "Maturity Date", "Rate of Interest"),
                List.of("Recurring Deposit"), 6));

        assertThat(result.type()).isEqualTo(FinancialProductType.RECURRING_DEPOSIT);
    }

    @Test
    void aSectionWithNoRecognisableMarkersIsUnknownRatherThanGuessed() {
        // The decision that matters most: never invent a product. An unidentified section goes to
        // the user to name once, because a wrong guess writes wrong data into their net worth
        // silently, while UNKNOWN costs one question.
        var result = classifier.classify(Section.of(List.of("Column 1", "Column 2"),
                List.of("Miscellaneous"), 0));

        assertThat(result.type()).isEqualTo(FinancialProductType.UNKNOWN);
        assertThat(result.type().requiresUserConfirmation()).isTrue();
        assertThat(result.type().accountType()).as("nothing is created for an unknown product").isNull();
    }

    @Test
    void aSingleSignalNeverDecidesOnItsOwn() {
        // "no single keyword may decide a product" as an executable rule. A lone maturity field is
        // a deposit-shaped hint and nothing more; with no rate, no principal and no naming to
        // corroborate it, the honest answer is UNKNOWN.
        //
        // The column is "Maturity" rather than "Maturity Date" on purpose: the latter is genuinely
        // TWO facts (a maturity field and a date column), which is corroboration, not a single
        // signal. Testing the rule requires a section that really does offer only one.
        var result = classifier.classify(Section.of(List.of("Maturity"), List.of(), 0));

        assertThat(result.type()).isEqualTo(FinancialProductType.UNKNOWN);
        assertThat(result.explain())
                .anyMatch(line -> line.contains("independent signal"));
    }

    @Test
    void contradictoryEvidenceDisqualifiesRatherThanCostingAPoint() {
        // A section calling itself a deposit while carrying a full ledger is far more likely a
        // savings account whose narration mentions a deposit. The deposit hypothesis is not made
        // marginally less likely by the contradiction -- it is removed from contention.
        var evidence = collector.collect(Section.of(
                List.of("Date", "Narration", "Withdrawals", "Deposits", "Closing Balance"),
                List.of("Fixed Deposit", "Maturity Date"), 30));

        var fd = new FinancialProductClassifier(collector);
        var result = fd.classify(evidence);

        assertThat(result.type())
                .as("the ledger structure wins; the deposit vocabulary is contradicted by it")
                .isNotEqualTo(FinancialProductType.FIXED_DEPOSIT);
    }

    @Test
    void everyClassificationCarriesTheEvidenceBehindIt() {
        var result = classifier.classify(Section.of(
                List.of("Card Number", "Transaction Date", "Description"),
                List.of("Minimum Amount Due", "Total Payment Due", "Credit Limit"), 20));

        assertThat(result.type()).isEqualTo(FinancialProductType.CREDIT_CARD);
        assertThat(result.explain()).isNotEmpty();
        assertThat(result.explain()).anyMatch(line -> line.startsWith("POSITIVE"));
    }

    @Test
    void cardNumberIsNotAnRdNumber() {
        // Regression: "rd number" matched as a raw substring inside "Card Number" (ca-RD NUMBER),
        // classifying a credit card as a recurring deposit. Same failure shape as "Rewards Bill"
        // detecting as the bank SBI.
        var evidence = collector.collect(Section.of(List.of("Card Number"), List.of(), 0));

        assertThat(evidence.factsFor(ProductSignal.PRODUCT_NAME))
                .as("no product is named by the words \"Card Number\" alone")
                .noneMatch(f -> f.named() == FinancialProductType.RECURRING_DEPOSIT);
    }

    @Test
    void aDepositScheduleIsNotALedgerJustBecauseItHasADepositColumn() {
        // The real misclassification this stage split exists for. A fixed-deposit schedule's
        // "Deposit(Mnth)" column is the monthly contribution, not money moving in. One keyword used
        // to be enough to call the whole section a transaction account.
        var evidence = collector.collect(Section.of(
                List.of("Amount(Rs)", "Start Date", "Deposit(Mnth)", "Maturity Date", "Amount(Rs)*"),
                List.of(), 2));

        assertThat(evidence.looksLikeALedger())
                .as("no narration column means no ledger, whatever the amount columns are called")
                .isFalse();
    }

    @Test
    void aProductNamedOnlyInADocumentLevelSummaryCannotDecideASection() {
        // A combined statement's opening summary enumerates every product the customer holds, and
        // that block sits next to whichever table comes first. Naming several products at once is
        // what marks it as document-level: one section is one product.
        var evidence = collector.collect(Section.of(
                List.of("Principal Amount", "Maturity Date", "Rate of Interest"),
                List.of("SAVINGS ACCOUNTS 83413.31", "FIXED DEPOSITS 124053.00",
                        "RECURRING DEPOSITS 20000.00"), 2));

        assertThat(evidence.productNamesAtLeast(EvidenceSource.SECTION_TEXT))
                .as("an enumeration of three products describes the document, not this section")
                .isEmpty();

        var result = classifier.classify(evidence);
        assertThat(result.type())
                .as("structure decides; the leaked \"SAVINGS ACCOUNTS\" phrase does not")
                .isEqualTo(FinancialProductType.FIXED_DEPOSIT);
    }

    @Test
    void nothingIsCreatedFromAClassificationAlone() {
        // The persistence gate. A named-only product with no structural proof is recognised and
        // still refused -- it reaches the review screen instead of creating an account.
        var found = discovery.discover(Section.of(List.of("Folio"), List.of("Mutual Fund"), 0));

        assertThat(found.mayCreateAutomatically()).isFalse();
        assertThat(found.needsReview()).isTrue();
    }

    @Test
    void aValidatedLedgerIsAllowedToCreateAnAccount() {
        var found = discovery.discover(Section.of(
                List.of("Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance"),
                List.of("Opening Balance", "Closing Balance"), 42));

        assertThat(found.type()).isEqualTo(FinancialProductType.SAVINGS);
        assertThat(found.validation().verdict()).isEqualTo(ProductValidator.Verdict.VALIDATED);
        assertThat(found.mayCreateAutomatically()).isTrue();
        assertThat(found.report()).isNotEmpty();
    }

    @Test
    void theRealCombinedStatementsThreeSectionsAreNotAllAccounts() {
        // Against the captured trace of a real HDFC combined statement -- the document that exposed
        // all three sections being offered as accounts.
        var doc = new PdfTableLocator().locateAll(PdfTrace.load("hdfc-composite-deposit-schedules"), null);

        List<FinancialProductType> detected = doc.sections().stream()
                .map(s -> classifier.classify(Section.of(
                        s.rows().isEmpty() ? List.of() : List.copyOf(s.rows().get(0).keySet()),
                        s.auxiliaryText(), s.rows().size())).type())
                .toList();

        assertThat(detected).hasSize(3);
        assertThat(detected.get(0))
                .as("the savings account, the only section carrying a ledger")
                .isEqualTo(FinancialProductType.SAVINGS);

        // The two deposit schedules must not be offered as accounts. They previously classified as
        // SAVINGS -- confidently, and wrongly -- because a single "Deposit(Mnth)" column satisfied
        // the old any-one-keyword ledger test.
        //
        // Note what this asserts and what it does not. Neither section reaches FIXED_DEPOSIT here,
        // because this fixture's own capture redacted the words "Maturity" and "Mnth" out of its
        // column headers before it was committed (PdfTraceRedactor's allowlist had no deposit
        // vocabulary at the time -- since fixed, but a committed trace cannot be un-redacted). With
        // that vocabulary gone there is genuinely nothing in these sections to identify them, and
        // UNKNOWN is the correct answer to evidence that is not there. Re-capturing this trace with
        // the current redactor is what would let it assert FIXED_DEPOSIT.
        assertThat(detected.get(1).domain())
                .as("a deposit schedule is not an account")
                .isNotEqualTo(FinancialProductType.Domain.ACCOUNT);
        assertThat(detected.get(2).domain())
                .as("a deposit schedule is not an account")
                .isNotEqualTo(FinancialProductType.Domain.ACCOUNT);
    }
}
