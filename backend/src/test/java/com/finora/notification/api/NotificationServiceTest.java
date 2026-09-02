package com.finora.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.template.RenderedMessage;
import com.finora.notification.template.TemplateRenderer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito-based unit tests against mocked repositories, matching this codebase's established
 * pattern (GoalServiceTest, MerchantLearningServiceTest).
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

        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
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

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());

        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.PUSH);
        assertThat(saved).extracting(Notification::getNotificationKey)
                .containsExactlyInAnyOrder("K1:EMAIL", "K1:PUSH");
    }

    @Test
    void request_suppressesADuplicateIdempotencyKey() {
        when(repository.existsByNotificationKey("K1:EMAIL")).thenReturn(true);

        service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        verify(repository, never()).saveAndFlush(any(Notification.class));
    }

    @Test
    void request_skipsAChannelTheUserHasDisabled() {
        when(preferenceResolver.isEnabled(userId, NotificationCategory.FINANCIAL,
                NotificationChannel.SMS)).thenReturn(false);

        service.request(request(Set.of(NotificationChannel.EMAIL, NotificationChannel.SMS), "K1"));

        verify(repository, times(1)).saveAndFlush(any(Notification.class));
    }

    @Test
    void request_returnsTheIdsItActuallyWrote() {
        UUID savedId = UUID.randomUUID();
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> {
            Notification saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", savedId);
            return saved;
        });

        var ids = service.request(request(Set.of(NotificationChannel.EMAIL), "K1"));

        assertThat(ids).containsExactly(savedId);
    }

    @Test
    void request_neverThrowsWhenTheTemplateIsMissing() {
        when(templateRenderer.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("no template"));

        // A notification failure must never fail the caller's own business transaction.
        assertThat(service.request(request(Set.of(NotificationChannel.EMAIL), "K1"))).isEmpty();
    }

    @Test
    void request_suppressesARaceLostAgainstAConcurrentWriterForTheSameKey() {
        // The exists() pre-check can miss a concurrent writer for the identical key: both see
        // false, both attempt to insert, and the UNIQUE constraint on notification_key decides the
        // race at the database. saveAndFlush surfaces that as a DataIntegrityViolationException
        // synchronously, inside this method's own try -- it must be swallowed exactly like the
        // exists() branch, not propagated to the caller.
        when(repository.saveAndFlush(any(Notification.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThat(service.request(request(Set.of(NotificationChannel.EMAIL), "K1"))).isEmpty();
    }
}
