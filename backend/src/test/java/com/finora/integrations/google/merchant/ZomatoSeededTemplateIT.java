package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import com.finora.domain.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V86's seeded row, not a hand-built one — same reasoning as {@link UberSeededTemplateIT}.
 * {@link TemplateEmailParserTest} already proves the engine itself works; this is the second data
 * point (after Uber) that a real merchant's receipt shape, edited as a row rather than a class,
 * holds up in practice.
 */
class ZomatoSeededTemplateIT extends AbstractIntegrationTest {

    @Autowired private MerchantTemplateRepository templates;

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();

    @Test
    @DisplayName("the migration seeded exactly one enabled template for zomato.com")
    void theMigrationSeedsAnEnabledZomatoTemplate() {
        var template = templates.findByMerchantDomainAndEnabledTrue("zomato.com");

        assertThat(template).isPresent();
        assertThat(template.get().getMerchantName()).isEqualTo("Zomato");
    }

    @Test
    @DisplayName("a real order receipt parses correctly through the seeded row")
    void theSeededTemplateParsesARealOrderReceiptShape() {
        TemplateEmailParser parser = new TemplateEmailParser(templates);
        SanitizedGmailMessage message = load("order-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("367.50")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    @DisplayName("a differently-laid-out order receipt with a slash date still parses")
    void theSeededTemplateParsesAnAlternateOrderReceiptLayout() {
        TemplateEmailParser parser = new TemplateEmailParser(templates);
        SanitizedGmailMessage message = load("order-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("210.00")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    @Test
    @DisplayName("a Zomato marketing email is ignored, not staged")
    void theSeededTemplateIgnoresMarketingEmail() {
        TemplateEmailParser parser = new TemplateEmailParser(templates);
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/zomato", fixture));
            return sanitizer.sanitize(gmailMessageId, "zomato.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
