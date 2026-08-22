package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRED's credit-card-bill-payment confirmation — a different shape from PhonePe's P2P transfer
 * despite both being "payment-relay" domains flagged together in V103. See
 * docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update: CRED's real
 * counterparty is a bounded "{@code <Bank> •••• <last4>}" string (which bank's card bill was
 * paid), not an arbitrary payee name — structurally closer to "which account" than "who got paid".
 *
 * <h2>Two other real, recurring CRED email shapes this must NOT match</h2>
 *
 * Most real {@code cred.club} mail in a live inbox is not a completed payment at all: a "your
 * credit card bill ... has been generated" notice and a "your credit card payment is due"
 * reminder, both sent before any money moves. Neither contains this parser's marker text, so both
 * correctly fall through to {@link ParserResult.Status#NOT_A_RECEIPT} — see {@code
 * CredEmailParserTest} for real-shaped fixtures of both.
 *
 * <h2>Config-gated</h2>
 *
 * Same reasoning as {@link PhonePeEmailParser}: verified against real data does not mean safe to
 * run unconditionally the moment this deploys. Gated on {@code
 * app.integrations.google.parsers.cred.enabled} (default false).
 */
@Component
public class CredEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "cred.club";

    private static final double FIXED_CONFIDENCE = 0.9;

    /** Present only on a completed-payment confirmation; absent from CRED's bill-generated and
     *  payment-due mail, both ordinary, expected traffic through this trusted domain — not a
     *  receipt this parser should stage. */
    private static final Pattern MARKER = Pattern.compile(
            "payment confirmation", Pattern.CASE_INSENSITIVE);

    /** The bank name and masked card sit right after "successful in N seconds", e.g. "successful
     *  in 9 seconds Yes Bank •••• 9042" — captured together since that anchor is what keeps the
     *  bank-name group from matching the wrong "Bank" occurrence elsewhere in the message. */
    private static final Pattern BANK_AND_CARD = Pattern.compile(
            "successful in \\d+ seconds\\s+(.+?\\s+Bank)\\s*•{4}\\s*(\\d{4})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AMOUNT = Pattern.compile(
            "amount paid\\s*₹\\s*(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_TEXT = Pattern.compile(
            "payment date\\s*:?\\s*([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{4})", Pattern.CASE_INSENSITIVE);

    @Value("${app.integrations.google.parsers.cred.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        String text = message.plainText();

        if (!MARKER.matcher(text).find()) {
            return ParserResult.notAReceipt("no payment-confirmation marker found");
        }

        Matcher bankMatch = BANK_AND_CARD.matcher(text);
        if (!bankMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no bank/card "
                    + "could be extracted -- template may have changed");
        }
        String counterpartyName = bankMatch.group(1).strip() + " •••• " + bankMatch.group(2);

        Matcher amountMatch = AMOUNT.matcher(text);
        if (!amountMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no amount "
                    + "could be extracted -- template may have changed");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(amountMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount matched but did not parse as a number: "
                    + amountMatch.group(1));
        }

        Matcher dateMatch = DATE_TEXT.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised as a payment confirmation but no date "
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
