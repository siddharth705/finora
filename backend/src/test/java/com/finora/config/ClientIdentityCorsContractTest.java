package com.finora.config;

import com.finora.support.ClientIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client-identity headers have to survive preflight, or nothing works.
 *
 * <p>This is the case {@code CorrelationIdCorsContractTest} and BH-036 predicted: {@code
 * allowedHeaders} is an explicit allowlist, and the moment a client actually starts sending a
 * custom header that is not on it, the browser refuses the request at preflight.
 *
 * <p>The blast radius is what makes this worth its own test rather than a line in a service test.
 * The web client attaches these headers in its axios request interceptor, so they ride on <b>every
 * request the app makes</b>, not just the two endpoints that store the values. This app is
 * deployed cross-origin — static frontend on Cloudflare, backend on Railway — so every one of
 * those is preflighted. Omitting either header here would not have produced null metadata on
 * support tickets; it would have failed every authenticated call in the product, from a change
 * whose diff looks like it only touches support.
 *
 * <p>Asserted against the constants rather than the literal strings, so renaming a header cannot
 * leave the CORS policy pointing at the old name while this test still passes.
 */
class ClientIdentityCorsContractTest {

    private CorsConfiguration configuration() {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", "https://app.finoratech.info");
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) corsConfig.corsConfigurationSource();
        return source.getCorsConfigurations().get("/**");
    }

    @Test
    @DisplayName("both client-identity headers may be sent cross-origin")
    void theClientIdentityHeadersSurvivePreflight() {
        CorsConfiguration config = configuration();

        assertThat(config.getAllowedHeaders())
                .as("every request the web app makes carries %s; omitting it fails preflight on all "
                        + "of them, not only the support endpoints", ClientIdentity.PLATFORM_HEADER)
                .contains(ClientIdentity.PLATFORM_HEADER);
        assertThat(config.getAllowedHeaders())
                .as("%s rides on the same interceptor as the platform header", ClientIdentity.VERSION_HEADER)
                .contains(ClientIdentity.VERSION_HEADER);
    }

    /**
     * Neither is echoed back, so neither belongs in {@code exposedHeaders} — the client already
     * knows what it sent. Pinned so the pair does not get added there by reflex alongside the
     * correlation id, which genuinely does need to be readable.
     */
    @Test
    void theyAreNotExposedBecauseNothingReadsThemBack() {
        CorsConfiguration config = configuration();

        assertThat(config.getExposedHeaders())
                .doesNotContain(ClientIdentity.PLATFORM_HEADER, ClientIdentity.VERSION_HEADER);
    }
}
