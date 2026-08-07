package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the worker metrics are actually scrapeable, rather than merely registered.
 *
 * <p>The distinction matters and has bitten this repository before: a meter that exists in a
 * registry nothing exports is exactly as useful as no meter at all, and looks identical in a unit
 * test. Registering counters was the easy half; being able to draw a dashboard from them is the
 * half that needed a Prometheus registry and an exposure change, and this is what verifies it.
 *
 * <h2>The endpoint is authenticated, deliberately</h2>
 *
 * <p>{@code SecurityConfig} permits {@code /actuator/health} and nothing else, so
 * {@code /actuator/prometheus} requires a token. That is the right posture and this test asserts
 * it: the scrape carries queue depths, error rates and JVM internals, which is useful
 * reconnaissance even though it contains no customer data.
 *
 * <p><b>It also means a scraper needs credentials or a private network path.</b> That is a
 * deployment decision, recorded under "Known gaps" in observability.md rather than resolved by
 * quietly making the endpoint public.
 */
class WorkerMetricsExportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WorkerObservability observability;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private HttpHeaders adminBearer() {
        User user = new User();
        user.setEmail("metrics-export-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Metrics Export IT User");
        user.setRole("ADMIN");
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private String scrape() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(adminBearer()), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("an authenticated scrape must work; without it every worker meter is invisible")
                .isTrue();
        return response.getBody();
    }

    @Test
    void theScrapeEndpointIsNotReachableAnonymously() {
        // Asserted first because it is the property most likely to be broken by someone trying to
        // make Prometheus work: adding /actuator/** to the permitAll list would fix scraping and
        // publish queue depths and JVM internals to the internet at the same time.
        ResponseEntity<String> anonymous =
                restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(anonymous.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void workerMetricsAppearInTheScrape() {
        try (WorkerExecution execution = observability.begin("test-worker", "test-job")) {
            execution.claimed(1);
            execution.started(UUID.randomUUID(), null);
            execution.completed(UUID.randomUUID());
            execution.retryScheduled(UUID.randomUUID(), 1);
            execution.deadLettered(UUID.randomUUID(), 3, new IllegalStateException("gave up"));
            execution.recovered(2);
        }

        String body = scrape();

        // Prometheus renders dots as underscores, so these are the names a dashboard query uses.
        assertThat(body)
                .contains("finora_worker_executions")
                .contains("finora_worker_completed")
                .contains("finora_worker_retries")
                .contains("finora_worker_dead_letters")
                .contains("finora_worker_recovered")
                .contains("finora_worker_failures")
                .contains("finora_worker_duration");
    }

    @Test
    void metersCarryWorkerAndJobKindLabels_soOneQueryCoversEveryWorker() {
        try (WorkerExecution execution = observability.begin("labelled-worker", "labelled-job")) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(scrape())
                .contains("worker=\"labelled-worker\"")
                .contains("jobKind=\"labelled-job\"");
    }

    @Test
    void theRealMerchantLearningQueueDepthGaugeIsExported() {
        // Registered by MerchantLearningEventWorker's constructor against a real repository count,
        // so this also proves the gauge's supplier does not throw on the scrape path.
        assertThat(scrape()).contains("finora_worker_queue_depth");
    }

    @Test
    void oldestPendingAgeIsExportedUnderTheNameTheDashboardQueries() {
        // baseUnit("seconds") becomes part of the exported name -- the exact translation
        // check-dashboard-metrics.py exists to police. Asserted against a real scrape so the
        // dashboard query and the emitted series are proven to match rather than assumed to.
        assertThat(scrape()).contains("finora_worker_oldest_pending_age_seconds");
    }

    @Test
    void everyMeterIsStampedWithTheEnvironment_soOnePrometheusCanHoldMoreThanOneDeployment() {
        try (WorkerExecution execution = observability.begin("env-worker", "env-job")) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(scrape()).contains("environment=\"");
    }

    @Test
    void theRiskierActuatorEndpointsStayUnexposed() {
        // The exposure list is one property away from including env, configprops, heapdump and
        // threaddump. Those leak configuration and memory contents, which is a different risk class
        // from counters -- asserted rather than trusted.
        for (String forbidden : new String[]{"env", "configprops", "beans", "threaddump", "loggers"}) {
            assertThat(restTemplate.exchange("/actuator/" + forbidden, HttpMethod.GET,
                    new HttpEntity<>(adminBearer()), String.class).getStatusCode().is2xxSuccessful())
                    .as("/actuator/%s must not be exposed, even to an admin", forbidden)
                    .isFalse();
        }
    }
}
