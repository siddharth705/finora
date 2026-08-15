package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;

/**
 * The judgment {@link ParsedReceipt}'s own constructor deliberately does not make — Phase C5.
 *
 * <p>{@link ParsedReceipt} refuses to exist with a null field; that eliminates bugs where a parser
 * forgot a field entirely. It says nothing about a field that exists but is nonsense: a parser is
 * regex and string-matching against attacker-adjacent input (a trusted sender, not trusted
 * content — see {@link AmazonEmailParser}'s own reasoning), and a template change or an edge case
 * nobody anticipated can produce a syntactically valid {@code ParsedReceipt} carrying an amount off
 * by several orders of magnitude, or a date the extraction logic misread.
 *
 * <p>This is the last check before {@code GmailStagingBridge} (C5-B) would turn a receipt into
 * something a user reviews. Its job is narrow: catch a parser bug before it becomes something a
 * user has to notice and dismiss themselves. It is not a fraud detector and not a substitute for
 * user review — a receipt that passes here is still only a proposal, exactly as
 * {@link ParsedReceipt}'s own class doc says.
 */
@Component
public final class ParsedReceiptValidator {

    /**
     * A ceiling on what a personal receipt-derived transaction plausibly is, not on what Finora can
     * represent. Chosen generously — ₹1,00,00,000 (one crore) is far beyond an ordinary consumer
     * purchase from any of the merchants this pipeline targets — specifically so a real (if unusual)
     * large purchase is not the case this rejects. What this catches is a parser reading digits from
     * the wrong place entirely: an order number, a phone number, a promotional code concatenated
     * with a price.
     */
    static final Money MAX_PLAUSIBLE_AMOUNT = Money.of(new BigDecimal("10000000.00"));

    /**
     * How far into the future a transaction date may sit before it is treated as wrong rather than
     * merely a timezone artifact. Not zero: a receipt dated in IST can read as tomorrow relative to
     * a server clock at certain hours, and a validator that rejected that would reject ordinary
     * correct receipts near local midnight. Not large either — a transaction date meaningfully in
     * the future is not a timezone artifact, it is the parser reading the wrong field (a delivery
     * estimate, a subscription renewal date) as the transaction date.
     */
    static final Period FUTURE_DATE_TOLERANCE = Period.ofDays(1);

    /** One field's problem, named so a person (or a future automated retry) knows which extraction
     *  step to distrust without re-deriving it from the message text. */
    public record Violation(String field, String reason) {}

    private final Clock clock;

    public ParsedReceiptValidator() {
        this(Clock.systemDefaultZone());
    }

    /** Package-private: only this package's tests need to fix "now" to make a future-date check
     *  deterministic rather than dependent on the day the test happens to run. */
    ParsedReceiptValidator(Clock clock) {
        this.clock = clock;
    }

    /** Empty when the receipt is plausible. Never throws — an implausible receipt is expected,
     *  ordinary traffic through this validator, not an exceptional one. */
    public List<Violation> validate(ParsedReceipt receipt) {
        List<Violation> violations = new ArrayList<>();

        if (!receipt.amount().isPositive()) {
            violations.add(new Violation("amount",
                    "amount must be greater than zero, was " + receipt.amount()));
        } else if (receipt.amount().isGreaterThan(MAX_PLAUSIBLE_AMOUNT)) {
            violations.add(new Violation("amount",
                    "amount exceeds what a personal receipt plausibly reaches: " + receipt.amount()));
        }

        LocalDate latestPlausible = LocalDate.now(clock).plus(FUTURE_DATE_TOLERANCE);
        if (receipt.transactionDate().isAfter(latestPlausible)) {
            violations.add(new Violation("transactionDate",
                    "transaction date " + receipt.transactionDate() + " is in the future"));
        }

        return violations;
    }

    public boolean isValid(ParsedReceipt receipt) {
        return validate(receipt).isEmpty();
    }
}
