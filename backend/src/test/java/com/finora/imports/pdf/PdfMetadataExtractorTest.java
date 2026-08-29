package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GRID_METADATA_TRAILING_LABEL: an account-details grid where each row's VALUE comes BEFORE its
 * own label on the same line (e.g. "100200300400599 Account Number", "ABCD0123456 IFSC" --
 * genericized per the Synthetic Fixture Policy, see the engineering principles doc), the reverse
 * of the ordinary "Label: Value" shape {@code ACCOUNT_HOLDER}/{@code ACCOUNT_NUMBER}/{@code IFSC}
 * already handle. Modeled on a real Union Bank of India statement (see
 * {@code PdfMetadataExtractor}'s own doc comments for the exact lines and reasoning), but the
 * underlying capability -- a metadata grid whose values precede their labels -- isn't specific to
 * that bank.
 */
class PdfMetadataExtractorTest {

    private final PdfMetadataExtractor extractor = new PdfMetadataExtractor();

    @Test
    void extract_stillHandlesTheOrdinaryLabelThenValueShape_unaffectedByTheNewFallbacks() {
        var metadata = extractor.extract(List.of(
                "Account Holder Name: JOHN DOE",
                "Account Number: 000123456789",
                "Branch Name: MG ROAD BRANCH",
                "IFSC: SBIN0001234"));

        assertThat(metadata.accountHolderName()).isEqualTo("JOHN DOE");
        assertThat(metadata.accountNumberMasked()).endsWith("6789");
        assertThat(metadata.branchName()).isEqualTo("MG ROAD BRANCH");
        assertThat(metadata.ifscCode()).isEqualTo("SBIN0001234");
    }

    /**
     * ACCOUNT_PRODUCT_BANNER. A real BOB savings statement never prints the phrase "Account
     * Number" anywhere: its account identity lives only in a per-page product banner that names
     * the product and states the number after a hyphen, with the holder's name leading the same
     * line. {@code PdfTableLocator} already recognises this exact shape (SECTION_MARKER plus
     * ACCOUNT_NUMBER_IN_MARKER) and keeps the first such banner in the section's auxiliary text --
     * verified directly against the real document -- so the line reaches this class unchanged and
     * simply matched nothing here.
     *
     * <p>Two details are load-bearing and both come from the real line's measured shape: the
     * holder name PRECEDES the banner, so this pattern must find() rather than match a whole
     * line; and the flattened line carries more than one space before the hyphen, so the
     * separator must be whitespace-tolerant.
     *
     * <p>Digits here are altered, never the real document's -- the geometry and token order are
     * what this test exercises, and the real value has no business in the repository.
     */
    @Test
    void extract_recognizesAnAccountNumber_fromAProductBannerNamingTheAccountType() {
        // Only the token ORDER and the doubled space before the hyphen are corpus-derived.
        var metadata = extractor.extract(
                List.of("ANANYA VERMA SAVINGS ACCOUNT  - 41870200031276")); // synthetic-ok: invented value and name

        assertThat(metadata.accountNumberMasked()).endsWith("1276");
    }

    /** The same banner shape for a product other than savings, proving the tier keys off the
     *  shared ACCOUNT_PRODUCT_LABELS vocabulary rather than one hardcoded word. */
    @Test
    void extract_recognizesAProductBannerAccountNumber_forANonSavingsProduct() {
        var metadata = extractor.extract(
                List.of("CURRENT ACCOUNT - 90441200556613")); // synthetic-ok: invented, no corpus document supplied it

        assertThat(metadata.accountNumberMasked()).endsWith("6613");
    }

    /** A banner with no number states a product, not an identity -- it must not resolve, and must
     *  not leave a partial or fabricated value behind. */
    @Test
    void extract_doesNotResolveAnAccountNumber_fromAProductBannerCarryingNoDigits() {
        var metadata = extractor.extract(List.of("SAVINGS ACCOUNT - PREMIUM TIER"));

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    /** Precedence: the banner tier is last, so a document that already stated its number the
     *  ordinary way keeps that value even when a banner is also present. */
    @Test
    void extract_prefersAnExplicitAccountNumberLabel_overAProductBannerOnAnotherLine() {
        var metadata = extractor.extract(List.of(
                "Account Number: 000123456789", // synthetic-ok: reuses this file's existing placeholder
                "ANANYA VERMA SAVINGS ACCOUNT  - 41870200031276")); // synthetic-ok: invented, as above

        assertThat(metadata.accountNumberMasked()).endsWith("6789");
    }

    @Test
    void extract_recognizesAnAccountNumber_whenTheValuePrecedesItsLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("100200300400599 Account Number"));

        assertThat(metadata.accountNumberMasked()).endsWith("0599");
    }

    /**
     * Phase 1C: real credit-card statements speak of a CARD number, not an "Account Number" --
     * verified against real HDFC and Kotak statements, neither of which ever uses the phrase
     * ACCOUNT_NUMBER above looks for at all. The masked value is stored EXACTLY as printed (no
     * re-masking, no reconstructing which digits are hidden), which {@code isEqualTo} here checks
     * literally rather than the looser {@code endsWith} the plain-digit ACCOUNT_NUMBER tests above
     * use -- there is no unmasked full number to test the last-4 tail of.
     */
    @Test
    void extract_recognizesACreditCardNumber_labelledCreditCardNo() {
        var metadata = extractor.extract(List.of("Credit Card No. XXXX XXXX XXXX 1234"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX 1234");
    }

    @Test
    void extract_recognizesACreditCardNumber_labelledPrimaryCardNumber() {
        var metadata = extractor.extract(List.of("Primary Card Number XXXX XXXX XXXX 5678"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX 5678");
    }

    /**
     * The real Kotak shape: unrelated text ("(Principal Outstanding)") precedes the label on the
     * same line, which the start-anchored ACCOUNT_NUMBER-style pattern could never tolerate --
     * this is the same-line-anywhere fallback ({@code CARD_NUMBER_LABEL} found via {@code find()},
     * not anchored to the start), the same contract the payment-due-date same-line fallback uses.
     */
    @Test
    void extract_recognizesACardNumber_whenUnrelatedTextPrecedesTheLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("(Principal Outstanding) Primary Card Number XXXX XXXX XXXX 9012"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX 9012");
    }

    /**
     * The real HDFC shape: the value comes BEFORE its label (the same "value, then label" order
     * ACCOUNT_NUMBER_TRAILING_LABEL already handles), but the cardholder's name trails the label
     * on the same line too -- ACCOUNT_NUMBER_TRAILING_LABEL's own end-of-line anchor could never
     * tolerate that. CARD_NUMBER_TRAILING_LABEL is the same shape widened to allow trailing text.
     */
    @Test
    void extract_recognizesACardNumber_whenTrailingTextFollowsTheLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("XXXX XXXX XXXX 3456 Credit Card No. JOHN DOE"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX 3456");
    }

    /**
     * Phase 1C.1: the real SBI shape -- the label sits alone on its own line, and the masked
     * value is on the very next line entirely (a genuine multi-line grid, the same "label row,
     * then a value row" shape GRID_DUE_DATE_LABEL's own fallback already reads for payment due
     * date). SBI's real card number also reveals only its last 2 digits, not the usual 4 -- the
     * reason the digit-count floor was lowered from 4 to 2, verified safe across the full real
     * corpus (see looksLikeCardOrAccountNumber's own doc comment).
     */
    @Test
    void extract_recognizesACardNumber_onATrailingLineWhenTheLabelLineItselfHasNoValue() {
        var metadata = extractor.extract(List.of(
                "JOHN DOE Credit Card Number",
                "XXXX XXXX XXXX XX56"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX XX56");
    }

    /** A trailing colon on the label's own line is ordinary formatting, not evidence the label
     *  was merely mentioned in passing -- must not block the multi-line grid fallback the way
     *  genuine trailing prose correctly does (see the mid-sentence negative test below). */
    @Test
    void extract_recognizesACardNumber_onATrailingLineWhenTheLabelLineEndsWithAColon() {
        var metadata = extractor.extract(List.of(
                "Credit Card Number:",
                "XXXX XXXX XXXX XX78"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX XX78");
    }

    /**
     * Phase 1C.1: a real HSBC statement's own account-number field is fully unmasked (a summary
     * table column, not a masked card field) -- verified via direct visual confirmation against
     * the rendered PDF. A mask-character requirement was considered for grid-derived candidates
     * specifically and rejected because it would have rejected this genuine match, not just
     * noise: label proximity and findGridValue's own narrow search window are what make the grid
     * fallback safe, not an assumption about how a bank chooses to print the value.
     */
    @Test
    void extract_recognizesAFullyUnmaskedCardNumber_onATrailingLineViaTheGridFallback() {
        var metadata = extractor.extract(List.of(
                "Account Number",
                "123456789012")); // synthetic-ok

        assertThat(metadata.accountNumberMasked()).isEqualTo("••••9012");
    }

    /**
     * The grid fallback still applies looksLikeCardOrAccountNumber, the same as every other
     * matching path -- a short, non-identifying token on the label's trailing line (too short to
     * be a real card/account number, e.g. a page or note number) must not be picked up just
     * because it happens to sit where the real value would.
     */
    @Test
    void extract_doesNotMatchATooShortTokenOnTheGridsTrailingLine() {
        var metadata = extractor.extract(List.of(
                "Credit Card Number",
                "12"));

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    /**
     * Bug fix, caught by a real-corpus sweep before this shipped: the grid fallback used to fire
     * whenever CARD_NUMBER_LABEL matched anywhere on a line with no same-line value, with no check
     * on WHY the same-line search failed. That let an incidental mention of "account number" or
     * "card number" buried mid-sentence in unrelated prose trigger a 3-line forward scan that could
     * land on some unrelated nearby digit-shaped token -- confirmed against 3 real documents this
     * way (a credit-card statement and two savings statements), every one of them the label
     * mid-sentence with several more words following it on the same line. The real SBI line this
     * fallback exists for has the label as the LAST thing on its line; requiring that -- not just
     * "the label matched somewhere" -- is what closes this without touching the same-line-anywhere
     * path (Kotak's shape) at all, since that path already requires a real value to follow.
     */
    @Test
    void extract_doesNotMatchAnUnrelatedNumberOnTheNextLine_whenTheLabelIsMidSentenceNotItsOwnLine() {
        var metadata = extractor.extract(List.of(
                "Please update your account number if it has recently changed.",
                "Reference: 1234567890")); // synthetic-ok

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    /**
     * F22 (extraction-coverage-audit.md, real-corpus follow-up): the same-line-anywhere fallback's
     * unanchored {@code CARD_NUMBER_LABEL} match also fires on an incidental "card number" mention
     * buried in unrelated instructional prose, and {@code firstMatchAfter}'s same-line search then
     * had no bound on how far past the label it would look -- an unrelated digit run appearing
     * later in the same sentence (a phone number, an unrelated code) was captured as if it were the
     * card number. Confirmed on two real statements this way: one where the label sat inside a
     * sentence about the number's own digit count, another inside an SMS-instruction sentence whose
     * only digit run was a customer-service phone number. Genericized per the Synthetic Fixture
     * Policy -- the shape being tested is "label mid-sentence, unrelated digits later on the same
     * line," not either bank's specific wording.
     */
    @Test
    void extract_doesNotMatchAnUnrelatedNumberOnTheSameLine_whenProseSeparatesTheLabelFromIt() {
        var metadata = extractor.extract(List.of(
                "Please quote your complete card number when calling. For help call 9876543210.")); // synthetic-ok

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    /**
     * The positive counterpart to the test above, restated explicitly so the fix can't be
     * over-tightened into rejecting Kotak's own real shape: a label immediately followed by its
     * value (only whitespace between them) must still match, even with unrelated text BEFORE the
     * label on the same line -- this is a restatement of
     * {@link #extract_recognizesACardNumber_whenUnrelatedTextPrecedesTheLabelOnTheSameLine()},
     * kept here so both tests are visible together during the F22 fix.
     */
    @Test
    void extract_stillMatchesTheSameLineValue_whenOnlyWhitespaceSeparatesLabelFromValue() {
        var metadata = extractor.extract(List.of("Primary Card Number XXXX XXXX XXXX 9012"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("XXXX XXXX XXXX 9012");
    }

    /**
     * The label identifies the field -- not the value's shape. A masked-looking number with no
     * recognized card/account-number label anywhere near it must never be picked up, however
     * identifier-shaped it looks; otherwise this would regress into exactly the "find any
     * masked-looking number in the document" design this class deliberately avoids.
     */
    @Test
    void extract_doesNotMatchAMaskedLookingReferenceNumber_withNoRecognizedLabelNearby() {
        var metadata = extractor.extract(List.of("Reference Number: XXXX XXXX XXXX 7890"));

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    @Test
    void extract_leavesAccountNumberMaskedNull_whenNoCardOrAccountNumberIsPresentAtAll() {
        var metadata = extractor.extract(List.of("Statement Period: 01/06/2026 to 30/06/2026"));

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    @Test
    void extract_recognizesAnIfscCode_byItsDistinctiveShape_evenMergedWithAnUnrelatedField() {
        // The real statement's IFSC line is merged with an unrelated Email field by the time it
        // reaches this class (both landed on the same extracted line) -- IFSC's fixed shape (4
        // letters, a literal 0, 6 more alphanumerics) is found directly, independent of any label
        // or of what else shares its line.
        var metadata = extractor.extract(List.of("M***************0@GMAIL.COM Email id ABCD0123456 IFSC"));

        assertThat(metadata.ifscCode()).isEqualTo("ABCD0123456");
    }

    @Test
    void extract_recognizesAnAccountHolderName_fromAnAccountNameFieldEndingTheLine() {
        // The preceding "3,BEHIND" is itself capitalized ("BEHIND") -- the 3-word cap on the
        // captured name is what keeps it out of the result. (Name genericized per the Synthetic
        // Fixture Policy -- see the engineering principles doc -- this test originally quoted a
        // real account holder's name verbatim.)
        var metadata = extractor.extract(List.of(
                "58 ROAD NO 3,BEHIND  KAVITA RAMESH DESAI Account Name"));

        assertThat(metadata.accountHolderName()).isEqualTo("KAVITA RAMESH DESAI");
    }

    /**
     * Bug fix, missing coverage found while completing this fix: ACCOUNT_NAME_TRAILING_LABEL's
     * leading {@code (?i)} used to case-insensitize the WHOLE pattern, not just the "Account Name"
     * label text -- so the captured-name group's own {@code [A-Z]} requirement was never actually
     * enforced, and a lowercase phrase ending in "account name" could still have its preceding
     * lowercase words captured as if they were a real name. Constructed to demonstrate the
     * mechanism directly (not verified against a specific real statement, unlike the sibling test
     * above): under the old pattern, "please update your" (three lowercase words) would have
     * satisfied the case-insensitized capture group and been returned as the account holder.
     * {@code (?i:...)} now scopes case-insensitivity to just the label; the captured portion is
     * case-sensitive again, so no word here starting lowercase can open a match at all.
     */
    @Test
    void extract_doesNotCaptureLowercaseWords_asAnAccountHolderName_viaAccountNameTrailingLabel() {
        var metadata = extractor.extract(List.of("please update your account name"));

        assertThat(metadata.accountHolderName()).isNull();
    }

    @Test
    void extract_recognizesAStatementPeriod_whenTheDatesPrecedeTheLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("01-05-2026 to 31-07-2026 Statement Period"));

        assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
        assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
    }

    @Test
    void extract_doesNotMisreadATwoColumnSectionHeader_asABranchNameField() {
        // "Branch Address" here is a section header ("Branch Address" | "Statement Details" side
        // by side), not a genuine "Branch: <name>" line -- a real regression found against the
        // same statement: the bare "Branch" match used to consume "Address Statement Details" as
        // if it were the branch name.
        var metadata = extractor.extract(List.of("Branch Address Statement Details"));

        assertThat(metadata.branchName()).isNull();
    }

    @Test
    void extract_recordsGridMetadataTrailingLabel_onDocumentContext_whenATrailingLabelLineMatches() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfMetadataExtractor");

        extractor.extract(List.of("100200300400599 Account Number"), ctx);

        assertThat(ctx.capabilities()).extracting(a -> a.capability()).contains("GRID_METADATA_TRAILING_LABEL");
    }

    @Test
    void extract_doesNotRecordGridMetadataTrailingLabel_forTheOrdinaryLabelFirstShape() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfMetadataExtractor");

        extractor.extract(List.of("Account Holder Name: JOHN DOE"), ctx);

        assertThat(ctx.capabilities()).extracting(a -> a.capability()).doesNotContain("GRID_METADATA_TRAILING_LABEL");
    }

    // CARD_ENDING_DIGITS: a credit card's identity stated inside an ordinary sentence rather than
    // any "Label: Value" or grid shape -- modeled on a real AU Small Finance Bank credit-card
    // statement's own "Statement for your credit card ending with <4 digits>" phrasing (digits
    // genericized per the Synthetic Fixture Policy). Only the last 4 digits are ever known this
    // way, so this always produces a masked identity directly, never a full unmasked number.

    @Test
    void extract_recognizesACardEndingDigitsSentence_asAMaskedAccountIdentity() {
        var metadata = extractor.extract(List.of(
                "Statement for your credit card ending with 4321 (19 Mar - 18 Apr 2026)"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("••••4321");
    }

    @Test
    void extract_recordsCardEndingDigitsIdentity_onDocumentContext_whenTheSentenceMatches() {
        DocumentContext ctx = new DocumentContext("PDF", "PdfMetadataExtractor");

        extractor.extract(List.of("Statement for your credit card ending with 4321"), ctx);

        assertThat(ctx.capabilities()).extracting(a -> a.capability()).contains("CARD_ENDING_DIGITS_IDENTITY");
    }

    @Test
    void extract_preferAnEarlierLabelledAccountNumber_overALaterCardEndingDigitsSentence() {
        // Same "first field found wins" discipline every other guarded assignment in this class
        // already follows -- a later, unrelated "ending with" mention (e.g. a linked debit card
        // referenced deep in a T&C appendix) must not override an already-found real identity.
        var metadata = extractor.extract(List.of(
                "Account Number: 000123456789", // synthetic-ok
                "your linked debit card ending with 9999 is separately governed by..."));

        assertThat(metadata.accountNumberMasked()).endsWith("6789");
    }

    /**
     * Bug fix, found during self-review before this ever reached a real document: the pattern used
     * to match bare "card ending with/in", not specifically "credit card ending with/in". Since
     * this scans the WHOLE document's auxiliary text with the same guarded first-match discipline
     * every field here follows, a savings/current-account statement that mentions a linked DEBIT
     * card in passing -- with no other Account Number field ever appearing at all -- would have
     * pinned accountNumberMasked to the debit card's digits, a real account misidentified by its
     * unrelated linked card. Requiring the literal phrase "credit card" closes this without
     * narrowing AU's own real phrasing at all.
     */
    @Test
    void extract_doesNotMatchABareDebitCardMention_whenNoAccountNumberFieldExistsAtAll() {
        var metadata = extractor.extract(List.of(
                "your linked debit card ending with 9999 is separately governed by..."));

        assertThat(metadata.accountNumberMasked()).isNull();
    }

    // LEADING_NAME_LINE: real-document-evidenced (a Bank of Baroda savings account statement, an
    // Axis Bank Neo Rupay credit card statement, and a Kotak Mahindra Bank savings statement --
    // three different banks) -- all put the holder's plain name as one of the document's first
    // few pre-table lines, with no label anywhere identifying it as such.

    @Test
    void extract_recognizesAnAccountHolderName_fromAnUnlabeledFirstLine_withACourtesyTitle() {
        var metadata = extractor.extract(List.of("MR. JOHN DOE", "Some other line", "Account Number: 12345"));

        assertThat(metadata.accountHolderName()).isEqualTo("MR. JOHN DOE");
    }

    @Test
    void extract_recognizesAnAccountHolderName_fromAnUnlabeledFirstLine_withNoTitleAtAll() {
        var metadata = extractor.extract(List.of("JOHN DOE", "Some other line"));

        assertThat(metadata.accountHolderName()).isEqualTo("JOHN DOE");
    }

    @Test
    void extract_doesNotMisreadAKnownBankNameAsTheAccountHolder_evenThoughItShapeMatchesEqually() {
        // "AXIS BANK" shape-matches a plausible name just as well as "JOHN DOE" does (two
        // capitalized words, no digits) -- only the BankRegistry check tells them apart.
        var metadata = extractor.extract(List.of("AXIS BANK", "Neo Rupay Credit Card Statement"));

        assertThat(metadata.accountHolderName()).isNull();
    }

    /**
     * Bug fix: LEADING_NAME_LINE's leading {@code (?i)} used to case-insensitize the WHOLE
     * pattern, including the {@code [A-Z]} the "2-4 capitalized words" requirement depends on -- so
     * ordinary lowercase prose (two words, an optional trailing period) shape-matched exactly as
     * well as a real name. Verified against a real ICICI credit-card statement: an unrelated
     * disclosure sentence left an all-lowercase trailing fragment (two words, a trailing period) as
     * its own extracted line, which -- despite being entirely lowercase -- satisfied the old
     * pattern and was captured as the account holder. Input below is a generic phrase with the same
     * shape, genericized per the Synthetic Fixture Policy rather than quoting the real document.
     */
    @Test
    void extract_doesNotMisreadLowercaseProse_asAnAccountHolderName_viaTheLeadingLineFallback() {
        var metadata = extractor.extract(List.of("please disregard this."));

        assertThat(metadata.accountHolderName()).isNull();
    }

    @Test
    void extract_recognizesAnAccountHolderName_pastGenericTitleAndDateRangeLines() {
        // Bug fix: verified against a real Kotak Mahindra Bank statement, whose first two
        // pre-table lines are a generic title ("Account Statement") and a date range before the
        // holder's name appears on the third -- the original i==0-only restriction missed this
        // real file entirely. "Account Statement" itself shape-matches the LEADING_NAME_LINE
        // pattern just as well as a real name does (two capitalized words, no digits) --
        // LEADING_TITLE_WORDS is what correctly skips it and keeps scanning to the real name.
        var metadata = extractor.extract(List.of(
                "Account Statement", "01 Jul 2026 - 31 Jul 2026", "Rahul Verma", "CRN xxxxxx357"));

        assertThat(metadata.accountHolderName()).isEqualTo("Rahul Verma");
    }

    @Test
    void extract_doesNotApplyTheLeadingNameLineFallback_beyondTheSearchWindow() {
        // Five filler lines that each contain a digit (so none of them shape-match
        // LEADING_NAME_LINE themselves -- it requires letters-only words) push the real name to
        // index 5, past LEADING_NAME_LINE_SEARCH_WINDOW (5, i.e. valid indices 0-4 only).
        var metadata = extractor.extract(List.of(
                "Line 1", "Line 2", "Line 3", "Line 4", "Line 5", "JOHN DOE"));

        assertThat(metadata.accountHolderName()).isNull();
    }

    @Test
    void extract_prefersAnExplicitlyLabeledHolderName_overTheLeadingLineFallback() {
        var metadata = extractor.extract(List.of("Account Holder Name: JANE ROE"));

        assertThat(metadata.accountHolderName()).isEqualTo("JANE ROE");
    }

    @Test
    void extract_recognizesCustomerNameAsAnAccountHolderLabelSynonym() {
        // Bug fix: verified against a real PNB ONE statement, whose "Customer Details" panel
        // labels the account holder "Customer Name:" rather than any "Account Holder" variant.
        var metadata = extractor.extract(List.of("Customer Name: ANIL KUMAR"));

        assertThat(metadata.accountHolderName()).isEqualTo("ANIL KUMAR");
    }

    @Test
    void extract_recognizesBareNameAsAnAccountHolderLabelSynonym() {
        // Bug fix: verified against a real Canara Bank e-passbook, whose account-details panel
        // labels the holder with the single word "Name" (no colon) -- previously fell through to
        // LEADING_NAME_LINE, which wrongly captured "Name PRIYA NAIR" (the label ITSELF
        // included in the name) since "name" wasn't in LEADING_TITLE_WORDS either.
        var metadata = extractor.extract(List.of("Name PRIYA NAIR"));

        assertThat(metadata.accountHolderName()).isEqualTo("PRIYA NAIR");
    }

    @Test
    void extract_doesNotMisreadAWordMerelyStartingWithName_asTheBareNameLabel() {
        // The trailing "\b" on the bare "Name" alternative is what keeps "Named"/"Nameplate"/
        // etc. from being misread as the label -- without it, labelPattern's own permissive
        // "zero separator required" shape would let "Named" match too.
        var metadata = extractor.extract(List.of("Named beneficiary: JOHN DOE"));

        assertThat(metadata.accountHolderName()).isNull();
    }

    // Multi-column payment-summary grid (real Axis Bank Neo Rupay evidence) -- see
    // PdfMetadataExtractor.GRID_DUE_DATE_LABEL/GRID_CREDIT_LIMIT_LABEL's own doc comments.

    @Test
    void extract_findsPaymentDueDate_inAMultiColumnGrid_skippingTheStatementPeriodRangeOnTheSameRow() {
        var metadata = extractor.extract(List.of(
                "Total Payment Due Minimum Payment Due Statement Period Payment Due Date Statement Generation Date",
                "12,345.67 Dr 500.00 Dr 01/06/2026 - 30/06/2026 20/07/2026 30/06/2026"));

        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 7, 20));
    }

    /** Defensive coverage: an ordinal-suffixed date ("04th Aug 2026") returns null from
     *  {@code CsvParser.parseDate} unless stripped first -- fixed at the parser layer (see
     *  {@code CsvParserTest.parseDate_retriesWithAnOrdinalDaySuffixStripped}), asserted here too
     *  so the label-matching layer is proven to carry the fix end to end. */
    @Test
    void extract_recognizesPaymentDueDate_writtenWithAnOrdinalDaySuffix() {
        var metadata = extractor.extract(List.of("Payment Due Date: 04th Aug 2026"));

        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 4));
    }

    /**
     * Real credit-card statement evidence: a due-date UI element's own "Pay Now" button text was
     * merged onto the same extracted line AHEAD of the "Payment due date" label -- the same-line
     * anchored {@code PAYMENT_DUE_DATE} pattern requires the label at the very start of the line,
     * so it never matched at all. Not a date-format problem (see the ordinal-suffix test above,
     * a different real cause on a different document) -- the label simply isn't first. Invented
     * trailing UI text ("Pay Now"/an amount/a section header) reproduces the shape without using
     * the real statement's own wording.
     */
    @Test
    void extract_recognizesPaymentDueDate_whenUnrelatedButtonTextPrecedesTheLabelOnTheSameLine() {
        var metadata = extractor.extract(List.of("Pay Now Payment due date 15 Sep 2026 ₹0.00 EMIs"));

        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
    }

    /** The same-line fallback must still yield to the genuine multi-line grid shape
     *  ({@link #extract_findsPaymentDueDate_inAMultiColumnGrid_skippingTheStatementPeriodRangeOnTheSameRow})
     *  when the label's own line has no date-shaped value at all -- proving the two fallbacks are
     *  additive, not one replacing the other. */
    @Test
    void extract_stillFindsPaymentDueDate_onATrailingLineWhenTheLabelLineItselfHasNoDate() {
        var metadata = extractor.extract(List.of(
                "Due Date",
                "15 Sep 2026"));

        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 15));
    }

    /** Real Kotak Mahindra Bank credit-card statement evidence: no "Due Date" label anywhere near
     *  the due date at all, stated instead as a plain address-block sentence. See
     *  PAYMENT_DUE_DATE_SENTENCE's own doc comment. */
    @Test
    void extract_recognizesPaymentDueDate_statedAsAPlainSentenceWithNoDueDateLabelAnywhere() {
        var metadata = extractor.extract(List.of("Remember to pay by 02-Apr-2026"));

        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 2));
    }

    /** Bug fix: PAYMENT_DUE_DATE_SENTENCE used to capture everything after "pay by" as one greedy
     *  group and hand the whole thing to a parser that requires an exact full-string match -- any
     *  trailing punctuation or words after the date silently failed every format, reproducing the
     *  exact "field stayed null" bug this pattern exists to fix, just for a slightly different real
     *  phrasing than the one evidenced trace happens to have. Two independent real-shaped trailers:
     *  a bare period, and a following clause. */
    @Test
    void extract_recognizesPaymentDueDate_inASentenceWithTrailingPunctuationOrText() {
        var withTrailingPeriod = extractor.extract(List.of("Remember to pay by 02-Apr-2026."));
        var withTrailingClause =
                extractor.extract(List.of("Remember to pay by 02-Apr-2026 to avoid late fees"));

        assertThat(withTrailingPeriod.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 2));
        assertThat(withTrailingClause.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 4, 2));
    }

    /** Negative case: a due-date mention with no date-shaped value anywhere nearby (an
     *  explanatory sentence, not a real field) must stay null rather than guessing. */
    @Test
    void extract_doesNotInventAPaymentDueDate_fromAnExplanatorySentenceMentioningTheWords() {
        var metadata = extractor.extract(List.of(
                "Interest is charged if the total amount due is not paid by the payment due date."));

        assertThat(metadata.paymentDueDate()).isNull();
    }

    @Test
    void extract_findsCreditLimit_inAMultiColumnGrid_notAvailableCreditLimitOnTheSameRow() {
        var metadata = extractor.extract(List.of(
                "Credit Card Number Credit Limit Available Credit Limit Available Cash Limit",
                "123456******7890 100,000.00 85,000.00 10,000.00"));

        assertThat(metadata.creditLimit()).isEqualByComparingTo("100000.00");
    }

    /**
     * Bug fix, verified against a real HDFC "Tata Neu Plus" statement: two compounding real
     * gaps, both exposed by this exact document. First, its Credit Limit is a WHOLE rupee amount
     * with no decimal places ("78,000", not "78,000.00") -- AMOUNT_LIKE originally required a
     * literal decimal suffix. Second, and more subtly: this document's grid header line is
     * "TOTAL CREDIT LIMIT (Including Cash) AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT" -- which
     * fully satisfies the ORDINARY same-line CREDIT_LIMIT pattern (label matches at the start,
     * its greedy trailing "(.+)$" just soaks up "AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT" as
     * if that non-numeric text were the value). The old code committed to that as a failed
     * (null) parse and unconditionally moved to the next line, so the GRID_CREDIT_LIMIT_LABEL
     * fallback below it never even got a chance to run on this line. Fixing only the decimal
     * requirement without ALSO fixing the greedy same-line capture would still have left this
     * real document's credit limit null.
     */
    @Test
    void extract_findsWholeNumberCreditLimit_onARealMultiLabelHeaderLine_thatAlsoSatisfiesTheSameLineLabelPattern() {
        var metadata = extractor.extract(List.of(
                "TOTAL CREDIT LIMIT (Including Cash) AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT",
                "78,000 76,183 31,200"));

        assertThat(metadata.creditLimit()).isEqualByComparingTo("78000");
    }

    // "First match wins", not "last match wins" -- every primary Label: Value field below now
    // commits only on the first matching line, mirroring how every GRID_*/TRAILING_LABEL fallback
    // already guarded its own assignment. Two of the seven guarded fields are exercised here
    // (rather than all seven near-identically): creditLimit reproduces the real motivating case
    // directly; accountHolderName proves the fix is the shared loop discipline, not something
    // specific to Credit Limit.

    /**
     * Bug fix: every primary "Label: Value" extraction used to commit on EVERY matching line, so
     * whichever occurrence appeared LAST in the document silently won. Verified against a real
     * ICICI credit-card statement whose genuine early Credit Limit field was overwritten by a
     * later, entirely fictional "Credit Limit" figure from the MITC section's worked example of
     * how Minimum Amount Due is calculated.
     */
    @Test
    void extract_keepsTheFirstCreditLimit_notALaterUnrelatedOccurrenceOfTheSameLabel() {
        var metadata = extractor.extract(List.of(
                "Credit Limit: 100000.00",
                "Some unrelated text",
                "Credit Limit: 500.00"));

        assertThat(metadata.creditLimit()).isEqualByComparingTo("100000.00");
    }

    /**
     * Same fix, a different field -- proving "first real field wins" is the shared loop discipline
     * now, not a Credit-Limit-specific special case. A genuine field is stated once, prominently,
     * near the top of a statement; any later occurrence of the same label is either a harmless
     * repeat or unrelated boilerplate, never something that should override an already-found answer.
     */
    @Test
    void extract_keepsTheFirstAccountHolderName_notALaterDuplicateLabelOccurrence() {
        var metadata = extractor.extract(List.of(
                "Account Holder Name: JOHN DOE",
                "Some unrelated text",
                "Account Holder Name: JANE ROE"));

        assertThat(metadata.accountHolderName()).isEqualTo("JOHN DOE");
    }

    /**
     * Bug fix: Statement Period fills TWO fields (start and end) from one match, unlike the other
     * six guarded fields above. A first line whose value doesn't parse as a full "X to Y" range
     * (e.g. missing the "to" separator) must not half-commit -- if it did, the AND-guarded pair
     * would be permanently stuck with one side null, unable to fall through to a later, fully-formed
     * "Statement Period" line that states the whole range together.
     */
    @Test
    void extract_ignoresAPartiallyParseableStatementPeriod_andKeepsALaterFullyFormedOne() {
        var metadata = extractor.extract(List.of(
                "Statement Period: 01-05-2026",
                "Some unrelated text",
                "Statement Period: 01-05-2026 to 31-05-2026"));

        assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
        assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 5, 31));
    }

    // Deferred capability evidence (see the Capability Registry's "Capability Backlog" table in
    // docs/engineering/financial-document-intelligence-principles.md) -- real structural patterns
    // found in a real HDFC "Tata Neu Plus" statement that NEITHER capability above handles, kept
    // here the same way GRID_METADATA_TRAILING_LABEL's own tests are (pure string-matching against
    // extract(List), no rendered PDF needed) so the evidence survives even though no capability was
    // built from it yet -- per "Evidence Before Capability," single-document evidence alone doesn't
    // justify one. Each asserts today's actual (honest) behavior, not a wish -- so if someone later
    // builds a partial fix, these catch it silently producing a WRONG value instead of remaining
    // null, which is the specific failure mode "Don't fix it yet, root-cause it" was written to
    // prevent for the credit-limit case below.

    @Test
    void extract_doesNotYetRecognizeAnAccountHolderName_fromAValueThenLabelThenNameCompositeLine() {
        // Real HDFC line shape (masked card number and name genericized): "<card number> Credit
        // Card No. <NAME>" -- a card number, THEN its own trailing label ("Credit Card No."),
        // THEN an unlabeled name, all on one line. None of the three existing account-holder
        // shapes cover "value, label, name" in sequence: ACCOUNT_HOLDER wants "Label: Value";
        // ACCOUNT_NAME_TRAILING_LABEL wants "<name> Account Name" (the name comes BEFORE its own
        // label, not after someone else's); LEADING_NAME_LINE wants a line with nothing else on
        // it at all.
        //
        // This is deliberately documented as a general STRUCTURAL pattern, not an HDFC quirk --
        // "<value> <trailing label for that value> <trailing, unlabeled value for a DIFFERENT
        // field>" could recur on a loan statement ("Loan Number XXXXXXXX Borrower Name") or
        // another bank's card statement just as easily. Worth watching for a second independent
        // document before generalizing -- see "Evidence before capability."
        var metadata = extractor.extract(List.of("412345XXXXXX6789 Credit Card No. ANIL KUMAR"));

        assertThat(metadata.accountHolderName()).isNull();
    }

    @Test
    void extract_doesNotYetFindCreditLimit_inARealGridWhereAnUnrelatedRowSitsBetweenTheLabelAndItsValue() {
        // Real HDFC lines, in PDFBox's actual extraction order -- the credit-limit label splits
        // across two lines ("TOTAL CREDIT LIMIT" ... "(Including Cash)"), with an UNRELATED row
        // ("AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT MINIMUM DUE DUE DATE") sitting between
        // them, and the value rows arrive in reversed order: the Minimum Due/Due Date VALUES
        // (200.00, 09 Aug 2026) appear BEFORE the Credit Limit VALUES (78,000, 76,183, 31,200),
        // even though their labels appear in the opposite order. Note the amounts are also
        // prefixed with a glued "C" (a Rupee-glyph font-encoding artifact) -- that part is NOT
        // the blocker: CsvParser.parseNumeric already strips it (see its own doc comment). The
        // real blocker is purely the scrambled row/column order.
        //
        // A fix I verified would be actively unsafe rather than merely incomplete: widening
        // GRID_VALUE_SEARCH_WINDOW enough to reach "C78,000" also reaches "C200.00" (Minimum Due)
        // FIRST, since it sits in an intervening row -- naively taking the first amount in the
        // window would silently set creditLimit to 200.00 instead of 78,000.00. Null is the
        // honest, correct result until a real column-aware (not just "nearest amount") grid
        // reader exists -- see the "Capability Backlog" entry for this.
        var metadata = extractor.extract(List.of(
                "TOTAL CREDIT LIMIT",
                "AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT MINIMUM DUE DUE DATE",
                "(Including Cash)",
                "C200.00 09 Aug, 2026",
                "C78,000 C76,183 C31,200"));

        assertThat(metadata.creditLimit()).isNull();
        // What DOES already work on this same real grid, for contrast: Payment Due Date isn't
        // affected by the scrambled Credit Limit columns, since GRID_DUE_DATE_LABEL's own label
        // ("DUE DATE") and its value ("09 Aug, 2026") both sit within one ordinary window-scan.
        assertThat(metadata.paymentDueDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 9));
    }

    /**
     * PNB never labels its account number "Account Number" at all -- it states the number inline
     * in a "Statement of Account:&lt;number&gt; For Period: ..." line (verified against a real PNB
     * savings statement; see {@code pnb-savings-ledger-validation.trace}, genericized here per the
     * Synthetic Fixture Policy). Neither {@code ACCOUNT_NUMBER} nor
     * {@code ACCOUNT_NUMBER_TRAILING_LABEL} recognize this shape, which is why account number
     * extraction previously fell through to null for every PNB statement -- and, downstream, why
     * ProductIdentityResolver could never match a later PNB re-import to the same account (it had
     * neither a full nor a masked number to build a strong key from).
     */
    @Test
    void extract_recognizesAPnbAccountNumber_fromTheStatementOfAccountLine() {
        var metadata = extractor.extract(List.of(
                "Statement of Account:12345678901234 For Period: 01/01/2026 to 31/01/2026")); // synthetic-ok

        assertThat(metadata.accountNumberMasked()).isEqualTo("••••1234");
        assertThat(metadata.accountNumberFullForHashingOnly()).isEqualTo("12345678901234"); // synthetic-ok
    }

    @Test
    void extract_recognizesAPnbAccountNumber_toleratingExtraWhitespaceAroundTheColon() {
        var metadata = extractor.extract(List.of(
                "Statement of Account : 12345678901234 For Period :")); // synthetic-ok

        assertThat(metadata.accountNumberMasked()).isEqualTo("••••1234");
        assertThat(metadata.accountNumberFullForHashingOnly()).isEqualTo("12345678901234"); // synthetic-ok
    }

    /**
     * PDF text extraction often introduces unexpected line breaks -- the label and its value can
     * land on separate lines entirely, the same genuine multi-line-grid shape
     * {@code findGridValue}'s other callers already handle for their own labels.
     */
    @Test
    void extract_recognizesAPnbAccountNumber_whenTheLabelAndValueAreSplitAcrossLines() {
        var metadata = extractor.extract(List.of(
                "Statement of Account:",
                "12345678901234", // synthetic-ok
                "For Period:"));

        assertThat(metadata.accountNumberMasked()).isEqualTo("••••1234");
        assertThat(metadata.accountNumberFullForHashingOnly()).isEqualTo("12345678901234"); // synthetic-ok
    }
}
