package com.finora.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationLogRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mockito-based, matching this codebase's established worker-test pattern (ImportJobWorkerTest),
 * rather than a Spring-context IT. The TransactionTemplate is stubbed to run its callback inline.
 *
 * <p>Task 4 wires {@code NotificationLog}/{@code NotificationLogRepository} into
 * {@link NotificationDispatcher}: a log row is written for every send attempt, success and
 * failure alike, and a failure writing that log row must never affect the notification's own
 * status write. See the "log row" tests below for both halves of that contract.
 */
class NotificationDispatcherTest {

    private NotificationRepository repository;
    private NotificationLogRepository logRepository;
    private NotificationChannelProvider emailProvider;
    private WorkerObservability observability;
    private TransactionTemplate transactionTemplate;
    private NotificationDispatcher dispatcher;

    private Notification pending;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        logRepository = mock(NotificationLogRepository.class);
        emailProvider = mock(NotificationChannelProvider.class);
        observability = mock(WorkerObservability.class);
        transactionTemplate = mock(TransactionTemplate.class);

        when(observability.beginScheduled(any(), any())).thenReturn(mock(WorkerExecution.class));
        when(observability.begin(any(), any())).thenReturn(mock(WorkerExecution.class));
        when(emailProvider.channel()).thenReturn(NotificationChannel.EMAIL);
        when(emailProvider.isConfigured()).thenReturn(true);

        // Run both TransactionTemplate forms inline so the worker's logic is what is under test.
        // executeWithoutResult is a default method TransactionTemplate inherits rather than
        // overrides, and a Mockito mock does not delegate an unstubbed method to its real
        // implementation just because that implementation happens to be a default method -- see
        // AccountPurgeSweepServiceTest / UserAccountLifecycleServiceTest for this codebase's
        // established fix, applied identically here.
        when(transactionTemplate.execute(any())).thenAnswer(
                inv -> ((org.springframework.transaction.support.TransactionCallback<?>)
                        inv.getArgument(0)).doInTransaction(null));
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        pending = Notification.create(UUID.randomUUID(), NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Title", "Body", Instant.now());

        when(repository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(any())).thenReturn(Optional.of(pending));
        when(repository.findOldestPendingAt()).thenReturn(Optional.empty());

        dispatcher = new NotificationDispatcher(repository, logRepository, List.of(emailProvider),
                observability, transactionTemplate);
    }

    @Test
    void drainOnce_marksSentWhenTheProviderSucceeds() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));

        int processed = dispatcher.drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(pending.getSentAt()).isNotNull();
    }

    @Test
    void drainOnce_schedulesRetryWhenTheProviderFails() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any()))
                .thenReturn(ChannelSendResult.failure("resend", "502 from provider"));

        dispatcher.drainOnce();

        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void drainOnce_deadLettersWhenNoProviderIsConfiguredForTheChannel() {
        when(emailProvider.isConfigured()).thenReturn(false);
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));

        dispatcher.drainOnce();

        // An unconfigured provider will never succeed; retrying five times just wastes the queue.
        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
    }

    @Test
    void drainOnce_returnsZeroWhenNothingIsDue() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

        assertThat(dispatcher.drainOnce()).isZero();
    }

    /**
     * Fix round 1, CRITICAL 1: {@code poll()} did not call {@code recoverAbandoned()}, so a row
     * stranded in PROCESSING by a crashed worker was never returned to the queue -- silently, with
     * no alert. This proves the fix through {@code poll()} itself, not by calling
     * {@code recoverAbandoned()} directly (which would only prove that method works in isolation,
     * not that the scheduled entry point actually reaches it).
     */
    @Test
    void poll_recoversAnAbandonedProcessingRowBeforeDraining() {
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        pending.markProcessing(Instant.now().minus(Duration.ofMinutes(30)));
        when(repository.findAbandoned(any(), any())).thenReturn(List.of(pending));
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of());

        dispatcher.poll();

        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.QUEUED);
    }

    /**
     * Fix round 1, IMPORTANT 2: the outcome-recording methods had no outer try/catch of their own,
     * so a persistence failure while recording one row's outcome would propagate out of
     * {@code deliverOne} and abort the rest of {@code drain}'s loop over the claimed batch. Proven
     * here by making the SECOND {@code repository.save(pending)} call -- the one inside
     * {@code recordSuccess}, after the claim-phase save already succeeded -- throw, and checking
     * that a second row claimed in the same batch still reaches SENT rather than never being
     * attempted.
     */
    @Test
    void drainOnce_continuesTheBatchWhenRecordingOneItemsOutcomeThrows() {
        Notification other = Notification.create(UUID.randomUUID(),
                NotificationType.IMPORT_STATEMENT_READY, NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL, NotificationPriority.NORMAL, "K2:EMAIL", "Title", "Body",
                Instant.now());

        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending, other));
        when(emailProvider.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));
        // First save() is claimBatch's markProcessing write and must succeed so both rows are
        // claimed; the second is recordSuccess's outcome-recording write for `pending`, which
        // fails -- exactly the persistence failure the outer try/catch exists to contain.
        when(repository.save(pending))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new RuntimeException("db unavailable"));

        AtomicInteger processed = new AtomicInteger();
        assertThatCode(() -> processed.set(dispatcher.drainOnce())).doesNotThrowAnyException();

        assertThat(processed.get()).isEqualTo(2);
        // `other` is claimed and delivered after `pending` in the batch; it only reaches SENT if
        // the drain loop moved past pending's recording failure instead of aborting there.
        assertThat(other.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    /** Task 4: a log row is written for the success path, not just the failure path -- "we called
     * the provider" and "it worked" must both be recorded, not just the latter. */
    @Test
    void drainOnce_writesALogRowWhenTheProviderSucceeds() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));

        dispatcher.drainOnce();

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        NotificationLog written = captor.getValue();
        assertThat(written.getNotificationId()).isEqualTo(pending.getId());
        assertThat(written.getProvider()).isEqualTo("resend");
        assertThat(written.getResponse()).isEqualTo("ok");
        assertThat(written.isSuccess()).isTrue();
        assertThat(written.getAttempt()).isEqualTo(1);
    }

    /** Task 4: the failure path writes its own log row, distinct from the success path. */
    @Test
    void drainOnce_writesALogRowWhenTheProviderFails() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any()))
                .thenReturn(ChannelSendResult.failure("resend", "502 from provider"));

        dispatcher.drainOnce();

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        NotificationLog written = captor.getValue();
        assertThat(written.getProvider()).isEqualTo("resend");
        assertThat(written.getResponse()).isEqualTo("502 from provider");
        assertThat(written.isSuccess()).isFalse();
        assertThat(written.getAttempt()).isEqualTo(1);
    }

    /** Task 4: the no-configured-provider terminal path is a send attempt too (an attempt that
     * discovered there was nobody to call) and gets its own log row. */
    @Test
    void drainOnce_writesALogRowWhenNoProviderIsConfigured() {
        when(emailProvider.isConfigured()).thenReturn(false);
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));

        dispatcher.drainOnce();

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        NotificationLog written = captor.getValue();
        assertThat(written.isSuccess()).isFalse();
        assertThat(written.getAttempt()).isEqualTo(1);
    }

    /**
     * Task 4, the other half of the contract: a log-write failure must not prevent the
     * notification's own status write, nor propagate out of {@code drainOnce}. Proven by making
     * {@code logRepository.save} throw on the success path and asserting the notification still
     * reaches SENT and the pass completes normally.
     */
    @Test
    void drainOnce_stillMarksSentWhenTheLogWriteThrows() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any())).thenReturn(ChannelSendResult.success("resend", "ok"));
        when(logRepository.save(any(NotificationLog.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> dispatcher.drainOnce()).doesNotThrowAnyException();

        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(pending.getSentAt()).isNotNull();
    }

    /** Same contract as above, exercised on the failure-recording path. */
    @Test
    void drainOnce_stillSchedulesRetryWhenTheLogWriteThrows() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any()))
                .thenReturn(ChannelSendResult.failure("resend", "502 from provider"));
        when(logRepository.save(any(NotificationLog.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> dispatcher.drainOnce()).doesNotThrowAnyException();

        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(pending.getAttemptCount()).isEqualTo(1);
    }

    /**
     * Attempt numbering: recordFailure increments {@code attemptCount} before this test's second
     * delivery, so the second log row's {@code attempt} must be 2, not repeat 1 -- proving the
     * dispatcher reads the notification's own attempt count rather than hardcoding it.
     */
    @Test
    void drainOnce_incrementsTheLoggedAttemptNumberAcrossRetries() {
        when(repository.claimDue(any(), anyInt())).thenReturn(List.of(pending));
        when(emailProvider.send(any()))
                .thenReturn(ChannelSendResult.failure("resend", "502 from provider"))
                .thenReturn(ChannelSendResult.success("resend", "ok"));

        dispatcher.drainOnce();
        dispatcher.drainOnce();

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationLog::getAttempt)
                .containsExactly(1, 2);
    }
}
