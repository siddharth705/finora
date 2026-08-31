package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for a real configuration-robustness bug found during review:
 * allowedOrigins.split(",") without a .trim() on each entry silently breaks CORS for every
 * origin after the first, the moment the operator writes a space after the comma in the
 * app.cors.allowed-origins property -- a very natural way to format a comma-separated list.
 */
class CorsConfigTest {

    private CorsConfiguration configFor(String allowedOrigins) {
        CorsConfig corsConfig = new CorsConfig();
        ReflectionTestUtils.setField(corsConfig, "allowedOrigins", allowedOrigins);
        CorsConfigurationSource source = corsConfig.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest());
    }

    @Test
    void singleOrigin_isRegisteredExactly() {
        CorsConfiguration config = configFor("http://localhost:5173");
        assertThat(config.getAllowedOriginPatterns()).containsExactly("http://localhost:5173");
    }

    @Test
    void multipleOrigins_withASpaceAfterTheComma_areAllRegisteredTrimmed() {
        CorsConfiguration config = configFor("http://localhost:5173, https://app.finora.example");
        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("http://localhost:5173", "https://app.finora.example");
    }

    @Test
    void multipleOrigins_withNoSpaceAfterTheComma_areAllRegistered() {
        CorsConfiguration config = configFor("http://localhost:5173,https://app.finora.example");
        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("http://localhost:5173", "https://app.finora.example");
    }

    @Test
    void aTrailingComma_doesNotProduceABlankOriginEntry() {
        CorsConfiguration config = configFor("http://localhost:5173,https://app.finora.example,");
        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("http://localhost:5173", "https://app.finora.example");
    }

    @Test
    void wildcardSubdomainPattern_isRegisteredForCloudflarePagesPreviews() {
        CorsConfiguration config = configFor("https://*.finora-cng.pages.dev");
        assertThat(config.getAllowedOriginPatterns())
                .containsExactly("https://*.finora-cng.pages.dev");
    }
}
