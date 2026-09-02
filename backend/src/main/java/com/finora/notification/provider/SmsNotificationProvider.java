package com.finora.notification.provider;

import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.repository.UserRepository;
import com.finora.service.SmsProvider;
import com.finora.service.SmsRequest;
import com.finora.service.SmsResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Wraps the existing SmsProvider. See EmailNotificationProvider for the shared rationale. */
@Component
public class SmsNotificationProvider implements NotificationChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationProvider.class);
    private static final String PROVIDER_NAME = "sms";

    private final SmsProvider smsProvider;
    private final UserRepository userRepository;

    public SmsNotificationProvider(SmsProvider smsProvider, UserRepository userRepository) {
        this.smsProvider = smsProvider;
        this.userRepository = userRepository;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean isConfigured() {
        return smsProvider.isConfigured();
    }

    @Override
    public ChannelSendResult send(Notification notification) {
        try {
            Optional<User> user = userRepository.findById(notification.getUserId());
            if (user.isEmpty()) {
                return ChannelSendResult.failure(PROVIDER_NAME, "no phone number on file");
            }
            User u = user.get();
            // A purged account currently happens to have its phoneNumber nulled by
            // AccountPurgeSweepService.purgeOne(), so the blank check below would catch it today --
            // but that is a coincidence of how purge is implemented, not a guarantee. Checking
            // status explicitly means SMS stays safe even if purge later moves to a phone
            // sentinel the way it already does for email.
            if (u.isDeleted()) {
                return ChannelSendResult.failure(PROVIDER_NAME, "user account deleted");
            }
            if (u.getPhoneNumber() == null || u.getPhoneNumber().isBlank()) {
                // Never put the (missing or present) phone number in the detail -- it lands in
                // notification_logs, which admins read.
                return ChannelSendResult.failure(PROVIDER_NAME, "no phone number on file");
            }
            SmsResult result = smsProvider.send(buildRequest(u.getPhoneNumber(),
                    notification.getMessage()));
            return result.success()
                    ? ChannelSendResult.success(PROVIDER_NAME, "sent")
                    : ChannelSendResult.failure(PROVIDER_NAME, "provider reported failure");
        } catch (RuntimeException e) {
            log.error("SMS notification {} could not be sent", notification.getId(), e);
            return ChannelSendResult.failure(PROVIDER_NAME,
                    "exception: " + e.getClass().getSimpleName());
        }
    }

    private SmsRequest buildRequest(String phoneNumber, String body) {
        return new SmsRequest(phoneNumber, body);
    }
}
