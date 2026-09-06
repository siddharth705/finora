package com.finora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.dto.NotificationAdminDetailDto;
import com.finora.dto.NotificationAdminDto;
import com.finora.exception.ApiException;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit-level coverage for AdminNotificationService (Task 12) -- the admin notification
 * dashboard's read side: summary counts, the filtered list, and the detail view's attempt log.
 * Mockito-based, matching AdminUserServiceTest/AdminLearningQueueService's own test style:
 * {@code mock(Class.class)} wiring in {@code @BeforeEach}, no {@code @Mock}/{@code @InjectMocks}/
 * {@code MockitoExtension}, AssertJ assertions.
 */
class AdminNotificationServiceTest {

    private NotificationRepository notificationRepository;
    private NotificationLogRepository logRepository;
    private AuditService auditService;
    private AdminNotificationService service;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        logRepository = mock(NotificationLogRepository.class);
        auditService = mock(AuditService.class);
        service = new AdminNotificationService(notificationRepository, logRepository, auditService);
    }

    private Notification notification(UUID id, NotificationChannel channel) {
        Notification n = Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, channel, NotificationPriority.NORMAL,
                "IMPORT_READY_" + id + ":" + channel, "Statement ready",
                "Your statement was imported.", Instant.parse("2026-09-01T10:00:00Z"));
        ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    @Test
    void summary_returnsSentAndFailedCounts_overallAndByChannel() {
        when(notificationRepository.countByStatus(NotificationStatus.SENT)).thenReturn(12L);
        when(notificationRepository.countByStatus(NotificationStatus.DEAD_LETTER)).thenReturn(3L);
        for (NotificationChannel channel : NotificationChannel.values()) {
            when(notificationRepository.countByChannelAndStatus(channel, NotificationStatus.SENT)).thenReturn(0L);
            when(notificationRepository.countByChannelAndStatus(channel, NotificationStatus.DEAD_LETTER)).thenReturn(0L);
        }
        when(notificationRepository.countByChannelAndStatus(NotificationChannel.EMAIL, NotificationStatus.SENT))
                .thenReturn(10L);
        when(notificationRepository.countByChannelAndStatus(NotificationChannel.EMAIL, NotificationStatus.DEAD_LETTER))
                .thenReturn(2L);
        when(notificationRepository.countByChannelAndStatus(NotificationChannel.PUSH, NotificationStatus.SENT))
                .thenReturn(2L);
        when(notificationRepository.countByChannelAndStatus(NotificationChannel.PUSH, NotificationStatus.DEAD_LETTER))
                .thenReturn(1L);

        NotificationAdminDto.Summary summary = service.summary();

        assertThat(summary.sent()).isEqualTo(12L);
        assertThat(summary.failed()).isEqualTo(3L);
        assertThat(summary.byChannel()).hasSize(3);
        assertThat(summary.byChannel()).filteredOn(c -> c.channel().equals("EMAIL"))
                .extracting(NotificationAdminDto.ChannelSummary::sent, NotificationAdminDto.ChannelSummary::failed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10L, 2L));
        assertThat(summary.byChannel()).filteredOn(c -> c.channel().equals("SMS"))
                .extracting(NotificationAdminDto.ChannelSummary::sent, NotificationAdminDto.ChannelSummary::failed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0L, 0L));
    }

    @Test
    void list_filtersByStatus() {
        UUID id = UUID.randomUUID();
        Notification sent = notification(id, NotificationChannel.EMAIL);
        sent.markQueued(Instant.now());
        sent.markProcessing(Instant.now());
        sent.markSent(Instant.now());

        Page<Notification> page = new PageImpl<>(List.of(sent));
        when(notificationRepository.findByStatus(eq(NotificationStatus.SENT), any(PageRequest.class)))
                .thenReturn(page);

        var result = service.list("sent", 0, 25);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).id()).isEqualTo(id);
        assertThat(result.content().get(0).status()).isEqualTo("SENT");
    }

    @Test
    void list_withNoStatusFilter_returnsEveryStatus() {
        Page<Notification> page = new PageImpl<>(List.of(notification(UUID.randomUUID(), NotificationChannel.SMS)));
        when(notificationRepository.findAll(any(PageRequest.class))).thenReturn(page);

        var result = service.list(null, 0, 25);

        assertThat(result.content()).hasSize(1);
    }

    @Test
    void get_returnsDetailWithAttemptLogNewestFirst_andAuditsTheAccess() {
        UUID id = UUID.randomUUID();
        Notification n = notification(id, NotificationChannel.EMAIL);
        when(notificationRepository.findById(id)).thenReturn(Optional.of(n));

        NotificationLog older = NotificationLog.of(id, "resend", "connection reset", false, 1,
                Instant.parse("2026-09-01T10:00:00Z"));
        NotificationLog newer = NotificationLog.of(id, "resend", "ok", true, 2,
                Instant.parse("2026-09-01T10:05:00Z"));
        // The repository's own contract is "already newest-first"
        // (findByNotificationIdOrderByTimestampDesc) -- stub it in that order rather than having
        // the service re-sort, so this test also proves the service doesn't quietly re-order it.
        when(logRepository.findByNotificationIdOrderByTimestampDesc(id)).thenReturn(List.of(newer, older));

        NotificationAdminDetailDto detail = service.get(adminId, id);

        assertThat(detail.id()).isEqualTo(id);
        assertThat(detail.message()).isEqualTo("Your statement was imported.");
        assertThat(detail.attempts()).hasSize(2);
        assertThat(detail.attempts().get(0).response()).isEqualTo("ok");
        assertThat(detail.attempts().get(1).response()).isEqualTo("connection reset");
        verify(auditService).record(eq(n.getUserId()), eq("NOTIFICATION_DETAIL_VIEWED"), eq("Notification"),
                eq(id), any());
    }

    @Test
    void get_missingId_throwsTheCodebasesStandardNotFound() {
        UUID missing = UUID.randomUUID();
        when(notificationRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(adminId, missing))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
