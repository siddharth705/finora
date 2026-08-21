package com.finora.repository;

import com.finora.entity.AdminMfaChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminMfaChallengeRepository extends JpaRepository<AdminMfaChallenge, UUID> {
    Optional<AdminMfaChallenge> findByTokenHash(String tokenHash);
    void deleteByUserId(UUID userId);
}
