package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase C5.3 — hand-written, like {@link MyntraEmailParser} and for a parallel reason: a "Booking
 * Confirmed" email for a pay-at-the-property reservation carries the same confirmation language
 * and can still show a total price for the stay, but no money has moved yet — payment happens
 * later, at the hotel, outside Gmail entirely. Staging that would create a ledger entry nothing
 * backs. Telling a real charge apart from a future one needs a negative check before the positive
 * one, the same shape as Myntra's return/refund exclusion; a template's one receipt marker can't
 * express it, which is why Booking.com stays a class rather than a row like Zomato's.
 *
 * <p>The date extracted here is when the charge happened ({@code Booked on}), not the future
 * check-in/check-out dates a confirmation also shows — a transaction's date is when money moved,
 * the same rule every parser in this package already follows for its own merchant.
 */
@Component
public class BookingEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "booking.com";

    /** Same reasoning as every other parser in this package's {@code FIXED_CONFIDENCE}. */
    private static final double FIXED_CONFIDENCE = 0.9;

    private static final Pattern BOOKING_MARKER = Pattern.compile("Booking Confirmed");

    /** Checked before the amount is extracted at all — see the class doc comment. */
    private static final Pattern PAY_AT_PROPERTY_MARKER = Pattern.compile(
            "Pay at the property|No prepayment needed", Pattern.CASE_INSENSITIVE);

    /** Label-anchored and digit-run-bounded for the same reason as every sibling parser's amount
     *  pattern — see {@code AmazonEmailParser.TOTAL}'s doc comment. */
    private static final Pattern AMOUNT_CHARGED = Pattern.compile(
            "Amount Charged\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BOOKED_ON = Pattern.compile(
            "Booked on:?\\s*([A-Za-z]+ \\d{1,2}, \\d{4}|\\d{4}-\\d{2}-\\d{2}"
                    + "|\\d{1,2} [A-Za-z]+ \\d{4}|\\d{1,2}/\\d{1,2}/\\d{4})");

    @Override
    public boolean canParse(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!BOOKING_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no booking confirmation marker found");
        }

        if (PAY_AT_PROPERTY_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("pay-at-property booking, nothing charged yet");
        }

        Matcher amountMatch = AMOUNT_CHARGED.matcher(text);
        if (!amountMatch.find()) {
            return ParserResult.malformed("recognised as a booking confirmation but no amount "
                    + "charged could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(amountMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount charged matched but did not parse as a number: "
                    + amountMatch.group(1));
        }

        Matcher dateMatch = BOOKED_ON.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a booking confirmation but no booking "
                    + "date could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("booking date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, amount, date, FIXED_CONFIDENCE));
    }
}
