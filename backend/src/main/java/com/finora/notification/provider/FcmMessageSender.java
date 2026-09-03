package com.finora.notification.provider;

/** One-method seam over the Firebase SDK, so FcmPushProvider is testable without a live Firebase. */
public interface FcmMessageSender {
    /**
     * Attempts delivery to one device token. Must not throw.
     *
     * @return {@link FcmSendOutcome#ACCEPTED}, {@link FcmSendOutcome#TOKEN_DEAD}, or
     *         {@link FcmSendOutcome#TRANSIENT_FAILURE} -- never the token, never an SDK
     *         exception's message. The return type carries no message slot at all: that is what
     *         keeps this seam structurally incapable of leaking token material back to a caller,
     *         regardless of what the underlying SDK's error text happens to contain.
     */
    FcmSendOutcome send(String deviceToken, String title, String body);
}
