package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PhonePe's payment-notification email — a P2P/UPI transfer, not a merchant receipt in the sense
 * every other parser assumes. See docs/proposals/gmail-merchant-template-admin-ui-proposal.md's
 * 2026-08-22 update for the full reasoning: the domain is PhonePe, but the actual counterparty
 * (who the money went to) is a name embedded in the body, so this parser — unlike every other one
 * in this package — populates {@link ParsedReceipt#counterpartyName()} rather than leaving it
 * null.
 *
 * <h2>Config-gated, unlike the marker/pattern verification this parser already has</h2>
 *
 * Every field here is verified against real Gmail data (five consistent real messages spanning
 * 2019–2024). That is not the same thing as safe to run unconditionally the moment this deploys —
 * unlike {@code merchant_templates}' {@code enabled} column, a hand-written parser's {@code
 * canParse} has no per-row kill switch, so this one gates on {@code
 * app.integrations.google.parsers.phonepe.enabled} (default false) the same way {@code
 * AdminMfaService} gates on {@code app.admin-mfa.enabled} — merging this class must not make it
 * live; that is a separate, deliberate flip.
 */
@Component
public class PhonePeEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "phonepe.com";

    private static final double FIXED_CONFIDENCE = 0.9;

    /** Present on every completed PhonePe transfer seen so far; absent from PhonePe's other mail
     *  (offers, cashback promos, app notifications). */
    private static final Pattern SUCCESS_MARKER = Pattern.compile(
            "Txn\\.\\s*status\\s*:\\s*Successful", Pattern.CASE_INSENSITIVE);

    /**
     * Captures the counterparty and the amount together, since they sit adjacent in the body
     * ("Paid to &lt;name&gt; ₹ &lt;amount&gt;") — anchoring the name capture to stop at the
     * currency symbol is what keeps it from swallowing the rest of the line. The amount has no
     * mandatory decimal part: real PhonePe amounts seen so far are plain integers ("₹ 27000"),
     * unlike Amazon's always-two-decimal totals. The name capture is bounded to a sane length
     * (80 chars, generous for a real payee name) so a message body with "Paid to" but no ₹ symbol
     * anywhere after it — attacker-controlled input, per {@link MerchantEmailSanitizer}'s own
     * class doc — can't force an unbounded scan, the same defensive reasoning
     * {@code AmazonEmailParser}'s own amount pattern already documents.
     */
    private static final Pattern PAID_TO = Pattern.compile(
            "Paid to\\s+(.{1,80}?)\\s*₹\\s*(?<!\\d)([\\d,]{1,18}(?:\\.\\d{2})?)(?!\\d)");

    /** The date line has no label at all — it sits right after "PhonePe" and right before "Paid
     *  to" ("PhonePe Jul 14, 2026 Paid to ..."), an abbreviated-month format {@link
     *  ReceiptDateFormats} did not parse until this class needed it. */
    private static final Pattern HEADER_DATE = Pattern.compile(
            "PhonePe\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4})\\s+Paid to");

    @Value("${app.integrations.google.parsers.phonepe.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    /** Unconditional, unlike {@link #canParse} -- this domain is PhonePe's regardless of whether
     *  the feature flag above happens to be on, so the admin collision guard must see it as claimed
     *  even while the flag is off. See {@link MerchantEmailParser#claimsDomain} for why. */
    @Override
    public boolean claimsDomain(String authenticatedDomain) {
        return DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!SUCCESS_MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no successful-transaction marker found");
        }

        Matcher paidTo = PAID_TO.matcher(text);
        if (!paidTo.find()) {
            return ParserResult.malformed("recognised as a successful transaction but no "
                    + "counterparty/amount could be extracted -- template may have changed");
        }

        String counterpartyName = paidTo.group(1).strip();

        Money amount;
        try {
            amount = Money.of(new BigDecimal(paidTo.group(2).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount matched but did not parse as a number: "
                    + paidTo.group(2));
        }

        Matcher dateMatch = HEADER_DATE.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a successful transaction but no date "
                    + "could be extracted -- template may have changed");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("date matched \"" + dateMatch.group(1)
                    + "\" but did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), DOMAIN, counterpartyName, amount, date, FIXED_CONFIDENCE));
    }
}
