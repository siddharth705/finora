package com.finora.util;

import com.finora.accounts.AccountDto.BankDto;
import com.finora.dto.MerchantDto;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the constraint actually rejects the payloads it exists to stop, against a real Bean
 * Validation engine rather than by reading the regex and assuming.
 *
 * <p>The bug: {@code Bank.websiteUrl} was persisted with no scheme validation, so a
 * {@code BANK_MANAGE} admin could store {@code javascript:alert(1)} and the admin portal rendered
 * it verbatim as a clickable {@code <a href>} for every other admin -- stored XSS in the admin
 * origin. Validating on the way in is the layer that holds for every consumer, not just the one
 * that shipped a render guard.
 */
class SafeHttpUrlTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** A bank create request that is valid in every respect except the website URL under test. */
    private static BankDto.CreateRequest bankWithWebsite(String websiteUrl) {
        return new BankDto.CreateRequest("IOB", "Indian Overseas Bank", "IOB",
                "#123456", "IO", "PUBLIC_SECTOR", websiteUrl, "IOBA");
    }

    private static boolean bankWebsiteAccepted(String websiteUrl) {
        return validator.validate(bankWithWebsite(websiteUrl)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The exact payload from the reported stored XSS.
            "javascript:alert(1)",
            // Control-character variant -- browsers historically stripped TAB/CR/LF inside a
            // scheme, so this reaches the same sink while looking unlike the string above.
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "java\rscript:alert(1)",
            // Case is not a defence on its own; the scheme comparison must not be the only check.
            "JaVaScRiPt:alert(1)",
            // Other script-capable or data-bearing schemes that are equally unsafe in an href.
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            // Scheme-relative and path-relative values are not absolute http(s) URLs.
            "//evil.example.com",
            "/relative/path",
            "evil.example.com",
            // Leading whitespace must not let a bad scheme slip past a start-anchored check.
            " javascript:alert(1)",
            "\tjavascript:alert(1)",
    })
    void rejectsAnythingThatIsNotAnHttpOrHttpsUrl(String hostile) {
        assertThat(bankWebsiteAccepted(hostile))
                .as("%s must not be persistable as a bank website URL", hostile)
                .isFalse();
    }

    /**
     * The specific reason the pattern uses {@code \A}/{@code \z} rather than {@code ^}/{@code $}.
     * Java's {@code $} also matches before a line terminator at the end of input, so an anchored
     * {@code ^https?://\S+$} would have accepted a trailing newline -- and {@code .} not matching
     * a newline is what would otherwise let a second line ride along behind a valid-looking first.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://ok.example.com\n",
            "https://ok.example.com\njavascript:alert(1)",
            "https://ok.example.com javascript:alert(1)",
    })
    void rejectsAValidPrefixWithAnythingSmuggledAfterIt(String hostile) {
        assertThat(bankWebsiteAccepted(hostile))
                .as("%s must not be persistable -- every character has to be part of the URL", hostile)
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.iob.in",
            "http://www.iob.in",
            "https://www.iob.in/personal/accounts?tab=savings#top",
            // Scheme comparison is case-insensitive per RFC 3986; rejecting this would be a false
            // rejection of a genuinely valid URL, not extra safety.
            "HTTPS://WWW.IOB.IN",
            "HtTp://www.iob.in",
    })
    void acceptsRealHttpAndHttpsUrls(String legitimate) {
        assertThat(bankWebsiteAccepted(legitimate))
                .as("%s is a legitimate bank website and must still be accepted", legitimate)
                .isTrue();
    }

    @Test
    void acceptsNullAndEmpty_becauseTheWebsiteIsOptional() {
        // The admin form sends "" (not an omitted field) for every optional text input left blank,
        // and BankManagementService.blankToNull is what turns that into a null column. Rejecting
        // "" here would break creating a bank without a website at all.
        assertThat(bankWebsiteAccepted(null)).isTrue();
        assertThat(bankWebsiteAccepted("")).isTrue();
    }

    @Test
    void theSameConstraintGuardsTheBankUpdatePath_notJustCreate() {
        // update() reaches the identical column; validating only create() would leave the hole
        // open to anyone who creates a clean bank and then edits it.
        var hostile = new BankDto.UpdateRequest(null, null, null, null, null, "javascript:alert(1)", null);
        var legitimate = new BankDto.UpdateRequest(null, null, null, null, null, "https://www.iob.in", null);

        assertThat(validator.validate(hostile)).isNotEmpty();
        assertThat(validator.validate(legitimate)).isEmpty();
    }

    @Test
    void theSameConstraintGuardsMerchantWebsite_theOtherWritableUrlField() {
        // Bank.websiteUrl was the reported instance; Merchant.website is the same shape, writable
        // by a MERCHANT_MANAGE admin through AdminUserMerchantController.update.
        var hostile = new MerchantDto.UpdateRequest("Amazon", "javascript:alert(1)");
        var legitimate = new MerchantDto.UpdateRequest("Amazon", "https://www.amazon.in");

        assertThat(validator.validate(hostile)).isNotEmpty();
        assertThat(validator.validate(legitimate)).isEmpty();
    }

    @Test
    void theRejectionMessageSaysWhatIsActuallyAllowed() {
        var violations = validator.validate(bankWithWebsite("javascript:alert(1)"));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("http");
    }
}
