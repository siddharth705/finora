package com.finora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate-limits the handful of endpoints with a real, specific abuse cost: login (credential
 * stuffing — complements account lockout, which is per-account, by also limiting per-IP across
 * many accounts), register (spam account creation), and forgot-password (email enumeration /
 * spam) are all reachable with no credential at all. The OTP-sending endpoints and CSV import
 * staging DO require a valid JWT to call, but are limited anyway because each has a real
 * per-call resource cost even from a legitimate, authenticated client gone wrong (SMS costs
 * actual money per message; import staging persists a real row with the raw file bytes as of
 * ADR-0002, not just an in-memory response — see importStageLimiter's own comment). Everything
 * else is intentionally left unlimited here — blanket rate limiting every endpoint is a
 * different, heavier decision (needs per-endpoint tuning) than protecting specifically the
 * endpoints with a concrete cost.
 *
 * IP extraction uses request.getRemoteAddr() directly — this assumes the app isn't (yet) behind
 * a reverse proxy that would require trusting X-Forwarded-For instead. Add that parsing when an
 * Nginx/ALB actually sits in front of this in production; trusting X-Forwarded-For today, with
 * no proxy configured, would let a client simply set that header to bypass the entire limiter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after CorrelationIdFilter, before Spring Security
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter loginLimiter = new RateLimiter(10, 60);           // 10 attempts / min / IP
    private final RateLimiter registerLimiter = new RateLimiter(5, 300);        // 5 registrations / 5 min / IP
    private final RateLimiter forgotPasswordLimiter = new RateLimiter(5, 300);  // 5 requests / 5 min / IP
    private final RateLimiter sendOtpLimiter = new RateLimiter(3, 600);         // 3 SMS / 10 min / IP — SMS costs real money per message
    // Staging used to be memory-only (parse, return the response, nothing persisted) -- as of
    // ADR-0002 (persisted import sessions), every call writes a real row to import_sessions
    // INCLUDING the raw file bytes, bounded only by a 48h TTL and cleanup that only runs on that
    // same user's own next stage() call. Unprotected, this endpoint became a way to grow that
    // table indefinitely within the TTL window just by hammering it in a loop -- 10/10min is
    // generous for legitimate use (re-staging after fixing a file, trying a few statements) while
    // still bounding that.
    private final RateLimiter importStageLimiter = new RateLimiter(10, 600);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();

        RateLimiter limiter = switch (path) {
            case "/api/v1/auth/login" -> loginLimiter;
            case "/api/v1/auth/register" -> registerLimiter;
            case "/api/v1/auth/forgot-password" -> forgotPasswordLimiter;
            // Both of these trigger an actual SMS send via OtpService.issueOtp() -- same real
            // per-message cost, same limiter. Missing this on the password-reset endpoint when
            // it was added would have left it as the one unprotected way to spam SMS to an
            // arbitrary phone number (given a valid reset token, which itself doesn't require
            // knowing anything about the account beyond having received one email).
            case "/api/v1/phone/send-otp", "/api/v1/auth/reset-password/request-otp" -> sendOtpLimiter;
            case "/api/v1/import/csv/stage", "/api/v1/import/pdf/stage" -> importStageLimiter;
            default -> null;
        };

        if (limiter != null && !limiter.allow(ip)) {
            response.setStatus(429); // Too Many Requests
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiResponse<Void> body = ApiResponse.error(
                    "Too many requests. Please wait before trying again.", "RATE_LIMITED");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
