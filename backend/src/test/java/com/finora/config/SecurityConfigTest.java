package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API docs must not be anonymously reachable in production.
 *
 * <p>They were {@code permitAll()} in every profile, and the only thing keeping them off the public
 * internet was {@code application-prod.yml} turning springdoc off. Two layers that had to agree,
 * with one of them not actually deciding anything -- and the prod config's own comment invites
 * exactly the edit that breaks the arrangement ("Re-enable deliberately... behind a separate
 * auth-gated route"). Flipping that boolean would have published every route, parameter and DTO
 * field of a financial API to anonymous callers, because the authorization rule nobody re-read
 * already said permitAll.
 *
 * <p>Asserted here rather than through a running context on purpose: booting the prod profile means
 * satisfying {@link ProductionConfigValidator}, which needs real secrets and a configured Firebase
 * credential before it will let the context refresh. {@code OpenApiSpecIT} already covers the other
 * half against a real server -- that the docs ARE reachable outside prod, which is what makes them
 * useful in development.
 */
class SecurityConfigTest {

    @Test
    void apiDocsAreNotPubliclyReachableUnderTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThat(SecurityConfig.apiDocsPubliclyReachable(prod))
                .as("prod must not permitAll the Swagger endpoints -- if springdoc is ever "
                        + "re-enabled there, the docs have to require authentication rather than "
                        + "being served to anonymous callers")
                .isFalse();
    }

    @Test
    void apiDocsStayPubliclyReachableInDev() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        assertThat(SecurityConfig.apiDocsPubliclyReachable(dev)).isTrue();
    }

    @Test
    void apiDocsStayPubliclyReachableInTest() {
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");

        assertThat(SecurityConfig.apiDocsPubliclyReachable(test)).isTrue();
    }

    /** A deployment running "prod" alongside another profile is still production. Matching on the
     *  active-profile SET rather than on "is prod the only profile" is what makes that hold. */
    @Test
    void prodCombinedWithAnotherProfileIsStillClosed() {
        MockEnvironment prodPlus = new MockEnvironment();
        prodPlus.setActiveProfiles("prod", "metrics");

        assertThat(SecurityConfig.apiDocsPubliclyReachable(prodPlus)).isFalse();
    }

    /** No active profile at all means Spring falls back to "default", which is a developer running
     *  the app without configuration -- not production. */
    @Test
    void noActiveProfileIsTreatedAsNonProd() {
        Environment none = new MockEnvironment();

        assertThat(SecurityConfig.apiDocsPubliclyReachable(none)).isTrue();
    }
}
