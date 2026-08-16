package com.finora.repository;

import com.finora.entity.PhoneChangeSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PhoneChangeSessionRepository extends JpaRepository<PhoneChangeSession, UUID> {
    /** Scoped to the caller's own userId -- a session ID alone must never be enough to act on
     *  someone else's in-progress phone number change, even if it were somehow guessed/leaked. */
    Optional<PhoneChangeSession> findByIdAndUserId(UUID id, UUID userId);
}
