package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase C5.3 — hand-written, not templated. {@code ZomatoEmailParser} doesn't exist; Zomato is
 * C5.3's templated merchant (V86's seeded row, routed through {@link TemplateEmailParser}) because
 * a food-delivery receipt is the same single-total, single-date shape Uber already proved out.
 * Myntra isn't: return, exchange, and refund notifications reuse the same "Order" language a fresh
 * purchase confirmation does, each with an amount and a date of their own that must NOT be staged
 * as a new transaction. Telling those apart needs a negative check before the positive one — a
 * template's one receipt marker has no way to express "and also not this other thing," which is
 * why Myntra stays a class like {@code AmazonEmailParser} and {@code OlaEmailParser} do.
 */
@Component
public class MyntraEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "myntra.com";

    /** Same reasoning as every other parser in this package's {@code FIXED_CONFIDENCE}: one path
     *  to {@code PARSED}, so a single fixed value is what this parser currently knows about its
     *  own reliability. */
    private static final double FIXED_CONFIDENCE = 0.9;

    private static final Pattern ORDER_MARKER = Pattern.compile("Order Confirmed");

    /**
     * Checked before {@link #ORDER_MARKER}: a message that mentions a return/exchange/refund is
     * about undoing or crediting a purchase, not making one, regardless of whether it also quotes
     * the original order confirmation's own text inline (a common template pattern — "Your return
     * for Order Confirmed on 9 Aug is on its way").
     */
    private static final Pattern RETURN_OR_REFUND_MARKER = Pattern.compile(
            "Return Initiated|Refund Processed|Exchange Confirmed", Pattern.CASE_INSENSITIVE);

    /** Label-anchored and digit-run-bounded for the same reason as every sibling parser's total
     *  pattern — see {@code AmazonEmailParser.TOTAL}'s doc comment for the full reasoning, which
     *  applies unchanged here. */
    private static final Pattern TOTAL = Pattern.compile(
            "Order Total\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_TEXT = Pattern.compile(
            "Order Date:?\\s*([A-Za-z]+ \\d{1,2}, \\d{4}|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2} [A-Za-z]+ \\d{4}|\\d{1,2}/\\d{1,2}/\\d{4})");

    @Override
    public boolean canParse(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (RETURN_OR_REFUND_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("return/exchange/refund notification, not a purchase");
        }

        if (!ORDER_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no order confirmation marker found");
        }

        Matcher totalMatch = TOTAL.matcher(text);
        if (!totalMatch.find()) {
            return ParserResult.malformed("recognised as an order confirmation but no order total "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(totalMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("order total matched but did not parse as a number: "
                    + totalMatch.group(1));
        }

        Matcher dateMatch = DATE_TEXT.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as an order confirmation but no order date "
                    + "could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("order date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, amount, date, FIXED_CONFIDENCE));
    }
}
