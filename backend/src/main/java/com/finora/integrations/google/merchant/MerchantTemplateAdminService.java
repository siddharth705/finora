package com.finora.integrations.google.merchant;

import com.finora.exception.ApiException;
import com.finora.integrations.google.TrustedSenderDomain;
import com.finora.integrations.google.TrustedSenderDomainRepository;
import com.finora.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages {@link MerchantTemplate} rows -- the admin-editable half of the templating experiment
 * V85/V86 started. Every template before this class existed was added by a Flyway migration; this
 * is what lets an admin add or fix one (for the "single amount, single date, stable format" case
 * templates cover -- see {@link MerchantTemplate}'s own doc comment) without an engineering
 * release.
 *
 * <p><b>Not the trust boundary.</b> That is {@code TrustedSenderDomainService} /
 * {@code gmail_trusted_sender_domains}: a template for a domain that was never trusted is simply
 * unreachable, since {@code GmailReceiptExtractionService} only ever runs a parser against a
 * message already marked {@code DETECTED_NOT_STAGED}, which requires having already passed
 * {@code SenderAuthenticationService}. This class is a data-quality surface -- a bad pattern still
 * mis-stages a wrong amount into a real user's ledger, so mutations are still audited and nothing
 * is ever hard-deleted, same reasoning as the trust registry, just not the same severity of
 * consequence.
 */
@Service
public class MerchantTemplateAdminService {

    private static final Logger log = LoggerFactory.getLogger(MerchantTemplateAdminService.class);

    private final MerchantTemplateRepository templates;
    private final AuditService auditService;
    private final List<MerchantEmailParser> parsers;
    private final TrustedSenderDomainRepository trustedSenders;

    public MerchantTemplateAdminService(MerchantTemplateRepository templates, AuditService auditService,
                                         List<MerchantEmailParser> parsers,
                                         TrustedSenderDomainRepository trustedSenders) {
        this.templates = templates;
        this.auditService = auditService;
        this.parsers = parsers;
        this.trustedSenders = trustedSenders;
    }

    /** Informational only -- see this class's own doc comment on why {@code merchant_templates} is
     *  not itself the trust boundary. Lets the admin UI warn that a correctly-tested template
     *  still won't run in production until its domain is also in the trust registry, rather than
     *  living behind {@code AdminMerchantTemplateController} reaching into
     *  {@link TrustedSenderDomainRepository} directly, which {@code LayerDependencyDirectionTest}
     *  forbids for exactly the reason its own failure message gives: the transaction boundary,
     *  authorization check and error translation for a repository read belong in one place, a
     *  service, not scattered into each controller that happens to want one field from it. */
    @Transactional(readOnly = true)
    public boolean isDomainTrusted(String domain) {
        return trustedSenders.findByDomain(domain).filter(TrustedSenderDomain::isActive).isPresent();
    }

    @Transactional(readOnly = true)
    public List<MerchantTemplate> listAll() {
        return templates.findAllByOrderByMerchantNameAscMerchantDomainAsc();
    }

    @Transactional(readOnly = true)
    public MerchantTemplate get(UUID id) {
        return templates.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such merchant template."));
    }

    /**
     * Creates a new template, disabled by default -- see {@code MerchantTemplateTestRunner} and
     * the admin UI's own gate: a template must be tested against a real sample before it is worth
     * activating, and activation is a separate, audited call ({@link #activate}), never a side
     * effect of creation.
     */
    @Transactional
    public MerchantTemplate create(UUID actingAdminId, String rawDomain, String merchantName,
                                    String receiptMarker, String amountPattern, String datePattern) {
        String domain = requireValidDomain(rawDomain);
        requireNonBlank(merchantName, "A merchant name is required.");
        requireNonBlank(receiptMarker, "A receipt marker is required.");
        requireNonBlank(amountPattern, "An amount pattern is required.");
        requireNonBlank(datePattern, "A date pattern is required.");

        templates.findByMerchantDomain(domain).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A template for " + domain + " already exists ("
                            + (existing.isEnabled() ? "enabled" : "disabled")
                            + "). Edit that one instead of creating a second row.");
        });
        rejectIfClaimedByAnotherParser(domain);

        MerchantTemplate entry = new MerchantTemplate();
        entry.setMerchantDomain(domain);
        entry.setMerchantName(merchantName.trim());
        entry.setReceiptMarker(receiptMarker);
        entry.setAmountPattern(amountPattern);
        entry.setDatePattern(datePattern);
        requireCompilablePatterns(entry);
        // Ignoring any client-sent enabled value on purpose -- see the class doc and
        // MerchantTemplateAdminService's own javadoc above: untested-by-construction on creation.
        entry.setEnabled(false);
        entry.setCreatedByUserId(actingAdminId);
        MerchantTemplate saved = templates.save(entry);

        auditService.record(actingAdminId, "GMAIL_MERCHANT_TEMPLATE_CREATED",
                "MerchantTemplate", saved.getId(),
                Map.of("merchantDomain", domain, "merchantName", saved.getMerchantName()));
        log.info("Merchant template for {} created by admin {}, disabled pending test.", domain, actingAdminId);
        return saved;
    }

    /**
     * Edits an existing template. The domain itself is not accepted here and cannot change --
     * same reasoning as {@code TrustedSenderDomainService.rename}: moving a row to a different
     * domain in place would leave an audit trail that still describes the original.
     *
     * <p>Unlike {@code rename} (label-only), this DOES allow editing the matching fields
     * (receiptMarker/amountPattern/datePattern) in place -- fixing a template after a merchant
     * changes their email format, without a new row, is the entire point of this method existing.
     * If any of those three fields actually changes while the template is currently enabled, it is
     * forced back to disabled as part of the same update: an untested fix must not go live simply
     * because it was typed into an edit form for an already-active template.
     */
    @Transactional
    public MerchantTemplate update(UUID actingAdminId, UUID id, String merchantName,
                                    String receiptMarker, String amountPattern, String datePattern) {
        requireNonBlank(merchantName, "A merchant name is required.");
        requireNonBlank(receiptMarker, "A receipt marker is required.");
        requireNonBlank(amountPattern, "An amount pattern is required.");
        requireNonBlank(datePattern, "A date pattern is required.");

        MerchantTemplate entry = get(id);
        boolean matchingFieldsChanged = !Objects.equals(entry.getReceiptMarker(), receiptMarker)
                || !Objects.equals(entry.getAmountPattern(), amountPattern)
                || !Objects.equals(entry.getDatePattern(), datePattern);
        boolean autoDisabled = matchingFieldsChanged && entry.isEnabled();

        entry.setMerchantName(merchantName.trim());
        entry.setReceiptMarker(receiptMarker);
        entry.setAmountPattern(amountPattern);
        entry.setDatePattern(datePattern);
        requireCompilablePatterns(entry);
        if (autoDisabled) {
            entry.setEnabled(false);
        }
        MerchantTemplate saved = templates.save(entry);

        auditService.record(actingAdminId, "GMAIL_MERCHANT_TEMPLATE_UPDATED",
                "MerchantTemplate", saved.getId(),
                Map.of("merchantDomain", saved.getMerchantDomain(),
                        "matchingFieldsChanged", matchingFieldsChanged,
                        "autoDisabled", autoDisabled));
        if (autoDisabled) {
            log.info("Merchant template for {} edited by admin {}; auto-disabled pending re-test "
                    + "(matching fields changed on a previously active template).",
                    saved.getMerchantDomain(), actingAdminId);
        }
        return saved;
    }

    /** Separate, audited, explicit -- mirrors {@code TrustedSenderDomainService.setStatus}'s own
     *  reasoning: going live is a deliberate act, never a side effect of an edit. */
    @Transactional
    public MerchantTemplate activate(UUID actingAdminId, UUID id) {
        return setEnabled(actingAdminId, id, true, "GMAIL_MERCHANT_TEMPLATE_ACTIVATED");
    }

    /** Never a hard delete, same reasoning as {@code TrustedSenderDomain}: "who decided this and
     *  when" must stay answerable, and a bad past template still mis-staged real amounts. */
    @Transactional
    public MerchantTemplate deactivate(UUID actingAdminId, UUID id) {
        return setEnabled(actingAdminId, id, false, "GMAIL_MERCHANT_TEMPLATE_DEACTIVATED");
    }

    private MerchantTemplate setEnabled(UUID actingAdminId, UUID id, boolean enabled, String auditAction) {
        MerchantTemplate entry = get(id);
        if (entry.isEnabled() == enabled) {
            return entry; // no-op, nothing worth auditing
        }
        entry.setEnabled(enabled);
        MerchantTemplate saved = templates.save(entry);

        auditService.record(actingAdminId, auditAction, "MerchantTemplate", saved.getId(),
                Map.of("merchantDomain", saved.getMerchantDomain()));
        log.info("Merchant template for {} set to enabled={} by admin {}.",
                saved.getMerchantDomain(), enabled, actingAdminId);
        return saved;
    }

    /** {@link MerchantTemplate#compileAmountPattern()}/{@code compileDatePattern()} already throw
     *  on a malformed placeholder -- calling them here, eagerly, at save time turns a typo into an
     *  immediate, specific error instead of a template that silently fails on every real message
     *  until someone notices via {@code GmailMerchantStatsService}'s stats page. */
    private void requireCompilablePatterns(MerchantTemplate template) {
        try {
            template.compileAmountPattern();
            template.compileDatePattern();
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** No {@code @Order}/{@code Ordered} exists anywhere in this package, and
     *  {@code GmailReceiptExtractionService} picks the first parser whose {@code canParse} returns
     *  true -- so a template claiming a domain a hand-written parser (Amazon, Myntra, Ola,
     *  Booking.com) already handles would create real, undocumented nondeterminism between the
     *  two. Checked against every registered {@link MerchantEmailParser} except
     *  {@link TemplateEmailParser} itself, which would otherwise always match nothing-yet-created
     *  templates trivially. */
    private void rejectIfClaimedByAnotherParser(String domain) {
        boolean claimed = parsers.stream()
                .filter(p -> !(p instanceof TemplateEmailParser))
                .anyMatch(p -> p.canParse(domain));
        if (claimed) {
            throw new ApiException(HttpStatus.CONFLICT,
                    domain + " is already handled by a hand-written parser -- a template for this "
                            + "domain would create undefined behavior about which one actually runs.");
        }
    }

    private String requireValidDomain(String rawDomain) {
        requireNonBlank(rawDomain, "A domain is required.");
        // Reuses TrustedSenderDomain's own canonicalisation rather than a second implementation --
        // this table matches TrustedSenderDomain.domain by value (routing comment on
        // MerchantTemplate.merchantDomain), so the two must normalize identically or a template
        // could silently fail to line up with the trust registry's own casing/trailing-dot rules.
        return TrustedSenderDomain.normalize(rawDomain);
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
