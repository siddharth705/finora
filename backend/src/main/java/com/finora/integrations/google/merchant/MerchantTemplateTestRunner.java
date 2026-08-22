package com.finora.integrations.google.merchant;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Would this template match?" against a pasted sample email, without creating or persisting
 * anything -- the admin UI's answer to "a wrong template silently mis-stages a wrong amount into
 * a real user's ledger, so this must never be a blind CRUD screen." Same shape as the Rule Engine
 * module's own dry-run precedent ({@code AdminRuleController.POST /rules/test} ->
 * {@code RuleEngineService.testMatch()}, which builds a transient, never-saved {@code CategoryRule}
 * and reuses the exact evaluation logic every real match goes through): build a throwaway
 * {@link MerchantTemplate}, run it through {@link TemplateEmailParser}'s real matching method, done.
 *
 * <p>Deliberately not a second implementation of the matching logic. Reusing
 * {@link TemplateEmailParser#parse(SanitizedGmailMessage, MerchantTemplate)} (package-private
 * specifically for this caller) is what makes "this tested clean" mean something -- there is only
 * one evaluation code path, so a passing test here cannot disagree with what the real pipeline
 * does once the same fields are saved and activated.
 */
@Component
public class MerchantTemplateTestRunner {

    private final MerchantEmailSanitizer sanitizer;
    private final TemplateEmailParser parser;
    private final ParsedReceiptValidator validator;

    public MerchantTemplateTestRunner(MerchantEmailSanitizer sanitizer, TemplateEmailParser parser,
                                       ParsedReceiptValidator validator) {
        this.sanitizer = sanitizer;
        this.parser = parser;
        this.validator = validator;
    }

    /** One test run's result. {@code amount}/{@code transactionDate}/{@code confidence} are only
     *  populated when {@code status == PARSED}; {@code reason} is only populated otherwise --
     *  mirrors {@link ParserResult}'s own PARSED-xor-reason shape. {@code violations} is always a
     *  (possibly empty) list, never null, and is only ever non-empty when {@code status == PARSED}
     *  -- see {@link ParsedReceiptValidator}'s own doc comment on why a syntactically valid match
     *  can still be implausible. */
    public record TestOutcome(ParserResult.Status status, String reason, BigDecimal amount,
                              LocalDate transactionDate, Double confidence,
                              List<ParsedReceiptValidator.Violation> violations) {

        static TestOutcome notParsed(ParserResult.Status status, String reason) {
            return new TestOutcome(status, reason, null, null, null, List.of());
        }

        static TestOutcome parsed(ParsedReceipt receipt, List<ParsedReceiptValidator.Violation> violations) {
            return new TestOutcome(ParserResult.Status.PARSED, null, receipt.amount().toBigDecimal(),
                    receipt.transactionDate(), receipt.confidence(), violations);
        }
    }

    /**
     * @param merchantDomain not used by the matching logic itself (see
     *                       {@link TemplateEmailParser#parse}'s own body -- it never reads
     *                       {@code message.authenticatedDomain()}), only carried through into the
     *                       resulting {@link ParsedReceipt#merchantDomain()} so a test result looks
     *                       exactly like what real staging would produce
     */
    public TestOutcome test(String merchantDomain, String receiptMarker, String amountPattern,
                             String datePattern, String sampleHtml) {
        MerchantTemplate probe = new MerchantTemplate();
        probe.setMerchantDomain(merchantDomain);
        probe.setMerchantName("(test)");
        probe.setReceiptMarker(receiptMarker);
        probe.setAmountPattern(amountPattern);
        probe.setDatePattern(datePattern);

        // A synthetic, never-persisted message id -- this sample never came from Gmail and has no
        // real gmail_processed_messages row to key on. Routed through the SAME sanitizer real
        // extraction uses (MerchantEmailSanitizer is the ONLY way to produce a SanitizedGmailMessage
        // by construction) so a test result reflects what the pipeline would actually see, not the
        // admin's raw pasted HTML.
        SanitizedGmailMessage message = sanitizer.sanitize(
                "sandbox-" + UUID.randomUUID(), merchantDomain, sampleHtml);

        ParserResult result = parser.parse(message, probe);
        return switch (result.status()) {
            case NOT_A_RECEIPT, MALFORMED -> TestOutcome.notParsed(result.status(), result.reason());
            case PARSED -> TestOutcome.parsed(result.receipt(), validator.validate(result.receipt()));
        };
    }
}
