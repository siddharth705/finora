package com.finora.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for a defense-in-depth gap found during review: the client-supplied
 * X-Request-Id header was trusted completely unvalidated into MDC (and therefore every log
 * line) and echoed back into the response header, with no length or character-class check --
 * a log/header injection primitive.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void reusesAWellFormedClientSuppliedId() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("abc-123.def_456");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(CorrelationIdFilter.HEADER_NAME, "abc-123.def_456");
        verify(chain).doFilter(request, response);
    }

    @Test
    void generatesAFreshIdWhenTheHeaderIsMissing() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(CorrelationIdFilter.HEADER_NAME), argThat(v -> v != null && !v.isBlank()));
    }

    @Test
    void rejectsANewlineContainingHeader_generatingAFreshIdInstead() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("abc\ninjected-log-line");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(CorrelationIdFilter.HEADER_NAME, "abc\ninjected-log-line");
        verify(response).setHeader(eq(CorrelationIdFilter.HEADER_NAME), argThat(v -> !v.contains("\n")));
    }

    @Test
    void rejectsAnExcessivelyLongHeader_generatingAFreshIdInstead() throws Exception {
        String tooLong = "a".repeat(200);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn(tooLong);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(CorrelationIdFilter.HEADER_NAME, tooLong);
    }

    @Test
    void clearsMdcEvenWhenTheDownstreamFilterChainThrows() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("abc-123");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("downstream failure")).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, chain);
        } catch (RuntimeException expected) {
            // expected -- we only care that MDC was cleaned up in the finally block
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
