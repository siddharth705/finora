package com.finora.integrations.google;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One domain Finora is willing to read financial detail from — Phase C3, design proposal §12.2.
 *
 * <p>Membership here is half of the trust decision. The other half is that the message actually
 * came from this domain, which {@link SenderAuthenticationService} establishes from Gmail's own
 * DKIM/SPF/DMARC verdict. Neither is sufficient alone: authentication proves a message really came
 * from the domain it claims, and says nothing about whether that domain is one Finora should parse
 * receipts from — anyone can DKIM-sign mail from a domain they own.
 *
 * <p><b>Adding a row is a security action, not configuration.</b> It grants parse-trust to a new
 * sender, which is why the management endpoints are admin-only and audited.
 */
@Entity
@Table(name = "gmail_trusted_sender_domains")
public class TrustedSenderDomain {

    public enum Status {
        /** Messages authenticated as coming from this domain may be parsed. */
        ACTIVE,
        /** Kept for the audit trail, treated exactly as if absent. Disabling rather than deleting
         *  preserves the answer to "when did we stop trusting this, and who decided". */
        DISABLED
    }

    @Id
    @GeneratedValue
    private UUID id;

    /** Lower-case, matched exactly. See {@link #normalize}. */
    @Column(nullable = false, length = 253)
    private String domain;

    /** Display and grouping only — never used for matching, so a wrong label cannot widen trust. */
    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "added_by_user_id")
    private UUID addedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * The single place a domain is canonicalised, so that storage and lookup cannot disagree.
     *
     * <p>Lower-cased because DNS is case-insensitive and {@code AMAZON.IN} is the same host as
     * {@code amazon.in} — storing them as different rows would let a trusted domain be silently
     * duplicated, or a lookup miss a domain that is present. A trailing dot (the fully-qualified
     * form, {@code amazon.in.}) is stripped for the same reason.
     *
     * <p>Deliberately does NOT strip subdomains: {@code mail.amazon.in} is a different sender from
     * {@code amazon.in} and earns its own row if it needs one. Collapsing them here would be a
     * suffix rule wearing a normaliser's clothes.
     */
    public static String normalize(String domain) {
        if (domain == null) return null;
        String trimmed = domain.trim().toLowerCase();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Rejects anything that is not a plausible bare domain.
     *
     * <p>Originally lived only in {@code TrustedSenderDomainService.requireValidDomain} -- moved
     * here, unchanged, so {@code MerchantTemplateAdminService.create} could reuse the identical
     * rule rather than accepting a weaker check of its own. Both callers take an admin-entered
     * domain string; a wildcard, a URL, or an email address should be refused identically by
     * either one, not more strictly in one place than the other. Not decoration even for a table
     * that (unlike this one) is not itself the trust boundary: a malformed value here would
     * silently never match anything real, and an admin deserves to be told why at the moment they
     * typed it rather than discover it later as a template that mysteriously never fires.
     *
     * @throws IllegalArgumentException if {@code rawDomain} is null/blank, carries a scheme, a
     *         path, a wildcard, an {@code @}, or whitespace, or otherwise does not match a
     *         plausible domain shape
     */
    public static String requireValid(String rawDomain) {
        if (rawDomain == null || rawDomain.isBlank()) {
            throw new IllegalArgumentException("A domain is required.");
        }
        String domain = normalize(rawDomain);
        if (domain.contains("*") || domain.contains("/") || domain.contains("@") || domain.contains(" ")) {
            throw new IllegalArgumentException(
                    "Enter a bare domain such as amazon.in -- wildcards, addresses and URLs are not "
                            + "accepted, because matching is exact by design.");
        }
        if (!PLAUSIBLE_DOMAIN.matcher(domain).matches() || domain.length() > 253) {
            throw new IllegalArgumentException(
                    "That does not look like a domain. Enter a bare domain such as amazon.in.");
        }
        return domain;
    }

    /** See {@link #requireValid}'s own doc comment for why this is not a looser check. */
    private static final Pattern PLAUSIBLE_DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]*[a-z0-9])?)+$");

    public boolean isActive() { return status == Status.ACTIVE; }

    public UUID getId() { return id; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = normalize(domain); touch(); }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; touch(); }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; touch(); }
    public UUID getAddedByUserId() { return addedByUserId; }
    public void setAddedByUserId(UUID addedByUserId) { this.addedByUserId = addedByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void touch() { this.updatedAt = Instant.now(); }
}
