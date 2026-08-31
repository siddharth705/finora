package com.finora.service;

import com.finora.config.BuildVersionResolver;
import com.finora.dto.DiagnosticsDto.PlatformDiagnosticsDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.entity.PlatformSettings;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every field this service reports needs a real, verifiable source -- these tests exist mainly
 * to lock in the "null/false when unavailable, never a fabricated placeholder" behavior for the
 * optional build/git metadata and cache-manager presence, and the ADR-0001 wording for phone
 * verification's non-configurable status (see DiagnosticsDto's class doc).
 */
@SuppressWarnings("unchecked")
class AdminDiagnosticsServiceTest {

    private AdminHealthRegistryService healthRegistryService;
    private AdminSystemService adminSystemService;
    private PlatformSettingsService platformSettingsService;
    private Environment environment;
    private Flyway flyway;
    private ObjectProvider<BuildProperties> buildProperties;
    private ObjectProvider<GitProperties> gitProperties;
    private BuildVersionResolver buildVersionResolver;
    private ObjectProvider<CacheManager> cacheManager;
    private AdminDiagnosticsService service;

    @BeforeEach
    void setUp() {
        healthRegistryService = mock(AdminHealthRegistryService.class);
        adminSystemService = mock(AdminSystemService.class);
        platformSettingsService = mock(PlatformSettingsService.class);
        environment = mock(Environment.class);
        flyway = mock(Flyway.class);
        buildProperties = mock(ObjectProvider.class);
        cacheManager = mock(ObjectProvider.class);
        // Still a mocked ObjectProvider<GitProperties>, now used to build a REAL
        // BuildVersionResolver per test rather than being read by AdminDiagnosticsService itself --
        // proves the wiring end-to-end while BuildVersionResolverTest owns the resolution edge cases.
        gitProperties = mock(ObjectProvider.class);
        buildVersionResolver = new BuildVersionResolver(gitProperties, "");
        service = new AdminDiagnosticsService(healthRegistryService, adminSystemService, platformSettingsService,
                environment, flyway, buildProperties, buildVersionResolver, cacheManager);

        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", java.util.List.of()));
        when(adminSystemService.recentImports()).thenReturn(java.util.List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        PlatformSettings settings = new PlatformSettings();
        ReflectionTestUtils.setField(settings, "registrationsEnabled", true);
        ReflectionTestUtils.setField(settings, "setupCompleted", true);
        when(platformSettingsService.getEntity()).thenReturn(settings);

        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo currentMigration = mock(MigrationInfo.class);
        when(currentMigration.getVersion()).thenReturn(MigrationVersion.fromVersion("33"));
        when(infoService.current()).thenReturn(currentMigration);
        when(flyway.info()).thenReturn(infoService);
    }

    @Test
    void reportsNullBuildAndGitInfo_whenThoseBeansAreUnavailable() {
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.application().version()).isNull();
        assertThat(dto.application().gitCommit()).isNull();
        assertThat(dto.runtime().cacheEnabled()).isFalse();
    }

    // --- Git commit fallback -----------------------------------------------------------------
    //
    // git-commit-id-maven-plugin reads a .git directory at build time, and the production image
    // has none: backend/Dockerfile's build context is backend/ while .git sits at the repository
    // root. With failOnNoGitDirectory=false it produces nothing, silently -- so this field worked
    // on every developer machine and was blank on every deployed environment, which is precisely
    // where "which build is actually live?" gets asked. It cost a real investigation several
    // rounds of inference before anyone noticed the field was empty rather than the build old.

    @Test
    void fallsBackToTheConfiguredCommit_whenGitMetadataIsAbsent() {
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);
        service = new AdminDiagnosticsService(healthRegistryService, adminSystemService, platformSettingsService,
                environment, flyway, buildProperties, new BuildVersionResolver(gitProperties, "77bbfe4"), cacheManager);

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.application().gitCommit()).isEqualTo("77bbfe4");
    }

    @Test
    void truncatesAFullShaToTheSameLengthGitPropertiesWouldReport() {
        // RAILWAY_GIT_COMMIT_SHA is the full 40-character sha, where GitProperties reports the
        // 7-character abbreviation. The field must look the same whichever source supplied it.
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);
        service = new AdminDiagnosticsService(healthRegistryService, adminSystemService, platformSettingsService,
                environment, flyway, buildProperties,
                new BuildVersionResolver(gitProperties, "77bbfe493cf230ce3e4624dfaa41fe617c8ae127"), cacheManager);

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.application().gitCommit()).isEqualTo("77bbfe4");
    }

    @Test
    void prefersRealGitMetadataOverTheConfiguredFallback() {
        // Precedence matters and is not arbitrary: git.properties is derived from the tree that
        // was actually compiled and cannot disagree with it, whereas an environment variable can
        // be stale or simply wrong. A diagnostic that confidently reports the wrong commit is
        // worse than one that reports nothing.
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);

        Properties gitProps = new Properties();
        gitProps.setProperty("commit.id.abbrev", "a1b2c3d");
        when(gitProperties.getIfAvailable()).thenReturn(new GitProperties(gitProps));
        service = new AdminDiagnosticsService(healthRegistryService, adminSystemService, platformSettingsService,
                environment, flyway, buildProperties, new BuildVersionResolver(gitProperties, "9999999"), cacheManager);

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.application().gitCommit()).isEqualTo("a1b2c3d");
    }

    @Test
    void reportsRealBuildAndGitInfo_whenThoseBeansExist() {
        Properties props = new Properties();
        props.setProperty("version", "0.1.0");
        when(buildProperties.getIfAvailable()).thenReturn(new BuildProperties(props));

        Properties gitProps = new Properties();
        gitProps.setProperty("commit.id.abbrev", "a1b2c3d");
        when(gitProperties.getIfAvailable()).thenReturn(new GitProperties(gitProps));
        when(cacheManager.getIfAvailable()).thenReturn(mock(CacheManager.class));

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.application().version()).isEqualTo("0.1.0");
        assertThat(dto.application().gitCommit()).isEqualTo("a1b2c3d");
        assertThat(dto.runtime().cacheEnabled()).isTrue();
    }

    @Test
    void reportsFlywayVersionFromRealMigrationHistory() {
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);

        PlatformDiagnosticsDto dto = service.overview();

        assertThat(dto.runtime().flywayVersion()).isEqualTo("33");
    }

    @Test
    void phoneVerificationPolicyIsDescriptiveNotAToggle() {
        when(buildProperties.getIfAvailable()).thenReturn(null);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        when(cacheManager.getIfAvailable()).thenReturn(null);

        PlatformDiagnosticsDto dto = service.overview();

        // Deliberately not a boolean -- see ADR-0001 and DiagnosticsDto's own doc comment.
        assertThat(dto.configuration().phoneVerificationPolicy()).contains("ADR-0001");
        assertThat(dto.configuration().registrationsEnabled()).isTrue();
        assertThat(dto.configuration().setupCompleted()).isTrue();
    }
}
