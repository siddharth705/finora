package com.finora.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Runs once per request: pulls the Bearer token out of the Authorization header,
 * validates it, and — if valid — populates the Spring Security context so
 * downstream controllers can use @AuthenticationPrincipal / SecurityContextHolder.
 *
 * <p>"Valid" means two things, not one: the signature and expiry have to hold (JwtService), AND the
 * session the token names has to still exist ({@link SessionValidator}). The second half is what
 * makes a revocation take effect on a token already in circulation — see that class for why a
 * signed, unexpired token is not on its own evidence that the platform still recognises the
 * session.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /**
     * Request attribute holding the {@code sid} claim of the token that authenticated this
     * request, as a {@link java.util.UUID}, or null when the token predates the claim.
     */
    public static final String SESSION_ID_ATTRIBUTE = "com.finora.security.sessionId";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final CurrentUserDetailsService userDetailsService;
    private final SessionValidator sessionValidator;

    public JwtAuthFilter(JwtService jwtService, CurrentUserDetailsService userDetailsService,
                          SessionValidator sessionValidator) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.sessionValidator = sessionValidator;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            if (jwtService.isTokenValid(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
                // Which SESSION this request belongs to. Read before anything else is done with the
                // token, because it now decides whether the request authenticates at all.
                UUID sessionId = jwtService.extractSessionId(token);

                // The revocation check. A signed, unexpired token proves the platform issued it; it
                // does not prove the platform still honours it. Every revocation path writes to
                // refresh_tokens and none of them can reach a token already in someone's hands, so
                // without this the platform could conclude a token was stolen, sign the user out
                // everywhere, and have the attacker keep working for the rest of the fifteen
                // minutes. See SessionValidator for why the session and not the token is the unit.
                //
                // Rejection is expressed as "do not authenticate", not as a response written from
                // here. Spring Security's own entry point then answers 401 exactly as it does for
                // an expired token, which is the shape all three clients already handle: their
                // interceptors attempt a refresh, that refresh presents the revoked refresh token,
                // and /auth/refresh is where the specific reason (AUTH_004 and friends) is
                // reported. Writing a distinct body here would add a fourth place that decides what
                // a session ending means, and would be a new response shape on every existing
                // endpoint for clients that cannot update in step with a deploy.
                //
                // Written as an else rather than an early `doFilter(...); return;`, deliberately:
                // the surrounding catch is `catch (Exception)`, so continuing the chain from inside
                // the try would let it absorb any downstream controller's exception AND then fall
                // through to the second doFilter below — one request served twice.
                if (sessionValidator.isSessionLive(sessionId)) {
                    // Resolved by the token's SUBJECT (the user id), not its email claim. Since V52
                    // an email identifies a user only within a portal scope, so loading by email
                    // would pick an arbitrary one of the two accounts a person may hold under it --
                    // and could authenticate a request as the wrong account entirely. The id was
                    // already in the token; this simply stops ignoring it.
                    UserDetails userDetails = userDetailsService.loadUserByUsername(
                            jwtService.extractUserId(token).toString());

                    var authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // Published for the handful of endpoints that need to tell "this device" from
                    // the others. A request attribute rather than the Authentication's details,
                    // which already carries WebAuthenticationDetails and is read by Spring Security
                    // itself -- overloading it would couple an application concern to framework
                    // state. Never null by the time it is set: a null sid is not a live session.
                    request.setAttribute(SESSION_ID_ATTRIBUTE, sessionId);
                } else {
                    log.debug("Rejecting an access token for a session that is no longer live on a "
                            + "{} request", request.getMethod());
                }
            }
        } catch (Exception e) {
            // A stale/malformed token (e.g. referencing a user that no longer exists after a DB
            // reset, or otherwise unparseable) must never crash the filter chain — that would
            // incorrectly block permitAll endpoints too, and turn what should be a clean 401 on
            // protected endpoints into an opaque, uncaught-exception-driven 403. Fail open: treat
            // this request as unauthenticated and let Spring Security's own authorization rules
            // decide from there.
            //
            // Bug fix: this caught Exception and logged NOTHING. The fail-open behaviour is right,
            // but the catch is as wide as it gets, so it also absorbs a database outage or
            // connection-pool exhaustion — conditions with nothing to do with the token. The
            // production signature of that is every user's token appearing invalid at once, with
            // zero log evidence anywhere to explain it. Logged at debug for the ordinary case
            // (a bad token is routine and must not fill error logs on every probe request) and at
            // warn for anything that isn't a token problem, which is the case that needs to be
            // visible. GlobalExceptionHandler already logs method and path for exactly this
            // reason — "Unhandled exception" alone was not debuggable.
            // Deliberately logs the request METHOD but not the URI. FilterPathMatchingTest forbids
            // a Filter reading request.getRequestURI() without parsing it, because a filter that
            // decides anything from the raw URI can be bypassed by percent-encoding one character.
            // This use would be read-only and harmless, but the guard cannot tell the two apart,
            // and refining it to distinguish "reads for logging" from "reads for a decision" would
            // trade a rule that is always right for one that is usually right. The exception and
            // its stack trace are what actually make this diagnosable; CorrelationIdFilter's id
            // ties the entry back to the access log if the path is ever needed.
            if (e instanceof io.jsonwebtoken.JwtException || e instanceof IllegalArgumentException) {
                log.debug("Rejecting an unusable JWT on a {} request: {}", request.getMethod(), e.toString());
            } else {
                log.warn("Authentication failed on a {} request for a reason unrelated to the token "
                        + "itself — treating it as unauthenticated. If this is widespread, suspect the "
                        + "database or connection pool rather than the tokens.", request.getMethod(), e);
            }
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
