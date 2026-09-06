package com.finora.onboarding;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final CurrentUser currentUser;

    public OnboardingController(OnboardingService onboardingService, CurrentUser currentUser) {
        this.onboardingService = onboardingService;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    public ApiResponse<OnboardingDto.StatusResponse> status() {
        return ApiResponse.ok(onboardingService.getStatus(currentUser.id()));
    }

    @PostMapping("/financial-focus")
    public ApiResponse<OnboardingDto.StatusResponse> setFinancialFocus(
            @RequestBody OnboardingDto.FinancialFocusRequest request) {
        return ApiResponse.ok(onboardingService.setFinancialFocus(currentUser.id(), request.focusKeys()),
                "Financial focus saved");
    }
}
