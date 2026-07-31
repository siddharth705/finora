package com.finora.controller;

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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.register(request), "Account created"));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request), "Signed in"));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<ForgotPasswordResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            @RequestHeader(value = "Origin", required = false) String origin) {
        return ResponseEntity.ok(ApiResponse.ok(authService.forgotPassword(request, origin)));
    }

    @PostMapping("/reset-password/request-otp")
    public ResponseEntity<ApiResponse<RequestPasswordResetOtpResponse>> requestPasswordResetOtp(
            @Valid @RequestBody RequestPasswordResetOtpRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.requestPasswordResetOtp(request)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<ResetPasswordResponse>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.resetPassword(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(@Valid @RequestBody LogoutRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.logout(request)));
    }

    // TODO Phase 2: /oauth/google callback endpoint, /2fa/verify endpoint.
    // Both need real provider credentials (Google OAuth client ID/secret, an OTP/TOTP library)
    // that only make sense once you're deploying somewhere real — stubbing them here would just
    // be dead code that looks functional but isn't.
}
