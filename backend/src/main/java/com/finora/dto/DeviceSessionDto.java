package com.finora.dto;

import com.finora.entity.RefreshToken;

import java.time.Instant;
import java.util.UUID;

/** The caller's own view of one of their active refresh-token sessions — see RefreshToken's own
 *  doc comment for why browser/device/lastSeenIp are best-effort labels, not a durable per-device
 *  fingerprint. Deliberately never includes the token hash or any other secret. */
public record DeviceSessionDto(UUID id, UUID sessionId, boolean current,
                                String browser, String device, String lastSeenIp,
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
    /**
     * @param callerSessionId the {@code sid} of the token making this request, or null when it
     *        predates the claim. Null means "cannot tell", so nothing is marked current -- never
     *        that this session is not the caller's, which would invite signing yourself out.
     *
     * <p>{@code id} identifies the current refresh TOKEN and changes on every rotation;
     * {@code sessionId} identifies the SESSION and does not. Both are exposed because they answer
     * different questions: revoke still operates on the token row, while anything scoped to the
     * sign-in itself — device naming, trusted devices, per-session audit — keys off the session.
     */
    public static DeviceSessionDto from(RefreshToken rt, long absoluteSessionMs,
                                        UUID callerSessionId) {
        Instant sessionExpiresAt = absoluteSessionMs > 0
                ? rt.getSessionStartedAt().plusMillis(absoluteSessionMs)
                : null;
        boolean current = callerSessionId != null && callerSessionId.equals(rt.getSessionId());
        return new DeviceSessionDto(rt.getId(), rt.getSessionId(), current,
                rt.getBrowser(), rt.getDevice(), rt.getLastSeenIp(),
                rt.getLastSeenAt(), rt.getCreatedAt(), rt.getExpiresAt(),
                rt.getSessionStartedAt(), sessionExpiresAt);
    }
}
