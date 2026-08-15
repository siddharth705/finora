package com.finora.repository;

import com.finora.entity.AccountReactivationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AccountReactivationTokenRepository extends JpaRepository<AccountReactivationToken, UUID> {
    Optional<AccountReactivationToken> findByTokenHash(String tokenHash);

    /** Mirrors PasswordResetTokenRepository.markAllUnusedAsUsed -- burns every still-unused
     *  reactivation link for this user in one statement, so a stale earlier link (e.g. from a
     *  login attempt the user abandoned) can't be replayed after a later one already reactivated
     *  the account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE AccountReactivationToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedAsUsed(@Param("userId") UUID userId, @Param("now") Instant now);

    /** AccountPurgeSweepService -- moot after purge (the account can never log in again to
     *  reactivate) but cheap to clean up. Hard delete, no soft-delete concern. */
    void deleteByUserId(UUID userId);
}
