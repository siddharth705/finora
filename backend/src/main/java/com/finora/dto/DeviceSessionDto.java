package com.finora.dto;

import com.finora.entity.RefreshToken;

import java.time.Instant;
import java.util.UUID;

/** The caller's own view of one of their active refresh-token sessions — see RefreshToken's own
 *  doc comment for why browser/device/lastSeenIp are best-effort labels, not a durable per-device
 *  fingerprint. Deliberately never includes the token hash or any other secret. */
public record DeviceSessionDto(UUID id, String browser, String device, String lastSeenIp,
                                Instant lastSeenAt, Instant createdAt, Instant expiresAt) {

    public static DeviceSessionDto from(RefreshToken rt) {
        return new DeviceSessionDto(rt.getId(), rt.getBrowser(), rt.getDevice(), rt.getLastSeenIp(),
                rt.getLastSeenAt(), rt.getCreatedAt(), rt.getExpiresAt());
    }
}
