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
 * Phase C5 follow-up, 2026-08-22. Fixtures are a real PhonePe transaction-notification shape
 * (synthetic counterparty name, amount, transaction id, and bank reference — see
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update for how the real
 * shape was verified without ever putting real values in this codebase), run through
 * {@link MerchantEmailSanitizer} exactly as the pipeline will.
 */
class PhonePeEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final PhonePeEmailParser parser = new PhonePeEmailParser();

    @BeforeEach
    void enableParser() {
        ReflectionTestUtils.setField(parser, "enabled", true);
    }

    @Test
    void canParseOnlyClaimsPhonePesAuthenticatedDomainWhenEnabled() {
        assertThat(parser.canParse("phonepe.com")).isTrue();
        assertThat(parser.canParse("phonepe.attacker.example")).isFalse();
        assertThat(parser.canParse("paytm.com")).isFalse();
    }

    @Test
    @DisplayName("disabled by default -- canParse is false until the config property is set")
    void canParseIsFalseWhenNotExplicitlyEnabled() {
        PhonePeEmailParser disabledParser = new PhonePeEmailParser();

        assertThat(disabledParser.canParse("phonepe.com")).isFalse();
    }

    @Test
    @DisplayName("a completed transfer is parsed with the payee as the counterparty")
    void shouldParseSuccessfulTransfer() {
        SanitizedGmailMessage message = load("paid-to-successful.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("phonepe.com");
        assertThat(receipt.counterpartyName()).isEqualTo("Sunrise General Store");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("480.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(receipt.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("a cashback offer is recognised as not-a-receipt, not as a parse failure")
    void shouldIgnoreMarketingMail() {
        SanitizedGmailMessage message = load("cashback-offer.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    @Test
    @DisplayName("a successful-transaction email with no extractable counterparty/amount is malformed, not ignored")
    void shouldRejectMalformedTransfer() {
        SanitizedGmailMessage message = load("missing-paid-to.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.receipt()).isNull();
        assertThat(result.reason()).isNotBlank();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        String html = readFixture(fixture);
        return sanitizer.sanitize(gmailMessageId, "phonepe.com", html);
    }

    private static String readFixture(String name) {
        try {
            Path path = Path.of("src/test/resources/gmail/phonepe", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
