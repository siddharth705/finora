package com.finora.repository;

import com.finora.entity.AdminTotpCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminTotpCredentialRepository extends JpaRepository<AdminTotpCredential, UUID> {
    Optional<AdminTotpCredential> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
