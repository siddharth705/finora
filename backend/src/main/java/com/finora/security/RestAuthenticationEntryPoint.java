package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Without this, Spring Security's default entry point (Http403ForbiddenEntryPoint) answers every
 * unauthenticated request — including a genuinely missing, expired, or invalid JWT, not just a
 * real authorization failure — with 403. That collapses two different problems into one status
 * code: "you're not signed in" and "you're signed in but not allowed to do this."
 *
 * The frontend's axios interceptor (client.ts) relies on that distinction to know when to attempt
 * a silent token refresh: it only does so on 401. With the default 403-for-everything behavior,
 * an expired access token never triggers a refresh — every subsequent request just fails forever
 * (visible as a wall of 403s on every protected endpoint) until the user manually logs out and
 * back in, which is exactly what was happening before this fix.
 *
 * This restores the standard split: 401 for "not authenticated" (handled here), 403 for
 * "authenticated but forbidden" — still thrown as ApiException(FORBIDDEN, ...) from service-layer
 * ownership checks like AccountService.getOwned(), which go through GlobalExceptionHandler and
 * are entirely unaffected by this class, since those requests are authenticated by the time they
 * reach the service layer at all.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error("Authentication required — please sign in again.", "UNAUTHORIZED")));
    }
}
