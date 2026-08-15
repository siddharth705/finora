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
 * Phase C5.3 — Myntra, hand-written. See {@link MyntraEmailParser}'s own doc comment for why this
 * merchant stays a class rather than a {@link TemplateEmailParser} row like Zomato (C5.3's other
 * new merchant): the return/exchange/refund exclusion this suite specifically exercises.
 */
class MyntraEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final MyntraEmailParser parser = new MyntraEmailParser();

    @Test
    void canParseOnlyClaimsMyntrasAuthenticatedDomain() {
        assertThat(parser.canParse("myntra.com")).isTrue();
        assertThat(parser.canParse("myntra.attacker.example")).isFalse();
        assertThat(parser.canParse("amazon.in")).isFalse();
    }

    @Test
    @DisplayName("an order confirmation is parsed into a receipt with the right amount and date")
    void shouldParseMyntraOrderConfirmation() {
        SanitizedGmailMessage message = load("order-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("myntra.com");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("1699.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 9));
    }

    /** Second fixture uses a dd/MM/yyyy date, unlike the first's "MMMM d, yyyy" -- both must
     *  work, matching Amazon's and Ola's own multi-format coverage. */
    @Test
    @DisplayName("a differently-templated order confirmation still parses")
    void shouldParseMyntraOrderConfirmationInAnAlternateTemplate() {
        SanitizedGmailMessage message = load("order-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("2499.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    @Test
    @DisplayName("a marketing email is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreMyntraMarketingEmail() {
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    /** The exclusion this whole class exists for: a return notification quotes the original
     *  order's own "Order Confirmed" / total / date text inline, and must still not be staged as
     *  a fresh purchase. */
    @Test
    @DisplayName("a return notification is not-a-receipt even though it quotes order text")
    void shouldIgnoreReturnNotificationDespiteQuotedOrderText() {
        SanitizedGmailMessage message = load("return-notification.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a receipt-shaped email with an unparseable total is malformed, not ignored")
    void shouldRejectMalformedAmount() {
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: [[AMOUNT_PLACEHOLDER]]</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-5", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountIsRejectedNotSilentlyTruncated() {
        String hugeDigitRun = "1".repeat(25);
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p>"
                + "<p>Order Total: Rs. " + hugeDigitRun + ".00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-6", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
    }

    /** Extraction fidelity, not business judgment -- same reasoning as AmazonEmailParserTest's
     *  and OlaEmailParserTest's identical cases. */
    @Test
    @DisplayName("a fully-discounted order still parses honestly; the validator decides staging separately")
    void aZeroTotalStillParses() {
        String html = "<p>Order Confirmed</p><p>Order Date: 2026-08-01</p><p>Order Total: Rs. 0.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-7", "myntra.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.ZERO);
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/myntra", fixture));
            return sanitizer.sanitize(gmailMessageId, "myntra.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
