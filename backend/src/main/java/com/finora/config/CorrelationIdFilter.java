package com.finora.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every incoming request a correlation ID — reused from the client's X-Request-Id
 * header if present (so a frontend or gateway can propagate its own ID), otherwise generated
 * fresh. Runs at HIGHEST_PRECEDENCE so it executes before Spring Security's filter chain,
 * meaning even a 401/403 gets logged with a correlation ID, not just successful requests.
 *
 * The ID goes into MDC (so every log line during this request includes it, via the logging
 * pattern in application.yml), the response header (so a client can report "this is the
 * request that failed" without needing to parse logs), and — via ApiResponse's factory
 * methods reading MDC directly — every API response body, success or error.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // A client-supplied header echoed straight into MDC (hence every log line) and the
    // response header, completely unvalidated, is a log-injection / header-injection primitive:
    // a malicious value containing newlines could forge extra log lines, and Tomcat's own header
    // validation aside, this is defense-in-depth that shouldn't rely solely on the servlet
    // container catching it. Bounded length + a tight character allowlist closes that off while
    // still accepting any reasonable ID a legitimate gateway or frontend would send.
    private static final int MAX_LENGTH = 100;
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");

    private static boolean isWellFormed(String requestId) {
        return requestId != null && !requestId.isBlank()
                && requestId.length() <= MAX_LENGTH && SAFE_ID.matcher(requestId).matches();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_NAME);
        if (!isWellFormed(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // MDC is thread-local and Tomcat reuses worker threads across requests —
            // failing to clear this would leak one request's ID into the next request
            // that happens to land on the same thread.
            MDC.remove(MDC_KEY);
        }
    }
}
