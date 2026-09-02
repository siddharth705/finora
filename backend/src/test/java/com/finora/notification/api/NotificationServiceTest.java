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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        templateRenderer = mock(TemplateRenderer.class);
        preferenceResolver = mock(NotificationPreferenceResolver.class);
        service = new NotificationService(repository, templateRenderer, preferenceResolver);

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
}
