package com.finora.service;

import com.finora.dto.DiagnosticsDto.ApplicationInfoDto;
import com.finora.dto.DiagnosticsDto.ConfigurationSummaryDto;
import com.finora.dto.DiagnosticsDto.PlatformDiagnosticsDto;
import com.finora.dto.DiagnosticsDto.RuntimeInfoDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;

/**
 * Platform Diagnostics -- a lightweight developer/support page, explicitly NOT the start of an
 * in-house observability platform. See DiagnosticsDto's class doc for the full reasoning and the
 * RFC's out-of-scope list (log aggregation, distributed tracing, exception management, etc. all
 * remain the job of dedicated tools when the platform reaches that phase of the roadmap).
 *
 * Composes AdminHealthRegistryService (health providers -- same registry the Dashboard uses) and
 * AdminSystemService (recent imports) rather than duplicating either; the only things actually
 * computed here are the handful of small, genuinely new signals the RFC called for.
 */
@Service
public class AdminDiagnosticsService {

    private final AdminHealthRegistryService healthRegistryService;
    private final AdminSystemService adminSystemService;
    private final PlatformSettingsService platformSettingsService;
    private final Environment environment;
    private final Flyway flyway;
    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;
    private final ObjectProvider<CacheManager> cacheManager;

    public AdminDiagnosticsService(AdminHealthRegistryService healthRegistryService,
                                    AdminSystemService adminSystemService,
                                    PlatformSettingsService platformSettingsService,
                                    Environment environment,
                                    Flyway flyway,
                                    ObjectProvider<BuildProperties> buildProperties,
                                    ObjectProvider<GitProperties> gitProperties,
                                    ObjectProvider<CacheManager> cacheManager) {
        this.healthRegistryService = healthRegistryService;
        this.adminSystemService = adminSystemService;
        this.platformSettingsService = platformSettingsService;
        this.environment = environment;
        this.flyway = flyway;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
        this.cacheManager = cacheManager;
    }

    @Transactional(readOnly = true)
    public PlatformDiagnosticsDto overview() {
        return new PlatformDiagnosticsDto(
                applicationInfo(),
                runtimeInfo(),
                health(),
                configurationSummary(),
                adminSystemService.recentImports());
    }

    private ApplicationInfoDto applicationInfo() {
        // Both null when the app was started without the build-info/git-commit-id Maven goals
        // having run (e.g. straight from an IDE run configuration, or `mvn spring-boot:run` --
        // that goal binds to `package`, not `spring-boot:run`'s own lifecycle) -- see this
        // class's own doc and pom.xml's comments on those two plugins.
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        String[] activeProfiles = environment.getActiveProfiles();
        return new ApplicationInfoDto(
                build != null ? build.getVersion() : null,
                git != null ? git.getShortCommitId() : null,
                activeProfiles.length > 0 ? String.join(",", activeProfiles) : "default");
    }

    private RuntimeInfoDto runtimeInfo() {
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        // Flyway's own schema_history table is the real source of truth for "what version is
        // this database on" -- same number the startup log already prints
        // ("Current version of schema public: N"), just read programmatically here instead of
        // grepped from a log line.
        String flywayVersion = flyway.info().current() != null
                ? flyway.info().current().getVersion().getVersion()
                : "none";
        return new RuntimeInfoDto(uptimeSeconds, flywayVersion, cacheManager.getIfAvailable() != null);
    }

    private PlatformHealthDto health() {
        return healthRegistryService.platformHealth();
    }

    private ConfigurationSummaryDto configurationSummary() {
        var settings = platformSettingsService.getEntity();
        return new ConfigurationSummaryDto(
                settings.isRegistrationsEnabled(),
                settings.isSetupCompleted(),
                "Always required (not currently configurable -- see ADR-0001)");
    }
}
