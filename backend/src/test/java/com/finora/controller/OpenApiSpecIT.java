package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the running application for its OpenAPI document.
 *
 * springdoc is the one dependency here that Boot's BOM does not manage, so its version has to be
 * moved by hand whenever the parent's Spring Framework minor moves, and nothing fails loudly when
 * someone forgets. The Java 21 to 25 upgrade is the worked example: raising Boot 3.3.2 to 3.5.16
 * (Spring 6.1 to 6.2) left springdoc 2.6.0 in place, and every request to /v3/api-docs started
 * returning 500 with
 *
 *   NoSuchMethodError: 'void org.springframework.web.method.ControllerAdviceBean.&lt;init&gt;(...)'
 *
 * while all 1645 other tests stayed green. A linkage error on one endpoint is invisible to a suite
 * that never calls that endpoint, and Swagger is dev-only, so the first person to notice would have
 * been a developer opening the page some days later and assuming they had broken it themselves.
 *
 * Asserting on the parsed content rather than just the status because a 200 carrying an error
 * document would also pass a status-only check.
 */
class OpenApiSpecIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void apiDocsEndpointServesTheGeneratedSpec() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .isNotNull()
                .contains("\"openapi\"")
                .contains("Finora API");
    }

    @Test
    void specDescribesTheVersionedApiSurface() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        // A spec that generated but picked up no controllers is still valid JSON and still 200.
        // The floor is deliberately low: this guards "the generator ran and found the API", not a
        // route count that any new endpoint would churn.
        assertThat(response.getBody()).contains("\"/api/v1");
    }
}
