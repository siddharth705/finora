package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.BillingHistoryEntryDto;
import com.finora.security.CurrentUser;
import com.finora.service.BillingHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** D-28 PR4-B. The user's own billing history (proposal §3.4) -- empty today, same reason
 *  BillingHistoryService returns an empty list for everyone (no payment gateway yet, §10). */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingHistoryController {

    private final BillingHistoryService billingHistoryService;
    private final CurrentUser currentUser;

    public BillingHistoryController(BillingHistoryService billingHistoryService, CurrentUser currentUser) {
        this.billingHistoryService = billingHistoryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/history")
    public ApiResponse<List<BillingHistoryEntryDto>> history() {
        return ApiResponse.ok(billingHistoryService.history(currentUser.id()));
    }
}
