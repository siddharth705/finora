package com.finora.config;

import com.finora.support.ClientIdentity;
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
                .as("headers are added one at a time, never a wildcard -- '*' with allowCredentials "
                        + "is both rejected by browsers and the wrong instinct here")
                .doesNotContain("*");
        // Named rather than counted. This used to assert hasSize(3), which failed the moment the
        // client-identity pair was added and said only that the number had changed -- it could not
        // say whether something reasonable had been allowed or the policy had been thrown open.
        // Listing the set means widening it requires writing the new header down here, which is the
        // guard this test was actually for.
        assertThat(config.getAllowedHeaders())
                .as("every header the browser may send, enumerated")
                .containsExactlyInAnyOrder(
                        "Authorization",
                        "Content-Type",
                        CorrelationIdFilter.HEADER_NAME,
                        // Advisory client metadata for support tickets and feedback; nothing
                        // authorises on either. See ClientIdentity.
                        ClientIdentity.PLATFORM_HEADER,
                        ClientIdentity.VERSION_HEADER);
        assertThat(config.getAllowCredentials()).isTrue();
    }
}
