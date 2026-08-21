package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReferralDtos.MyReferralCodeDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.security.CurrentUser;
import com.finora.service.ReferralService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** D-28 PR4-C. The current user's own referral code, referrals, and wallet balance
 *  (proposal §4). */
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService referralService;
    private final CurrentUser currentUser;

    public ReferralController(ReferralService referralService, CurrentUser currentUser) {
        this.referralService = referralService;
        this.currentUser = currentUser;
    }

    @GetMapping("/my-code")
    public ApiResponse<MyReferralCodeDto> myCode() {
        return ApiResponse.ok(new MyReferralCodeDto(referralService.myCode(currentUser.id())));
    }

    @GetMapping("/mine")
    public ApiResponse<MyReferralsDto> mine() {
        return ApiResponse.ok(referralService.myReferrals(currentUser.id()));
    }
}
