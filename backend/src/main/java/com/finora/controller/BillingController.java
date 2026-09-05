package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.CheckoutRequest;
import com.finora.dto.BillingDtos.CheckoutResponseDto;
import com.finora.security.CurrentUser;
import com.finora.service.BillingCheckoutService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscription billing V1 (design spec §7). User-initiated billing actions -- distinct from
 * {@code AdminSubscriptionController} (admin-initiated) and {@code BillingHistoryController}
 * (read-only history), matching this codebase's existing view/manage/history separation.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingCheckoutService billingCheckoutService;
    private final CurrentUser currentUser;

    public BillingController(BillingCheckoutService billingCheckoutService, CurrentUser currentUser) {
        this.billingCheckoutService = billingCheckoutService;
        this.currentUser = currentUser;
    }

    @PostMapping("/checkout")
    public ApiResponse<CheckoutResponseDto> checkout(@Valid @RequestBody CheckoutRequest request) {
        return ApiResponse.ok(billingCheckoutService.checkout(currentUser.id(), request.planCode(), request.billingCycle()));
    }

    @PostMapping("/cancel")
    public ApiResponse<Void> cancel() {
        billingCheckoutService.cancel(currentUser.id());
        return ApiResponse.ok(null, "Cancelled");
    }
}
