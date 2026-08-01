package com.finora.repository;

import com.finora.entity.PlatformSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, Short> {

    /** Atomic claim used by SetupService/PlatformSettingsService to close a concurrent-setup
     *  race -- see PlatformSettingsService.tryMarkSetupCompleted's own doc comment. A single
     *  UPDATE ... WHERE setup_completed = false can only ever affect a row once, database-enforced,
     *  regardless of how many callers race it; the returned row count (0 or 1) tells the caller
     *  whether THIS call was the one that won. */
    @Modifying
    @Query("UPDATE PlatformSettings p SET p.setupCompleted = true, p.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE p.id = 1 AND p.setupCompleted = false")
    int markSetupCompletedIfNotAlready();
}
