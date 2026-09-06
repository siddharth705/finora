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
