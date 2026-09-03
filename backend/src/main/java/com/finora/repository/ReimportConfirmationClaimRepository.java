package com.finora.repository;

import com.finora.entity.ReimportConfirmationClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * @see com.finora.entity.ReimportConfirmationClaim for why this exists and why the unique index —
 *      not the lookup below — is what actually prevents a duplicate re-import.
 */
public interface ReimportConfirmationClaimRepository extends JpaRepository<ReimportConfirmationClaim, UUID> {

    /**
     * Used only to turn the race the unique index already lost into a clear, specific error
     * message. Never used as the guard on its own: two concurrent confirms can both return empty
     * here and both proceed, which is exactly the case the index is there for.
     */
    Optional<ReimportConfirmationClaim> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
