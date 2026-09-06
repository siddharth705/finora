package com.finora.onboarding;

import java.util.List;

public class OnboardingDto {

    public record StatusResponse(boolean onboardingCompleted, List<String> financialFocus) {}

    public record FinancialFocusRequest(List<String> focusKeys) {}

    public record ChecklistItemDto(String key, boolean completed) {}

    public record ChecklistResponse(List<ChecklistItemDto> items, int completedCount, int totalCount) {}
}
