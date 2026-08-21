package com.finora.integrations.google;

import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the trusted sender registry — Phase C3.
 *
 * <p><b>Every mutation here is a security decision, not configuration.</b> Adding a domain grants
 * parse-trust to a new sender: from that moment, authenticated mail from it may become financial
 * records in someone's ledger. That is why each change is audited with the acting admin, and why
 * nothing is ever hard-deleted.
 */
@Service
public class TrustedSenderDomainService {

    private static final Logger log = LoggerFactory.getLogger(TrustedSenderDomainService.class);

    /**
     * Rejects anything that is not a plausible bare domain.
     *
     * <p>Not decoration. The gate matches on exactly this string, so a value carrying a scheme, a
     * path, a wildcard, or whitespace would either never match anything (a silently useless entry an
     * admin believes is protecting them) or match something unintended. Wildcards are refused
     * outright rather than interpreted, since the one thing this registry must never support is a
     * pattern.
     */
    private static final java.util.regex.Pattern PLAUSIBLE_DOMAIN =
            java.util.regex.Pattern.compile("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]*[a-z0-9])?)+$");

    private final TrustedSenderDomainRepository domains;
    private final AuditService auditService;

    public TrustedSenderDomainService(TrustedSenderDomainRepository domains, AuditService auditService) {
        this.domains = domains;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<TrustedSenderDomain> listAll() {
        return domains.findAllByOrderByMerchantNameAscDomainAsc();
    }

    /**
     * Adds a domain to the registry.
     *
     * <p>A domain that already exists is a conflict rather than an update, deliberately: silently
     * re-enabling a domain someone previously disabled would undo a security decision without
     * anyone deciding to. Re-enabling is an explicit {@link #setStatus} call, and it is audited as
     * one.
     */
    @Transactional
    public TrustedSenderDomain add(UUID actingAdminId, String rawDomain, String merchantName) {
        String domain = requireValidDomain(rawDomain);
        if (merchantName == null || merchantName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A merchant name is required.");
        }

        domains.findByDomain(domain).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "That domain is already in the registry (" + existing.getStatus() + "). "
                            + "Change its status instead of adding it again.");
        });

        TrustedSenderDomain entry = new TrustedSenderDomain();
        entry.setDomain(domain);
        entry.setMerchantName(merchantName.trim());
        entry.setStatus(TrustedSenderDomain.Status.ACTIVE);
        entry.setAddedByUserId(actingAdminId);
        TrustedSenderDomain saved = domains.save(entry);

        auditService.record(actingAdminId, "GMAIL_TRUSTED_DOMAIN_CREATED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", domain, "merchantName", saved.getMerchantName()));
        log.info("Trusted sender domain {} added for {} by admin {}.", domain, merchantName, actingAdminId);
        return saved;
    }

    /**
     * Enables or disables a domain — the "delete" this registry has.
     *
     * <p>Nothing is ever removed. "When did we stop trusting this domain, and who decided" is the
     * question asked after an incident, and a deleted row cannot answer it. A disabled entry behaves
     * exactly as an absent one at the gate.
     */
    @Transactional
    public TrustedSenderDomain setStatus(UUID actingAdminId, UUID id, TrustedSenderDomain.Status status) {
        TrustedSenderDomain entry = domains.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such trusted sender domain."));

        TrustedSenderDomain.Status previous = entry.getStatus();
        if (previous == status) {
            return entry; // no-op, and nothing worth auditing
        }
        entry.setStatus(status);
        TrustedSenderDomain saved = domains.save(entry);

        // Both directions are audited. Disabling is the obvious one; RE-ENABLING is the one that
        // silently restores parse-trust, so it is if anything the more important of the two.
        auditService.record(actingAdminId,
                status == TrustedSenderDomain.Status.ACTIVE
                        ? "GMAIL_TRUSTED_DOMAIN_ENABLED" : "GMAIL_TRUSTED_DOMAIN_DISABLED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", saved.getDomain(),
                        "previousStatus", previous.name(),
                        "newStatus", status.name()));
        log.info("Trusted sender domain {} moved {} -> {} by admin {}.",
                saved.getDomain(), previous, status, actingAdminId);
        return saved;
    }

    /**
     * Changes the merchant label. Deliberately cannot change the DOMAIN.
     *
     * <p>Editing a domain in place would silently move trust from one sender to another under a row
     * whose audit trail still describes the first — "amazon.in, added by X on date Y" would now mean
     * something else entirely. Trusting a different domain is an add, and untrusting one is a
     * disable; both leave a record of what actually happened.
     */
    @Transactional
    public TrustedSenderDomain rename(UUID actingAdminId, UUID id, String merchantName) {
        if (merchantName == null || merchantName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A merchant name is required.");
        }
        TrustedSenderDomain entry = domains.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such trusted sender domain."));

        String previous = entry.getMerchantName();
        entry.setMerchantName(merchantName.trim());
        TrustedSenderDomain saved = domains.save(entry);

        auditService.record(actingAdminId, "GMAIL_TRUSTED_DOMAIN_RELABELLED",
                "TrustedSenderDomain", saved.getId(),
                Map.of("domain", saved.getDomain(),
                        "previousMerchantName", previous,
                        "newMerchantName", saved.getMerchantName()));
        return saved;
    }

    private String requireValidDomain(String rawDomain) {
        if (rawDomain == null || rawDomain.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A domain is required.");
        }
        String domain = TrustedSenderDomain.normalize(rawDomain);
        if (domain.contains("*") || domain.contains("/") || domain.contains("@") || domain.contains(" ")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Enter a bare domain such as amazon.in -- wildcards, addresses and URLs are not "
                            + "accepted, because matching is exact by design.");
        }
        if (!PLAUSIBLE_DOMAIN.matcher(domain).matches() || domain.length() > 253) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "That does not look like a domain. Enter a bare domain such as amazon.in.");
        }
        return domain;
    }
}
