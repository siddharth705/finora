package com.finora.repository;

import com.finora.entity.EmailChangeSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailChangeSessionRepository extends JpaRepository<EmailChangeSession, UUID> {
    /** Scoped to the caller's own userId -- a session ID alone must never be enough to act on
     *  someone else's in-progress email change, even if it were somehow guessed/leaked. */
    Optional<EmailChangeSession> findByIdAndUserId(UUID id, UUID userId);
}
