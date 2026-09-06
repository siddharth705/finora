package com.finora.notification.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailNotificationProviderTest {

    private EmailProvider emailProvider;
    private UserRepository userRepository;
    private EmailNotificationProvider provider;

    @BeforeEach
    void setUp() {
        emailProvider = mock(EmailProvider.class);
        userRepository = mock(UserRepository.class);
        provider = new EmailNotificationProvider(emailProvider, userRepository);
        when(emailProvider.isConfigured()).thenReturn(true);
    }

    private Notification notification() {
        return notification(NotificationType.IMPORT_STATEMENT_READY);
    }

    private Notification notification(NotificationType type) {
        return Notification.create(UUID.randomUUID(), type,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, "K1:EMAIL", "Statement ready",
                "We finished importing your statement.", Instant.now());
    }

    private User activeUser() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setStatus(User.STATUS_ACTIVE);
        return user;
    }

    @Test
    void channel_isEmail() {
        assertThat(provider.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void send_failsWhenTheUserHasNoEmailOnFile() {
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // Fix wave, IMPORTANT 4: retrying cannot make a user row that doesn't exist appear.
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void send_neverThrowsWhenTheUnderlyingProviderThrows() {
        when(userRepository.findById(any())).thenThrow(new RuntimeException("db down"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        // A transient infrastructure exception carries no evidence this is permanently
        // undeliverable -- must stay on the ordinary retry path.
        assertThat(result.permanent()).isFalse();
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasABlankEmail() {
        User user = new User();
        user.setEmail("");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_failsWithoutLeakingWhenTheUserHasANullEmail() {
        User user = new User();
        user.setEmail(null);
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    @Test
    void send_refusesDeliveryToAPurgedUserEvenThoughItsSentinelEmailIsNonBlank() {
        User user = new User();
        // AccountPurgeSweepService.purgeOne() overwrites a purged account's email with this
        // synthetic, non-blank sentinel -- a null/blank check alone would miss it.
        user.setEmail("deleted-" + UUID.randomUUID() + "@deleted.finora.invalid");
        user.setStatus(User.STATUS_DELETED);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).doesNotContain("@");
        // A purge is not undone by waiting and retrying.
        assertThat(result.permanent()).isTrue();
        verify(emailProvider, never()).send(any());
    }

    /** The flip side: a real provider call that itself reports failure is not confidently
     *  permanent (could be a transient outage on the provider's side) and must stay retryable. */
    @Test
    void send_aProviderReportedFailureIsNotPermanent() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setStatus(User.STATUS_ACTIVE);
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
        when(emailProvider.send(any()))
                .thenReturn(EmailResult.failure(ProviderType.RESEND, "provider outage"));

        ChannelSendResult result = provider.send(notification());

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isFalse();
    }

    // ------------------------------------------------------------------ sender + HTML wrapping

    /** Product decision, 2026-09-06: a held/ready statement notice should let the customer reply
     *  and reach a person, so it goes out as support@, not noreply@. */
    @Test
    void send_usesTheSupportSenderForAHeldStatementNotice() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.IMPORT_STATEMENT_HELD));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    @Test
    void send_usesTheSupportSenderForAReadyStatementNotice() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.IMPORT_STATEMENT_READY));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
    }

    /** Every other DB-template type stays on the default (noreply@) sender -- PASSWORD_CHANGED is
     *  the one other type the enum declares today, even though nothing calls it live yet (the
     *  actual password-changed email is ResendEmailProvider's own hand-built send). */
    @Test
    void send_usesTheDefaultSenderForEveryOtherNotificationType() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification(NotificationType.PASSWORD_CHANGED));

        verify(emailProvider).send(captor.capture());
        assertThat(captor.getValue().sender()).isEqualTo(EmailMessage.Sender.DEFAULT);
    }

    /** Found live in testing: this used to send the notification's raw plain-text body with no
     *  styling at all -- "this one line is looking very bad". Both the branded HTML and the
     *  original plain sentence (a fallback for clients that strip HTML) must reach the message. */
    @Test
    void send_wrapsTheBodyInBrandedHtmlWhileKeepingThePlainTextFallback() {
        when(userRepository.findById(any())).thenReturn(Optional.of(activeUser()));
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);

        provider.send(notification());

        verify(emailProvider).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.html()).contains("FYNORA").contains("We finished importing your statement.");
        assertThat(sent.text()).isEqualTo("We finished importing your statement.");
    }
}
