package com.finora.repository;

import com.finora.entity.PhoneOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhoneOtpRepository extends JpaRepository<PhoneOtp, UUID> {
    List<PhoneOtp> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
