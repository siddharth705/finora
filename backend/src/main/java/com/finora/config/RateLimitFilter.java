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
 * spam) are all reachable with no credential at all. CSV import staging DOES require a valid JWT
 * to call, but is limited anyway because it has a real per-call resource cost even from a
 * legitimate, authenticated client gone wrong (persists a real row with the raw file bytes as of
 * ADR-0002, not just an in-memory response — see importStageLimiter's own comment). Everything
 * else is intentionally left unlimited here — blanket rate limiting every endpoint is a
 * different, heavier decision (needs per-endpoint tuning) than protecting specifically the
 * endpoints with a concrete cost.
 *
 * Architecture change: phone verification (registration, password reset, authenticated password
 * change) moved to Firebase Phone Authentication -- this backend no longer triggers any SMS send
 * itself (see FirebaseConfig's own doc comment), so the OTP-sending rate limiter this class used
 * to have (justified specifically by "SMS costs real money per message") no longer applies to
 * anything here. Firebase's own reCAPTCHA-based SMS fraud protection covers that concern now, on
 * Firebase's side of the boundary. /phone/verify and /auth/reset-password/phone are both cheap,
 * no-real-cost reads/checks (a Firebase Admin SDK token verification; a DB lookup gated by an
 * unguessable reset token) -- left unlimited here for the same reason every other low-cost
 * endpoint in this app is.
 *
 * The authenticated Change Password flow (/users/me/password-change/start|verify-otp|complete)
 * IS limited, unlike the two paragraphs above -- unlike /phone/verify, start() does a real bcrypt
 * comparison against the account's current password on every call, which is exactly the kind of
 * per-call cost this class already protects elsewhere (see importStageLimiter). A JWT stolen via
 * XSS or a compromised device is the realistic threat this defends against: without a limiter, an
 * attacker holding a stolen-but-still-valid token could hammer this flow long after the
 * legitimate user would ever call it 15 times in 10 minutes themselves.
 *
 * IP extraction uses request.getRemoteAddr() directly by default — correct when nothing sits
 * between the client and this app, wrong the moment a reverse proxy (Railway's own edge proxy,
 * an ALB, Nginx, ...) actually does, since every request would then arrive from THAT proxy's own
 * IP regardless of who the real client is, collapsing every user onto one shared rate-limit
 * bucket. Reads the real client IP from X-Forwarded-For instead specifically when
 * app.security.trust-proxy-headers is enabled (TRUST_PROXY_HEADERS=true) -- never unconditionally,
 * since blindly trusting a client-supplied header with no proxy actually in front to have set it
 * would let any caller spoof any IP and bypass this filter entirely. See application.yml's own
 * comment on that property for when to turn it on.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // right after CorrelationIdFilter, before Spring Security
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter loginLimiter = new RateLimiter(10, 60);           // 10 attempts / min / IP
    private final RateLimiter registerLimiter = new RateLimiter(5, 300);        // 5 registrations / 5 min / IP
    private final RateLimiter forgotPasswordLimiter = new RateLimiter(5, 300);  // 5 requests / 5 min / IP
    // Staging used to be memory-only (parse, return the response, nothing persisted) -- as of
    // ADR-0002 (persisted import sessions), every call writes a real row to import_sessions
    // INCLUDING the raw file bytes, bounded only by a 48h TTL and cleanup that only runs on that
    // same user's own next stage() call. Unprotected, this endpoint became a way to grow that
    // table indefinitely within the TTL window just by hammering it in a loop -- 10/10min is
    // generous for legitimate use (re-staging after fixing a file, trying a few statements) while
    // still bounding that.
    private final RateLimiter importStageLimiter = new RateLimiter(10, 600);
    // Shared across all three password-change steps rather than one limiter each -- a caller
    // working through the flow normally touches all three anyway, so bucketing them together is
    // both simpler and doesn't require guessing a separate reasonable ceiling for each individual
    // step. 15/10min is generous for a legitimate user (including a few genuine retries after a
    // wrong current password or a mistyped code) while still bounding repeated abuse.
    private final RateLimiter passwordChangeLimiter = new RateLimiter(15, 600);
    // Bug fix: this used to be `new ObjectMapper()` -- a second, freshly-constructed mapper with
    // none of the auto-configuration Spring Boot's own JacksonAutoConfiguration applies to its
    // managed ObjectMapper bean (in particular, no JavaTimeModule). ApiResponse.timestamp is a
    // java.time.Instant, so the moment any rate limit actually tripped, serializing the 429 body
    // below threw InvalidDefinitionException instead of returning the intended "too many
    // requests" response -- discovered via this class's own new test suite actually exercising
    // the trip-the-limiter path for the first time. Injecting the real bean fixes it and removes
    // a redundant, differently-configured mapper instance.
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.security.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String ip = resolveClientIp(request);

        RateLimiter limiter = switch (path) {
            case "/api/v1/auth/login" -> loginLimiter;
            case "/api/v1/auth/register" -> registerLimiter;
            case "/api/v1/auth/forgot-password" -> forgotPasswordLimiter;
            case "/api/v1/import/csv/stage", "/api/v1/import/pdf/stage" -> importStageLimiter;
            case "/api/v1/users/me/password-change/start", "/api/v1/users/me/password-change/verify-otp",
                    "/api/v1/users/me/password-change/complete" -> passwordChangeLimiter;
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

    /** Bug fix: this used to take the FIRST entry of X-Forwarded-For on the (backwards) theory
     *  that "the first entry is the original client." A reverse proxy APPENDS the IP it observed
     *  to whatever X-Forwarded-For it received from upstream -- it does not replace the header --
     *  so with this app deployed behind exactly one trusted proxy (Railway's edge, per this
     *  filter's own class doc comment and application.yml's comment on this property), the LAST
     *  entry is the one the trusted proxy itself appended, and every entry before it (including
     *  the first) is whatever the original client chose to send, fully attacker-controlled. A
     *  request with a client-supplied "X-Forwarded-For: 1.2.3.4" header arrives here as
     *  "1.2.3.4, &lt;real address&gt;" -- taking the first entry let every rate limiter in this
     *  class (login, register, forgot-password, import staging) be bypassed completely by
     *  sending a fresh random value on every request, since each one landed in its own bucket.
     *  Falls back to getRemoteAddr() if the header is missing/blank even when trust is enabled,
     *  rather than resolving to null/empty. */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] hops = forwardedFor.split(",");
                return hops[hops.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
