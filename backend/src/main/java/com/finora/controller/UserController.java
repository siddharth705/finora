package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MeAccessDto;
import com.finora.dto.UserSettingsDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.security.CurrentUser;
import com.finora.service.AuthorizationService;
import com.finora.service.UserSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserSettingsService userSettingsService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;

    public UserController(UserSettingsService userSettingsService, CurrentUser currentUser,
                           UserRepository userRepository, AuthorizationService authorizationService) {
        this.userSettingsService = userSettingsService;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ApiResponse<UserSettingsDto> get() {
        return ApiResponse.ok(userSettingsService.get(currentUser.id()));
    }

    @PutMapping
    public ApiResponse<UserSettingsDto> update(@RequestBody UserSettingsDto.UpdateRequest request) {
        return ApiResponse.ok(userSettingsService.update(currentUser.id(), request), "Preferences saved");
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
