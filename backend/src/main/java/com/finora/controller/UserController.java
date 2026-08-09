package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MeAccessDto;
import com.finora.dto.PasswordChangeDtos.*;
import com.finora.dto.UserSettingsDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.CurrentUser;
import com.finora.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;
import com.finora.service.AuthorizationService;
import com.finora.service.PasswordChangeService;
import com.finora.service.UserSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserSettingsService userSettingsService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final PasswordChangeService passwordChangeService;

    public UserController(UserSettingsService userSettingsService, CurrentUser currentUser,
                           UserRepository userRepository, AuthorizationService authorizationService,
                           PasswordChangeService passwordChangeService) {
        this.userSettingsService = userSettingsService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.passwordChangeService = passwordChangeService;
    }

    @GetMapping
    public ApiResponse<UserSettingsDto> get() {
        return ApiResponse.ok(userSettingsService.get(currentUser.id()));
    }

    @PutMapping
    public ApiResponse<UserSettingsDto> update(@Valid @RequestBody UserSettingsDto.UpdateRequest request) {
        return ApiResponse.ok(userSettingsService.update(currentUser.id(), request), "Preferences saved");
    }

    /**
     * The authenticated, OTP-gated Change Password flow -- see PasswordChangeService's own doc
     * comment for the full start -> verify-otp -> complete state machine these three back.
     */
    @PostMapping("/password-change/start")
    public ApiResponse<StartResponse> startPasswordChange(@Valid @RequestBody StartRequest request) {
        return ApiResponse.ok(passwordChangeService.start(currentUser.id(), request));
    }

    @PostMapping("/password-change/verify-otp")
    public ApiResponse<VerifyOtpResponse> verifyPasswordChangeOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ApiResponse.ok(passwordChangeService.verifyOtp(currentUser.id(), request));
    }

    /**
     * Which session is asking comes from the access token's {@code sid} claim, published as a
     * request attribute by {@code JwtAuthFilter} -- not from a refresh token in the body. BH-012
     * removed the web client's ability to read its own refresh token (it is HttpOnly now), and the
     * cookie is path-scoped to {@code /api/v1/auth} so it never reaches here. The sid was always
     * the more accurate answer anyway: it survives rotation, and a token does not.
     */
    @PostMapping("/password-change/complete")
    public ApiResponse<CompleteResponse> completePasswordChange(
            @Valid @RequestBody CompleteRequest request, HttpServletRequest httpRequest) {
        UUID currentSessionId = (UUID) httpRequest.getAttribute(JwtAuthFilter.SESSION_ID_ATTRIBUTE);
        return ApiResponse.ok(passwordChangeService.complete(currentUser.id(), request, currentSessionId));
    }

    /**
     * The caller's own effective roles + permissions. Any authenticated user can read their own
     * access (there's no permission gate here beyond "you have a valid token") -- the sensitive
     * operations those permissions unlock are each gated individually on the endpoints that
     * perform them. This exists specifically so the admin portal (frontend-admin/) can ask "does
     * this account have any admin-relevant access" right after login, before showing the admin
     * shell at all -- see AuthorizationService.meAccess.
     */
    @GetMapping("/access")
    public ApiResponse<MeAccessDto> access() {
        User user = userRepository.findById(currentUser.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return ApiResponse.ok(authorizationService.meAccess(user));
    }
}
