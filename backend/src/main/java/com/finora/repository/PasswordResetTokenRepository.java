package com.finora.repository;

import com.finora.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, java.util.UUID> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Burns every still-unused reset link this user holds, in one statement, and returns how many
     * there were.
     *
     * <p>Reset tokens were only ever consumed one at a time: {@code AuthService.resetPassword}
     * marked the single token it had just validated, and {@code forgotPassword} issued new ones
     * without voiding the old. Every link issued inside the 30-minute TTL therefore stayed live
     * alongside every other. That is the difference between an attacker who triggers a reset for
     * someone else's address holding a link for 30 minutes, and holding one that survives the
     * victim noticing and resetting their own password -- at which point the victim has no
     * recovery action that actually revokes it.
     *
     * <p>A bulk {@code UPDATE} rather than a read-then-save loop, for the same reason
     * {@code PlatformSettingsService.tryMarkSetupCompleted} is one: the database decides, in one
     * atomic statement, with no window between reading the set and writing it.
     *
     * <p>Marks used rather than deleting, so the trail of how many links were outstanding when an
     * account was recovered survives in the table.
     *
     * @return the number of links invalidated -- 0 is the normal case and worth recording as
     *         such, since anything higher means more than one reset was in flight at once
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.usedAt IS NULL")
    int markAllUnusedAsUsed(@Param("userId") java.util.UUID userId, @Param("now") Instant now);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(java.util.UUID userId);
}
