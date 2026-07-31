package com.finora.repository;

import com.finora.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    Optional<FeatureFlag> findByKey(String key);

    /**
     * The actual gate call sites (e.g. RecurringService.detectForUser) use, via
     * FeatureFlagService.isEnabled(key) -- an unknown key is treated as enabled (fail-open) rather
     * than silently disabling behavior that predates the flags table, or a typo'd key silently
     * turning a feature off platform-wide.
     */
    default boolean isEnabled(String key) {
        return findByKey(key).map(FeatureFlag::isEnabled).orElse(true);
    }
}
