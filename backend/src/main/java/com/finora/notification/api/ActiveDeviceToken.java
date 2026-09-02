package com.finora.notification.api;

/**
 * One device's decrypted push token, paired with the platform it was registered under.
 *
 * <p>Deliberately not a bare {@code String}. {@link DeviceTokenService#activeTokensFor} exists to
 * feed the push dispatcher (Task 11), which must route each token to FCM (ANDROID) or APNs (IOS) --
 * two providers with incompatible payload shapes and endpoints. A bare token string carries no
 * platform, which would force the dispatcher to either re-query
 * {@link com.finora.notification.repository.DeviceTokenRepository} itself (leaking persistence
 * concerns into the provider layer) or force a breaking signature change on this method after
 * Task 10 already depends on it. Carrying the platform alongside the token here is the smallest
 * correct fix, made at the point this method is introduced.
 *
 * <p>{@code token} is the plaintext, already decrypted by {@link DeviceTokenService} -- never log
 * or serialize it. See {@link com.finora.notification.domain.DeviceToken}'s class doc for why it
 * must be recoverable rather than hashed.
 */
public record ActiveDeviceToken(String token, String platform) {
}
