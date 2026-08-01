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

    /**
     * Bug fix: SetupService.completeSetup() used to check isSetupRequired() (a plain read) at the
     * START of its transaction, then only call this method (a plain read-modify-write) at the
     * END, after already creating a new SUPER_ADMIN. Under READ_COMMITTED (the default), two
     * overlapping completeSetup() calls -- a double-click on the setup wizard's submit button, or
     * a client retry after a slow/timed-out first response, both realistic given this endpoint's
     * one-time nature -- could both pass the initial check before either transaction committed,
     * each creating its own SUPER_ADMIN account. This method replaces that check-then-write
     * pattern with a single atomic UPDATE ... WHERE setup_completed = false: the database itself
     * guarantees only one caller's UPDATE can ever match that WHERE clause and actually flip the
     * row, no matter how many callers race it -- there's no window between "check" and "write" for
     * a second caller to slip through, because there IS no separate check. Called first thing in
     * SetupService.completeSetup(), before any admin is created, specifically so a losing caller
     * is rejected immediately rather than discovering the conflict only after already doing the
     * work. Returns whether THIS call was the one that won the race (false means setup was
     * already completed, by this call or a concurrent one) -- the caller is expected to abort
     * immediately when it gets false.
     */
    @Transactional
    public boolean tryMarkSetupCompleted() {
        return repository.markSetupCompletedIfNotAlready() == 1;
    }

    private PlatformSettingsDto toDto(PlatformSettings s) {
        return new PlatformSettingsDto(s.isRegistrationsEnabled(), s.getMaxFailedLoginAttempts(),
                s.getLockoutDurationMinutes(), s.getUpdatedAt());
    }
}
