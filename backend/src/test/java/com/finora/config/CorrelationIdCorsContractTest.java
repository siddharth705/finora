package com.finora.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-036. The correlation-id header has to be usable from a browser, in both directions.
 *
 * <p>{@code CorrelationIdFilter} reuses the client's {@code X-Request-Id} when one is supplied and
 * echoes it back on every response. CORS listed it in neither {@code allowedHeaders} nor
 * {@code exposedHeaders}, so cross-origin a request carrying it fails preflight and the response
 * header is invisible to JavaScript. The filter advertised a capability the transport refused.
 *
 * <p>Asserted against the header constant rather than the string, so renaming the header cannot
 * leave the CORS policy pointing at the old name while this test still passes.
 */
class CorrelationIdCorsContractTest {

    private CorsConfiguration configuration() {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", "https://app.finoratech.info");
        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) corsConfig.corsConfigurationSource();
        return source.getCorsConfigurations().get("/**");
    }

    @Test
    @DisplayName("BH-036: the correlation-id header may be sent and may be read")
    void theCorrelationHeaderIsBothAllowedAndExposed() {
        CorsConfiguration config = configuration();

        assertThat(config.getAllowedHeaders())
                .as("a cross-origin request carrying %s must survive preflight", CorrelationIdFilter.HEADER_NAME)
                .contains(CorrelationIdFilter.HEADER_NAME);
        assertThat(config.getExposedHeaders())
                .as("and JavaScript must be able to read it back, or a client-side error report "
                        + "cannot be correlated with a server log")
                .contains(CorrelationIdFilter.HEADER_NAME);
    }

    @Test
    @DisplayName("NEGATIVE: the policy did not become permissive")
    void nothingElseWasOpenedUp() {
        CorsConfiguration config = configuration();

        assertThat(config.getAllowedHeaders())
                .as("one header was added, not a wildcard -- '*' with allowCredentials is both "
                        + "rejected by browsers and the wrong instinct here")
                .doesNotContain("*")
                .hasSize(3);
        assertThat(config.getAllowCredentials()).isTrue();
    }
}
