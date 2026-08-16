package com.finora.controller;

import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import com.finora.security.RefreshTokenCookie;
import com.finora.exception.ErrorCode;
import com.finora.exception.ApiException;
import com.finora.dto.ApiResponse;
import com.finora.dto.AuthDtos.*;
import com.finora.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie) {
        this.refreshTokenCookie = refreshTokenCookie;
        this.authService = authService;
    }

    /**
     * Every endpoint that mints a session writes the refresh cookie, not just {@code /refresh}.
     *
     * <p>Wiring only the rotation path would work right up until the web client stops keeping the
     * token in {@code localStorage}: a freshly signed-in browser would then hold no refresh
     * credential at all, and the session would die at the first access-token expiry with nothing
     * to rotate. The cookie has to exist from the moment the session does.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return withRefreshCookie(response.refreshToken())
                .body(ApiResponse.ok(response, "Account created"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return withRefreshCookie(response.refreshToken())
                .body(ApiResponse.ok(response, "Signed in"));
    }

    /** Completes the "Welcome back — reactivate your account?" confirmation Login.tsx shows after
     *  a deactivated account's password checks out (login() throws AUTH_ACCOUNT_DEACTIVATED with
     *  a reactivation token in its details map). Same AuthResponse shape and refresh-cookie
     *  handling as login() itself, so the client's success path doesn't need to special-case it. */
    @PostMapping("/reactivate")
    public ResponseEntity<ApiResponse<AuthResponse>> reactivate(@Valid @RequestBody ReactivateRequest request) {
        AuthResponse response = authService.reactivate(request);
        return withRefreshCookie(response.refreshToken())
                .body(ApiResponse.ok(response, "Account reactivated"));
    }

    private ResponseEntity.BodyBuilder withRefreshCookie(String rawToken) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(rawToken).toString());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<ForgotPasswordResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            @RequestHeader(value = "Origin", required = false) String origin) {
        return ResponseEntity.ok(ApiResponse.ok(authService.forgotPassword(request, origin)));
    }

    @PostMapping("/reset-password/phone")
    public ResponseEntity<ApiResponse<ResolveResetPasswordPhoneResponse>> resolveResetPasswordPhone(
            @Valid @RequestBody ResolveResetPasswordPhoneRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resolveResetPasswordPhone(request)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resetPassword(request)));
    }

    /**
     * Accepts the refresh token from an {@code HttpOnly} cookie or from the body, cookie first —
     * see {@link RefreshTokenCookie#resolve}. The body is optional rather than {@code @NotBlank}
     * so a browser sending only the cookie is not rejected as a malformed request before the
     * token is ever looked at.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @RequestBody(required = false) RefreshRequest request, HttpServletRequest httpRequest) {

        String token = refreshTokenCookie
                .resolve(httpRequest, request == null ? null : request.refreshToken())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED,
                        "No refresh token supplied, in a cookie or in the request body."));

        RefreshResponse response = authService.refresh(new RefreshRequest(token));

        // Rotation issued a new token, so the cookie has to move with it. Written unconditionally,
        // including for clients that sent a body: a browser only stores this if the request was
        // made with credentials, so it is inert for a client that has not opted in and already
        // correct for one that has.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(response.refreshToken()).toString())
                .body(ApiResponse.ok(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(
            @RequestBody(required = false) LogoutRequest request, HttpServletRequest httpRequest) {

        // Absent is not an error here. Logout is idempotent, and a client whose token has already
        // expired still needs the cookie cleared — failing would leave the credential in place on
        // exactly the request that was trying to get rid of it.
        var token = refreshTokenCookie.resolve(httpRequest, request == null ? null : request.refreshToken());
        LogoutResponse response = token
                .map(t -> authService.logout(new LogoutRequest(t)))
                .orElseGet(() -> new LogoutResponse("Signed out."));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .body(ApiResponse.ok(response));
    }

    // TODO Phase 2: /oauth/google callback endpoint, /2fa/verify endpoint.
    // Both need real provider credentials (Google OAuth client ID/secret, an OTP/TOTP library)
    // that only make sense once you're deploying somewhere real — stubbing them here would just
    // be dead code that looks functional but isn't.
}
