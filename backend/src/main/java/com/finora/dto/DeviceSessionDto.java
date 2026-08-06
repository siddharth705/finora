package com.finora.dto;

import com.finora.entity.RefreshToken;

import java.time.Instant;
import java.util.UUID;

/** The caller's own view of one of their active refresh-token sessions — see RefreshToken's own
 *  doc comment for why browser/device/lastSeenIp are best-effort labels, not a durable per-device
 *  fingerprint. Deliberately never includes the token hash or any other secret. */
public record DeviceSessionDto(UUID id, String browser, String device, String lastSeenIp,
                                Instant lastSeenAt, Instant createdAt, Instant expiresAt,
                                Instant sessionStartedAt, Instant sessionExpiresAt) {

    /**
     * @param absoluteSessionMs the configured absolute session cap, or 0 when disabled.
     *
     * <p>{@code sessionExpiresAt} is computed here rather than sent as a policy value the client
     * does the arithmetic on. Two reasons: the client would need the server's clock to get it
     * right, and a UI that renders "expires in 2 days" from its own {@code Date.now()} drifts on
     * any device with a skewed clock — which is exactly the user about to be surprised by a
     * sign-out. It is also the answer to "why am I being asked to log in again", so it should come
     * from the thing that actually decides.
     *
     * <p>Null when the cap is disabled, which the UI renders as no expiry rather than as a
     * date far in the future.
     */
    public static DeviceSessionDto from(RefreshToken rt, long absoluteSessionMs) {
        Instant sessionExpiresAt = absoluteSessionMs > 0
                ? rt.getSessionStartedAt().plusMillis(absoluteSessionMs)
                : null;
        return new DeviceSessionDto(rt.getId(), rt.getBrowser(), rt.getDevice(), rt.getLastSeenIp(),
                rt.getLastSeenAt(), rt.getCreatedAt(), rt.getExpiresAt(),
                rt.getSessionStartedAt(), sessionExpiresAt);
    }
}
