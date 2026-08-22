package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase C5 follow-up, 2026-08-22. Fixtures are a real CRED credit-card-bill-payment shape
 * (synthetic bank, card, amount, date — see
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update), run through
 * {@link MerchantEmailSanitizer} exactly as the pipeline will. The two NOT_A_RECEIPT fixtures
 * (bill-generated, payment-due) are the two other real, recurring cred.club email shapes that
 * look receipt-adjacent but represent no completed payment — this parser must never mistake
 * either for a successful transaction.
 */
class CredEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final CredEmailParser parser = new CredEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsCredsAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("cred.club")).isTrue();
        assertThat(parser.canParse("cred.attacker.example")).isFalse();
        assertThat(parser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        CredEmailParser disabledParser = new CredEmailParser();

        assertThat(disabledParser.canParse("cred.club")).isFalse();
    }

    @Test
    @DisplayName("a successful bill payment is parsed with the bank+card as the counterparty")
    void shouldParseSuccessfulPayment() {
        SanitizedGmailMessage message = load("payment-successful.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("cred.club");
        assertThat(receipt.counterpartyName()).isEqualTo("Yes Bank •••• 9042");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("3450.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(receipt.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a bill-generated notice is not-a-receipt -- no money has moved yet")
    void shouldIgnoreBillGeneratedNotice() {
        SanitizedGmailMessage message = load("bill-generated.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a payment-due reminder is not-a-receipt -- no money has moved yet")
    void shouldIgnorePaymentDueReminder() {
        SanitizedGmailMessage message = load("payment-due.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a payment confirmation with no extractable bank/card is malformed, not ignored")
    void shouldRejectMalformedPayment() {
        SanitizedGmailMessage message = load("missing-bank-card.html", "msg-4");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        String html = readFixture(fixture);
        return sanitizer.sanitize(gmailMessageId, "cred.club", html);
    }

    private static String readFixture(String name) {
        try {
            Path path = Path.of("src/test/resources/gmail/cred", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
