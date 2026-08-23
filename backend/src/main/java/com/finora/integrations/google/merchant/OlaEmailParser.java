package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second merchant parser — Phase C5.2, gmail-transaction-sync proposal §16.1.
 *
 * <p>Hand-written deliberately, not templated — {@link TemplateEmailParser} handles Uber, and this
 * class is its direct comparison: same domain (a ride-hailing receipt), same extraction job, built
 * the {@code AmazonEmailParser} way instead. Whichever holds up better against real traffic informs
 * C5.3's approach for the remaining three merchants.
 *
 * <h2>What makes a message a receipt, here</h2>
 *
 * The presence of {@code Ride Bill} — Ola's ride-completion receipts, as distinct from booking
 * confirmations, promotional mail, and driver-arrived notifications, none of which carry a fare
 * total worth staging.
 */
@Component
public class OlaEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "olacabs.com";

    /** Same reasoning as {@code AmazonEmailParser.FIXED_CONFIDENCE}: one path to {@code PARSED},
     *  so a single fixed value is what this parser currently knows about its own reliability. */
    private static final double FIXED_CONFIDENCE = 0.9;

    private static final Pattern RIDE_BILL_MARKER = Pattern.compile("Ride\\s*Bill");

    /**
     * The fare total, label-anchored for the same reason Amazon's is: an Ola bill's per-leg fare
     * lines can equal the total on a single-leg ride, so matching by position rather than label
     * would be right by accident. Digit run capped and boundary-anchored, mirroring
     * {@code AmazonEmailParser.TOTAL} and {@code MerchantTemplate.AMOUNT_CAPTURE} exactly — same
     * "trusted sender, not trusted content" reasoning applies to every parser in this package, not
     * just the first one.
     */
    private static final Pattern TOTAL_FARE = Pattern.compile(
            "Total Fare\\s*:?\\s*(?:₹|Rs\\.?|INR)?\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RIDE_DATE = Pattern.compile(
            "Ride Date:?\\s*([A-Za-z]+ \\d{1,2}, \\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4})");

    @Override
    public boolean canParse(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!RIDE_BILL_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no ride bill marker found");
        }

        Matcher totalMatch = TOTAL_FARE.matcher(text);
        if (!totalMatch.find()) {
            return ParserResult.malformed("recognised as a ride bill but no total fare "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(totalMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("total fare matched but did not parse as a number: "
                    + totalMatch.group(1));
        }

        Matcher dateMatch = RIDE_DATE.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a ride bill but no ride date "
                    + "could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("ride date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, null, amount, date, FIXED_CONFIDENCE));
    }
}
