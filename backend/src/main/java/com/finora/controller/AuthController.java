package com.finora.controller;

import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletRequest;
import com.finora.security.RefreshTokenCookie;
import com.finora.exception.ErrorCode;
import com.finora.exception.ApiException;
import com.finora.dto.ApiResponse;
import com.finora.dto.AuthDtos.*;
import com.finora.integrations.apple.login.AppleIdTokenVerifierService;
import com.finora.integrations.google.login.GoogleIdTokenVerifierService;
import com.finora.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private final AppleIdTokenVerifierService appleIdTokenVerifierService;

    public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie,
                           GoogleIdTokenVerifierService googleIdTokenVerifierService,
                           AppleIdTokenVerifierService appleIdTokenVerifierService) {
        this.refreshTokenCookie = refreshTokenCookie;
        this.authService = authService;
        this.googleIdTokenVerifierService = googleIdTokenVerifierService;
        this.appleIdTokenVerifierService = appleIdTokenVerifierService;
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

    /**
     * Identifier-first entry step (auth/security review §2.2,
     * docs/proposals/authentication-account-security-review.md) -- fronts login()/register()
     * without replacing them: the client calls this first with just an email or phone, and shows
     * the right next step (password field, "Continue with Google", "Continue with Apple", or
     * signup) based on {@code nextAction}. Rate-limited more tightly than login() itself (see
     * RateLimitFilter) since it is unauthenticated and, unlike login, costs nothing per call to
     * probe.
     */
    @PostMapping("/identify")
    public ResponseEntity<ApiResponse<IdentifyResponse>> identify(@Valid @RequestBody IdentifyRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.identify(request), "Identified"));
    }

    /**
     * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Second step of
     * the flow login() starts by throwing AUTH_MFA_REQUIRED (challenge token in its details map)
     * -- same AuthResponse shape and refresh-cookie handling as login()/reactivate() themselves,
     * so the client's success path doesn't need to special-case where the tokens came from.
     */
    @PostMapping("/mfa/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        AuthResponse response = authService.completeMfaLogin(request.challengeToken(), request.code());
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

    /** D-23. Confirms a {@code /verify-email?token=...} link -- see AuthService.verifyEmail. Not
     *  authenticated: the token itself is the proof, the same posture reset-password/reactivate
     *  already have for their own emailed/returned links. */
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<VerifyEmailResponse>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.verifyEmail(request.token())));
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

    /**
     * D-23. Not an OAuth callback -- Google Identity Services (web) hands the frontend a signed ID
     * token directly, no redirect round trip, so this endpoint just verifies and trusts it. Same
     * shape as register()/login(): mints tokens and writes the refresh cookie, since a successful
     * Google sign-in is exactly as much of "a session starting" as either of those.
     *
     * <p>Serves both new-account creation and returning-user sign-in through the one endpoint --
     * {@code AuthService.loginWithGoogle} decides which happened by whether the verified email
     * already has a Finora account, not something the client declares.
     */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> google(@Valid @RequestBody GoogleAuthRequest request) {
        var identity = googleIdTokenVerifierService.verify(request.idToken());
        AuthResponse response = authService.loginWithGoogle(identity);
        return withRefreshCookie(response.refreshToken())
                .body(ApiResponse.ok(response, "Signed in with Google"));
    }

    /**
     * D-23 Phase 2. Same shape as {@link #google}, Apple's counterpart -- native
     * {@code AuthenticationServices} (via {@code expo-apple-authentication}) hands the client a
     * signed identity token directly, this endpoint verifies and trusts it. {@code fullName} is
     * forwarded straight through to {@code AuthService.loginWithApple} entirely unvalidated at
     * this layer -- see {@code AppleAuthRequest}'s own doc comment for why it's optional, where it
     * actually comes from, and why a DTO-level format check would be actively wrong here.
     */
    @PostMapping("/apple")
    public ResponseEntity<ApiResponse<AuthResponse>> apple(@Valid @RequestBody AppleAuthRequest request) {
        var identity = appleIdTokenVerifierService.verify(request.idToken());
        AuthResponse response = authService.loginWithApple(identity, request.fullName());
        return withRefreshCookie(response.refreshToken())
                .body(ApiResponse.ok(response, "Signed in with Apple"));
    }

    // TODO: /2fa/verify needs an OTP/TOTP library that only makes sense once there's a real
    // deployment to protect; stubbing it here would just be dead code that looks functional but
    // isn't. (The other half of the old Phase 2 TODO here -- native mobile Google Sign-In reusing
    // /google, plus Apple's new /apple above -- is this endpoint list; see D-23/D-26.)
}
