package com.finora.repository;

import com.finora.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /** Mirrors PasswordResetTokenRepository.markAllUnusedAsUsed -- burns every still-unused
     *  verification link for this user in one statement, so an earlier link (e.g. from
     *  register(), if loginWithGoogle() later mints a fresh one) can't be replayed after a later
     *  one already verified the account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedAsUsed(@Param("userId") UUID userId, @Param("now") Instant now);

    /** AccountPurgeSweepService -- moot after purge (the account can never log in again to use
     *  it) but cheap to clean up. Hard delete, no soft-delete concern. */
    void deleteByUserId(UUID userId);
}
