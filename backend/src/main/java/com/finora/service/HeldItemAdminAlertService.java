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
                + "<li><strong>Held at:</strong> " + escape(TIMESTAMP_FORMAT.format(job.getFinishedAt())) + "</li>"
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
                + "<li><strong>Held at:</strong> " + escape(TIMESTAMP_FORMAT.format(held.getCreatedAt())) + "</li>"
                + "</ul>"
                + "<p><a href=\"" + adminBaseUrl() + "/held-statements/" + held.getHeldId()
                + "\">Open this held statement</a></p>";
        sendToRecipients(TRUST_REVIEW_MANAGE, subject, html);
    }

    private String adminBaseUrl() {
        String base = emailProperties.getAdminAppBaseUrl();
        return base == null ? "" : base;
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

    /** Escapes the handful of characters that matter in an HTML email body. The strings placed
     *  here are curated, already user-safe messages ({@code ExtractionCheck}'s own error text,
     *  {@code HoldDecision.summary()}) -- not raw customer input -- but a filename IS attacker-
     *  chosen (see {@code StatementUpload}'s own doc comment), so this costs nothing and removes
     *  any doubt for every field, not just that one. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
