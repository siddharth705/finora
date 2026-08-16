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
 * Phase C5.3 — Booking.com, hand-written. See {@link BookingEmailParser}'s own doc comment for why
 * this merchant stays a class rather than a {@link TemplateEmailParser} row like Zomato: the
 * pay-at-property exclusion this suite specifically exercises.
 */
class BookingEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final BookingEmailParser parser = new BookingEmailParser();

    @Test
    void canParseOnlyClaimsBookingsAuthenticatedDomain() {
        assertThat(parser.canParse("booking.com")).isTrue();
        assertThat(parser.canParse("booking.attacker.example")).isFalse();
        assertThat(parser.canParse("uber.com")).isFalse();
    }

    @Test
    @DisplayName("a paid booking confirmation is parsed into a receipt with the right amount and date")
    void shouldParseBookingConfirmation() {
        SanitizedGmailMessage message = load("booking-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("booking.com");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("8500.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    /** Second fixture uses a dd/MM/yyyy "Booked on" date, unlike the first's "MMMM d, yyyy" --
     *  both must work, matching every other parser's multi-format coverage. Also proves the date
     *  extracted is the charge date, not the check-in/check-out dates the fixture also shows. */
    @Test
    @DisplayName("a differently-templated booking confirmation still parses")
    void shouldParseBookingConfirmationInAnAlternateTemplate() {
        SanitizedGmailMessage message = load("booking-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("4250.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 16));
    }

    @Test
    @DisplayName("a marketing email is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreBookingMarketingEmail() {
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    /** The exclusion this whole class exists for: a pay-at-property confirmation shows a total
     *  price and is otherwise receipt-shaped, but no charge has actually happened. */
    @Test
    @DisplayName("a pay-at-property booking is not-a-receipt even though it shows a total price")
    void shouldIgnorePayAtPropertyBookingDespiteShowingATotal() {
        SanitizedGmailMessage message = load("pay-at-property.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a receipt-shaped email with an unparseable amount is malformed, not ignored")
    void shouldRejectMalformedAmount() {
        String html = "<p>Booking Confirmed</p><p>Booked on: 2026-08-01</p>"
                + "<p>Amount Charged: [[AMOUNT_PLACEHOLDER]]</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-5", "booking.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an implausibly long amount is rejected outright, not truncated to a wrong value")
    void anOversizedAmountIsRejectedNotSilentlyTruncated() {
        String hugeDigitRun = "1".repeat(25);
        String html = "<p>Booking Confirmed</p><p>Booked on: 2026-08-01</p>"
                + "<p>Amount Charged: Rs. " + hugeDigitRun + ".00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-6", "booking.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
    }

    /** Extraction fidelity, not business judgment -- same reasoning as every sibling parser's
     *  identical case. */
    @Test
    @DisplayName("a zero-amount charge still parses honestly; the validator decides staging separately")
    void aZeroAmountStillParses() {
        String html = "<p>Booking Confirmed</p><p>Booked on: 2026-08-01</p>"
                + "<p>Amount Charged: Rs. 0.00</p>";
        SanitizedGmailMessage message = sanitizer.sanitize("msg-7", "booking.com", html);

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.ZERO);
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/booking", fixture));
            return sanitizer.sanitize(gmailMessageId, "booking.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
