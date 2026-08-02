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
 * and this endpoint needs a real authenticated user (CurrentUser.id()) since it acts on "the
 * current user's phone number," not an anonymous request. Living under /api/v1/phone instead
 * means it correctly falls under the default authenticated() rule.
 *
 * Just one endpoint now -- there's no backend-triggered "send" step anymore (Firebase's own
 * client SDK sends the OTP directly; the frontend already knows the account's real phone number
 * from GET /users/me), only verifying the Firebase ID token that results from it.
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

    @PostMapping("/verify")
    public ApiResponse<VerifyPhoneResponse> verify(@Valid @RequestBody VerifyPhoneRequest request) {
        return ApiResponse.ok(authService.verifyPhoneWithFirebase(currentUser.id(), request.firebaseIdToken()));
    }
}
