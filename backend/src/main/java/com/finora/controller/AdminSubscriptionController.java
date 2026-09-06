package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.ChangePlanRequest;
import com.finora.dto.BillingDtos.SubscriptionHealthDto;
import com.finora.dto.BillingDtos.SubscriptionSummaryDto;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import com.finora.service.SubscriptionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** D-28 PR4-A. Admin Portal, Subscription Management (proposal §6) -- read access and the manual
 *  plan change gated separately (SUBSCRIPTION_MANAGEMENT_VIEW vs. _MANAGE, V99), matching this
 *  codebase's own established split between viewing platform data and mutating it. */
@RestController
@RequestMapping("/api/v1/admin/subscriptions")
public class AdminSubscriptionController {

    private final SubscriptionService subscriptionService;
    private final CurrentUser currentUser;

    public AdminSubscriptionController(SubscriptionService subscriptionService, CurrentUser currentUser) {
        this.subscriptionService = subscriptionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_VIEW')")
    public ApiResponse<PagedResponse<SubscriptionSummaryDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(subscriptionService.listAll(page, size));
    }

    @GetMapping("/health")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_VIEW')")
    public ApiResponse<SubscriptionHealthDto> health() {
        return ApiResponse.ok(subscriptionService.health());
    }

    @PutMapping("/{userId}/plan")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_MANAGE')")
    public ApiResponse<Void> changePlan(@PathVariable UUID userId, @Valid @RequestBody ChangePlanRequest request) {
        subscriptionService.changePlan(userId, request.planCode(), request.reason(), currentUser.id());
        return ApiResponse.ok(null, "Plan updated");
    }

    @PostMapping("/{userId}/cancel-paid-subscription")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_MANAGE')")
    public ApiResponse<Void> cancelPaidSubscription(@PathVariable UUID userId) {
        subscriptionService.cancelPaidSubscription(userId, currentUser.id());
        return ApiResponse.ok(null, "Paid subscription cancelled");
    }
}
