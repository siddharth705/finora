package com.finora.service;

import com.finora.dto.NotificationAdminDetailDto;
import com.finora.dto.NotificationAdminDto;
import com.finora.dto.NotificationAdminDto.ChannelSummary;
import com.finora.dto.NotificationAdminDto.Summary;
import com.finora.dto.PagedResponse;
import com.finora.exception.ApiException;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.util.EnumParsing;
import com.finora.util.PageBounds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read side of the admin notification dashboard (Task 12) -- the operator surface for {@code
 * notifications} / {@code notification_logs}. Every method here is read-only: nothing saves,
 * retries, or otherwise mutates a notification. {@code NotificationDispatcher} (Task 3) is the
 * only thing in this codebase that moves a notification's status, and it stays that way.
 *
 * <p>Deliberately narrow, per the proposal (section 2.5/4): a list, a filter by status, and basic
 * send-outcome counts. No trend charts, no engagement scoring, no open rates -- there is no
 * volume yet to make anything richer meaningful.
 */
@Service
public class AdminNotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationLogRepository logRepository;
    private final AuditService auditService;

    public AdminNotificationService(NotificationRepository notificationRepository,
            NotificationLogRepository logRepository, AuditService auditService) {
        this.notificationRepository = notificationRepository;
        this.logRepository = logRepository;
        this.auditService = auditService;
    }

    /** One page of the outbox, newest first, optionally filtered to a single status. */
    @Transactional(readOnly = true)
    public PagedResponse<NotificationAdminDto> list(String status, int page, int size) {
        NotificationStatus filter = status == null || status.isBlank()
                ? null
                : EnumParsing.parse(NotificationStatus.class, status.trim().toUpperCase(), "status");

        PageRequest pageable = PageRequest.of(PageBounds.safePage(page),
                PageBounds.safeSize(size > 0 ? size : 25), Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Notification> result = filter == null
                ? notificationRepository.findAll(pageable)
                : notificationRepository.findByStatus(filter, pageable);
        return PagedResponse.of(result.map(NotificationAdminDto::from));
    }

    /** Sent/failed counts, overall and per channel -- six cheap counts, not a GROUP BY, matching
     *  AdminLearningQueueService.summary()'s own "simple indexed counts" discipline. See {@link
     *  NotificationAdminDto.Summary}'s own doc comment for why "failed" counts DEAD_LETTER, not
     *  the unused FAILED status value. */
    @Transactional(readOnly = true)
    public Summary summary() {
        long sent = notificationRepository.countByStatus(NotificationStatus.SENT);
        long failed = notificationRepository.countByStatus(NotificationStatus.DEAD_LETTER);

        List<ChannelSummary> byChannel = Arrays.stream(NotificationChannel.values())
                .map(channel -> new ChannelSummary(
                        channel.name(),
                        notificationRepository.countByChannelAndStatus(channel, NotificationStatus.SENT),
                        notificationRepository.countByChannelAndStatus(channel, NotificationStatus.DEAD_LETTER)))
                .toList();

        return new Summary(sent, failed, byChannel);
    }

    /**
     * Notification detail plus its attempt log, newest first.
     *
     * <p>Audit-logged, unlike {@link #list} and {@link #summary}: those are re-fetched by the
     * dashboard's own polling every 30 seconds (see admin-portal's QueryClient staleTime), and
     * logging every one of those would flood {@code audit_logs} with nothing but read traffic --
     * exactly the unbounded-growth concern {@code AuditService}'s own class doc (BH-044) already
     * flags for this table. A detail view is different: it is a deliberate, bounded look at one
     * notification's actual title/message and its provider attempt history, which is worth a
     * trail entry the same way {@code AdminLearningQueueService}'s mutating actions are -- so this
     * follows the same {@code auditService.record(...)} call shape, with the notification's own
     * user as the subject and the acting admin recorded in {@code metadata}.
     *
     * <p><b>Bug fix.</b> Originally {@code @Transactional(readOnly = true)}, which silently
     * discarded the audit write below rather than erroring on it: Spring's {@code
     * HibernateJpaDialect} sets the Hibernate session's flush mode to {@code MANUAL} for a
     * read-only transaction, so a new entity registered via {@code entityManager.persist()} (what
     * {@code JpaRepository.save()} does for a transient row) is never flushed to the database
     * before commit -- no exception, the row just never existed. A read-only-in-spirit method that
     * has one genuine side effect needs a real read-write transaction for that side effect to
     * actually take place; {@code AdminLearningQueueService}'s own {@code retry}/{@code
     * markResolved} (which write) are plain {@code @Transactional} for the same reason, never
     * {@code readOnly = true}.
     */
    @Transactional
    public NotificationAdminDetailDto get(UUID actingAdminId, UUID notificationId) {
        Notification notification = require(notificationId);
        List<NotificationLog> attempts =
                logRepository.findByNotificationIdOrderByTimestampDesc(notificationId);

        auditService.record(notification.getUserId(), "NOTIFICATION_DETAIL_VIEWED", "Notification",
                notificationId, Map.of("actorId", actingAdminId.toString()));

        return NotificationAdminDetailDto.from(notification, attempts);
    }

    private Notification require(UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such notification."));
    }
}
