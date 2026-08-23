package com.finora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
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

    /**
     * Every ceiling below is a property, and every default is the value that was hardcoded here
     * before -- so an untouched deployment behaves exactly as it did.
     *
     * <p>Two reasons they became configurable. The first is operational: these are per-IP, and a
     * whole office or a school behind one NAT shares a bucket. Five registrations per five minutes
     * is right for an open internet and wrong for a corporate customer onboarding a team, and the
     * only remedy was a redeploy.
     *
     * <p>The second is that the product could not be end-to-end tested at any useful volume. The
     * e2e suite registers an account per test for isolation and stages a statement in most of them,
     * so it hit {@code registerLimiter} at test six and {@code importStageLimiter} at test eleven --
     * every run, regardless of whether the product worked. A limit that cannot be raised for a test
     * stack does not make the system safer; it makes the system unverifiable, and an unverified
     * import engine is the larger risk. The test stack raises these and nothing else.
     *
     * <p>Rate limiting itself stays covered: the e2e suite's negative phase asserts a limit still
     * trips, against the configured ceiling rather than a hardcoded one.
     */
    private final RateLimiter loginLimiter;
    private final RateLimiter registerLimiter;
    private final RateLimiter forgotPasswordLimiter;
    private final RateLimiter identifyLimiter;
    // Staging used to be memory-only (parse, return the response, nothing persisted) -- as of
    // ADR-0002 (persisted import sessions), every call writes a real row to import_sessions
    // INCLUDING the raw file bytes, bounded only by a 48h TTL and cleanup that only runs on that
    // same user's own next stage() call. Unprotected, this endpoint became a way to grow that
    // table indefinitely within the TTL window just by hammering it in a loop -- 10/10min is
    // generous for legitimate use (re-staging after fixing a file, trying a few statements) while
    // still bounding that.
    private final RateLimiter importStageLimiter;
    // Shared across all three password-change steps rather than one limiter each -- a caller
    // working through the flow normally touches all three anyway, so bucketing them together is
    // both simpler and doesn't require guessing a separate reasonable ceiling for each individual
    // step. 15/10min is generous for a legitimate user (including a few genuine retries after a
    // wrong current password or a mistyped code) while still bounding repeated abuse.
    private final RateLimiter passwordChangeLimiter;
    // Change Phone Number, gated the same way password-change is and for a related but distinct
    // reason: unlike password-change (a real bcrypt comparison on every start() call), start()
    // here does only cheap, indexed lookups -- the concern isn't per-call cost, it's that a stolen
    // JWT could otherwise hammer this flow to hijack the phone number an account's own password
    // reset relies on. Same 15/10min ceiling as passwordChangeLimiter, for the same reason: generous
    // enough for a legitimate user working through a few retries, tight enough to bound abuse of a
    // flow whose outcome is a real account-takeover vector.
    private final RateLimiter phoneChangeLimiter;
    // Change Email, gated the same way password-change is and for the same cost-class reason:
    // start() here does a real GoogleReauthVerifier check (bcrypt, or a fresh Google/Apple token
    // verification) on every call, unlike phone-change's cheap indexed lookups. Same 15/10min
    // ceiling as passwordChangeLimiter/phoneChangeLimiter, for the same reason: generous enough
    // for a legitimate user working through a few retries, tight enough to bound abuse of a flow
    // whose outcome is the account's own password-reset delivery channel.
    private final RateLimiter emailChangeLimiter;
    // Bug fix: /auth/reset-password performs bcrypt work per call (hashing the new password, plus
    // the password-history comparison) and sat outside every limiter -- while this class's own
    // comment on passwordChangeLimiter names "a real bcrypt comparison" as "exactly the kind of
    // per-call cost this class already protects elsewhere." The same reasoning applied here and
    // simply had not been applied. Deliberately narrow rather than urgent: both a valid reset
    // token AND a Firebase-verified phone gate the expensive work, so this was never an anonymous
    // DoS -- which is why the ceiling is generous. It bounds a token holder retrying in a loop.
    private final RateLimiter resetPasswordLimiter;
    // D-23. /auth/google does real work per call even on a rejected token -- Google's own
    // signature/JWKS verification -- and for a first-time email, the full account-creation path
    // (BCrypt hash, default-category seeding) that /register already sits behind a limiter for.
    // It needs no phone number the way /register does, which makes it the CHEAPER path to spam
    // account creation if left unguarded, not a lesser concern. Same ceiling as registerLimiter,
    // for the same cost class.
    private final RateLimiter googleLimiter;
    // D-23 Phase 2. Apple's counterpart to googleLimiter, same reasoning and same ceiling: real
    // work per call (JWKS-backed signature verification) and, for a first-time email, the full
    // account-creation path -- the cheapest way to spam account creation if left unguarded.
    private final RateLimiter appleLimiter;
    // SEC-16 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Every other
    // session-lifecycle endpoint that consumes a bearer credential is limited under this class's
    // own "bounds a token holder retrying in a loop" reasoning (see resetPasswordLimiter above) --
    // this one, arguably the most frequently hit of all of them, was simply missed. Not a raw
    // brute-force concern (refresh tokens are 256-bit SecureRandom, RefreshTokenService.issue --
    // infeasible to guess regardless of any limiter), but an unbounded retry ceiling on a
    // stolen/leaked token is still worth closing for consistency.
    //
    // A dedicated, more generous limiter rather than sharing resetPasswordLimiter's bucket:
    // refresh is legitimately called far more often per user (every access-token expiry, from
    // every open tab/device), and several Finora users on one shared IP (a household, an office
    // NAT) refreshing around the same time is ordinary use, not abuse -- resetPasswordLimiter's
    // tighter ceiling is sized for a rare, single-shot flow and would false-positive here.
    private final RateLimiter refreshLimiter;
    // Phase C (Download My Data). Far stricter than importStageLimiter -- 5/day, not 10/10min --
    // because a full export is strictly more expensive per call (every in-scope table plus every
    // original statement file, read and zipped) and legitimately needed far less often. Bug fix
    // (review): raised from an original 3. This filter runs before the controller, so it counts
    // every request that reaches this path -- wrong password or not, since
    // DataExportService.buildBundle requires the password fresh on every call with no session or
    // grace window. 3/day left almost no room for a single mistyped password without losing a
    // real day's access to the feature; 5 leaves two spare attempts alongside the "a handful of
    // exports a day" ceiling this ceiling actually intends to enforce.
    //
    // A token holder retrying in a loop -- a JWT stolen via XSS or a compromised device -- is
    // what this bounds, same reasoning as passwordChangeLimiter/resetPasswordLimiter. Like every
    // limiter in this class, the bucket is per-IP (see clientIpResolver.resolve below), not
    // per-token: it does not stop an attacker who rotates IPs, only one retrying from the same
    // one. See resetPasswordLimiter's own comment for the same, more honestly-scoped claim.
    private final RateLimiter dataExportLimiter;
    // /account/delete itself had no dedicated limiter at all -- the credential-proving steps that
    // precede it (password-change/start, verify-otp) are covered, but a caller already holding a
    // verified sessionId could call this endpoint directly in a loop, each call triggering
    // AccountPurgeSweepService.purgeOne's full synchronous purge across every user table. 5/hour,
    // not passwordChangeLimiter's shared bucket: the real per-call cost here (a full purge) is
    // larger than a single bcrypt comparison, so this gets its own, stricter ceiling -- generous
    // enough that a user re-reading the irreversible-action warning and confirming again doesn't
    // trip it.
    private final RateLimiter deleteAccountLimiter;
    // SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Same reasoning as
    // resetPasswordLimiter/reactivate/verify-email directly above: an unguessable, short-TTL,
    // single-use challenge token (AdminMfaService.issueChallenge) already gates reaching this
    // endpoint at all, so this bounds a token holder retrying in a loop -- not a raw brute-force
    // stopper on the 6-digit code space, which the challenge's own 5-minute expiry already makes
    // infeasible to exhaust over the network regardless of any limiter.
    private final RateLimiter mfaVerifyLimiter;
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

    // Bug 07 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). This filter runs at
    // Ordered.HIGHEST_PRECEDENCE + 1, entirely before Spring Security's own FilterChainProxy --
    // and CORS is wired INSIDE that chain (CorsConfig's own comment explains why, deliberately, as
    // HttpSecurity.cors(...) rather than a standalone CorsFilter bean). A 429 short-circuit here
    // therefore never reaches Spring's CORS handling, so a browser sees a bare cross-origin
    // response with no Access-Control-Allow-Origin header and reports a generic network error
    // instead of rendering the actual RATE_LIMITED body. Reuses the SAME
    // CorsConfigurationSource bean SecurityConfig wires in (see the constructor) rather than a
    // second, duplicated allowed-origins list, so the two can never drift apart.
    private final CorsConfigurationSource corsConfigurationSource;

    /** One rate-limited endpoint. Kept as a pattern rather than a String so matching goes through
     *  the same engine that decides which controller actually handles the request. */
    private record LimitedEndpoint(PathPattern pattern, RateLimiter limiter) {}

    /** A parser of this class's own, rather than PathPatternParser.defaultInstance, so nothing
     *  else reconfiguring that shared instance can silently change what this filter matches.
     *  Defaults are identical to the ones DispatcherServlet routes with under Spring Boot 3
     *  (spring.mvc.pathmatch.matching-strategy defaults to PATH_PATTERN_PARSER). */
    private static final PathPatternParser PARSER = new PathPatternParser();

    private final List<LimitedEndpoint> limitedEndpoints;

    /**
     * The shipped ceilings, as constants rather than as literals inside the {@code @Value}
     * defaults.
     *
     * There is exactly one copy of each number, referenced by both the property default and the
     * test constructor below. Written as literals in two places they would drift, and the direction
     * they drift in is "the tests assert a limit the application does not have" -- which is the one
     * failure mode a rate-limiting test exists to prevent.
     */
    static final int DEFAULT_LOGIN_MAX = 10, DEFAULT_LOGIN_WINDOW = 60;
    static final int DEFAULT_REGISTER_MAX = 5, DEFAULT_REGISTER_WINDOW = 300;
    static final int DEFAULT_FORGOT_MAX = 5, DEFAULT_FORGOT_WINDOW = 300;
    // Auth/security review §2.2: tighter than loginLimiter's per-second rate (10/300s here vs
    // 10/60s for login) -- unlike login, a hit here costs the caller nothing (no password to
    // guess, no lockout risk), so it is the cheaper endpoint to script against and gets the
    // tighter ceiling.
    static final int DEFAULT_IDENTIFY_MAX = 10, DEFAULT_IDENTIFY_WINDOW = 300;
    static final int DEFAULT_IMPORT_STAGE_MAX = 10, DEFAULT_IMPORT_STAGE_WINDOW = 600;
    static final int DEFAULT_PASSWORD_CHANGE_MAX = 15, DEFAULT_PASSWORD_CHANGE_WINDOW = 600;
    static final int DEFAULT_PHONE_CHANGE_MAX = 15, DEFAULT_PHONE_CHANGE_WINDOW = 600;
    static final int DEFAULT_EMAIL_CHANGE_MAX = 15, DEFAULT_EMAIL_CHANGE_WINDOW = 600;
    static final int DEFAULT_RESET_PASSWORD_MAX = 10, DEFAULT_RESET_PASSWORD_WINDOW = 600;
    static final int DEFAULT_DATA_EXPORT_MAX = 5, DEFAULT_DATA_EXPORT_WINDOW = 86400;
    static final int DEFAULT_DELETE_ACCOUNT_MAX = 5, DEFAULT_DELETE_ACCOUNT_WINDOW = 3600;
    static final int DEFAULT_GOOGLE_MAX = 5, DEFAULT_GOOGLE_WINDOW = 300;
    static final int DEFAULT_APPLE_MAX = 5, DEFAULT_APPLE_WINDOW = 300;
    static final int DEFAULT_MFA_VERIFY_MAX = 10, DEFAULT_MFA_VERIFY_WINDOW = 600;
    // 15/300s (one every 20s sustained, or bursts) rather than an even larger ceiling: generous
    // enough to absorb several Finora users on one shared IP refreshing around the same time
    // without a false trip, while staying below tripsRateLimitAfterManyRequests' fixed 20-request
    // probe (RateLimitFilterTest) -- every limiter this class defines needs a max under that
    // probe's iteration count for the shared "does this endpoint actually trip" test to work.
    static final int DEFAULT_REFRESH_MAX = 15, DEFAULT_REFRESH_WINDOW = 300;

    /**
     * The shipped configuration, for tests.
     *
     * Twelve positional ints is a poor thing to ask a caller to get right -- transposing a max and
     * a window silently weakens a security control and nothing would fail -- so the only caller
     * that types them out is Spring, from named properties. Everything else goes through here.
     */
    RateLimitFilter(ObjectMapper objectMapper, ClientIpResolver clientIpResolver,
                     CorsConfigurationSource corsConfigurationSource) {
        this(objectMapper, clientIpResolver, corsConfigurationSource,
                DEFAULT_LOGIN_MAX, DEFAULT_LOGIN_WINDOW,
                DEFAULT_REGISTER_MAX, DEFAULT_REGISTER_WINDOW,
                DEFAULT_FORGOT_MAX, DEFAULT_FORGOT_WINDOW,
                DEFAULT_IDENTIFY_MAX, DEFAULT_IDENTIFY_WINDOW,
                DEFAULT_IMPORT_STAGE_MAX, DEFAULT_IMPORT_STAGE_WINDOW,
                DEFAULT_PASSWORD_CHANGE_MAX, DEFAULT_PASSWORD_CHANGE_WINDOW,
                DEFAULT_PHONE_CHANGE_MAX, DEFAULT_PHONE_CHANGE_WINDOW,
                DEFAULT_EMAIL_CHANGE_MAX, DEFAULT_EMAIL_CHANGE_WINDOW,
                DEFAULT_RESET_PASSWORD_MAX, DEFAULT_RESET_PASSWORD_WINDOW,
                DEFAULT_DATA_EXPORT_MAX, DEFAULT_DATA_EXPORT_WINDOW,
                DEFAULT_DELETE_ACCOUNT_MAX, DEFAULT_DELETE_ACCOUNT_WINDOW,
                DEFAULT_GOOGLE_MAX, DEFAULT_GOOGLE_WINDOW,
                DEFAULT_APPLE_MAX, DEFAULT_APPLE_WINDOW,
                DEFAULT_MFA_VERIFY_MAX, DEFAULT_MFA_VERIFY_WINDOW,
                DEFAULT_REFRESH_MAX, DEFAULT_REFRESH_WINDOW);
    }

    /**
     * Ceilings arrive as constructor parameters rather than field {@code @Value} injection because
     * {@code limitedEndpoints} is built here and needs the limiters to already exist. Field
     * injection would leave them null at construction time, which is the sort of thing that fails
     * as a NullPointerException on the first rate-limited request rather than at startup.
     *
     * <p>{@code @Autowired} is required, not decorative: a class with more than one constructor
     * gives Spring nothing to choose by, and it falls back to looking for a no-arg one. Without
     * this the application starts as far as Tomcat and then fails with
     * "No default constructor found", which names the symptom and not the cause.
     */
    @Autowired
    public RateLimitFilter(
            ObjectMapper objectMapper,
            ClientIpResolver clientIpResolver,
            CorsConfigurationSource corsConfigurationSource,
            @Value("${app.rate-limit.login.max:10}") int loginMax,
            @Value("${app.rate-limit.login.window-seconds:60}") int loginWindow,
            @Value("${app.rate-limit.register.max:5}") int registerMax,
            @Value("${app.rate-limit.register.window-seconds:300}") int registerWindow,
            @Value("${app.rate-limit.forgot-password.max:5}") int forgotMax,
            @Value("${app.rate-limit.forgot-password.window-seconds:300}") int forgotWindow,
            @Value("${app.rate-limit.identify.max:10}") int identifyMax,
            @Value("${app.rate-limit.identify.window-seconds:300}") int identifyWindow,
            @Value("${app.rate-limit.import-stage.max:10}") int importStageMax,
            @Value("${app.rate-limit.import-stage.window-seconds:600}") int importStageWindow,
            @Value("${app.rate-limit.password-change.max:15}") int passwordChangeMax,
            @Value("${app.rate-limit.password-change.window-seconds:600}") int passwordChangeWindow,
            @Value("${app.rate-limit.phone-change.max:15}") int phoneChangeMax,
            @Value("${app.rate-limit.phone-change.window-seconds:600}") int phoneChangeWindow,
            @Value("${app.rate-limit.email-change.max:15}") int emailChangeMax,
            @Value("${app.rate-limit.email-change.window-seconds:600}") int emailChangeWindow,
            @Value("${app.rate-limit.reset-password.max:10}") int resetPasswordMax,
            @Value("${app.rate-limit.reset-password.window-seconds:600}") int resetPasswordWindow,
            @Value("${app.rate-limit.data-export.max:5}") int dataExportMax,
            @Value("${app.rate-limit.data-export.window-seconds:86400}") int dataExportWindow,
            @Value("${app.rate-limit.delete-account.max:5}") int deleteAccountMax,
            @Value("${app.rate-limit.delete-account.window-seconds:3600}") int deleteAccountWindow,
            @Value("${app.rate-limit.google.max:5}") int googleMax,
            @Value("${app.rate-limit.google.window-seconds:300}") int googleWindow,
            @Value("${app.rate-limit.apple.max:5}") int appleMax,
            @Value("${app.rate-limit.apple.window-seconds:300}") int appleWindow,
            @Value("${app.rate-limit.mfa-verify.max:10}") int mfaVerifyMax,
            @Value("${app.rate-limit.mfa-verify.window-seconds:600}") int mfaVerifyWindow,
            @Value("${app.rate-limit.refresh.max:15}") int refreshMax,
            @Value("${app.rate-limit.refresh.window-seconds:300}") int refreshWindow) {
        this.objectMapper = objectMapper;
        this.clientIpResolver = clientIpResolver;
        this.corsConfigurationSource = corsConfigurationSource;
        this.loginLimiter = new RateLimiter(loginMax, loginWindow);
        this.registerLimiter = new RateLimiter(registerMax, registerWindow);
        this.forgotPasswordLimiter = new RateLimiter(forgotMax, forgotWindow);
        this.identifyLimiter = new RateLimiter(identifyMax, identifyWindow);
        this.importStageLimiter = new RateLimiter(importStageMax, importStageWindow);
        this.passwordChangeLimiter = new RateLimiter(passwordChangeMax, passwordChangeWindow);
        this.phoneChangeLimiter = new RateLimiter(phoneChangeMax, phoneChangeWindow);
        this.emailChangeLimiter = new RateLimiter(emailChangeMax, emailChangeWindow);
        this.resetPasswordLimiter = new RateLimiter(resetPasswordMax, resetPasswordWindow);
        this.dataExportLimiter = new RateLimiter(dataExportMax, dataExportWindow);
        this.deleteAccountLimiter = new RateLimiter(deleteAccountMax, deleteAccountWindow);
        this.googleLimiter = new RateLimiter(googleMax, googleWindow);
        this.appleLimiter = new RateLimiter(appleMax, appleWindow);
        this.mfaVerifyLimiter = new RateLimiter(mfaVerifyMax, mfaVerifyWindow);
        this.refreshLimiter = new RateLimiter(refreshMax, refreshWindow);
        this.limitedEndpoints = List.of(
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/login"), loginLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/refresh"), refreshLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/register"), registerLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/google"), googleLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/apple"), appleLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/identify"), identifyLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/forgot-password"), forgotPasswordLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/reset-password"), resetPasswordLimiter),
                // BH-015. This class's comment above dismissed /auth/reset-password/phone as
                // "cheap, no-real-cost" and left it unlimited -- reasoning about COST, on an
                // endpoint whose problem was DISCLOSURE. It used to return the account's full
                // phone number to any holder of a valid reset token; now inverted (see
                // AuthService.verifyResetPasswordPhone) so the user supplies a candidate number
                // and the endpoint only confirms a match -- still a phone-number-guessing oracle,
                // so this limiter remains the bound on how many guesses a token holder gets.
                // Shares resetPasswordLimiter because the two are steps of one flow and one
                // bucket is the honest way to bound it.
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/reset-password/phone"), resetPasswordLimiter),
                // Shares resetPasswordLimiter for the same reason reset-password/phone does: an
                // unguessable, single-use, short-TTL token gates the real cost here (issuing real
                // access/refresh tokens), so this bounds a token holder retrying in a loop rather
                // than defending against an anonymous guesser.
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/reactivate"), resetPasswordLimiter),
                // D-23. Same reasoning as reactivate directly above: an unguessable, single-use,
                // short-TTL token gates the real cost here, so this bounds a token holder retrying
                // in a loop rather than defending against an anonymous guesser.
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/verify-email"), resetPasswordLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/import/csv/stage"), importStageLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/import/pdf/stage"), importStageLimiter),
                // The asynchronous upload path, sharing importStageLimiter because it is the same
                // cost with more of it. This list covered the two synchronous staging endpoints and
                // not this one -- and this one is what the web app now uploads through.
                //
                // The justification importStageLimiter already carries applies here verbatim and
                // then some: every call writes an object to storage AND a queue row, and the job it
                // creates then writes an import_sessions row with the raw statement bytes. Content
                // addressing dedupes identical uploads, so an abusive caller varies one byte and
                // every request is a new object -- which nothing ever deletes.
                //
                // NOT the same thing as ImportConcurrencyLimiter, which ImportJobController
                // deliberately skips: that one bounds simultaneous PARSING, and this request does
                // no parsing. This bounds how often an upload may be ACCEPTED, which is a different
                // question and was simply not being asked.
                new LimitedEndpoint(PARSER.parse("/api/v1/import/jobs"), importStageLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/start"), passwordChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/verify-otp"), passwordChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/password-change/complete"), passwordChangeLimiter),
                // Bug fix (review): this endpoint had no limiter at all. deactivate() does the
                // same real per-call cost passwordChangeLimiter's own comment names -- a bcrypt
                // comparison against the account's current password on every call -- and its
                // wrong-password branch now also runs AuditService.recordEvenOnRollback (a
                // REQUIRES_NEW transaction, briefly holding a second pooled DB connection). With
                // no ceiling, a caller holding a valid-but-stolen token could loop this endpoint
                // unboundedly, paying that bcrypt + double-connection cost on every call. Shares
                // passwordChangeLimiter, not dataExportLimiter -- same cost shape as password
                // change, not the much larger per-call cost a full data export carries.
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/account/deactivate"), passwordChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/account/delete"), deleteAccountLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/phone-change/start"), phoneChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/phone-change/verify-otp"), phoneChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/phone-change/complete"), phoneChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/email-change/start"), emailChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/email-change/verify"), emailChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/email-change/complete"), emailChangeLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/users/me/data-export"), dataExportLimiter),
                new LimitedEndpoint(PARSER.parse("/api/v1/auth/mfa/verify"), mfaVerifyLimiter));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);

        RateLimiter limiter = limiterFor(request);

        if (limiter != null && !limiter.allow(ip)) {
            applyCorsHeadersForShortCircuitedResponse(request, response);
            response.setStatus(429); // Too Many Requests
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiResponse<Void> body = ApiResponse.error(
                    "Too many requests. Please wait before trying again.", "RATE_LIMITED");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    // See corsConfigurationSource's own field comment for why this is needed at all. Deliberately
    // NOT the full CorsProcessor/DefaultCorsProcessor machinery Spring uses inside the security
    // chain -- that class also owns preflight (OPTIONS) handling, which this filter has no reason
    // to take on. Setting just the two headers a real (non-preflight) cross-origin response needs
    // to actually be readable by the calling page's JavaScript is enough: the browser only checks
    // Access-Control-Allow-Origin/-Credentials on the actual response, not a repeat of the
    // preflight dance, for a simple already-permitted request method like this filter ever
    // short-circuits (GET/POST against JSON endpoints, never a preflighted request type).
    private void applyCorsHeadersForShortCircuitedResponse(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) return;
        CorsConfiguration corsConfig = corsConfigurationSource.getCorsConfiguration(request);
        if (corsConfig == null) return;
        String allowedOrigin = corsConfig.checkOrigin(origin);
        if (allowedOrigin == null) return;
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        if (Boolean.TRUE.equals(corsConfig.getAllowCredentials())) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
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
