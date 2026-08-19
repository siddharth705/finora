package com.finora.repository;

import com.finora.entity.AdminMfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminMfaRecoveryCodeRepository extends JpaRepository<AdminMfaRecoveryCode, UUID> {
    Optional<AdminMfaRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);
    List<AdminMfaRecoveryCode> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
