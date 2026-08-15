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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase C5.2 — Uber via {@link MerchantTemplate} rather than a class. Same fixtures a hand-written
 * parser would use, run through the generic engine instead, so this is a fair comparison of
 * templating against {@code AmazonEmailParserTest}'s own coverage.
 */
class TemplateEmailParserTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final MerchantTemplateRepository templates = mock(MerchantTemplateRepository.class);
    private final TemplateEmailParser parser = new TemplateEmailParser(templates);

    private static MerchantTemplate uberTemplate() {
        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("uber.com");
        template.setMerchantName("Uber");
        template.setReceiptMarker("Trip Fare");
        template.setAmountPattern("Total: Rs. {amount}");
        template.setDatePattern("Trip Date: {date}");
        template.setEnabled(true);
        return template;
    }

    @Test
    void canParseChecksForAnActiveTemplate() {
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com"))
                .thenReturn(Optional.of(uberTemplate()));
        when(templates.findByMerchantDomainAndEnabledTrue("myntra.com")).thenReturn(Optional.empty());

        assertThat(parser.canParse("uber.com")).isTrue();
        assertThat(parser.canParse("myntra.com")).isFalse();
        assertThat(parser.canParse(null)).isFalse();
    }

    @Test
    @DisplayName("a real trip receipt is parsed into a receipt with the right amount and date")
    void shouldParseUberTripReceipt() {
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com"))
                .thenReturn(Optional.of(uberTemplate()));
        SanitizedGmailMessage message = load("trip-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("uber.com");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("255.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    /** The second fixture uses a different total layout and an ISO date, unlike the first --
     *  both have to work through the SAME template row, the same way Amazon's two fixtures both
     *  work through one hand-written parser. */
    @Test
    @DisplayName("a differently-laid-out trip receipt still parses via the same template")
    void shouldParseAlternateTripReceiptLayout() {
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com"))
                .thenReturn(Optional.of(uberTemplate()));
        SanitizedGmailMessage message = load("trip-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        assertThat(result.receipt().amount()).isEqualTo(Money.of(new BigDecimal("200.50")));
        assertThat(result.receipt().transactionDate()).isEqualTo(LocalDate.of(2026, 8, 13));
    }

    @Test
    void shouldIgnoreUberMarketingEmail() {
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com"))
                .thenReturn(Optional.of(uberTemplate()));
        SanitizedGmailMessage message = load("marketing-email.html", "msg-3");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
        assertThat(result.receipt()).isNull();
    }

    /** Discovery's canParse and extraction's parse are two separate calls, not one atomic
     *  operation (MerchantEmailParser's own contract) -- an admin disabling the template in
     *  between must not crash the run. */
    @Test
    @DisplayName("a template disabled between canParse and parse is treated as not-a-receipt, not a crash")
    void aTemplateDisabledBetweenCanParseAndParseIsHandledGracefully() {
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com")).thenReturn(Optional.empty());
        SanitizedGmailMessage message = load("trip-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.NOT_A_RECEIPT);
    }

    /** A misauthored template (e.g. the {amount} placeholder typo'd away) must fail loudly and
     *  identically for every message, not silently match nothing forever. */
    @Test
    @DisplayName("a misconfigured template is reported as malformed, not silently ignored")
    void aMisconfiguredTemplateIsReportedAsMalformed() {
        MerchantTemplate broken = uberTemplate();
        broken.setAmountPattern("Total: Rs. no placeholder here");
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com")).thenReturn(Optional.of(broken));
        SanitizedGmailMessage message = load("trip-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
        assertThat(result.reason()).contains("misconfigured");
    }

    @Test
    @DisplayName("a receipt-shaped message whose amount pattern doesn't match is malformed")
    void shouldRejectMalformedAmount() {
        MerchantTemplate template = uberTemplate();
        template.setAmountPattern("Grand Total: Rs. {amount}"); // does not appear in the fixture
        when(templates.findByMerchantDomainAndEnabledTrue("uber.com")).thenReturn(Optional.of(template));
        SanitizedGmailMessage message = load("trip-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.status()).isEqualTo(ParserResult.Status.MALFORMED);
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
