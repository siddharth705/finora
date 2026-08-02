package com.finora.repository;

import com.finora.entity.PasswordChangeSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswordChangeSessionRepository extends JpaRepository<PasswordChangeSession, UUID> {
    /** Scoped to the caller's own userId -- a session ID alone must never be enough to act on
     *  someone else's in-progress password change, even if it were somehow guessed/leaked. */
    Optional<PasswordChangeSession> findByIdAndUserId(UUID id, UUID userId);
}
