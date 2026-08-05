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

import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;

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
 * Which limiter applies is decided by matching Spring's own PathPattern against the request's
 * DECODED path, deliberately not by string-comparing request.getRequestURI(). See
 * {@link #limiterFor} for the bypass that distinction fixes.
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
    // Bug fix: /auth/reset-password performs bcrypt work per call (hashing the new password, plus
    // the password-history comparison) and sat outside every limiter -- while this class's own
    // comment on passwordChangeLimiter names "a real bcrypt comparison" as "exactly the kind of
    // per-call cost this class already protects elsewhere." The same reasoning applied here and
    // simply had not been applied. Deliberately narrow rather than urgent: both a valid reset
    // token AND a Firebase-verified phone gate the expensive work, so this was never an anonymous
    // DoS -- which is why the ceiling is generous. It bounds a token holder retrying in a loop.
    private final RateLimiter resetPasswordLimiter = new RateLimiter(10, 600);
    // Bug fix: this used to be `new ObjectMapper()` -- a second, freshly-constructed mapper with
    // none of the auto-configuration Spring Boot's own JacksonAutoConfiguration applies to its
    // managed ObjectMapper bean (in particular, no JavaTimeModule). ApiResponse.timestamp is a
    // java.time.Instant, so the moment any rate limit actually tripped, serializing the 429 body
    // below threw InvalidDefinitionException instead of returning the intended "too many
    // requests" response -- discovered via this class's own new test suite actually exercising
    // the trip-the-limiter path for the first time. Injecting the real bean fixes it and removes
    // a redundant, differently-configured mapper instance.
    private final ObjectMapper objectMapper;

    /**
     * Injected rather than reimplemented. This filter used to carry a byte-identical private copy
     * of ClientIpResolver.resolve(), and that class's own doc comment ended "keep the two in sync
     * if this logic ever changes" -- an instruction where a mechanism belongs, on a security
     * control where the two failure directions are collapsing every user onto one rate-limit
     * bucket (the proxy's own IP) or letting anyone spoof any IP and bypass the limiter entirely.
     *
     * The original reason for duplicating was not wanting to touch this filter's already-tested
     * internals. That no longer holds: the filter and its tests were both reworked in 6ee925a.
     */
    private final ClientIpResolver clientIpResolver;


    /** One rate-limited endpoint. Kept as a pattern rather than a String so matching goes through
     *  the same engine that decides which controller actually handles the request. */
    private record LimitedEndpoint(PathPattern pattern, RateLimiter limiter) {}

    /** A parser of this class's own, rather than PathPatternParser.defaultInstance, so nothing
     *  else reconfiguring that shared instance can silently change what this filter matches.
     *  Defaults are identical to the ones DispatcherServlet routes with under Spring Boot 3
     *  (spring.mvc.pathmatch.matching-strategy defaults to PATH_PATTERN_PARSER). */
    private static final PathPatternParser PARSER = new PathPatternParser();

    private final List<LimitedEndpoint> limitedEndpoints;

    public RateLimitFilter(ObjectMapper objectMapper, ClientIpResolver clientIpResolver) {
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
        this.limitedEndpoints = List.of(
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/login"), loginLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/register"), registerLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/forgot-password"), forgotPasswordLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/reset-password"), resetPasswordLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/import/csv/stage"), importStageLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/import/pdf/stage"), importStageLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/start"), passwordChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/verify-otp"), passwordChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/complete"), passwordChangeLimiter));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);

        RateLimiter limiter = limiterFor(request);

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

    /**
     * Bug fix: this used to be a {@code switch} over {@code request.getRequestURI()}, comparing the
     * RAW request line against exact literals. Spring routes on the DECODED path -- both
     * DispatcherServlet's handler mapping and SecurityConfig's own {@code requestMatchers(...)} use
     * PathPattern against the parsed request path, where {@code %6C} is just {@code l}. So
     * {@code POST /api/v1/auth/%6Cogin} reached AuthController.login() and performed a completely
     * ordinary login attempt, while this filter's exact-string switch saw a path it had never heard
     * of and applied no limiter at all.
     *
     * <p>That made every limiter in this class bypassable by percent-encoding any single character
     * of the path, with no credential and no authentication required: unlimited credential stuffing
     * against login (defeating the per-IP half of the lockout design this class exists to provide),
     * unlimited registration spam, unlimited forgot-password mail, unbounded growth of
     * import_sessions including raw statement bytes, and unlimited bcrypt work against
     * password-change. Every rate-limit test in RateLimitFilterTest passed throughout, because they
     * all fed the filter already-canonical paths.
     *
     * <p>Matching through PathPattern against the decoded path is what makes this filter agree with
     * the router by construction rather than by a duplicated string literal that only holds for the
     * exact spelling someone thought to write down. Enforced by
     * {@code FilterPathMatchingTest}, which fails the build if a filter goes back to
     * comparing getRequestURI() as a string.
     */
    private RateLimiter limiterFor(HttpServletRequest request) {
        PathContainer path = pathWithinApplication(request);
        for (LimitedEndpoint endpoint : limitedEndpoints) {
            if (endpoint.pattern().matches(path)) return endpoint.limiter();
        }
        return null;
    }

    /** getRequestURI() includes the context path; the patterns above are written relative to the
     *  application, exactly like SecurityConfig's matchers. Parsed (not string-compared) so the
     *  segments this matches against are the decoded ones. */
    private static PathContainer pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) return PathContainer.parsePath("/");
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return PathContainer.parsePath(uri.isEmpty() ? "/" : uri);
    }

}
