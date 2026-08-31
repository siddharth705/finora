package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.EntitlementsDto;
import com.finora.security.CurrentUser;
import com.finora.service.EntitlementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** D-28 PR4-A. What {@code PremiumFeatureGate} (frontend) reads to decide whether to show or gate
 *  a feature -- the current user's own plan and entitlement map. */
@RestController
@RequestMapping("/api/v1/entitlements")
public class EntitlementController {

    private final EntitlementService entitlementService;
    private final CurrentUser currentUser;

    public EntitlementController(EntitlementService entitlementService, CurrentUser currentUser) {
        this.entitlementService = entitlementService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<EntitlementsDto> mine() {
        return ApiResponse.ok(entitlementService.entitlementsFor(currentUser.id()));
    }
}
