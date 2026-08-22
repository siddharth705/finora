package com.finora.integrations.google.merchant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A deliberate exception to this codebase's usual "don't build ahead of evidence" rule —
 * scaffolded per the project owner's explicit decision despite zero real Paytm transactional
 * email found across 30 real threads reviewed (marketing, gift cards, monthly statements,
 * wallet-inactive nags, Paytm's own direct bookings — no "paid to X, successful" shape anywhere).
 * See docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update.
 *
 * <p>Every message is reported {@link ParserResult.Status#MALFORMED} rather than guessing a
 * pattern the way V103's SQL guess did — there is nothing to extract correctly yet, and a wrong
 * guess that happened to match something would be a worse outcome than an honest "not implemented"
 * signal. Config-gated the same as {@link PhonePeEmailParser}/{@link CredEmailParser} on {@code
 * app.integrations.google.parsers.paytm.enabled} (default false); off by default means this never
 * runs in production regardless.
 */
@Component
public class PaytmEmailParser implements MerchantEmailParser {

    private static final String DOMAIN = "paytm.com";

    @Value("${app.integrations.google.parsers.paytm.enabled:false}")
    private boolean enabled;

    @Override
    public boolean canParse(String authenticatedDomain) {
        return enabled && DOMAIN.equals(authenticatedDomain);
    }

    @Override
    public ParserResult parse(SanitizedGmailMessage message) {
        return ParserResult.malformed("PaytmEmailParser has no verified extraction pattern yet -- "
                + "no real Paytm transactional email has been confirmed to exist; see "
                + "docs/proposals/gmail-merchant-template-admin-ui-proposal.md's 2026-08-22 update");
    }
}
