package com.finora.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTemplateRendererTest {

    private NotificationTemplateRepository repository;
    private DatabaseTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationTemplateRepository.class);
        renderer = new DatabaseTemplateRenderer(repository);
    }

    private void stubTemplate(String title, String body) {
        when(repository.findByTypeAndChannelAndActiveTrue(any(), any()))
                .thenReturn(Optional.of(NotificationTemplate.of(
                        NotificationType.IMPORT_STATEMENT_READY, NotificationChannel.EMAIL, title,
                        body)));
    }

    @Test
    void render_substitutesParameters() {
        stubTemplate("Your {{bank}} statement is ready",
                "We imported {{count}} transactions from your {{bank}} statement.");

        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of("bank", "HDFC", "count", "42"));

        assertThat(rendered.title()).isEqualTo("Your HDFC statement is ready");
        assertThat(rendered.body())
                .isEqualTo("We imported 42 transactions from your HDFC statement.");
    }

    @Test
    void render_leavesUnknownPlaceholdersIntactRatherThanEmitting_null() {
        stubTemplate("Hello {{name}}", "Body");

        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of());

        // Better a visible {{name}} in a log than the literal string "null" in a user's inbox.
        assertThat(rendered.title()).isEqualTo("Hello {{name}}");
    }

    @Test
    void render_throwsWhenNoActiveTemplateExists() {
        when(repository.findByTypeAndChannelAndActiveTrue(any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IMPORT_STATEMENT_READY");
    }
}
