package com.finora.repository;

import com.finora.entity.PhoneOtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PhoneOtpRepository extends JpaRepository<PhoneOtp, UUID> {
    /** Scoped to one purpose (see PhoneOtp.Purpose) -- a code issued for one purpose must never
     *  be found by a lookup verifying a different one, even if both were issued to the same user
     *  around the same time. */
    List<PhoneOtp> findByUserIdAndPurposeOrderByCreatedAtDesc(UUID userId, PhoneOtp.Purpose purpose);
}
