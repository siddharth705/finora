package com.finora.service;

import com.finora.config.CacheConfig;
import com.finora.entity.Bank;
import com.finora.repository.BankRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The cached read side of the custom-banks table, split out of {@link BankManagementService} for
 * one reason: {@code @Cacheable} only takes effect through Spring's proxy, so a method calling it
 * via {@code this.} from inside the same bean silently never hits the cache. {@code resolve()},
 * {@code listAll()} and {@code search()} all needed the same cached read, so it had to live on a
 * different bean for any of them to actually benefit.
 *
 * <p>One entry, not one per bank id -- {@code all()} caches the whole list under a constant key.
 * The dataset this covers is admin-added custom banks (small, low tens at most in practice), so a
 * linear scan over the cached list for a single {@code resolve(id)} costs nothing measurable, and
 * caching the list rather than per-id lookups is what also serves {@link #search} without a second
 * cache entry per query string.
 *
 * <p>Evidence this is worth caching at all: {@code project-plan-v1.0.md} §5a names
 * {@code bankRepository.findById} per account, inside {@code AccountService.listForUser}'s read
 * transaction, as a measured N+1 -- every account on the page re-queries this same small table.
 * Caching the underlying list collapses that to one query per cache window, the same practical
 * effect as batching the N+1 without needing to restructure {@code AccountService}'s query shape.
 */
@Component
public class CustomBankLookup {

    private final BankRepository bankRepository;

    public CustomBankLookup(BankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    /** {@code sync = true}: concurrent callers that miss this key block behind the first load
     *  rather than each independently querying -- see {@link CacheConfig}'s own doc comment. */
    @Cacheable(cacheNames = CacheConfig.CUSTOM_BANKS_CACHE, key = "'all'", sync = true)
    @Transactional(readOnly = true)
    public List<Bank> all() {
        return bankRepository.findAllByOrderByOfficialNameAsc();
    }

    /** Called after every admin create/update/delete so the change is visible on the very next
     *  read, rather than waiting out the cache's TTL safety net. */
    @CacheEvict(cacheNames = CacheConfig.CUSTOM_BANKS_CACHE, key = "'all'")
    public void invalidate() {
    }
}
