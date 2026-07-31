package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.AuthDtos.*;
import com.finora.security.CurrentUser;
import com.finora.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately NOT under /api/v1/auth/** — that prefix is entirely permitAll in SecurityConfig,
 * and these two endpoints need a real authenticated user (CurrentUser.id()) since they act on
 * "the current user's phone number," not an anonymous request. Living under /api/v1/phone
 * instead means they correctly fall under the default authenticated() rule.
 */
@RestController
@RequestMapping("/api/v1/phone")
public class PhoneController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public PhoneController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/send-otp")
    public ApiResponse<SendOtpResponse> sendOtp() {
        return ApiResponse.ok(authService.sendPhoneOtp(currentUser.id()));
    }

    @PostMapping("/verify-otp")
    public ApiResponse<VerifyOtpResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ApiResponse.ok(authService.verifyPhoneOtp(currentUser.id(), request.otp()));
    }
}
