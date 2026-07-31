package com.finora.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogDto(
        UUID id,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> metadata,
        String requestId,
        Instant createdAt
) {}
