package com.finora.repository;

import com.finora.entity.MerchantAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, UUID> {
    Optional<MerchantAlias> findByUserIdAndNormalizedAlias(UUID userId, String normalizedAlias);
    List<MerchantAlias> findByMerchantId(UUID merchantId);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);

    /**
     * Inserts the alias, or does nothing if {@code (user_id, normalized_alias)} is already taken --
     * see {@code MerchantNormalizationEngine.addAlias}'s doc comment for the race this exists to
     * survive and why a plain {@code saveAndFlush()} + {@code catch(DataIntegrityViolationException)}
     * cannot be used here.
     *
     * <p>Same shape as {@link MerchantCategoryLearningRepository#ensurePairExists}: a single
     * statement, deliberately left in the CALLER's transaction (no {@code REQUIRES_NEW} here --
     * see that method's own doc comment for why a suspended-and-restarted inner transaction is the
     * wrong tool when the row being inserted has a foreign key into a parent the caller may have
     * just created and not yet committed, which {@code merchant_id} routinely is here).
     *
     * @return 1 if this call inserted the row, 0 if it already existed -- from an earlier call in
     *         this same transaction, or a concurrent writer that got there first. Either way, by
     *         the time this returns, a row for {@code (userId, normalizedAlias)} is guaranteed to
     *         exist.
     */
    @Modifying
    @Query(value = """
           INSERT INTO merchant_aliases (id, merchant_id, user_id, normalized_alias, created_at)
           VALUES (gen_random_uuid(), :merchantId, :userId, :normalizedAlias, now())
           ON CONFLICT (user_id, normalized_alias) DO NOTHING
           """, nativeQuery = true)
    int insertIfAbsent(@Param("merchantId") UUID merchantId, @Param("userId") UUID userId,
                        @Param("normalizedAlias") String normalizedAlias);
}
