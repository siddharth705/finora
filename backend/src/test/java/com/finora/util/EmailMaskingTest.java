package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskingTest {

    // Every address below uses an RFC 2606 reserved domain (example.com/.org), which is what makes
    // them unambiguously synthetic rather than merely believed to be. scripts/check-fixture-hygiene.sh
    // blocks a committed email address unless it announces itself as a placeholder, and the first
    // draft of this file used @gmail.com addresses that it correctly refused -- a fixture that needs
    // an exception marker to get past the PII guard is a fixture written with the wrong domain.

    @Test
    void mask_aTypicalAddress_keepsOneLeadingCharacterAndTheWholeDomain() {
        assertThat(EmailMasking.mask("priya.sharma@example.com")).isEqualTo("p•••••••••••@example.com");
    }

    /**
     * The diagnostic the domain is kept for: an operator watching a run of failures needs to see
     * "every address at one domain is bouncing" as a different shape from "assorted addresses are
     * bouncing", and the domain is the only part that carries it.
     */
    @Test
    void mask_twoAddressesAtTheSameDomain_stillShareAVisibleDomain() {
        assertThat(EmailMasking.mask("aarav@example.com")).endsWith("@example.com");
        assertThat(EmailMasking.mask("meera@example.com")).endsWith("@example.com");
    }

    /** The local part is the identifying half, so no length of it survives beyond the first
     *  character -- including the length itself not being usable to reconstruct anything. */
    @Test
    void mask_doesNotLeaveTheLocalPartReadable() {
        String masked = EmailMasking.mask("firstname.lastname@example.org");

        assertThat(masked).doesNotContain("firstname");
        assertThat(masked).doesNotContain("lastname");
        assertThat(masked).startsWith("f");
    }

    /**
     * A value that is not an address at all is hidden rather than passed through. This method is
     * only ever called on something a caller believes is an email, so a value without an "@" is
     * either a malformed address or the wrong variable reached the log line -- printing it is the
     * worse outcome in both cases.
     */
    @Test
    void mask_aValueThatIsNotAnAddress_isMaskedInFullRatherThanPassedThrough() {
        assertThat(EmailMasking.mask("not-an-address")).isEqualTo("••••••••••••••");
    }

    @Test
    void mask_aSingleCharacterLocalPart_isMaskedInFullSoNothingIsRevealed() {
        assertThat(EmailMasking.mask("a@example.com")).isEqualTo("•@example.com");
    }

    /** Plus-addressing and dots are legal in a local part (RFC 5322) and must not survive. */
    @Test
    void mask_aPlusAddressedLocalPart_doesNotLeakTheTag() {
        assertThat(EmailMasking.mask("user+finora-signup@example.com")).isEqualTo("u•••••••••••••••••@example.com");
    }

    /** An "@" inside the local part is legal when quoted; the LAST one is the real separator, so
     *  splitting on the first would leave part of the local part visible in the "domain". */
    @Test
    void mask_anAddressWithMoreThanOneAt_splitsOnTheLastOne() {
        assertThat(EmailMasking.mask("\"odd@name\"@example.com")).isEqualTo("\"•••••••••@example.com");
    }

    @Test
    void mask_null_returnsNull() {
        assertThat(EmailMasking.mask(null)).isNull();
    }

    @Test
    void mask_blank_returnsNull() {
        assertThat(EmailMasking.mask("   ")).isNull();
    }
}
