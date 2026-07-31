package com.finora.controller;

import com.finora.dto.AdminDtos.MerchantStatDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminMerchantStatsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Platform-wide merchant catalog for the admin Merchant Intelligence page (frontend-admin/).
 *  See AdminMerchantStatsService for what's actually computed. Per-user merchant management
 *  (rename/merge/audit on a specific account's merchants) is a separate controller,
 *  AdminUserMerchantController -- this one is read-only and never scoped to one user. */
@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminMerchantStatsController {

    private final AdminMerchantStatsService adminMerchantStatsService;

    public AdminMerchantStatsController(AdminMerchantStatsService adminMerchantStatsService) {
        this.adminMerchantStatsService = adminMerchantStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<List<MerchantStatDto>> stats() {
        return ApiResponse.ok(adminMerchantStatsService.platformCatalog());
    }
}
