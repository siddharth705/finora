package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first merchant parser — Phase C5.1, gmail-transaction-sync proposal §16.1.
 *
 * <p>Amazon first per the design proposal's own reasoning: highest volume, a small number of
 * template variants, and a receipt shape ("Order #", a labelled total) that is stable across the
 * variants seen so far. Everything this class does is meant to be the pattern the next five
 * parsers copy, not a one-off.
 *
 * <h2>What makes a message a receipt, here</h2>
 *
 * The presence of an order number marker ({@code Order #}). Amazon sends far more mail than order
 * confirmations from this domain — shipping updates, marketing, "how was your delivery" surveys —
 * and only a fraction of it is a receipt this parser should stage. A message that lacks the marker
 * is {@link ParserResult.Status#NOT_A_RECEIPT}, not an error: that is the expected, common case.
 *
 * <p>A message that HAS the marker but whose total cannot be extracted is
 * {@link ParserResult.Status#MALFORMED} — a template Amazon changed, or a receipt this parser was
 * never built to read. That distinction is the "write a parser update" signal; collapsing it into
 * {@code NOT_A_RECEIPT} would hide a broken parser behind ordinary marketing-mail noise.
 */
@Component
public class AmazonEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "amazon.in";

    /**
     * This parser has exactly one path to {@code PARSED}: label-anchored total, label-anchored
     * date, both parsing cleanly. There is no partial-confidence path yet — either every required
     * field extracted cleanly, or the result is {@code MALFORMED} — so a single fixed value is
     * honest about what this parser currently knows about its own reliability. Not 1.0: even a
     * clean extraction can be reading a total the template moved without breaking the label match
     * (e.g. a "before discount" total on a different row), and the number exists to be shown next
     * to the staged row in review, not to claim certainty this parser cannot back up.
     */
    private static final double FIXED_CONFIDENCE = 0.9;

    /** Present on every order-confirmation variant seen so far; absent from marketing and
     *  shipping-update mail. */
    private static final Pattern ORDER_MARKER = Pattern.compile("Order\\s*#");

    /**
     * The total, immediately after its label. Deliberately anchored to the label rather than
     * "the last amount in the email" — an order confirmation's item-price lines can coincidentally
     * equal the total (single-item orders, like this parser's own first fixture), so matching on
     * position rather than label would be right by accident rather than by construction.
     *
     * <p>The currency marker is optional and matches several forms Amazon's templates use across
     * variants ({@code ₹}, {@code Rs.}, the numeric HTML entity if sanitization ever leaves one
     * un-decoded) — the digits are what is actually required.
     *
     * <p>The digit run is capped at 18 characters (commas included) — far beyond any real personal
     * transaction, but a bound rather than {@code +} unbounded. This message passed C3's domain
     * authentication, not a content integrity check; "trusted sender" bounds who signed the bytes,
     * not what is inside them, and an unbounded digit run handed to {@code new BigDecimal(String)}
     * is an allocation an attacker fully controls the size of. Verified (not assumed) that the cap
     * rejects an oversized run outright rather than silently matching a truncated substring of it
     * — see {@code AmazonEmailParserTest#anOversizedAmountIsRejectedNotSilentlyTruncated} — which
     * matters because the fixed {@code Order Total}/{@code Grand Total} label only anchors one
     * candidate start position for the group in real input, leaving the engine nowhere to slide to.
     * The {@code (?<!\d)}/{@code (?!\d)} boundaries are added anyway, as an explicit statement of
     * the invariant rather than something inferred from this pattern's current shape — a later
     * change (a second, unanchored place this pattern gets reused, say) could reintroduce exactly
     * the sliding-match risk these boundaries close off by construction.
     */
    private static final Pattern TOTAL = Pattern.compile(
            "(?:Order Total|Grand Total)\\s*:?\\s*(?:₹|Rs\\.?|INR|&#8377;)?\\s*"
                    + "(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** Amazon.in's templates are not consistent about date format, so the captured text is tried
     *  against every format {@link ReceiptDateFormats} knows rather than one fixed pattern. */
    private static final Pattern DATE_TEXT = Pattern.compile(
            "Order Date:?\\s*([A-Za-z]+ \\d{1,2}, \\d{4}|\\d{4}-\\d{2}-\\d{2})");

    @Override
    public boolean canParse(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!ORDER_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no order number marker found");
        }

        Matcher totalMatch = TOTAL.matcher(text);
        if (!totalMatch.find()) {
            return ParserResult.malformed("recognised as an order confirmation but no total "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(totalMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("order total matched but did not parse as a number: "
                    + totalMatch.group(1));
        }

        LocalDate date = extractDate(text);
        if (date == null) {
            return ParserResult.malformed("recognised as an order confirmation but no order date "
                    + "could be extracted -- template may have changed");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, amount, date, FIXED_CONFIDENCE));
    }

    private static LocalDate extractDate(String text) {
        Matcher dateMatch = DATE_TEXT.matcher(text);
        if (!dateMatch.find()) return null;
        return ReceiptDateFormats.tryParse(dateMatch.group(1));
    }
}
