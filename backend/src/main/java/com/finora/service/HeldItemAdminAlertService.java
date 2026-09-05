package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.util.EmailMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Emails every admin holding the relevant permission the moment a statement lands in one of the
 * two triage queues -- a pointer into the admin portal, never a channel for statement content (see
 * {@code docs/superpowers/specs/2026-09-05-held-item-admin-email-alerts-design.md}).
 *
 * <p>Deliberately bypasses {@link NotificationService}: that system is built entirely around one
 * end-user's own channel preferences per {@code NotificationCategory}, which has no shape for
 * "every user holding permission X, unconditionally." Sends directly via {@link EmailProvider},
 * the same pattern {@code AuthService} already uses for its own transactional emails.
 *
 * <p>Never throws. A failed or missing email is logged and the caller continues -- the import
 * pipeline's success can never depend on email deliverability, the same rule every other
 * side-effecting call in this pipeline already follows.
 */
@Service
public class HeldItemAdminAlertService {

    private static final Logger log = LoggerFactory.getLogger(HeldItemAdminAlertService.class);

    private static final String IMPORT_TRIAGE_MANAGE = "IMPORT_TRIAGE_MANAGE";
    private static final String TRUST_REVIEW_MANAGE = "TRUST_REVIEW_MANAGE";

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final UserRepository userRepository;
    private final ImportJobRepository importJobRepository;
    private final HeldStatementRepository heldStatementRepository;
    private final EmailProvider emailProvider;
    private final EmailProperties emailProperties;

    public HeldItemAdminAlertService(UserRepository userRepository, ImportJobRepository importJobRepository,
                                      HeldStatementRepository heldStatementRepository, EmailProvider emailProvider,
                                      EmailProperties emailProperties) {
        this.userRepository = userRepository;
        this.importJobRepository = importJobRepository;
        this.heldStatementRepository = heldStatementRepository;
        this.emailProvider = emailProvider;
        this.emailProperties = emailProperties;
    }

    /**
     * A parser-gap hold ({@code ImportJob.Status.HELD_FOR_REVIEW}) was just created. Re-reads the
     * job fresh (rather than being handed the entity) so this is safe to call from
     * {@code AfterCommit.run(...)}, which fires after the transaction that created the hold has
     * committed -- a fresh read at that point is guaranteed to see it.
     */
    public void alertParserGapHeld(UUID jobId) {
        Optional<ImportJob> found = importJobRepository.findById(jobId);
        if (found.isEmpty()) {
            log.warn("Could not send a held-item admin alert for import job {}: job no longer exists", jobId);
            return;
        }
        ImportJob job = found.get();
        String subject = "Statement held for review — " + job.getFileName();
        String html = "<p>A statement failed to import and was held for admin review.</p>"
                + "<ul>"
                + "<li><strong>File:</strong> " + escape(job.getFileName()) + "</li>"
                + "<li><strong>Job ID:</strong> " + job.getId() + "</li>"
                + "<li><strong>Reason:</strong> " + escape(job.getLastError()) + "</li>"
                + "<li><strong>Held at:</strong> " + escape(formatTimestamp(job.getFinishedAt())) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/held-imports\">Open the held-imports queue</a></p>";
        sendToRecipients(IMPORT_TRIAGE_MANAGE, subject, html);
    }

    /**
     * A trust-review hold ({@code ImportJob.Status.HELD_FOR_TRUST_REVIEW}) was just created.
     * Re-reads the {@link HeldStatement} fresh by its human-readable id -- same "safe to call from
     * {@code AfterCommit}" reasoning as {@link #alertParserGapHeld}, and {@code heldId} is also
     * exactly what the admin portal's own detail route ({@code /held-statements/:heldId}) already
     * uses, so no separate lookup is needed to build the link.
     */
    public void alertTrustReviewHeld(String heldId) {
        Optional<HeldStatement> found = heldStatementRepository.findByHeldId(heldId);
        if (found.isEmpty()) {
            log.warn("Could not send a held-item admin alert for held statement {}: it no longer exists", heldId);
            return;
        }
        HeldStatement held = found.get();
        String bankLine = held.getBankName() == null || held.getBankName().isBlank()
                ? "" : "<li><strong>Bank:</strong> " + escape(held.getBankName()) + "</li>";
        String subject = "Statement held for trust review — " + held.getHeldId();
        String html = "<p>A statement's extraction was not trusted enough to reach the user's ledger "
                + "unreviewed.</p>"
                + "<ul>"
                + "<li><strong>Held ID:</strong> " + escape(held.getHeldId()) + "</li>"
                + bankLine
                + "<li><strong>Reason:</strong> " + escape(held.getTriggerSummary()) + "</li>"
                + "<li><strong>Held at:</strong> " + escape(formatTimestamp(held.getCreatedAt())) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/held-statements/" + escape(held.getHeldId())
                + "\">Open this held statement</a></p>";
        sendToRecipients(TRUST_REVIEW_MANAGE, subject, html);
    }

    /**
     * Deliberately reads {@code getAdminAppBaseUrl()} directly rather than going through {@link
     * EmailProperties#resolveBaseUrl}, even though that method already has a null-fallback: its
     * fallback is the USER-facing frontend's URL, chosen for password-reset links where a request
     * Origin makes which portal asked genuinely ambiguous. There is no such ambiguity here -- this
     * link is always for an admin, so falling back to the user app would be a wrong-portal link,
     * not a degraded-but-working one. Logs instead, so a missing {@code ADMIN_APP_BASE_URL} is a
     * visible deployment gap rather than a silently broken relative link nobody notices until an
     * admin clicks it.
     */
    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("ADMIN_APP_BASE_URL is not configured -- held-item alert emails will link with "
                    + "no domain until it is set");
            return "";
        }
        return base;
    }

    /** {@code null} only when the entity it came from was built without going through the normal
     *  transition that sets it -- not expected in practice, but a rendered "Held at: unknown" line
     *  is a far better failure than an {@code NPE} inside {@code AfterCommit.run(...)} that quietly
     *  kills the whole alert (the exact bug this guard fixes). */
    private static String formatTimestamp(Instant instant) {
        return instant == null ? "unknown" : TIMESTAMP_FORMAT.format(instant);
    }

    private void sendToRecipients(String permissionName, String subject, String html) {
        List<User> recipients =
                userRepository.findByPermissionNameAndAccountScope(permissionName, User.SCOPE_ADMIN);
        if (recipients.isEmpty()) {
            log.info("No admin holds {} -- no held-item alert sent for \"{}\"", permissionName, subject);
            return;
        }
        for (User recipient : recipients) {
            try {
                EmailResult result = emailProvider.send(EmailMessage.html(recipient.getEmail(), subject, html));
                if (!result.success()) {
                    log.warn("Held-item admin alert to {} failed: {}",
                            EmailMasking.mask(recipient.getEmail()), result.failureReason());
                }
            } catch (RuntimeException e) {
                log.warn("Held-item admin alert to {} threw", EmailMasking.mask(recipient.getEmail()), e);
            }
        }
    }

    /** Escapes every field placed in an HTML email body, via the same {@code HtmlUtils} Spring
     *  Web already ships (on the classpath through {@code spring-boot-starter-web}) rather than a
     *  hand-rolled subset -- the full HTML4 entity set, quotes included, not just the three
     *  characters an attribute-breakout needs. The strings placed here are mostly curated,
     *  already user-safe messages ({@code ExtractionCheck}'s own error text, {@code
     *  HoldDecision.summary()}) -- not raw customer input -- but a filename IS attacker-chosen
     *  (see {@code StatementUpload}'s own doc comment), so escaping every field uniformly costs
     *  nothing and removes any doubt about which ones need it. */
    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
