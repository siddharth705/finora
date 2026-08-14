package com.finora.service;

import com.finora.config.CacheConfig;
import com.finora.dto.AdminDtos.FeatureFlagDto;
import com.finora.entity.FeatureFlag;
import com.finora.exception.ApiException;
import com.finora.repository.FeatureFlagRepository;
import com.finora.util.AfterCommit;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
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
 *
 * <p>{@link #isEnabled} is cached (see {@link CacheConfig#FEATURE_FLAGS_CACHE}) -- it is read on
 * every call to {@code RecurringService.detectForUser}, which itself has 8 call sites (every
 * import confirm and every transaction mutation), so an admin-toggled boolean that changes rarely
 * was otherwise paying a database round trip on some of this codebase's hottest paths. No self-
 * invocation concern here the way {@code BankManagementService}/{@code CustomBankLookup} had to
 * work around: the cached method is called from other beans (e.g. {@code RecurringService}),
 * never from within this class, so Spring's proxy always sees the call.
 *
 * <p>{@link #setEnabled} evicts via a direct {@link CacheManager} call inside
 * {@link AfterCommit#run}, not {@code @CacheEvict} -- the same choice
 * {@code BankManagementService} makes and for the same reason: relying on
 * {@code @Transactional}/{@code @CacheEvict} interceptor ordering to guarantee eviction happens
 * strictly after commit is implicit and hard to verify, where an explicit post-commit callback is
 * neither.
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;
    private final AuditService auditService;
    private final CacheManager cacheManager;

    public FeatureFlagService(FeatureFlagRepository featureFlagRepository, AuditService auditService,
                               CacheManager cacheManager) {
        this.featureFlagRepository = featureFlagRepository;
        this.auditService = auditService;
        this.cacheManager = cacheManager;
    }

    public List<FeatureFlagDto> list() {
        return featureFlagRepository.findAll().stream()
                .sorted(Comparator.comparing(FeatureFlag::getKey))
                .map(FeatureFlagService::toDto)
                .toList();
    }

    /** {@code sync = true}: concurrent callers that miss the same key block behind the first load
     *  rather than each independently querying -- see {@link CacheConfig}'s own doc comment. */
    @Cacheable(cacheNames = CacheConfig.FEATURE_FLAGS_CACHE, key = "#key", sync = true)
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
        String flagKey = flag.getKey();
        AfterCommit.run("feature flag cache invalidation", () -> {
            Cache cache = cacheManager.getCache(CacheConfig.FEATURE_FLAGS_CACHE);
            if (cache != null) cache.evict(flagKey);
        });
        return toDto(flag);
    }

    private static FeatureFlagDto toDto(FeatureFlag f) {
        return new FeatureFlagDto(f.getId().toString(), f.getKey(), f.getDescription(), f.isEnabled(), f.getUpdatedAt());
    }
}
