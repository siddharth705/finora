package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserFinancialFocusRepository extends JpaRepository<UserFinancialFocus, UUID> {
    List<UserFinancialFocus> findByUserId(UUID userId);

    @Modifying
    @Query("DELETE FROM UserFinancialFocus f WHERE f.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
