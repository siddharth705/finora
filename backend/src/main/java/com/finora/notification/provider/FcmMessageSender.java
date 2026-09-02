package com.finora.notification.provider;

/** One-method seam over the Firebase SDK, so FcmPushProvider is testable without a live Firebase. */
public interface FcmMessageSender {
    /** @return true when FCM accepted the message for this token. Must not throw. */
    boolean send(String deviceToken, String title, String body);
}
