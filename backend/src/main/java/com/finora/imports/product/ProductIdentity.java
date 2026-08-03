package com.finora.imports.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Who a discovered product IS, so re-importing next month's statement recognises it instead of
 * creating another one.
 *
 * Classification is about to find the same fixed deposit in every monthly statement. Without an
 * identity it would create a new one each time and double-count it in net worth -- and unpicking
 * that afterwards is a data migration plus a merge UI, not a bug fix.
 *
 * <h2>The key is a hash, not the number</h2>
 *
 * {@link #strongKey} is a one-way hash of institution + the product's own full number. Exact
 * matching needs a value that compares equal across imports; it does not need a readable account
 * number. Storing the number itself would put customer data in a column that exists purely for
 * equality checks -- somewhere nobody would remember to look for it, and nothing would ever read
 * back. The masked last-4 already exists separately for display.
 *
 * <h2>Matching is a decision, not a lookup</h2>
 *
 * Only a strong-key match is certain. A masked number plus an institution is a coincidence away
 * from being wrong -- two deposits at the same bank ending 4521 is entirely ordinary -- so that
 * case is reported as {@link Match#PROBABLE} and belongs on the review screen. "Probably the same
 * FD" means ask, because silently merging two different deposits corrupts both, and silently
 * splitting one duplicates it.
 */
public record ProductIdentity(String institutionId, FinancialProductType type, String strongKey,
                              String maskedNumber) {

    /** How confident a match between two identities is. */
    public enum Match {
        /** Same institution and same product number. Safe to treat as the same product. */
        EXACT,
        /** Same institution, same product type, same masked digits -- and no full number to settle
         *  it. Plausible, not proven: this goes to the user, never to a silent merge. */
        PROBABLE,
        /** Not the same product, or not enough to say. */
        NONE
    }

    /**
     * @param institutionId  the bank/provider id, or null when undetected
     * @param type           what the product was classified as
     * @param fullNumber     the product's own full number as extracted. Hashed immediately and
     *                       never retained -- callers must not store it either.
     * @param maskedNumber   the display form (last 4), which may be all a document offers
     */
    public static ProductIdentity of(String institutionId, FinancialProductType type,
                                     String fullNumber, String maskedNumber) {
        return of(institutionId, type, fullNumber, maskedNumber, null);
    }

    /**
     * @param discriminator distinguishes several products that share one account number, or null
     *        when the number alone identifies the product.
     *
     *        Needed because a deposit section's account number is the CUSTOMER's relationship
     *        number: it appears once in the section's metadata and says nothing about which of the
     *        deposits listed underneath it is which. Without a discriminator every deposit in the
     *        section hashed to the same key, and the consequence was not a cosmetic duplicate --
     *        {@link ProductIdentityResolver} found exactly one EXACT match for the second deposit,
     *        so confirm() silently redirected it into the first deposit's account instead of
     *        creating it, and the second deposit disappeared. That is the mirror image of the
     *        double-counting this class exists to prevent, and worse: a duplicate is visible in the
     *        UI, a deposit that was never created is not.
     *
     *        Must be built from a product's STABLE terms only -- see
     *        {@link #forDeposit} for what qualifies and what does not.
     */
    public static ProductIdentity of(String institutionId, FinancialProductType type,
                                     String fullNumber, String maskedNumber, String discriminator) {
        return new ProductIdentity(normalize(institutionId), type,
                hash(institutionId, fullNumber, discriminator), normalizeDigits(maskedNumber));
    }

    /**
     * A deposit's discriminator: its principal, its maturity date, and its installment amount.
     *
     * Every one of these is fixed for the life of the deposit, which is what makes the identity
     * repeatable -- next month's statement lists the same deposit with the same terms and computes
     * the same key, so it is recognised rather than created again.
     *
     * Deliberately NOT the current balance or the number of installments PAID, both of which change
     * every month. Including either would give the same deposit a different identity in every
     * statement, turning a re-import into a new account each time -- exactly the failure identity
     * exists to prevent, just arrived at from the other direction.
     *
     * Returns null when the deposit has no terms at all, so a product with nothing to distinguish
     * it falls back to number-only identity rather than to a hash of three nulls (which would make
     * every attribute-less deposit identical again).
     */
    public static String forDeposit(java.math.BigDecimal principalAmount, java.time.LocalDate maturityDate,
                                    java.math.BigDecimal installmentAmount) {
        if (principalAmount == null && maturityDate == null && installmentAmount == null) return null;
        // Plain toString on BigDecimal keeps scale ("5000.00" != "5000"), so the same amount printed
        // with different precision across two statements would otherwise be two identities.
        // stripTrailingZeros normalises that; toPlainString avoids scientific notation.
        return normalizeAmount(principalAmount) + "|" + (maturityDate == null ? "" : maturityDate)
                + "|" + normalizeAmount(installmentAmount);
    }

    private static String normalizeAmount(java.math.BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    /** Rebuilds an identity from what a stored account already has, for comparison against a
     *  freshly discovered one. */
    public static ProductIdentity stored(String institutionId, FinancialProductType type,
                                         String strongKey, String maskedNumber) {
        return new ProductIdentity(normalize(institutionId), type, strongKey,
                normalizeDigits(maskedNumber));
    }

    public Match matches(ProductIdentity other) {
        if (other == null) return Match.NONE;

        // Institution must agree before anything else is worth comparing. An unknown institution on
        // either side is not agreement -- "OTHER" matching "OTHER" would make every unidentified
        // product the same product.
        if (institutionId == null || other.institutionId == null
                || !institutionId.equals(other.institutionId)) {
            return Match.NONE;
        }

        if (strongKey != null && strongKey.equals(other.strongKey)) return Match.EXACT;

        // Two strong keys that DISAGREE settle the question -- these are different products, and
        // there is nothing for the weaker fallback below to add.
        //
        // Without this, several deposits listed under one section's account number all share that
        // section's masked digits, so every pair of them matched PROBABLE on the fallback and each
        // one was flagged "might be the same product" forever, pushing every deposit to manual
        // review permanently. The masked fallback exists for the case where no full number was
        // available; when both sides have one, disagreement is positive evidence of difference
        // rather than absence of evidence.
        if (strongKey != null && other.strongKey != null) return Match.NONE;

        // Falling back to the masked digits requires the product TYPE to agree too. A savings
        // account and a fixed deposit at the same bank ending in the same four digits are a
        // coincidence, not one product -- and without the type check this fallback would happily
        // merge them.
        boolean sameMasked = maskedNumber != null && maskedNumber.equals(other.maskedNumber);
        if (sameMasked && type == other.type) return Match.PROBABLE;

        return Match.NONE;
    }

    /** True when this identity is strong enough to act on without asking. */
    public boolean isResolvable() {
        return institutionId != null && strongKey != null;
    }

    private static String hash(String institutionId, String fullNumber, String discriminator) {
        String digits = normalizeDigits(fullNumber);
        if (institutionId == null || digits == null || digits.length() < 4) return null;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String material = normalize(institutionId) + ':' + digits
                    + (discriminator == null ? "" : ':' + discriminator);
            byte[] out = sha.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is genuinely absent, failing loudly beats
            // silently degrading every product to "no strong identity" and duplicating them all.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Digits only: statements render the same number as "1234 5678", "1234-5678" and "XXXX5678"
     *  across pages of one document, and three spellings of one number must not be three products. */
    private static String normalizeDigits(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) return null;
        String trimmed = id.trim().toUpperCase(Locale.ROOT);
        // BankRegistry's "unrecognised" sentinel is not an institution. Treating it as one would
        // make every product from an unrecognised bank identical to every other.
        return "OTHER".equals(trimmed) ? null : trimmed;
    }
}
