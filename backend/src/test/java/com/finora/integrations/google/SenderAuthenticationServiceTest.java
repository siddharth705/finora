package com.finora.integrations.google;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase C3, design proposal §12.2. These tests are written as the attacks, not as the happy path:
 * the gate's whole value is what it refuses, and a test suite that only proves real Amazon mail is
 * accepted would pass against a method that returns TRUSTED unconditionally.
 */
class SenderAuthenticationServiceTest {

    private TrustedSenderDomainRepository domains;
    private SenderAuthenticationService service;

    @BeforeEach
    void setUp() {
        domains = mock(TrustedSenderDomainRepository.class);
        service = new SenderAuthenticationService(domains);
        // Nothing is trusted unless a test says so.
        when(domains.findByDomain(anyString())).thenReturn(Optional.empty());
    }

    private void trust(String domain) {
        TrustedSenderDomain entry = new TrustedSenderDomain();
        entry.setDomain(domain);
        entry.setMerchantName("Test Merchant");
        entry.setStatus(TrustedSenderDomain.Status.ACTIVE);
        when(domains.findByDomain(domain)).thenReturn(Optional.of(entry));
    }

    @Test
    void aDmarcPassFromATrustedDomainIsTrusted() {
        trust("amazon.in");

        SenderAuthenticationService.Result result = service.evaluate(
                "mx.google.com; dkim=pass header.i=@amazon.in; spf=pass smtp.mailfrom=amazon.in; "
                        + "dmarc=pass header.from=amazon.in");

        assertThat(result.isTrusted()).isTrue();
        assertThat(result.authenticatedDomain()).isEqualTo("amazon.in");
    }

    /**
     * The headline attack. The display name says Amazon and the address does not — and the
     * authenticated domain is gmail.com, which is not on the registry.
     */
    @Test
    @DisplayName("a spoofed display name does not make a message trusted")
    void aMessageClaimingToBeAmazonFromAnotherDomainIsRejected() {
        trust("amazon.in");

        // Genuinely authenticated -- as gmail.com, because that is where it really came from.
        SenderAuthenticationService.Result result = service.evaluate(
                "mx.google.com; dkim=pass header.i=@gmail.com; spf=pass smtp.mailfrom=gmail.com; "
                        + "dmarc=pass header.from=gmail.com");

        assertThat(result.isTrusted()).isFalse();
        assertThat(result.verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED);
        assertThat(result.authenticatedDomain()).isEqualTo("gmail.com");
    }

    /**
     * A domain an attacker can register today. A suffix or {@code endsWith} rule would accept it;
     * exact matching is the only thing that does not.
     */
    @Test
    @DisplayName("a lookalike domain that merely ENDS WITH a trusted one is rejected")
    void aSuffixLookalikeDomainIsRejected() {
        trust("amazon.in");

        SenderAuthenticationService.Result result = service.evaluate(
                "mx.google.com; dmarc=pass header.from=amazon.in.attacker.example");

        assertThat(result.isTrusted()).isFalse();
        verify(domains).findByDomain("amazon.in.attacker.example");
        // The lookup used the FULL domain -- it never tried the trusted suffix on its own.
        verify(domains, never()).findByDomain("amazon.in");
    }

    /** The mirror case: a prefix lookalike. */
    @Test
    void aPrefixLookalikeDomainIsRejected() {
        trust("amazon.in");

        assertThat(service.evaluate("mx.google.com; dmarc=pass header.from=notamazon.in").isTrusted())
                .isFalse();
    }

    /**
     * A subdomain is a different sender. If receipts genuinely come from {@code mail.amazon.in} it
     * earns its own registry row — inferring it here would be a suffix rule in disguise.
     */
    @Test
    void aSubdomainOfATrustedDomainIsNotAutomaticallyTrusted() {
        trust("amazon.in");

        assertThat(service.evaluate("mx.google.com; dmarc=pass header.from=mail.amazon.in").isTrusted())
                .isFalse();
    }

    /**
     * The reason verdict and domain are captured from the SAME clause. Here SPF passes for a bulk
     * mailer while DKIM FAILS for the domain being claimed — a header that contains both the word
     * "pass" and the string "amazon.in", and must not be trusted on that basis.
     */
    @Test
    @DisplayName("a failing DKIM for the claimed domain is not rescued by a passing SPF for another")
    void aPassSomewhereElseInTheHeaderDoesNotTrustTheClaimedDomain() {
        trust("amazon.in");

        SenderAuthenticationService.Result result = service.evaluate(
                "mx.google.com; dkim=fail header.i=@amazon.in; spf=pass smtp.mailfrom=bulk-mailer.example");

        assertThat(result.isTrusted()).isFalse();
        // What passed was bulk-mailer.example, and that is what the decision was made against.
        assertThat(result.authenticatedDomain()).isEqualTo("bulk-mailer.example");
    }

    @Test
    void aMessageThatFailedEveryCheckIsNotAuthenticated() {
        trust("amazon.in");

        SenderAuthenticationService.Result result = service.evaluate(
                "mx.google.com; dkim=fail header.i=@amazon.in; spf=softfail smtp.mailfrom=amazon.in; "
                        + "dmarc=fail header.from=amazon.in");

        assertThat(result.verdict()).isEqualTo(SenderAuthenticationService.Verdict.NOT_AUTHENTICATED);
        assertThat(result.authenticatedDomain()).isNull();
    }

    @Test
    void aMissingHeaderIsUntrusted() {
        assertThat(service.evaluate(null).verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.NO_AUTHENTICATION_HEADER);
        assertThat(service.evaluate("  ").verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.NO_AUTHENTICATION_HEADER);
    }

    @Test
    void anUnparseableHeaderIsUntrustedRatherThanAssumedFine() {
        assertThat(service.evaluate("this is not an authentication-results header").isTrusted())
                .isFalse();
    }

    /** DNS is case-insensitive, so an upper-case domain in the header is the same host. Missing this
     *  would let a trusted sender be silently refused. */
    @Test
    void domainMatchingIsCaseInsensitive() {
        trust("amazon.in");

        assertThat(service.evaluate("mx.google.com; dmarc=pass header.from=AMAZON.IN").isTrusted())
                .isTrue();
    }

    /** A disabled entry must behave exactly as an absent one -- that is what disabling is for. */
    @Test
    void aDisabledDomainIsNotTrusted() {
        TrustedSenderDomain disabled = new TrustedSenderDomain();
        disabled.setDomain("amazon.in");
        disabled.setMerchantName("Amazon");
        disabled.setStatus(TrustedSenderDomain.Status.DISABLED);
        when(domains.findByDomain("amazon.in")).thenReturn(Optional.of(disabled));

        assertThat(service.evaluate("mx.google.com; dmarc=pass header.from=amazon.in").isTrusted())
                .isFalse();
    }

    /** The fully-qualified form of the same host. */
    @Test
    void aTrailingDotDoesNotDefeatTheMatch() {
        trust("amazon.in");

        assertThat(service.evaluate("mx.google.com; dmarc=pass header.from=amazon.in.").isTrusted())
                .isTrue();
    }
}
