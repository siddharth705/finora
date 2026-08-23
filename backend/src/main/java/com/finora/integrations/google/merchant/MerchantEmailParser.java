package com.finora.integrations.google.merchant;

/**
 * One merchant's receipt format — Phase C5.
 *
 * <h2>Routing is by domain, not by content</h2>
 *
 * {@link #canParse} takes the C3-authenticated domain, never anything read from the message body.
 * The body is attacker-shaped even after sanitization — sanitization removes dangerous constructs,
 * it does not make the text trustworthy — so a parser that routed itself by sniffing body content
 * (e.g. "contains the word Amazon") could be steered to the wrong merchant's parser by any sender
 * whose mail merely mentions a competitor. The domain already passed C3's authentication and
 * registry check; it is the one signal in this whole path that is not just words in an email.
 *
 * <h2>Every implementation is stateless</h2>
 *
 * A parser is a pure function of one {@link SanitizedGmailMessage}. No parser may hold per-user or
 * per-message state, call out to Gmail, or write anywhere — {@link #parse} returns a value, and
 * what happens to that value (staging it, recording it, discarding it) is {@code
 * GmailStagingBridge}'s job (C5-B), not this interface's.
 *
 * <h2>A parser's output is not yet trusted, either</h2>
 *
 * A {@code PARSED} result still has to clear {@link ParsedReceiptValidator} before {@code
 * GmailStagingBridge} treats it as something worth showing a user — a parser is regex and
 * string-matching against attacker-adjacent content, and a template edge case can produce a
 * syntactically valid receipt carrying a nonsensical amount or date. {@link ParsedReceipt}'s own
 * constructor only rules out a missing field; plausibility is the validator's job, not this
 * interface's or {@link ParsedReceipt}'s.
 */
public interface MerchantEmailParser {

    /** Whether this parser handles mail authenticated against this domain. */
    boolean canParse(String authenticatedDomain);

    /**
     * Whether this parser is the one responsible for a domain, independent of whether it is
     * currently allowed to run. Every parser with no runtime enable/disable gate simply IS its own
     * {@link #canParse} answer, which is what the default here gives for free. A config-gated
     * parser (one whose {@code canParse} also checks a feature flag — see {@code
     * PhonePeEmailParser}'s own class doc for why that gate exists) must override this to answer
     * unconditionally: admin tooling that guards against two parsing mechanisms competing for the
     * same domain (see {@code MerchantTemplateAdminService.rejectIfClaimedByAnotherParser}) needs
     * to know a domain is spoken for even while its parser is deliberately turned off, or an admin
     * could create and activate a {@code merchant_templates} row for a domain a disabled
     * hand-written parser will claim the moment it's switched on.
     */
    default boolean claimsDomain(String authenticatedDomain) {
        return canParse(authenticatedDomain);
    }

    /**
     * Extracts a receipt from one message this parser has already claimed via {@link #canParse}.
     *
     * <p>Never throws for ordinary "this isn't parseable" reasons — a marketing email or a template
     * change is expected traffic through a trusted merchant's mail, not an exceptional one. Reserve
     * exceptions for genuine bugs in the parser itself; every recognised outcome, including failure
     * to extract, is a {@link ParserResult}.
     */
    ParserResult parse(SanitizedGmailMessage message);
}
