package com.finora.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the production-readiness fix to handleGeneric(): the raw exception message used to be
 * included in every 500 response regardless of profile -- not a full stack trace, but still a
 * real information-disclosure risk for a financial API (a SQL exception's message can carry
 * table/column/constraint names, for instance). Now only withheld specifically in the prod
 * profile, with the full exception always logged server-side either way (not asserted here --
 * that's a side effect on a real logger, not this method's return value).
 */
class GlobalExceptionHandlerTest {

    @Test
    void handleGeneric_inProdProfile_withholdsTheRawExceptionMessage() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment);

        var response = handler.handleGeneric(new RuntimeException("column \"ssn\" violates not-null constraint"));

        assertThat(response.getBody().message()).isEqualTo("Unexpected error");
        assertThat(response.getBody().message()).doesNotContain("ssn", "constraint");
    }

    @Test
    void handleGeneric_outsideProdProfile_includesTheRawMessageForDeveloperConvenience() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment);

        var response = handler.handleGeneric(new RuntimeException("boom"));

        assertThat(response.getBody().message()).isEqualTo("Unexpected error: boom");
    }

    @Test
    void handleGeneric_withNoActiveProfilesAtAll_defaultsToIncludingTheMessage() {
        // e.g. a plain unit/integration test context with no profile set -- must not accidentally
        // behave as if it were prod just because the profiles array is empty.
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment);

        var response = handler.handleGeneric(new RuntimeException("boom"));

        assertThat(response.getBody().message()).isEqualTo("Unexpected error: boom");
    }

    @Test
    void handleGeneric_alwaysReturns500() {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        GlobalExceptionHandler handler = new GlobalExceptionHandler(environment);

        var response = handler.handleGeneric(new RuntimeException("boom"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
    }
}
