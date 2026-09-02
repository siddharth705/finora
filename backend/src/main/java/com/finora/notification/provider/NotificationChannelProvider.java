package com.finora.notification.provider;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;

/**
 * Delivers a notification on exactly one channel. Implementations wrap the existing
 * EmailProvider/SmsProvider or talk to FCM/APNs; the dispatcher selects one by
 * {@link #channel()} and never knows which concrete provider it got.
 */
public interface NotificationChannelProvider {

    NotificationChannel channel();

    /** False when credentials are absent -- the dispatcher dead-letters rather than retrying forever. */
    boolean isConfigured();

    /** Must not throw; failures come back as {@link ChannelSendResult#failure}. */
    ChannelSendResult send(Notification notification);
}
