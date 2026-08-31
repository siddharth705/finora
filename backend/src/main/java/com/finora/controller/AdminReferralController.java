package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.CreditReferralRewardRequest;
import com.finora.security.CurrentUser;
import com.finora.service.ReferralService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** D-28 PR4-C. Admin Portal, Referral dashboard (proposal §6) -- read access and reward crediting
 *  gated separately (REFERRAL_MANAGEMENT_VIEW vs. _MANAGE, V101), matching PR4-A's own
 *  Subscription Management split. */
@RestController
@RequestMapping("/api/v1/admin/referrals")
public class AdminReferralController {

    private final ReferralService referralService;
    private final CurrentUser currentUser;

    public AdminReferralController(ReferralService referralService, CurrentUser currentUser) {
        this.referralService = referralService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('REFERRAL_MANAGEMENT_VIEW')")
    public ApiResponse<PagedResponse<AdminReferralSummaryDto>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(referralService.listAll(page, size));
    }

    @PostMapping("/{referralId}/credit")
    @PreAuthorize("hasAuthority('REFERRAL_MANAGEMENT_MANAGE')")
    public ApiResponse<Void> creditReward(@PathVariable UUID referralId, @Valid @RequestBody CreditReferralRewardRequest request) {
        referralService.creditReward(referralId, request.amount(), request.reason(), currentUser.id());
        return ApiResponse.ok(null, "Reward credited");
    }
}
