package com.finora.imports;

import com.finora.entity.Merchant;

import java.util.Map;

/**
 * Every existing merchant a user owns, pre-indexed for one staging pass, so resolving a row's
 * merchant identity costs nothing per row.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code MerchantNormalizationEngine.resolveReadOnly(userId, description)} is safe to call once
 * per row only when the caller runs inside a transaction -- its own per-transaction memo
 * ({@code merchantsByFirstToken}) is what turns 500 full-table loads into 1, exactly as
 * {@code MerchantNormalizationEngineTest} documents. Transaction Intelligence Phase A's staging path
 * ({@code ImportService.parseAndStageWithSession}) is deliberately NOT wrapped in a transaction --
 * holding a database connection open for the duration of file parsing would reduce pool
 * availability for concurrent imports, which this project has separately profiled as a real
 * constraint (see the HikariCP/Railway-ceiling investigation). Calling {@code resolveReadOnly} once
 * per row from that path therefore reopens exactly the N+1 Bug 35 already fixed for {@code resolve()}
 * -- caught by {@code ImportQueryCountIT} when Transaction Intelligence Phase A first tried it.
 *
 * <p>This class is the same fix {@code DuplicateIndex} and the hoisted {@code List<CategoryRule>}
 * already apply to the other two per-row N+1s in this pipeline: build the per-user resource ONCE,
 * outside any transaction, and pass it into {@code TransactionNormalizer.normalize} for every row of
 * the statement. Unlike {@code DuplicateIndex} (which loads lazily per date because a statement's
 * date range isn't known until parsing finishes), a user's full merchant/alias set is bounded by
 * their own history, not by the statement being staged, so it can be loaded eagerly and in full.
 */
public final class MerchantIndex {

    private final Map<String, Merchant> byNormalizedAlias;
    private final Map<String, Merchant> byFirstToken;

    /**
     * Built only by {@code MerchantNormalizationEngine.indexFor} (a different package from this
     * one), which is why this constructor and the accessors below are public rather than
     * package-private: the maps' keys are {@code MerchantNormalizationEngine}'s own private
     * normalization output (a raw description reduced to a normalized alias, or to its first
     * significant token), and only that class knows how to compute them from a new description --
     * this class is deliberately just the storage, never the reduction logic.
     */
    public MerchantIndex(Map<String, Merchant> byNormalizedAlias, Map<String, Merchant> byFirstToken) {
        this.byNormalizedAlias = byNormalizedAlias;
        this.byFirstToken = byFirstToken;
    }

    public Merchant byNormalizedAlias(String normalizedAlias) {
        return byNormalizedAlias.get(normalizedAlias);
    }

    public Merchant byFirstToken(String firstToken) {
        return byFirstToken.get(firstToken);
    }
}
