package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A merchant's receipt read via {@link MerchantTemplate} instead of a class — Phase C5.2.
 *
 * <h2>What this is testing</h2>
 *
 * Whether a declarative pattern, edited as a database row, extracts as reliably as
 * {@code AmazonEmailParser}'s hand-written regex. Uber is the one merchant routed through this;
 * {@code OlaEmailParser} stays hand-written specifically so the two can be compared on real
 * traffic before deciding whether the other four merchants (C5.3) should be templated or coded.
 * See the design review's decision record for the full reasoning.
 *
 * <h2>Same contract, same fixed confidence, for the same reason</h2>
 *
 * This produces exactly the {@link ParserResult}/{@link ParsedReceipt} shape every other parser
 * does — {@link GmailReceiptExtractionService} does not know or care whether the parser it called
 * was a template or a class. {@link #FIXED_CONFIDENCE} matches {@code AmazonEmailParser}'s: there
 * is no partial-confidence path here either, so a single honest value is what this parser
 * currently knows about its own reliability, no more.
 */
@Component
public class TemplateEmailParser implements MerchantEmailParser {

    private static final double FIXED_CONFIDENCE = 0.9;

    private final MerchantTemplateRepository templates;

    public TemplateEmailParser(MerchantTemplateRepository templates) {
        this.templates = templates;
    }

    @Override
    public boolean canParse(String authenticatedDomain) {
        return authenticatedDomain != null
                && templates.findByMerchantDomainAndEnabledTrue(authenticatedDomain).isPresent();
    }

    /**
     * Re-fetches the template rather than relying on {@link #canParse} having just run --
     * {@link MerchantEmailParser}'s own contract says nothing about the two being called together,
     * and this class holds no state between calls, matching every other parser.
     */
    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        Optional<MerchantTemplate> template =
                templates.findByMerchantDomainAndEnabledTrue(message.authenticatedDomain());
        if (template.isEmpty()) {
            // The domain was enabled a moment ago (canParse routed here) and was disabled or
            // deleted since -- an edit racing a live extraction run, not a bug. Same "nothing to
            // decide" answer GmailReceiptExtractionService already gives a domain with no parser
            // at all.
            return ParserResult.notAReceipt("no active template for this domain");
        }

        return parse(message, template.get());
    }

    /** Package-private, not private: {@code MerchantTemplateTestRunner} (the admin test sandbox)
     *  calls this directly against a throwaway, never-persisted {@link MerchantTemplate} so a
     *  template can be verified before it is saved -- reusing the exact matching logic the real
     *  pipeline runs, rather than a second implementation that could drift from it. */
    ParserResult parse(SanitizedGmailMessage message, MerchantTemplate template) {
        String text = message.plainText();

        if (template.matchesNonReceiptMarker(text)) {
            return ParserResult.notAReceipt("matched non-receipt marker \""
                    + template.getNonReceiptMarker() + "\"");
        }

        if (!template.matchesReceiptMarker(text)) {
            return ParserResult.notAReceipt("receipt marker \"" + template.getReceiptMarker()
                    + "\" not found");
        }

        Pattern amountPattern;
        Pattern datePattern;
        try {
            amountPattern = template.compileAmountPattern();
            datePattern = template.compileDatePattern();
        } catch (IllegalStateException e) {
            // A misauthored template (the {amount}/{date} placeholder missing or duplicated) is
            // the template-editing equivalent of a parser that fails to compile -- every message
            // for this domain reports it identically until the row is fixed, which is the loud
            // failure a silent "just don't match anything" would hide.
            return ParserResult.malformed("template misconfigured: " + e.getMessage());
        }

        Matcher amountMatch = amountPattern.matcher(text);
        if (!amountMatch.find()) {
            return ParserResult.malformed("recognised via \"" + template.getReceiptMarker()
                    + "\" but the amount pattern did not match -- template may need updating");
        }

        Money amount;
        try {
            amount = Money.of(new BigDecimal(amountMatch.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return ParserResult.malformed("amount pattern matched but did not parse as a number: "
                    + amountMatch.group(1));
        }

        Matcher dateMatch = datePattern.matcher(text);
        if (!dateMatch.find()) {
            return ParserResult.malformed("recognised via \"" + template.getReceiptMarker()
                    + "\" but the date pattern did not match -- template may need updating");
        }

        LocalDate date = ReceiptDateFormats.tryParse(dateMatch.group(1));
        if (date == null) {
            return ParserResult.malformed("date pattern matched \"" + dateMatch.group(1)
                    + "\" but it did not parse as a recognised date format");
        }

        return ParserResult.parsed(new ParsedReceipt(
                message.gmailMessageId(), template.getMerchantDomain(), null, amount, date, FIXED_CONFIDENCE));
    }
}
