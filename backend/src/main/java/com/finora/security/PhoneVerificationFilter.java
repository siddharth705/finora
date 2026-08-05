package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ApiResponse;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Backend enforcement of phone verification. Before this filter existed, phone verification was
 * only a frontend redirect performed right after register/login (see Register.tsx/Login.tsx) —
 * a valid JWT alone was enough to call any protected endpoint directly, so a user could bypass
 * verification entirely by navigating straight to /app in the browser. The backend must be the
 * source of truth, not frontend navigation.
 *
 * Runs after JwtAuthFilter (so the authenticated principal is already populated) and rejects any
 * request from an authenticated-but-unverified user with a distinct error code the frontend's
 * axios interceptor keys off (client.ts) to redirect to /verify-phone — everywhere EXCEPT the
 * phone-verification endpoints themselves, since an unverified user must still be able to reach
 * those in order to ever become verified in the first place.
 *
 * Also excludes the whole /api/v1/auth/** family (login, register, refresh, forgot/reset
 * password, AND logout) — SecurityConfig already marks all of these permitAll at the
 * authorization layer, but this filter previously only mirrored that for /api/v1/phone/**, not
 * /api/v1/auth/**. That drift caused a real bug: client.ts's AUTH_ENDPOINTS_NO_TOKEN list skips
 * attaching a Bearer token for login/register/refresh/forgot-password/reset-password, but NOT
 * for /auth/logout (logout legitimately needs the token so the server knows whose refresh token
 * to revoke). So an unverified user's logout call WAS seen as authenticated here and got
 * rejected with 403 PHONE_VERIFICATION_REQUIRED before AuthService.logout() (and therefore
 * refreshTokenService.revoke(...)) ever ran. AuthContext.tsx's logout() swallows that failure
 * (.catch(() => {})) and clears local state anyway, so the user *looked* logged out client-side
 * while their refresh token silently stayed valid server-side indefinitely.
 *
 * Bug fix: GET /api/v1/users/me (both VerifyPhone.tsx pages -- user app and admin portal -- call
 * userApi.get() as the very first step of startVerification(), to learn the account's real,
 * unmasked phone number before it can hand that to Firebase's signInWithPhoneNumber(); the login/
 * register response only ever carries maskedPhone, never the real number) was never excluded
 * here, so every brand-new registration's phone-verification step failed immediately with this
 * same 403 before Firebase was ever reached -- there was no way to become verified at all. Scoped
 * to GET only (not the whole /api/v1/users/me/** family): PUT /users/me (preference updates) and
 * the password-change endpoints under this same base path have no reason to be reachable before
 * verification and should stay blocked.
 */
@Component
public class PhoneVerificationFilter extends OncePerRequestFilter {

    private static final AntPathRequestMatcher PHONE_ENDPOINTS = new AntPathRequestMatcher("/api/v1/phone/**");
    private static final AntPathRequestMatcher AUTH_ENDPOINTS = new AntPathRequestMatcher("/api/v1/auth/**");
    // GET only, matching SecurityConfig's own scoping of this one endpoint -- POST /setup/complete
    // is deliberately NOT excluded here, since only the pre-verified BOOTSTRAP_ADMIN account
    // (see BootstrapService) can ever legitimately reach it.
    private static final AntPathRequestMatcher SETUP_STATUS_ENDPOINT =
            new AntPathRequestMatcher("/api/v1/setup/status", "GET");
    // GET only, and the exact base path (no /** wildcard) -- must NOT match PUT /api/v1/users/me
    // (preference updates) or any /api/v1/users/me/** sub-path (password-change, /access), none
    // of which an unverified user has any legitimate reason to reach.
    private static final AntPathRequestMatcher USER_ME_ENDPOINT =
            new AntPathRequestMatcher("/api/v1/users/me", "GET");

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public PhoneVerificationFilter(UserRepository userRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails userDetails
                && !PHONE_ENDPOINTS.matches(request) && !AUTH_ENDPOINTS.matches(request)
                && !SETUP_STATUS_ENDPOINT.matches(request) && !USER_ME_ENDPOINT.matches(request)) {
            // The principal's username is the user id (see CurrentUserDetailsService) -- an email
            // would be ambiguous since V52, and could check phone verification against the wrong
            // one of the two accounts a person may hold under one address.
            //
            // Reads only the flag, not the whole User. This ran a second full findById on every
            // authenticated request -- JwtAuthFilter has already loaded the same user moments
            // earlier, and the two do not share a persistence context (the
            // OpenEntityManagerInViewInterceptor starts inside DispatcherServlet, after this
            // chain), so nothing was cached and each load dragged the eager roles -> permissions
            // graph. One boolean does not need any of that.
            Optional<Boolean> phoneVerified = parseId(userDetails.getUsername())
                    .flatMap(userRepository::findPhoneVerifiedById);
            if (phoneVerified.isPresent() && !phoneVerified.get()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(objectMapper.writeValueAsString(
                        ApiResponse.error("Please verify your phone number to continue.", "PHONE_VERIFICATION_REQUIRED")));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
    /** A principal that isn't a UUID cannot resolve to a user -- treated as "no user found", which
     *  lets this filter fall through rather than throwing inside the filter chain. */
    private static Optional<java.util.UUID> parseId(String raw) {
        try {
            return Optional.of(java.util.UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
