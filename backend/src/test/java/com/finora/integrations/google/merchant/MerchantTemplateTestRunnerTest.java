package com.finora.integrations.google.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The test that actually proves the admin sandbox is not a lookalike: it runs the same field
 * values {@code TemplateEmailParserTest.shouldParseUberTripReceipt} already asserts against, but
 * through {@link MerchantTemplateTestRunner} instead of {@link TemplateEmailParser} directly, and
 * must produce the identical amount and date. If this ever diverges from that test, the sandbox has
 * stopped being "the real logic" and started being a second implementation that can silently
 * disagree with what production actually does.
 */
class MerchantTemplateTestRunnerTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    // A real TemplateEmailParser, not a mock -- the whole point is exercising the real
    // package-private parse(SanitizedGmailMessage, MerchantTemplate) method.
    private final TemplateEmailParser parser = new TemplateEmailParser(mock(MerchantTemplateRepository.class));
    private final ParsedReceiptValidator validator = new ParsedReceiptValidator();
    private final MerchantTemplateTestRunner runner = new MerchantTemplateTestRunner(sanitizer, parser, validator);

    @Test
    @DisplayName("testing the real Uber template fields against the real Uber fixture matches TemplateEmailParserTest exactly")
    void matchesTemplateEmailParserTestExactly() {
        String html = load("trip-receipt-1.html");

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", null, "Total: Rs. {amount}", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.PARSED);
        assertThat(outcome.amount())
                .as("must match TemplateEmailParserTest.shouldParseUberTripReceipt's own assertion exactly")
                .isEqualByComparingTo(new BigDecimal("255.00"));
        assertThat(outcome.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
        assertThat(outcome.confidence()).isEqualTo(0.9);
        assertThat(outcome.violations()).isEmpty();
    }

    @Test
    @DisplayName("a marketing email correctly reports not-a-receipt, with a reason")
    void reportsNotAReceiptWithAReason() {
        String html = load("marketing-email.html");

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", null, "Total: Rs. {amount}", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(outcome.reason()).isNotBlank();
        assertThat(outcome.amount()).isNull();
    }

    @Test
    @DisplayName("a non-receipt marker set in the sandbox correctly excludes an otherwise-valid receipt")
    void nonReceiptMarkerExcludesAnOtherwiseValidReceiptInTheSandbox() {
        String html = load("trip-receipt-1.html");

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", "Trip Fare", "Total: Rs. {amount}", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(outcome.reason()).contains("non-receipt marker");
        assertThat(outcome.amount()).isNull();
    }

    @Test
    @DisplayName("an amount pattern that doesn't appear in the sample is reported as malformed, not silently empty")
    void reportsAnUnmatchedAmountPatternAsMalformed() {
        String html = load("trip-receipt-1.html");

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", null, "Grand Total: Rs. {amount}", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(outcome.reason()).isNotBlank();
    }

    /** A misauthored placeholder must fail loudly here too, matching
     *  {@code TemplateEmailParserTest.aMisconfiguredTemplateIsReportedAsMalformed} -- this is
     *  exactly the mistake the test sandbox exists to catch before an admin activates it. */
    @Test
    @DisplayName("a template missing the {amount} placeholder is reported as malformed, mirroring the real parser")
    void reportsAMisconfiguredTemplateAsMalformed() {
        String html = load("trip-receipt-1.html");

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", null, "Total: Rs. no placeholder here", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(outcome.reason()).contains("misconfigured");
    }

    /** {@link ParsedReceiptValidator} is only reachable on a PARSED outcome, and this proves it is
     *  actually wired in, not skipped -- a syntactically valid match with an implausible amount
     *  must still surface as a violation, not silently report success. */
    @Test
    @DisplayName("a syntactically valid but implausible amount is still reported as parsed, with a violation")
    void surfacesValidatorViolationsOnAnOtherwiseParsedResult() {
        String html = "<html><body>Trip Fare<br>Total: Rs. 99999999.00<br>Trip Date: August 12, 2026</body></html>";

        MerchantTemplateTestRunner.TestOutcome outcome = runner.test(
                "uber.com", "Trip Fare", null, "Total: Rs. {amount}", "Trip Date: {date}", html);

        assertThat(outcome.status()).isEqualTo(ParserResult.Status.PARSED);
        assertThat(outcome.violations())
                .as("99999999.00 exceeds ParsedReceiptValidator.MAX_PLAUSIBLE_AMOUNT")
                .isNotEmpty();
    }

    private String load(String fixture) {
        try {
            return Files.readString(Path.of("src/test/resources/gmail/uber", fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
