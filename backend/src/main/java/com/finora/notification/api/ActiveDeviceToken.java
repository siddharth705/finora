package com.finora.notification.api;

/**
 * One device's decrypted push token, paired with the platform it was registered under.
 *
 * <p>Deliberately not a bare {@code String}. {@link DeviceTokenService#activeTokensFor} carries
 * {@code platform} alongside the token for diagnostics, per-platform delivery metrics, and as the
 * seam a future direct-to-APNs path could dispatch on -- it is deliberately NOT used to route a
 * send today (Ruling O, Task 11). iOS devices register an FCM registration token (via
 * {@code @react-native-firebase/messaging}), the same token shape ANDROID gets, and Firebase
 * relays every send to APNs on this project's behalf once it reaches FCM -- the APNs
 * Authentication Keys live in the Firebase console, not in this codebase. Every token, Android or
 * iOS, is sent through the same single FCM path; see
 * {@link com.finora.notification.provider.FcmPushProvider}'s class doc for the full "why no
 * separate APNs client" reasoning. Independently of routing, a bare token string would still have
 * been the wrong shape here -- it would force a caller that needs the platform for diagnostics to
 * either re-query {@link com.finora.notification.repository.DeviceTokenRepository} itself (leaking
 * persistence concerns into the provider layer) or force a breaking signature change on this
 * method after Task 10 already depends on it. Carrying the platform alongside the token here is
 * the smallest correct fix, made at the point this method is introduced.
 *
 * <p>{@code token} is the plaintext, already decrypted by {@link DeviceTokenService} -- never log
 * or serialize it. See {@link com.finora.notification.domain.DeviceToken}'s class doc for why it
 * must be recoverable rather than hashed.
 */
public record ActiveDeviceToken(String token, String platform) {
}
