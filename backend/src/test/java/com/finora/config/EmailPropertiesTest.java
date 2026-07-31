package com.finora.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers resolveBaseUrl() directly -- see its own doc comment and AuthServiceEmailTest's mirror
 * tests for the real-world bug this fixes: the user frontend and admin portal are separate apps
 * at separate origins, each with its own /reset-password page, but forgotPassword() used to link
 * every reset email to the single user-frontend base URL regardless of which app asked for it.
 */
class EmailPropertiesTest {

    private EmailProperties propsWith(String userBaseUrl, String adminBaseUrl) {
        EmailProperties props = new EmailProperties();
        props.setAppBaseUrl(userBaseUrl);
        props.setAdminAppBaseUrl(adminBaseUrl);
        return props;
    }

    @Test
    void resolvesToAdminBaseUrl_whenOriginMatchesIt() {
        var props = propsWith("https://finora-cng.pages.dev", "https://finora-admin.pages.dev");
        assertThat(props.resolveBaseUrl("https://finora-admin.pages.dev")).isEqualTo("https://finora-admin.pages.dev");
    }

    @Test
    void resolvesToUserBaseUrl_whenOriginMatchesIt() {
        var props = propsWith("https://finora-cng.pages.dev", "https://finora-admin.pages.dev");
        assertThat(props.resolveBaseUrl("https://finora-cng.pages.dev")).isEqualTo("https://finora-cng.pages.dev");
    }

    @Test
    void isCaseInsensitiveAndIgnoresATrailingSlashOnEitherSide() {
        var props = propsWith("https://finora-cng.pages.dev", "https://finora-admin.pages.dev/");
        assertThat(props.resolveBaseUrl("HTTPS://FINORA-ADMIN.PAGES.DEV")).isEqualTo("https://finora-admin.pages.dev/");
    }

    @Test
    void fallsBackToUserBaseUrl_whenOriginIsNull() {
        var props = propsWith("https://finora-cng.pages.dev", "https://finora-admin.pages.dev");
        assertThat(props.resolveBaseUrl(null)).isEqualTo("https://finora-cng.pages.dev");
    }

    @Test
    void fallsBackToUserBaseUrl_whenAdminBaseUrlIsUnconfigured() {
        var props = propsWith("https://finora-cng.pages.dev", null);
        assertThat(props.resolveBaseUrl("https://finora-admin.pages.dev")).isEqualTo("https://finora-cng.pages.dev");
    }

    @Test
    void fallsBackToUserBaseUrl_whenOriginMatchesNeitherConfiguredValue() {
        var props = propsWith("https://finora-cng.pages.dev", "https://finora-admin.pages.dev");
        assertThat(props.resolveBaseUrl("https://some-other-site.example")).isEqualTo("https://finora-cng.pages.dev");
    }
}
