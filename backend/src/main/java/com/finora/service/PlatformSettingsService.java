package com.finora.service;

import com.finora.dto.AdminDtos.PlatformSettingsDto;
import com.finora.dto.AdminDtos.UpdatePlatformSettingsRequest;
import com.finora.entity.PlatformSettings;
import com.finora.repository.PlatformSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Real platform-wide configuration -- see V27__platform_settings.sql for why this is one
 * singleton row rather than a generic key-value table (two admin-editable settings today, not a
 * speculative framework for arbitrary future ones). AuthService reads maxFailedLoginAttempts /
 * lockoutDurationMinutes / registrationsEnabled from here on every login/register call rather
 * than caching them in memory -- traffic at this app's scale doesn't justify the invalidation
 * complexity a cache would add, and "an admin changes a setting and it takes effect on the very
 * next request" is a better property to have than "...eventually, once a cache expires."
 */
@Service
public class PlatformSettingsService {

    // The only row that will ever exist (see the migration's CHECK constraint) -- every read/
    // write asks for this exact id rather than a "find the row" query.
    private static final short SINGLETON_ID = 1;

    private final PlatformSettingsRepository repository;
    private final AuditService auditService;

    public PlatformSettingsService(PlatformSettingsRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PlatformSettingsDto get() {
        return toDto(getEntity());
    }

    @Transactional
    public PlatformSettingsDto update(UUID actingAdminId, UpdatePlatformSettingsRequest req) {
        PlatformSettings settings = getEntity();
        if (req.registrationsEnabled() != null) settings.setRegistrationsEnabled(req.registrationsEnabled());
        if (req.maxFailedLoginAttempts() != null) settings.setMaxFailedLoginAttempts(req.maxFailedLoginAttempts());
        if (req.lockoutDurationMinutes() != null) settings.setLockoutDurationMinutes(req.lockoutDurationMinutes());
        settings.setUpdatedAt(Instant.now());
        PlatformSettings saved = repository.save(settings);
        auditService.record(actingAdminId, "PLATFORM_SETTINGS_UPDATED", "PlatformSettings", null,
                Map.of("registrationsEnabled", saved.isRegistrationsEnabled(),
                        "maxFailedLoginAttempts", saved.getMaxFailedLoginAttempts(),
                        "lockoutDurationMinutes", saved.getLockoutDurationMinutes()));
        return toDto(saved);
    }

    /** Used directly by AuthService (not via the DTO) -- login()/register() need the live entity
     *  values, not a fresh DTO allocation, on every single call. */
    @Transactional(readOnly = true)
    public PlatformSettings getEntity() {
        // The migration always inserts this row -- findById().orElseGet() is defense against a
        // hand-rolled test database that skipped the migration, not an expected runtime path.
        return repository.findById(SINGLETON_ID).orElseGet(PlatformSettings::new);
    }

    /** Called once, by SetupService.completeSetup(), in the same transaction that creates the
     *  first real SUPER_ADMIN and locks the bootstrap account -- see V33__bootstrap_admin.sql.
     *  No audit entry here: SetupService already records SETUP_COMPLETED with the acting user, and
     *  this method has no separate caller or actor of its own to attribute one to. */
    @Transactional
    public void markSetupCompleted() {
        PlatformSettings settings = getEntity();
        settings.setSetupCompleted(true);
        settings.setUpdatedAt(Instant.now());
        repository.save(settings);
    }

    private PlatformSettingsDto toDto(PlatformSettings s) {
        return new PlatformSettingsDto(s.isRegistrationsEnabled(), s.getMaxFailedLoginAttempts(),
                s.getLockoutDurationMinutes(), s.getUpdatedAt());
    }
}
