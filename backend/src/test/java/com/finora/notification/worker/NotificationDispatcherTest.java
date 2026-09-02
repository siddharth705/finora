package com.finora.notification.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.provider.ChannelSendResult;
import com.finora.notification.provider.NotificationChannelProvider;
import com.finora.notification.repository.NotificationRepository;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mockito-based, matching this codebase's established worker-test pattern (ImportJobWorkerTest),
 * rather than a Spring-context IT. The TransactionTemplate is stubbed to run its callback inline.
 *
 * <p>Task 4 owns {@code NotificationLog}/{@code NotificationLogRepository} and will extend
 * {@link NotificationDispatcher} to write a log row per send attempt -- this test does not
 * reference either, by design (see NotificationDispatcher's own class doc).
 */
class NotificationDispatcherTest {

    private NotificationRepository repository;
    private NotificationChannelProvider emailProvider;
    private WorkerObservability observability;
    private TransactionTemplate transactionTemplate;
    private NotificationDispatcher dispatcher;

    private Notification pending;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
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

        dispatcher = new NotificationDispatcher(repository, List.of(emailProvider), observability,
                transactionTemplate);
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
}
