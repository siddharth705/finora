package com.finora.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The one place new in-process caches get registered. Every named cache here gets its own
 * {@link Caffeine} spec (TTL + max size) via {@link CaffeineCacheManager#registerCustomCache},
 * not the single blanket {@code spring.cache.caffeine.spec} property Spring Boot would otherwise
 * auto-apply to every cache alike -- different cached resources have different staleness
 * tolerances and dataset sizes, and a config file that can't express that would just push callers
 * back to inventing their own {@code ConcurrentHashMap}, which is exactly the "ad-hoc cache logic"
 * this class exists to avoid needing.
 *
 * <h2>Why Caffeine in-process, not Redis</h2>
 *
 * <p>Same reasoning {@code com.finora.imports.ImportConcurrencyLimiter} and {@code RateLimiter}
 * already document for their own in-process primitives: this is a single Railway instance, not a
 * distributed system.
 * Redis is the correct upgrade once a second backend instance exists and per-instance caches would
 * disagree with each other -- not before, and reaching for it now would be exactly the premature
 * complexity {@code distributed-resilience-patterns-audit-2026-08-14.md} found no evidence for.
 *
 * <h2>Cache-stampede protection is {@code sync = true}, not a separate mechanism</h2>
 *
 * <p>Every {@code @Cacheable} using one of these caches should set {@code sync = true}. Spring
 * resolves that to {@code Cache.get(key, Callable)}, which {@code CaffeineCache} delegates to
 * Caffeine's own {@code Cache.get(key, mappingFunction)} -- documented as atomic per key, so
 * concurrent callers that miss the same key block behind the first load rather than each
 * independently repeating the expensive work. This is what closes both the "thundering herd on
 * expiry" and "duplicate concurrent load" cases from the same audit with no extra code: it is a
 * property of the underlying cache, not something built on top of it.
 *
 * <h2>Adding a new cached resource</h2>
 *
 * <p>Register a name + {@link Caffeine} spec below, sized for that resource's own dataset and
 * staleness tolerance, then use {@code @Cacheable(cacheNames = "...", sync = true)} /
 * {@code @CacheEvict(cacheNames = "...")} on the read/write methods -- see
 * {@code BankManagementService} and {@code FeatureFlagService} for the pattern, including the
 * self-invocation pitfall both work around (a {@code @Cacheable} method is only intercepted
 * through Spring's proxy, so calling it via {@code this.} from inside the same class silently
 * never hits the cache).
 *
 * <h2>Diagnostics</h2>
 *
 * <p>{@code AdminDiagnosticsService} already reports whether a {@link CacheManager} bean exists
 * (it injects one as an {@code ObjectProvider} specifically to answer that) -- this bean is what
 * flips that diagnostic from false to true, not a new field added to make it so.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Admin-managed custom banks ({@code BankManagementService}) -- a small, rarely-changing
     *  dataset mutated only through admin CRUD, currently re-queried from Postgres on every
     *  account read (the accounts-listing N+1 named in {@code project-plan-v1.0.md} §5a). TTL is a
     *  safety net, not the primary invalidation path: {@code BankManagementService} evicts
     *  explicitly on create/update/delete, so an admin's own change is visible immediately rather
     *  than waiting out the window. */
    public static final String CUSTOM_BANKS_CACHE = "customBanks";

    /** Feature flags ({@code FeatureFlagService}) -- admin-toggled booleans, read on every call to
     *  {@code RecurringService.detectForUser} (8 call sites: every import confirm and every
     *  transaction mutation). Same explicit-eviction-plus-TTL-safety-net shape as the bank cache,
     *  with a shorter TTL: a flag gates real behavior, so bounding staleness tighter costs nothing
     *  against a dataset this small and buys a faster self-heal if an eviction path is ever missed. */
    public static final String FEATURE_FLAGS_CACHE = "featureFlags";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(CUSTOM_BANKS_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(10))
                .maximumSize(500)
                .build());
        manager.registerCustomCache(FEATURE_FLAGS_CACHE, Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(60))
                .maximumSize(200)
                .build());
        return manager;
    }
}
