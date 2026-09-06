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

    @PostMapping("/complete")
    public ApiResponse<Void> complete() {
        onboardingService.complete(currentUser.id());
        return ApiResponse.ok(null, "Onboarding complete");
    }

    @PostMapping("/reset")
    public ApiResponse<Void> reset() {
        onboardingService.reset(currentUser.id());
        return ApiResponse.ok(null, "Onboarding reset");
    }

    @GetMapping("/checklist")
    public ApiResponse<OnboardingDto.ChecklistResponse> checklist() {
        return ApiResponse.ok(onboardingService.getChecklist(currentUser.id()));
    }

    @PostMapping("/checklist/{itemKey}/complete")
    public ApiResponse<Void> completeChecklistItem(@PathVariable String itemKey) {
        onboardingService.completeChecklistItem(currentUser.id(), itemKey);
        return ApiResponse.ok(null, "Checklist item completed");
    }
}
