package com.finora.integrations.google.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One merchant's receipt read as data instead of code — Phase C5.2, the experiment the design
 * review asked for before committing to a template engine.
 *
 * <h2>The placeholder language is deliberately tiny</h2>
 *
 * Exactly one {@code {amount}} in {@link #amountPattern} and one {@code {date}} in
 * {@link #datePattern}; everything else in either string is matched as literal text. No regex
 * authoring, no nested placeholders, no conditionals — an editor of this table needs to be able to
 * copy a label straight out of a real email ({@code "Total: Rs. {amount}"}) and have it work,
 * which is the whole point of templating over a parser class. {@link #compileAmountPattern} and
 * {@link #compileDatePattern} are what turn that into something {@link TemplateEmailParser} can
 * actually match against — see their own doc comments for the compiled shape and why it mirrors
 * {@code AmazonEmailParser}'s hand-written regex rather than inventing a different one.
 */
@Entity
@Table(name = "merchant_templates")
public class MerchantTemplate {

    private static final String AMOUNT_PLACEHOLDER = "{amount}";
    private static final String DATE_PLACEHOLDER = "{date}";

    /**
     * The captured amount shape. Identical to {@code AmazonEmailParser.TOTAL}'s capture group,
     * intentionally — bounded digit run (an unbounded one is an allocation an attacker fully
     * controls the size of; a trusted sender bounds who signed the message, not what's inside it)
     * and boundary-anchored (a template edited to have only one candidate match position per
     * message is exactly what a literal-text-anchored pattern already is, but the anchors are kept
     * explicit rather than inferred from that, for the same reason {@code AmazonEmailParser}'s own
     * doc comment gives).
     */
    private static final String AMOUNT_CAPTURE = "(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?!\\d)";

    /**
     * A specific alternation of date SHAPES, not a loose character class over letters/digits/
     * punctuation — the first version of this was exactly that ({@code [A-Za-z0-9,:/ -]{1,40}}),
     * and it does not stay inside the date: ordinary sentence text after the date (the same letters,
     * digits, commas and spaces a real date is made of) keeps matching too, so the capture ran past
     * "August 12, 2026" straight into the following sentence and never stopped until 40 characters
     * or a character outside the class. Caught by {@code TemplateEmailParserTest} against a fixture
     * with trailing prose after the date, which a template pattern authored and tested only against
     * a receipt with nothing after the date would not have exposed.
     *
     * <p>Each alternative here corresponds to one {@link ReceiptDateFormats} entry. The two lists
     * are not mechanically coupled — a format added to one without the other silently fails to
     * capture (falls through every alternative here) or silently fails to parse (captures but
     * {@code ReceiptDateFormats.tryParse} returns null) — both already surface as {@code
     * ParserResult.malformed}, not a silent wrong answer, so the coupling is a maintenance note
     * worth keeping in mind rather than a live correctness gap.
     */
    private static final String DATE_CAPTURE =
            "([A-Za-z]+ \\d{1,2}, \\d{4}"          // "August 12, 2026"      -- MMMM d, yyyy
            + "|\\d{4}-\\d{2}-\\d{2}"               // "2026-08-12"           -- ISO_LOCAL_DATE
            + "|\\d{1,2} [A-Za-z]+ \\d{4}"          // "12 August 2026"       -- d MMMM yyyy
            + "|\\d{1,2}/\\d{1,2}/\\d{4}"           // "12/08/2026"           -- dd/MM/yyyy
            + "|\\d{1,2}-\\d{1,2}-\\d{4})";         // "12-08-2026"           -- dd-MM-yyyy

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_domain", nullable = false, length = 253)
    private String merchantDomain;

    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    /** Literal substring, not a regex — the same reason placeholder patterns below are also kept
     *  to plain text where possible. */
    @Column(name = "receipt_marker", nullable = false)
    private String receiptMarker;

    @Column(name = "amount_pattern", nullable = false)
    private String amountPattern;

    @Column(name = "date_pattern", nullable = false)
    private String datePattern;

    /** Optional. Pipe-separated literal phrases that, if any is found, mean this message is NOT a
     *  receipt for this template — the templated equivalent of {@code MyntraEmailParser}'s
     *  hand-written {@code RETURN_OR_REFUND_MARKER}: a refund/return/exchange/cancellation notice
     *  from the same domain routinely reuses the same amount/date-shaped language a real purchase
     *  receipt does, and without this a template would extract the amount and stage it as an
     *  EXPENSE regardless. Null/blank matches nothing, so every template predating this field
     *  (including the V85/V86 seeds and the V103 readiness seed) is unaffected. */
    @Column(name = "non_receipt_marker")
    private String nonReceiptMarker;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Null for the V85/V86 migration-seeded rows (Uber, Zomato), which predate any admin actor --
     *  same posture {@code TrustedSenderDomain.addedByUserId} already has for its own seeds. */
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MerchantTemplate() {
        // JPA
    }

    /** Whether {@code text} contains this template's receipt marker — checked before either
     *  pattern is compiled, since a message that isn't this marker's kind of receipt at all has
     *  nothing worth extracting regardless of what the patterns say. */
    public boolean matchesReceiptMarker(String text) {
        return text != null && text.contains(receiptMarker);
    }

    /** Whether {@code text} contains any of {@link #nonReceiptMarker}'s pipe-separated phrases —
     *  checked before {@link #matchesReceiptMarker}, since a refund/return notice that happens to
     *  also contain this template's receipt marker and a plausible amount is still not a purchase.
     *  Kept as plain {@link String#contains} per phrase, not a compiled {@link Pattern}, for the
     *  same "no regex authoring" reason {@link #matchesReceiptMarker} is. */
    public boolean matchesNonReceiptMarker(String text) {
        if (text == null || nonReceiptMarker == null || nonReceiptMarker.isBlank()) {
            return false;
        }
        for (String phrase : nonReceiptMarker.split("\\|")) {
            if (!phrase.isBlank() && text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles {@link #amountPattern} into a regex with exactly one capturing group, over the
     * amount's shape.
     *
     * @throws IllegalStateException if the pattern does not contain exactly one {@code {amount}}
     *         placeholder — a misauthored template (a typo, a copy-paste that dropped the
     *         placeholder) fails loudly at compile time rather than silently matching nothing or,
     *         worse, matching literal text that happens to contain the word "amount"
     */
    public Pattern compileAmountPattern() {
        return Pattern.compile(compile(amountPattern, AMOUNT_PLACEHOLDER, AMOUNT_CAPTURE),
                Pattern.CASE_INSENSITIVE);
    }

    public Pattern compileDatePattern() {
        return Pattern.compile(compile(datePattern, DATE_PLACEHOLDER, DATE_CAPTURE),
                Pattern.CASE_INSENSITIVE);
    }

    /**
     * The compiler. Splits on the placeholder, regex-escapes both literal halves independently
     * (via {@link Pattern#quote}), and rejoins with the capture group in between -- so nothing in
     * the literal text a template author writes is ever interpreted as regex syntax, which matters
     * because that text is exactly the kind of copy-pasted label (parentheses, dots, currency
     * symbols) that would otherwise silently change what the pattern matches.
     */
    private static String compile(String pattern, String placeholder, String captureGroup) {
        int index = pattern.indexOf(placeholder);
        if (index < 0 || pattern.indexOf(placeholder, index + 1) >= 0) {
            throw new IllegalStateException(
                    "Template pattern must contain exactly one " + placeholder + ": " + pattern);
        }
        String before = pattern.substring(0, index);
        String after = pattern.substring(index + placeholder.length());
        StringBuilder regex = new StringBuilder();
        if (!before.isEmpty()) regex.append(Pattern.quote(before));
        regex.append(captureGroup);
        if (!after.isEmpty()) regex.append(Pattern.quote(after));
        return regex.toString();
    }

    public UUID getId() { return id; }
    public String getMerchantDomain() { return merchantDomain; }
    public void setMerchantDomain(String merchantDomain) { this.merchantDomain = merchantDomain; touch(); }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; touch(); }
    public String getReceiptMarker() { return receiptMarker; }
    public void setReceiptMarker(String receiptMarker) { this.receiptMarker = receiptMarker; touch(); }
    public String getNonReceiptMarker() { return nonReceiptMarker; }
    public void setNonReceiptMarker(String nonReceiptMarker) { this.nonReceiptMarker = nonReceiptMarker; touch(); }
    public String getAmountPattern() { return amountPattern; }
    public void setAmountPattern(String amountPattern) { this.amountPattern = amountPattern; touch(); }
    public String getDatePattern() { return datePattern; }
    public void setDatePattern(String datePattern) { this.datePattern = datePattern; touch(); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; touch(); }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(UUID createdByUserId) { this.createdByUserId = createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Manual rather than a JPA {@code @PreUpdate}, matching {@code TrustedSenderDomain}'s own
     *  pattern exactly -- this table had no admin mutation path before this feature, so
     *  {@code updatedAt} was previously only ever set once, at construction. */
    private void touch() { this.updatedAt = Instant.now(); }
}
