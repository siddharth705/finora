package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5.1. Fixtures are real order-confirmation and marketing-email HTML shapes (synthetic order
 * numbers and amounts), run through {@link MerchantEmailSanitizer} exactly as the pipeline will —
 * this class never hands the parser raw HTML, because nothing in the real path does either.
 */
class AmazonEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final AmazonEmailParser parser = new AmazonEmailParser();

    @Test
    void canParseOnlyClaimsAmazonsAuthenticatedDomain() {
        assertThat(parser.canParse("amazon.in")).isTrue();
        assertThat(parser.canParse("amazon.attacker.example")).isFalse();
        assertThat(parser.canParse("myntra.com")).isFalse();
    }

    @Test
    @DisplayName("an order confirmation is parsed into a receipt with the right amount and date")
    void shouldParseAmazonOrder() {
        SanitizedGmailMessage message = load("order-confirmation-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("amazon.in");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("1299.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(receipt.confidence()).isBetween(0.0, 1.0);
    }

    /** The second fixture uses a different date format ("2026-08-12" vs "August 10, 2026") and a
     *  different total label ("Grand Total" vs "Order Total") -- both have to work, because Amazon's
     *  own templates are not consistent about either. */
    @Test
    @DisplayName("a differently-templated order confirmation still parses")
    void shouldParseAmazonOrderInAnAlternateTemplate() {
        SanitizedGmailMessage message = load("order-confirmation-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("2450.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a marketing email is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreAmazonMarketingEmail() {
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    /**
     * A message shaped like a receipt (has the order marker) whose total does not parse -- a
     * template change, not ordinary non-receipt mail. Must be distinct from NOT_A_RECEIPT: this is
     * the "the parser needs updating" signal, and folding it into NOT_A_RECEIPT would hide a broken
     * parser behind ordinary marketing-mail noise.
     */
    @Test
    @DisplayName("a receipt-shaped email with an unparseable total is malformed, not ignored")
    void shouldRejectMalformedAmount() {
        SanitizedGmailMessage message = load("malformed-total.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an amount that looks numeric but overflows a sane receipt is still just parsed data")
    void aZeroAmountReceiptStillParsesRatherThanBeingTreatedSpecially() {
        String html = "<p>Order #123-0000000-0000000</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: Rs. 0.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-5", "amazon.in", html);

        ParserResult result = parser.parse(message);

        // Zero-value orders happen (fully-covered by a gift card, a free promotional item) and are
        // not this parser's decision to filter -- that judgment belongs to the user at review, per
        // the design proposal's confidence-is-informational-only principle.
        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.ZERO);
    }

    /**
     * A trusted domain still bounds who signed the bytes, not what is inside them (C3's own doc
     * comment makes this exact distinction). An oversized digit run must fail the match entirely
     * rather than matching some truncated substring of it -- a truncated match would turn an
     * absurd amount into a wrong, plausible-looking one instead of a rejected one, which is the
     * more dangerous failure mode of the two. This pins that behaviour rather than assuming it.
     */
    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountIsRejectedNotSilentlyTruncated() {
        String hugeDigitRun = "1".repeat(25);
        String html = "<p>Order #123-0000000-0000000</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: Rs. " + hugeDigitRun + ".00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-6", "amazon.in", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        String html = readFixture(fixture);
        return sanitizer.sanitize(gmailMessageId, "amazon.in", html);
    }

    private static String readFixture(String name) {
        try {
            Path path = Path.of("src/test/resources/gmail/amazon", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
