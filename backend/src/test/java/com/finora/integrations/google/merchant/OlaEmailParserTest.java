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
 * Phase C5.2 — Ola, hand-written. {@code TemplateEmailParserTest} covers the identical job (a
 * ride-hailing receipt) through {@link TemplateEmailParser} instead; the two exist side by side
 * specifically so they can be compared.
 */
class OlaEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final OlaEmailParser parser = new OlaEmailParser();

    @Test
    void canParseOnlyClaimsOlasAuthenticatedDomain() {
        assertThat(parser.canParse("olacabs.com")).isTrue();
        assertThat(parser.canParse("olacabs.attacker.example")).isFalse();
        assertThat(parser.canParse("uber.com")).isFalse();
    }

    @Test
    @DisplayName("a ride bill is parsed into a receipt with the right amount and date")
    void shouldParseOlaRideBill() {
        SanitizedGmailMessage message = load("ride-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("olacabs.com");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("190.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 11));
    }

    /** Second fixture uses a dd/MM/yyyy date, unlike the first's ISO date -- both must work,
     *  matching Amazon's own two-date-format coverage. */
    @Test
    @DisplayName("a differently-templated ride bill still parses")
    void shouldParseOlaRideBillInAnAlternateTemplate() {
        SanitizedGmailMessage message = load("ride-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("60.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    @DisplayName("a marketing email is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreOlaMarketingEmail() {
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a receipt-shaped email with an unparseable total is malformed, not ignored")
    void shouldRejectMalformedAmount() {
        String html = "<p>Ride Bill</p><p>Ride Date: 2026-08-01</p><p>Total Fare: [[AMOUNT_PLACEHOLDER]]</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-4", "olacabs.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountIsRejectedNotSilentlyTruncated() {
        String hugeDigitRun = "1".repeat(25);
        String html = "<p>Ride Bill</p><p>Ride Date: 2026-08-01</p>"
                + "<p>Total Fare: Rs. " + hugeDigitRun + ".00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-5", "olacabs.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
    }

    /** Extraction fidelity, not business judgment -- same reasoning as
     *  AmazonEmailParserTest's identical case. */
    @Test
    @DisplayName("a zero fare is parsed honestly; the validator decides staging separately")
    void aZeroFareStillParses() {
        String html = "<p>Ride Bill</p><p>Ride Date: 2026-08-01</p><p>Total Fare: Rs. 0.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-6", "olacabs.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.ZERO);
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/ola", fixture));
            return sanitizer.sanitize(gmailMessageId, "olacabs.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
