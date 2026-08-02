package com.finora.service;

import com.finora.dto.AdminDtos.FeatureFlagDto;
import com.finora.entity.FeatureFlag;
import com.finora.exception.ApiException;
import com.finora.repository.FeatureFlagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Admin Portal Phase 8 -- read/toggle for the feature_flags table (V32). Flags are seeded by
 * migration, not created here: this is deliberately a toggle surface, not a general-purpose flag
 * builder, so every flag that exists has a real call site wired to it (see FeatureFlagRepository's
 * isEnabled() doc comment) rather than accumulating dead switches nothing ever reads.
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final AuditService auditService;

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository, AuditService auditService) {
        this.featureFlagRepository = featureFlagRepository;
        this.auditService = auditService;
    }

    public List<FeatureFlagDto> list() {
        return featureFlagRepository.findAll().stream()
                .sorted(Comparator.comparing(FeatureFlag::getKey))
                .map(FeatureFlagService::toDto)
                .toList();
    }

    public boolean isEnabled(String key) {
        return featureFlagRepository.isEnabled(key);
    }

    // Bug fix: an unknown flagId threw a bare NoSuchElementException, which GlobalExceptionHandler
    // has no specific handler for -- it fell through to the generic Exception handler and came
    // back as an opaque 500 instead of a 404, unlike every other "not found" lookup in this
    // codebase (which all throw ApiException(HttpStatus.NOT_FOUND, ...)).
    @Transactional
    public FeatureFlagDto setEnabled(UUID adminUserId, UUID flagId, boolean enabled) {
        FeatureFlag flag = featureFlagRepository.findById(flagId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Feature flag not found: " + flagId));
        boolean wasEnabled = flag.isEnabled();
        flag.setEnabled(enabled);
        flag.setUpdatedAt(Instant.now());
        featureFlagRepository.save(flag);

        if (wasEnabled != enabled) {
            auditService.record(adminUserId, enabled ? "FEATURE_FLAG_ENABLED" : "FEATURE_FLAG_DISABLED",
                    "FeatureFlag", flag.getId(), java.util.Map.of("key", flag.getKey()));
        }
        return toDto(flag);
    }

    private static FeatureFlagDto toDto(FeatureFlag f) {
        return new FeatureFlagDto(f.getId().toString(), f.getKey(), f.getDescription(), f.isEnabled(), f.getUpdatedAt());
    }
}
