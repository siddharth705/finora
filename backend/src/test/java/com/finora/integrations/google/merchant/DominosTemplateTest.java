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
 * V104's corrected dominos.co.in pattern (see that migration's own comment for what V103's original
 * guess got wrong), proven against two fixtures with different order numbers, amounts and dates --
 * same structural shape both times, which is what a real Domino's "Order Successful" email actually
 * has. Fixture values are fictional; the structural shape (the {@code <span>}-wrapped "Order Total"
 * with no separating space once sanitized, the pipe-bounded day-first date, the td/b-wrapped "Grand
 * Total") was verified against two real emails before this pattern was written.
 */
class DominosTemplateTest {

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();
    private final MerchantTemplateRepository templates = mock(MerchantTemplateRepository.class);
    private final TemplateEmailParser parser = new TemplateEmailParser(templates);

    private static MerchantTemplate dominosTemplate() {
        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain("dominos.co.in");
        template.setMerchantName("Domino's");
        template.setReceiptMarker("Order Confirmed");
        template.setAmountPattern("Grand Total : Rs.{amount}");
        template.setDatePattern("|{date}|");
        template.setEnabled(true);
        return template;
    }

    @Test
    @DisplayName("a real order-shape receipt is parsed into a receipt with the right amount and date")
    void shouldParseFirstOrderReceipt() {
        when(templates.findByMerchantDomainAndEnabledTrue("dominos.co.in"))
                .thenReturn(Optional.of(dominosTemplate()));
        SanitizedGmailMessage message = load("order-receipt-1.html", "msg-1");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.gmailMessageId()).isEqualTo("msg-1");
        assertThat(receipt.merchantDomain()).isEqualTo("dominos.co.in");
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("899.00")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    /** A different order number, amount and date through the SAME template row -- proves the
     *  pattern generalizes rather than being overfit to one fixture's exact values. */
    @Test
    @DisplayName("a second order, with a different amount/date/order number, also parses correctly")
    void shouldParseSecondOrderReceipt() {
        when(templates.findByMerchantDomainAndEnabledTrue("dominos.co.in"))
                .thenReturn(Optional.of(dominosTemplate()));
        SanitizedGmailMessage message = load("order-receipt-2.html", "msg-2");

        ParserResult result = parser.parse(message);

        assertThat(result.isParsed()).isTrue();
        ParsedReceipt receipt = result.receipt();
        assertThat(receipt.amount()).isEqualTo(Money.of(new BigDecimal("412.50")));
        assertThat(receipt.transactionDate()).isEqualTo(LocalDate.of(2025, 11, 22));
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/dominos", fixture));
            return sanitizer.sanitize(gmailMessageId, "dominos.co.in", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
