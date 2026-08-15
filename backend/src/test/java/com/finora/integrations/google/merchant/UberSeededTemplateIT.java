package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves V85's seeded row, not a hand-built one — {@link TemplateEmailParserTest} proves the
 * engine works against a template it constructs itself; this proves the actual production data
 * (what {@code TemplateEmailParser} reads at 3am when a real Uber receipt arrives) does too. Same
 * reasoning as {@code AdminTrustedSenderEndpointIT} checking V82's seed: an empty or wrong seed row
 * is indistinguishable from a broken engine without a test that goes through the real row.
 */
class UberSeededTemplateIT extends AbstractIntegrationTest {

    @Autowired private MerchantTemplateRepository templates;

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();

    @Test
    @DisplayName("the migration seeded exactly one enabled template for uber.com")
    void theMigrationSeedsAnEnabledUberTemplate() {
        var template = templates.findByMerchantDomainAndEnabledTrue("uber.com");

        assertThat(template).isPresent();
        assertThat(template.get().getMerchantName()).isEqualTo("Uber");
    }

    @Test
    @DisplayName("a real trip receipt parses correctly through the seeded row, not a test-built one")
    void theSeededTemplateParsesARealTripReceiptShape() {
        TemplateEmailParser parser = new TemplateEmailParser(templates);
        SanitizedGmailMessage message = load("trip-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount().toString()).isEqualTo("255.00");
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/uber", fixture));
            return sanitizer.sanitize(gmailMessageId, "uber.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
