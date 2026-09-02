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
    void render_doesNotReSubstituteAPlaceholderThatAppearsInsideAnotherParametersValue() {
        stubTemplate("Note: {{note}} / Amount: {{amount}}", "Body");

        // "note" and "amount" each hold the OTHER key's own placeholder text as their value --
        // deliberately symmetric, so the assertion below pins the bug regardless of Map.of()'s
        // unspecified iteration order. A template engine that re-scans the growing result string
        // (instead of matching once against the original, untouched template) would, under EITHER
        // processing order, end up substituting one of these placeholders twice -- once for its own
        // key, and again because the first substitution just inserted literal "{{...}}" text that
        // happens to match the other key still left to process. Matching only against the original
        // template can't do that: each placeholder's replacement is decided once, from what was
        // actually written in the template, never from text another substitution just inserted.
        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL,
                Map.of("note", "{{amount}}", "amount", "{{note}}"));

        assertThat(rendered.title()).isEqualTo("Note: {{amount}} / Amount: {{note}}");
    }

    @Test
    void render_treatsADollarSignInAParameterValueAsLiteralText() {
        stubTemplate("Amount charged: {{amount}}", "Body");

        // A raw (non-quoted) replacement string handed to Matcher.appendReplacement treats "$1" as
        // a backreference to the current match's first capture group -- here, the literal text
        // "amount" -- silently corrupting the output instead of inserting the dollar amount
        // verbatim. This is the test that actually pins Matcher.quoteReplacement(...) being called.
        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of("amount", "$1,000.50"));

        assertThat(rendered.title()).isEqualTo("Amount charged: $1,000.50");
    }

    @Test
    void render_treatsABackslashInAParameterValueAsLiteralText() {
        stubTemplate("Path: {{path}}", "Body");

        // A raw (non-quoted) replacement string treats a lone backslash as the start of an escape
        // sequence, which throws IllegalArgumentException unless followed by "$" or another "\" --
        // without Matcher.quoteReplacement(...) this parameter value would blow up render()
        // entirely rather than pass through as ordinary text.
        RenderedMessage rendered = renderer.render(NotificationType.IMPORT_STATEMENT_READY,
                NotificationChannel.EMAIL, Map.of("path", "C:\\Users\\test"));

        assertThat(rendered.title()).isEqualTo("Path: C:\\Users\\test");
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
