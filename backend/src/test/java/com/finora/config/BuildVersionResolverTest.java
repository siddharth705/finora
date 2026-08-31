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
