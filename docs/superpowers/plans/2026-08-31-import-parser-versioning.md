# Import Parser Versioning (Phase 1B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the residual staleness window Phase 1A's time-based dedup fix leaves open. A
session is now replayed automatically only if it was staged under the exact same backend build
that's asking to replay it — not merely "recently," which still had a window (a session created
seconds before a fix deploys could be replayed for up to 5 more minutes under Phase 1A alone).

**Architecture:** The backend already has a build-time version source:
`git-commit-id-maven-plugin` generates a `GitProperties` bean, with a `RAILWAY_GIT_COMMIT_SHA`
environment-variable fallback for production (where `.git` isn't available in the Docker build
context) — used today by `AdminDiagnosticsService` for its "Application Version" diagnostics
field. This plan extracts that resolution logic into a small shared component
(`BuildVersionResolver`), stamps every newly-staged `ImportSession` with the resolved commit, and
makes `ImportSessionService.findLiveSessionByContentHash` compare the stored version against the
current one: a mismatch (including a pre-this-feature session, whose version is null) forces a
fresh parse regardless of age; a match replays regardless of age (bounded by the existing 48h TTL).
Phase 1A's 5-minute trust window is kept, but repurposed as the fallback for the one case version
comparison can't resolve: an environment with no git metadata and no `RAILWAY_GIT_COMMIT_SHA`/
`GIT_COMMIT` set (local dev, and the test suite itself).

**Tech Stack:** Java 21 / Spring Boot, Flyway migrations, JUnit 5 + Mockito + AssertJ.

**Spec:** [import-pipeline-roadmap-review-2026-08-31.md](../../architecture/system-design/../../../../../../private/tmp/claude-501/-Users-sid-Downloads-finora/dd5c6810-4fe7-4a04-9a27-a25303bdd40e/scratchpad/import-pipeline-roadmap-review-2026-08-31.md)
§5 (the independent review that recommended this over extending the time-heuristic further) and
the Phase 1A plan/PR it follows: [PR #667](https://github.com/siddharth705/finora/pull/667).

## Global Constraints

- Migration version: **V122** — confirmed against `origin/main` (latest is V121) at plan-writing
  time; re-confirm with `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -3`
  immediately before creating the file, per this repo's shared-checkout migration rule.
- `AdminDiagnosticsService`'s existing behavior must not change — this is a behavior-preserving
  refactor for that class; only its internal implementation of commit resolution moves.
- New column is nullable, no backfill — every pre-existing `ImportSession` row simply has
  `parser_version = NULL`, which the comparison logic in Task 3 treats as "definitely stale"
  (correct: nothing before this feature shipped ever recorded what it was built from).
- Do not touch `SESSION_TTL` or `idx_import_sessions_live_content` — same scope boundary as
  Phase 1A.

---

## Task 1: Extract `BuildVersionResolver`, refactor `AdminDiagnosticsService` to use it

**Files:**
- Create: `backend/src/main/java/com/finora/config/BuildVersionResolver.java`
- Create: `backend/src/test/java/com/finora/config/BuildVersionResolverTest.java`
- Modify: `backend/src/main/java/com/finora/service/AdminDiagnosticsService.java`
- Modify: `backend/src/test/java/com/finora/service/AdminDiagnosticsServiceTest.java`

**Interfaces:**
- Produces: `BuildVersionResolver.currentCommit(): String` (nullable) — the short (7-char) commit
  id of the running build, or null if it cannot be determined at all.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/config/BuildVersionResolverTest.java`:

```java
package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.GitProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Extracted from AdminDiagnosticsService (which owned this logic alone until ImportSessionService
 * needed the same "what build is this?" answer for parser-version-aware session invalidation --
 * two callers of one deterministic function cannot drift; two implementations of one rule always
 * eventually do). See git-commit-id-maven-plugin's config in pom.xml for why the production image
 * needs the RAILWAY_GIT_COMMIT_SHA fallback at all: .git isn't in the Docker build context.
 */
@SuppressWarnings("unchecked")
class BuildVersionResolverTest {

    @Test
    void returnsNull_whenNeitherGitMetadataNorAConfiguredCommitExists() {
        ObjectProvider<GitProperties> gitProperties = mock(ObjectProvider.class);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        BuildVersionResolver resolver = new BuildVersionResolver(gitProperties, "");

        assertThat(resolver.currentCommit()).isNull();
    }

    @Test
    void fallsBackToTheConfiguredCommit_whenGitMetadataIsAbsent() {
        ObjectProvider<GitProperties> gitProperties = mock(ObjectProvider.class);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        BuildVersionResolver resolver = new BuildVersionResolver(gitProperties, "77bbfe4");

        assertThat(resolver.currentCommit()).isEqualTo("77bbfe4");
    }

    @Test
    void truncatesAFullShaToTheSameLengthGitPropertiesWouldReport() {
        // RAILWAY_GIT_COMMIT_SHA is the full 40-character sha, where GitProperties reports the
        // 7-character abbreviation -- must look the same whichever source supplied it, or a
        // session staged from one source could never match a lookup resolved from the other.
        ObjectProvider<GitProperties> gitProperties = mock(ObjectProvider.class);
        when(gitProperties.getIfAvailable()).thenReturn(null);
        BuildVersionResolver resolver = new BuildVersionResolver(
                gitProperties, "77bbfe493cf230ce3e4624dfaa41fe617c8ae127");

        assertThat(resolver.currentCommit()).isEqualTo("77bbfe4");
    }

    @Test
    void prefersRealGitMetadataOverTheConfiguredFallback() {
        ObjectProvider<GitProperties> gitProperties = mock(ObjectProvider.class);
        Properties gitProps = new Properties();
        gitProps.setProperty("commit.id.abbrev", "a1b2c3d");
        when(gitProperties.getIfAvailable()).thenReturn(new GitProperties(gitProps));
        BuildVersionResolver resolver = new BuildVersionResolver(gitProperties, "9999999");

        assertThat(resolver.currentCommit()).isEqualTo("a1b2c3d");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=BuildVersionResolverTest`
Expected: compile failure — `BuildVersionResolver` doesn't exist yet.

- [ ] **Step 3: Implement `BuildVersionResolver`**

Create `backend/src/main/java/com/finora/config/BuildVersionResolver.java`:

```java
package com.finora.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.stereotype.Component;

/**
 * What build is actually running, as a short commit id -- extracted from AdminDiagnosticsService,
 * which needed this for its "Application Version" diagnostics field before ImportSessionService
 * needed the same answer for parser-version-aware session invalidation (see that class's own doc
 * comment on findLiveSessionByContentHash for why). Two callers of one deterministic function
 * cannot drift; two implementations of one rule always eventually do.
 *
 * <p>Precedence is deliberate. Real git metadata (git-commit-id-maven-plugin, reading .git at
 * build time) wins when present, because it is derived from the tree that was actually compiled
 * and cannot disagree with it. The configured fallback exists because the production Docker image
 * has no .git directory at all -- backend/Dockerfile's build context is backend/, .git sits at the
 * repository root -- so on every deployed environment the plugin silently produces nothing. Its
 * default reads RAILWAY_GIT_COMMIT_SHA, which Railway sets per deployment, so the deployment
 * target needs no manual configuration.
 */
@Component
public class BuildVersionResolver {

    private static final int SHORT_COMMIT_LENGTH = 7;

    private final ObjectProvider<GitProperties> gitProperties;
    private final String configuredCommit;

    public BuildVersionResolver(ObjectProvider<GitProperties> gitProperties,
                                 @Value("${app.build.commit:}") String configuredCommit) {
        this.gitProperties = gitProperties;
        this.configuredCommit = configuredCommit;
    }

    /** The running build's short commit id, or null if it cannot be determined at all (no git
     *  metadata AND no configured fallback -- typically local dev without GIT_COMMIT or
     *  RAILWAY_GIT_COMMIT_SHA set). */
    public String currentCommit() {
        GitProperties git = gitProperties.getIfAvailable();
        if (git != null && git.getShortCommitId() != null) return git.getShortCommitId();

        if (configuredCommit == null || configuredCommit.isBlank()) return null;
        return configuredCommit.length() > SHORT_COMMIT_LENGTH
                ? configuredCommit.substring(0, SHORT_COMMIT_LENGTH)
                : configuredCommit;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -o test -Dtest=BuildVersionResolverTest`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Refactor `AdminDiagnosticsService` to use it**

In `AdminDiagnosticsService.java`: replace the `ObjectProvider<GitProperties> gitProperties`
field and constructor parameter with `BuildVersionResolver buildVersionResolver`. Replace the
`applicationInfo()` method's `resolveCommit(git)` call with `buildVersionResolver.currentCommit()`,
and delete the now-unused `git` local variable, the private `resolveCommit(GitProperties)` method,
and the `SHORT_COMMIT_LENGTH` constant (all moved into `BuildVersionResolver`). Remove the now-
unused `GitProperties` import.

```java
    private final BuildVersionResolver buildVersionResolver;
    // ... (buildProperties stays -- unrelated to commit resolution)

    public AdminDiagnosticsService(AdminHealthRegistryService healthRegistryService,
                                    AdminSystemService adminSystemService,
                                    PlatformSettingsService platformSettingsService,
                                    Environment environment,
                                    Flyway flyway,
                                    ObjectProvider<BuildProperties> buildProperties,
                                    BuildVersionResolver buildVersionResolver,
                                    ObjectProvider<CacheManager> cacheManager) {
        this.healthRegistryService = healthRegistryService;
        this.adminSystemService = adminSystemService;
        this.platformSettingsService = platformSettingsService;
        this.environment = environment;
        this.flyway = flyway;
        this.buildProperties = buildProperties;
        this.buildVersionResolver = buildVersionResolver;
        this.cacheManager = cacheManager;
    }
```

```java
    private ApplicationInfoDto applicationInfo() {
        BuildProperties build = buildProperties.getIfAvailable();
        String[] activeProfiles = environment.getActiveProfiles();
        return new ApplicationInfoDto(
                build != null ? build.getVersion() : null,
                buildVersionResolver.currentCommit(),
                activeProfiles.length > 0 ? String.join(",", activeProfiles) : "default");
    }
```

(Add `import com.finora.config.BuildVersionResolver;`.)

- [ ] **Step 6: Update `AdminDiagnosticsServiceTest` for the new constructor shape**

In `AdminDiagnosticsServiceTest.java`: replace the `ObjectProvider<GitProperties> gitProperties`
field with `BuildVersionResolver buildVersionResolver`, remove the `GitProperties`/`Properties`-
for-git-props plumbing from `setUp()`, and construct a real `BuildVersionResolver` per test (not
a mock — this proves the wiring end-to-end while `BuildVersionResolverTest` owns the resolution
edge cases). Replace the 4 commit-related tests:

```java
    private ObjectProvider<GitProperties> gitProperties; // -> DELETE this field
    private BuildVersionResolver buildVersionResolver;    // -> ADD this field instead
```

```java
    @BeforeEach
    void setUp() {
        healthRegistryService = mock(AdminHealthRegistryService.class);
        adminSystemService = mock(AdminSystemService.class);
        platformSettingsService = mock(PlatformSettingsService.class);
        environment = mock(Environment.class);
        flyway = mock(Flyway.class);
        buildProperties = mock(ObjectProvider.class);
        cacheManager = mock(ObjectProvider.class);
        gitProperties = mock(ObjectProvider.class); // still needed, now to build a real resolver
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
```

Keep `gitProperties` as a field (still declared above, just repurposed) so the 4 tests below can
keep stubbing `gitProperties.getIfAvailable()` exactly as before — only the "configured commit"
half of each test changes, from `when(environment.getProperty("app.build.commit", "")).thenReturn(...)`
to rebuilding `buildVersionResolver` with that string passed directly to its constructor, and
`service` rebuilt to use it (since `service` was already constructed in `setUp()` with the
default resolver):

```java
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
```

Add `import com.finora.config.BuildVersionResolver;` to the test file.

- [ ] **Step 7: Run both test files to verify everything passes**

Run: `cd backend && ./mvnw -o test -Dtest=BuildVersionResolverTest,AdminDiagnosticsServiceTest`
Expected: PASS, all tests in both classes.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/config/BuildVersionResolver.java \
        backend/src/test/java/com/finora/config/BuildVersionResolverTest.java \
        backend/src/main/java/com/finora/service/AdminDiagnosticsService.java \
        backend/src/test/java/com/finora/service/AdminDiagnosticsServiceTest.java
git commit -m "refactor(diagnostics): extract BuildVersionResolver from AdminDiagnosticsService

Pure extraction, no behavior change -- AdminDiagnosticsService's commit-resolution logic (real git
metadata, falling back to a configured RAILWAY_GIT_COMMIT_SHA/GIT_COMMIT value, since the
production Docker image has no .git directory to read at build time) moves to its own component
so ImportSessionService can reuse the exact same answer to 'what build is this?' for
parser-version-aware session invalidation in the next commit, rather than reimplementing it."
```

---

## Task 2: Add the `parser_version` column

**Files:**
- Create: `backend/src/main/resources/db/migration/V122__import_session_parser_version.sql`
  (re-confirm V122 is still free immediately before this step — see Global Constraints)
- Modify: `backend/src/main/java/com/finora/entity/ImportSession.java`

**Interfaces:**
- Produces: `ImportSession.getParserVersion(): String`, `ImportSession.setParserVersion(String)`.

- [ ] **Step 1: Re-confirm the migration version is free**

Run: `git fetch origin --quiet && ls backend/src/main/resources/db/migration | sort -V | tail -3`
Expected: `V121__...` is still the latest. If a newer one exists, use the next free number instead
of V122 throughout this task.

- [ ] **Step 2: Create the migration**

Create `backend/src/main/resources/db/migration/V122__import_session_parser_version.sql`:

```sql
-- Phase 1B of the import-pipeline session-freshness fix (see ImportSessionService's own doc
-- comment on findLiveSessionByContentHash). Phase 1A bounded automatic session replay to a short
-- time window as a heuristic for "this is plausibly the same upload attempt, not a genuinely
-- later re-upload" -- this column lets the actual question be asked directly: was this session
-- staged by the exact same backend build that's now deciding whether to replay it? A session
-- staged before this column existed has NULL here, which the application-level comparison in
-- ImportSessionService.findLiveSessionByContentHash treats as "definitely a different version
-- from whatever is running now" -- correct, since nothing before this feature shipped ever
-- recorded what it was built from, so replaying it automatically would be exactly the bug this
-- whole fix exists to close.
--
-- Nullable, no backfill, no default -- every existing row simply has no answer to a question that
-- didn't exist when it was written, the same posture V108's source_domain and V113's
-- credit_card_summary_json columns already take on this table.
ALTER TABLE import_sessions ADD COLUMN parser_version VARCHAR(40);
```

- [ ] **Step 3: Add the entity field**

In `ImportSession.java`, near the `source`/`sourceDomain` fields (mirror their exact style):

```java
    /** The short commit id (BuildVersionResolver.currentCommit()) of the backend build that
     *  staged this session -- null for any session staged before this column existed. Read by
     *  ImportSessionService.findLiveSessionByContentHash to decide whether a session is safe to
     *  replay automatically: a mismatch against the CURRENT build's commit means the parser may
     *  have changed since this session was staged, regardless of how little time has passed. */
    @Column(name = "parser_version", length = 40)
    private String parserVersion;
```

Add the getter/setter next to `getSource`/`setSource`:

```java
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
```

- [ ] **Step 4: Run the backend module to confirm it still compiles and existing tests pass**

Run: `cd backend && ./mvnw -o -q test-compile`
Expected: compiles cleanly (this task adds no new tests of its own — the field is exercised by
Task 3's tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V122__import_session_parser_version.sql \
        backend/src/main/java/com/finora/entity/ImportSession.java
git commit -m "feat(imports): add ImportSession.parserVersion column (V122)

Nullable, no backfill -- see the migration's own comment for why NULL is the correct value for
every pre-existing row. Wired into ImportSessionService's create/lookup paths in the next commit."
```

---

## Task 3: Stamp and compare `parserVersion` in `ImportSessionService`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportSessionService.java`
- Modify: `backend/src/test/java/com/finora/imports/ImportSessionServiceTest.java`

**Interfaces:**
- Consumes: `BuildVersionResolver.currentCommit(): String` (Task 1).
- Produces: `findLiveSessionByContentHash(UUID, String): Optional<ImportSession>` — same
  signature, refined behavior.

- [ ] **Step 1: Write the failing tests**

In `ImportSessionServiceTest.java`, add `import com.finora.config.BuildVersionResolver;` and a
`BuildVersionResolver buildVersionResolver;` field, constructed in `setUp()`:

```java
    private BuildVersionResolver buildVersionResolver;

    @BeforeEach
    void setUp() {
        importSessionRepository = mock(ImportSessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        buildVersionResolver = mock(BuildVersionResolver.class);
        // Every EXISTING test in this class (including all the Phase 1A dedup-window tests) was
        // written against a world with no version concept at all -- defaulting to null here makes
        // them exercise the fallback-window path Task 3 preserves for that case, unchanged, rather
        // than silently starting to exercise the new version-comparison path instead.
        when(buildVersionResolver.currentCommit()).thenReturn(null);
        service = new ImportSessionService(importSessionRepository, objectMapper, buildVersionResolver);
        when(importSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }
```

Add new tests, near the existing `findLiveSessionByContentHash_*` tests:

```java
    /**
     * The actual fix this task exists for: Phase 1A's 5-minute window still let a session created
     * moments before a parser fix deploys get replayed for the rest of that window. Version
     * comparison has no such gap -- a mismatch is a mismatch regardless of age.
     */
    @Test
    void findLiveSessionByContentHash_deletesAVersionMismatchedMatch_evenIfCreatedSecondsAgo() {
        when(buildVersionResolver.currentCommit()).thenReturn("newcommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(session, "createdAt", Instant.now());
        session.setParserVersion("oldcommit");
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-f", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-f");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(session);
    }

    /**
     * A session staged before this feature shipped has parserVersion == null, which must never
     * equal a real resolved commit -- otherwise every pre-existing staged session would look like
     * it matches the current build by coincidence of both being "unset".
     */
    @Test
    void findLiveSessionByContentHash_treatsANullStoredVersion_asAMismatch() {
        when(buildVersionResolver.currentCommit()).thenReturn("newcommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(session, "createdAt", Instant.now());
        // parserVersion left null -- the default for a session built via sessionOwnedBy.
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-g", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-g");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(session);
    }

    /**
     * The other half: when the version DOES match, the session replays even if it's old (though
     * still unexpired) -- proving version comparison, not age, is now the real freshness signal
     * whenever a version can be resolved at all.
     */
    @Test
    void findLiveSessionByContentHash_returnsAVersionMatchedMatch_evenIfCreatedDaysAgo() {
        when(buildVersionResolver.currentCommit()).thenReturn("samecommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                session, "createdAt", Instant.now().minus(java.time.Duration.ofHours(40)));
        session.setParserVersion("samecommit");
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-h", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-h");

        assertThat(found).contains(session);
        verify(importSessionRepository, never()).delete(any());
    }

    /** createSession stamps the resolved build version onto every newly-staged session. */
    @Test
    void createSession_stampsTheCurrentParserVersion() {
        when(buildVersionResolver.currentCommit()).thenReturn("stampedcommit");

        ImportSession created = service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getParserVersion()).isEqualTo("stampedcommit");
    }

    /** createMultiSection stamps it too -- a separate code path, not covered by the assertion above. */
    @Test
    void createMultiSection_stampsTheCurrentParserVersion() {
        when(buildVersionResolver.currentCommit()).thenReturn("stampedcommit");
        var section = new com.finora.dto.ImportDto.StagedAccountSection(
                sampleDetected(), List.of(sampleRow()), 1, 0, List.of());

        ImportSession created = service.createMultiSection(userId, "composite.pdf",
                new byte[]{1, 2, 3}, List.of(section));

        assertThat(created.getParserVersion()).isEqualTo("stampedcommit");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest`
Expected: compile failure (constructor doesn't accept a third argument yet, `setParserVersion`/
`getParserVersion` already exist from Task 2 so those two calls compile fine once Task 2 is done —
this task can't compile at all until the constructor is updated in Step 3 below, so "run to see it
fail" here means confirming the compile error names the constructor mismatch, not a runtime
assertion failure).

- [ ] **Step 3: Wire `BuildVersionResolver` into the constructor and stamping**

In `ImportSessionService.java`: add the field and constructor parameter, add `import
com.finora.config.BuildVersionResolver;`:

```java
    private final ImportSessionRepository importSessionRepository;
    private final ObjectMapper objectMapper;
    private final BuildVersionResolver buildVersionResolver;

    public ImportSessionService(ImportSessionRepository importSessionRepository, ObjectMapper objectMapper,
                                 BuildVersionResolver buildVersionResolver) {
        this.importSessionRepository = importSessionRepository;
        this.objectMapper = objectMapper;
        this.buildVersionResolver = buildVersionResolver;
    }
```

In the private full-arg `createSession` overload, right after `session.setExpiresAt(...)`:

```java
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session.setParserVersion(buildVersionResolver.currentCommit());
        applyDocumentContext(session, documentContext);
```

In the full-arg `createMultiSection` overload, same placement:

```java
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session.setParserVersion(buildVersionResolver.currentCommit());
        applyDocumentContext(session, documentContext);
```

- [ ] **Step 4: Rewrite `findLiveSessionByContentHash`**

```java
    @Transactional
    public Optional<ImportSession> findLiveSessionByContentHash(UUID userId, String contentHash) {
        Optional<ImportSession> match = importSessionRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                        userId, contentHash, ImportSession.STATUS_STAGED);
        if (match.isEmpty()) return Optional.empty();

        ImportSession session = match.get();
        boolean expired = session.getExpiresAt().isBefore(Instant.now());

        String currentVersion = buildVersionResolver.currentCommit();
        boolean stale;
        if (currentVersion != null) {
            // The real mechanism: a session is safe to replay only if it was staged under the
            // exact same build now asking to replay it. A null stored version (a session staged
            // before this column existed) can never equal a real resolved commit, so it's
            // correctly treated as a mismatch, not a coincidental match of two unset values.
            stale = !currentVersion.equals(session.getParserVersion());
        } else {
            // No version can be determined at all -- local dev without GIT_COMMIT/
            // RAILWAY_GIT_COMMIT_SHA set, or this test suite. Version comparison can't safely
            // distinguish stale from fresh here, so this falls back to the short trust window
            // Phase 1A introduced, rather than either always trusting (the original 48h bug) or
            // never trusting (breaking double-click/retry protection in every such environment).
            stale = session.getCreatedAt().isBefore(Instant.now().minus(DUPLICATE_UPLOAD_DEDUP_WINDOW));
        }

        if (!expired && !stale) {
            return Optional.of(session);
        }
        importSessionRepository.delete(session);
        return Optional.empty();
    }
```

- [ ] **Step 5: Fix every other test-file constructor call in this class**

`ImportSessionServiceTest.java`'s `setUp()` was already updated in Step 1 above. Confirm no other
file directly constructs `new ImportSessionService(...)`:

Run: `grep -rn "new ImportSessionService(" backend/src`
Expected: only `ImportSessionServiceTest.java`. If any other file appears, update its constructor
call the same way (inject a `BuildVersionResolver` mock or the real `@Component`).

- [ ] **Step 6: Run the full test class to verify everything passes**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest`
Expected: PASS, all tests including the 5 new ones and every pre-existing Phase 1A test (which
now exercises the fallback-window branch, since `buildVersionResolver.currentCommit()` defaults
to null in `setUp()`).

- [ ] **Step 7: Run the broader import-session-touching suite**

Run: `cd backend && ./mvnw -o test -Dtest=ImportSessionServiceTest,ImportServiceSessionTest,AdminDiagnosticsServiceTest,BuildVersionResolverTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportSessionService.java \
        backend/src/test/java/com/finora/imports/ImportSessionServiceTest.java
git commit -m "fix(imports): make staged-session replay version-aware, not just time-bounded

Phase 1A (PR #667) bounded automatic session replay to a 5-minute trust window -- a real
improvement over the original 48-hour bug, but still a heuristic with a residual gap: a session
staged seconds before a parser fix deploys could still be replayed for the rest of that window.

This closes it properly. Every staged session now records the backend build (short commit id)
that produced it; a re-upload is only replayed automatically when that matches the CURRENT
build's commit, regardless of how much or little time has passed. A mismatch -- including a
session staged before this column existed, which has no recorded version -- forces a fresh parse.
The 5-minute window from Phase 1A is kept as the fallback for the one case version comparison
can't resolve: an environment with no git metadata and no RAILWAY_GIT_COMMIT_SHA/GIT_COMMIT set."
```

---

## Self-check before opening the PR

- [ ] Run the full backend suite once: `cd backend && ./mvnw -o test`
- [ ] `git status --short` clean except the three commits above.
- [ ] Bug-and-gap self-review of the full diff before committing each task, per standing
      instruction — re-read as a reviewer, not as the author, looking specifically for: any other
      call site that constructs `AdminDiagnosticsService` or `ImportSessionService` directly and
      would silently break; whether `parserVersion` needs to appear anywhere `sourceDomain`/
      `source` already do (e.g. `rebuildStagingResponse`, session JSON serialization — it should
      NOT, since it's a plain entity column read only inside `findLiveSessionByContentHash`, not
      part of the staged-rows/detected-account JSON payload the frontend ever sees); and whether
      the multi-account creation path (`createMultiSection`) was updated everywhere `createSession`
      was, not just its most-delegated overload.
