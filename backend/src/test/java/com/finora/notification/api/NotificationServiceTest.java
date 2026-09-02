package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import com.finora.notification.worker.NotificationDispatcher;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * Mockito-based unit tests against mocked repositories, matching this codebase's established
 * pattern (GoalServiceTest, MerchantLearningServiceTest).
 *
 * <p>{@code NotificationRepository#insertIfAbsent} is a native {@code INSERT ... ON CONFLICT DO
 * NOTHING}, so a mock cannot exercise real Postgres transaction-abort semantics in either
 * direction -- that coverage lives in {@code NotificationConcurrentRequestRaceIT} instead, which
 * proves the actual race is closed against a real database. These tests only cover
 * {@code NotificationService.request}'s own branching (preference checks, the {@code exists} fast
 * path, argument plumbing, and never throwing).
 */
class NotificationServiceTest {

    private NotificationRepository repository;
    private TemplateRenderer templateRenderer;
    private NotificationPreferenceResolver preferenceResolver;
    private NotificationDispatcher dispatcher;
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        templateRenderer = mock(TemplateRenderer.class);
        preferenceResolver = mock(NotificationPreferenceResolver.class);
        dispatcher = mock(NotificationDispatcher.class);
        service = new NotificationService(repository, templateRenderer, preferenceResolver, dispatcher);

        when(repository.insertIfAbsent(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> Optional.of(UUID.randomUUID()));
        when(repository.existsByNotificationKey(anyString())).thenReturn(false);
        when(preferenceResolver.isEnabled(any(), any(), any())).thenReturn(true);
        when(templateRenderer.render(any(), any(), any()))
                .thenReturn(new RenderedMessage("Title", "Body"));
    }

    private NotificationRequest request(Set<NotificationChannel> channels, String key) {
        return NotificationRequest.of(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationPriority.NORMAL, key, channels,
                Map.of("bank", "HDFC"));
    }

    @Test
    void request_writesOneRowPerChannel() {
        service.request(request(Set.of(NotificationChannel.EMAIL, NotificationChannel.PUSH), "K1"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).insertIfAbsent(any(), keyCaptor.capture(), anyString(),
                anyString(), channelCaptor.capture(), anyString(), anyString(), anyString(), any());

        assertThat(keyCaptor.getAllValues()).containsExactlyInAnyOrder("K1:EMAIL", "K1:PUSH");
        assertThat(channelCaptor.getAllValues()).containsExactlyInAnyOrder("EMAIL", "PUSH");
    }

    @Test
    void request_suppressesADuplicateIdempotencyKey() {
        when(repository.existsByNotificationKey("K1:EMAIL")).thenReturn(true);

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        verify(repository, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void request_skipsAChannelTheUserHasDisabled() {
        when(preferenceResolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS)).thenReturn(false);

        service.request(request(Set.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "K1"));

        verify(repository, times(1)).insertIfAbsent(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void request_returnsTheIdsItActuallyWrote() {
        UUID savedId = UUID.randomUUID();
        when(repository.insertIfAbsent(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(savedId));

        var ids = service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        assertThat(ids).containsExactly(savedId);
    }

    @Test
    void request_suppressesAnInsertThatLostTheRaceAtTheDatabase() {
        // insertIfAbsent returns empty when ON CONFLICT DO NOTHING suppressed the row -- the same
        // outcome as the exists() branch, and must not appear in the returned ids.
        when(repository.insertIfAbsent(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.request(request(Set.of(NotificationChannel.EMAIL), "K1"))).isEmpty();
    }

    @Test
    void request_neverThrowsWhenTheTemplateIsMissing() {
        when(templateRenderer.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("no template"));

        // A notification failure must never fail the caller's own business transaction.
        assertThat(service.request(request(Set.of(NotificationChannel.EMAIL), "K1"))).isEmpty();
    }

    /**
     * Fix wave, IMPORTANT 2: an over-length title/message/key used to reach the insert at full
     * length, which raises a Postgres statement error INSIDE the caller's own ambient transaction --
     * see this class's own doc comment on {@code request} for the SQLSTATE 25P02 mechanism. These
     * three tests prove each of the three unbounded inputs is cut down to its column width
     * (V125: title VARCHAR(300), message VARCHAR(2000), notification_key VARCHAR(200)) before ever
     * reaching {@code insertIfAbsent}.
     */
    @Test
    void request_truncatesAnOverLengthTitleToTheColumnWidth() {
        when(templateRenderer.render(any(), any(), any()))
                .thenReturn(new RenderedMessage("T".repeat(301), "Body"));

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), titleCaptor.capture(), anyString(), any());
        assertThat(titleCaptor.getValue()).hasSize(300);
    }

    @Test
    void request_truncatesAnOverLengthMessageToTheColumnWidth() {
        when(templateRenderer.render(any(), any(), any()))
                .thenReturn(new RenderedMessage("Title", "B".repeat(2001)));

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).hasSize(2000);
    }

    @Test
    void request_truncatesAnOverLengthNotificationKeyToTheColumnWidth() {
        String overLongKey = "K".repeat(250);

        service.request(request(Set.of(NotificationChannel.EMAIL), overLongKey));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(any(), keyCaptor.capture(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
        assertThat(keyCaptor.getValue()).hasSize(200);
    }

    /**
     * Fix wave, IMPORTANT 3: {@code nudge()} was never wired to {@code request()}, so every
     * notification -- PASSWORD_CHANGED included -- waited for the 30-second poller. These prove the
     * after-commit wiring itself: no nudge before commit (the row is not visible to the dispatcher's
     * own claim query yet), a nudge once the transaction actually commits, no nudge at all if it
     * rolls back instead, and an immediate nudge when there is no ambient transaction to wait for.
     *
     * <p>{@code TransactionSynchronizationManager.initSynchronization()}/{@code
     * TransactionSynchronizationUtils.triggerAfterCommit()} simulate a real transaction's commit
     * without a Spring context or a database -- the same technique
     * {@code MerchantNormalizationEngineTest} already uses for transaction-scoped behaviour in a
     * plain Mockito unit test.
     */
    @Test
    void request_nudgesTheDispatcherOnlyAfterTheCallersTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

            verify(dispatcher, never()).nudge();

            TransactionSynchronizationUtils.triggerAfterCommit();

            verify(dispatcher).nudge();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void request_doesNotNudgeWhenTheCallersTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));
        } finally {
            // No triggerAfterCommit() here -- simulating a rollback, on which afterCommit is never
            // invoked at all.
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(dispatcher, never()).nudge();
    }

    @Test
    void request_nudgesImmediatelyWhenThereIsNoAmbientTransaction() {
        // No TransactionSynchronizationManager.initSynchronization() -- matches every other test in
        // this class, and any real caller with no transaction in play.
        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        verify(dispatcher).nudge();
    }

    @Test
    void request_doesNotNudgeWhenNothingWasActuallyWritten() {
        when(repository.existsByNotificationKey(anyString())).thenReturn(true);

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        verify(dispatcher, never()).nudge();
    }
}
