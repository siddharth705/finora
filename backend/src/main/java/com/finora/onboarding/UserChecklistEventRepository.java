package com.finora.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserChecklistEventRepository extends JpaRepository<UserChecklistEvent, UUID> {
    List<UserChecklistEvent> findByUserId(UUID userId);
    boolean existsByUserIdAndItemKey(UUID userId, String itemKey);
}
