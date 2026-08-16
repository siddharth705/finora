package com.finora.integrations.google;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a message may be parsed at all — Phase C3, design proposal §12.2.
 *
 * <h2>The attack this exists to stop</h2>
 *
 * Anyone can send mail with {@code From: Amazon <support@attacker.example>}. An attacker who knows a
 * target uses Finora can send a fabricated order confirmation for any amount to that person's
 * connected mailbox. Every Gmail-sourced row goes through review, but a fabricated financial record
 * entering a finance product's pipeline is still a real failure — and a user bulk-approving a queue
 * may not catch it.
 *
 * <h2>Two independent conditions, both required</h2>
 *
 * <ol>
 *   <li><b>The message is authenticated.</b> Gmail already performs DKIM, SPF and DMARC on delivery
 *       and records the verdict in {@code Authentication-Results}. Finora reads that verdict rather
 *       than attempting its own crypto: Gmail did it at delivery time with the DNS state as it was
 *       then, which is the only moment the check is meaningful.</li>
 *   <li><b>The authenticated domain is on the registry.</b> Authentication proves a message really
 *       came from the domain it claims. It says nothing about whether that domain is one Finora
 *       should read receipts from — an attacker can DKIM-sign mail from a domain they own perfectly
 *       well. {@link TrustedSenderDomain} answers that separate question.</li>
 * </ol>
 *
 * <p>Neither is sufficient alone, and the second is checked against the <b>authenticated</b> domain,
 * never the {@code From} header's display name or address as written.
 *
 * <h2>Fail closed</h2>
 *
 * Anything unparseable, missing, ambiguous, or merely unrecognised is untrusted. A header Finora
 * cannot read is not evidence of anything, and the cost of wrongly refusing a real receipt is one
 * unparsed message; the cost of wrongly accepting a forged one is a fabricated financial record.
 */
@Service
public class SenderAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(SenderAuthenticationService.class);

    /**
     * Matches {@code dkim=pass header.i=@amazon.in} and the SPF/DMARC equivalents.
     *
     * <p>The verdict and the domain are captured together, from the same clause, on purpose: an
     * {@code Authentication-Results} header routinely contains several method clauses, some passing
     * and some not. Reading "does this header contain the word pass" and separately "does it mention
     * amazon.in" would accept a header where SPF passed for a bulk mailer while DKIM failed for the
     * claimed domain.
     */
    private static final Pattern DKIM_CLAUSE = Pattern.compile(
            "\\bdkim=(\\w+)[^;]*?header\\.(?:i|d)=@?([A-Za-z0-9.\\-]+)");
    private static final Pattern SPF_CLAUSE = Pattern.compile(
            "\\bspf=(\\w+)[^;]*?smtp\\.(?:mailfrom|helo)=@?([A-Za-z0-9.\\-]+)");
    private static final Pattern DMARC_CLAUSE = Pattern.compile(
            "\\bdmarc=(\\w+)[^;]*?header\\.from=([A-Za-z0-9.\\-]+)");

    private final TrustedSenderDomainRepository domains;

    public SenderAuthenticationService(TrustedSenderDomainRepository domains) {
        this.domains = domains;
    }

    /** Why a message was refused — recorded so a rising rate is visible rather than silent. */
    public enum Verdict {
        /** Authenticated and on the registry. The only value that permits parsing. */
        TRUSTED,
        /** No method produced a pass for any domain — spoofed, or delivered without authentication. */
        NOT_AUTHENTICATED,
        /** Genuinely from that domain, but Finora does not read receipts from it. */
        DOMAIN_NOT_TRUSTED,
        /** No usable {@code Authentication-Results} header at all. */
        NO_AUTHENTICATION_HEADER
    }

    /**
     * @param verdict            the decision
     * @param authenticatedDomain the domain that actually passed, when one did — for logging and for
     *                            the "which parser should we write next" signal. Never taken from
     *                            the From header.
     */
    public record Result(Verdict verdict, String authenticatedDomain) {
        public boolean isTrusted() { return verdict == Verdict.TRUSTED; }
    }

    /**
     * @param authenticationResults the raw {@code Authentication-Results} header Gmail attached
     * @return whether this message may be parsed, and why not if it may not
     */
    public Result evaluate(String authenticationResults) {
        if (authenticationResults == null || authenticationResults.isBlank()) {
            // Gmail attaches this to essentially everything it delivers. Its absence means the
            // message was not fetched with the headers this check needs, or came from somewhere
            // that did not authenticate it -- either way there is nothing to verify against.
            return new Result(Verdict.NO_AUTHENTICATION_HEADER, null);
        }

        String header = authenticationResults.toLowerCase(Locale.ROOT);

        // DMARC first: it is the strongest signal, because it verifies alignment with the header
        // From: the domain a human sees. DKIM and SPF can each pass for a domain the reader never
        // sees, which is exactly how aligned-looking spoofs are built.
        Optional<String> authenticated = passingDomain(DMARC_CLAUSE, header)
                .or(() -> passingDomain(DKIM_CLAUSE, header))
                .or(() -> passingDomain(SPF_CLAUSE, header));

        if (authenticated.isEmpty()) {
            return new Result(Verdict.NOT_AUTHENTICATED, null);
        }

        String domain = TrustedSenderDomain.normalize(authenticated.get());
        boolean trusted = domains.findByDomain(domain)
                .filter(TrustedSenderDomain::isActive)
                .isPresent();

        if (!trusted) {
            // Not an error -- most mail in a mailbox is legitimately not a merchant receipt. Logged
            // at debug; the aggregate count is the useful signal (design proposal §16.1), since it
            // is how "we should write a parser for this merchant" surfaces.
            log.debug("Gmail message from authenticated domain {} is not on the trusted registry.", domain);
            return new Result(Verdict.DOMAIN_NOT_TRUSTED, domain);
        }

        return new Result(Verdict.TRUSTED, domain);
    }

    /**
     * Extracts the domain from a clause only when that clause's own verdict is {@code pass}.
     *
     * <p>The pairing is the point. {@code spf=fail ... dkim=pass header.i=@amazon.in} must yield
     * amazon.in from the DKIM clause and nothing from the SPF one — never "the header says pass
     * somewhere and mentions amazon.in somewhere".
     */
    private Optional<String> passingDomain(Pattern clause, String header) {
        Matcher matcher = clause.matcher(header);
        while (matcher.find()) {
            if ("pass".equals(matcher.group(1))) {
                String domain = matcher.group(2);
                if (domain != null && !domain.isBlank()) {
                    return Optional.of(domain);
                }
            }
        }
        return Optional.empty();
    }
}
