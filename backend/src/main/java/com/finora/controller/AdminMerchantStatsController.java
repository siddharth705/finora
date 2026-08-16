package com.finora.controller;

import com.finora.dto.AdminDtos.GmailMerchantParserStatDto;
import com.finora.dto.AdminDtos.MerchantStatDto;
import com.finora.dto.ApiResponse;
import com.finora.integrations.google.merchant.GmailMerchantStatsService;
import com.finora.service.AdminMerchantStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Platform-wide merchant catalog for the admin Merchant Intelligence page (admin-portal/), plus
 *  (C6.2) that same page's Gmail parser-health section -- a distinct dataset (email domains and
 *  extraction outcomes, not the categorization catalog {@link #stats} serves), kept on this
 *  controller because it's the same admin page and the same {@code MERCHANT_MANAGE} audience, not
 *  because the two are the same query. See AdminMerchantStatsService / GmailMerchantStatsService
 *  for what's actually computed. Per-user merchant management (rename/merge/audit on a specific
 *  account's merchants) is a separate controller, AdminUserMerchantController -- this one is
 *  read-only and never scoped to one user. */
@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminMerchantStatsController {

    private final AdminMerchantStatsService adminMerchantStatsService;
    private final GmailMerchantStatsService gmailMerchantStatsService;

    public AdminMerchantStatsController(AdminMerchantStatsService adminMerchantStatsService,
                                         GmailMerchantStatsService gmailMerchantStatsService) {
        this.adminMerchantStatsService = adminMerchantStatsService;
        this.gmailMerchantStatsService = gmailMerchantStatsService;
    }

    @GetMapping("/stats")
    public ApiResponse<List<MerchantStatDto>> stats() {
        return ApiResponse.ok(adminMerchantStatsService.platformCatalog());
    }

    /**
     * Gmail parser health by merchant domain, since {@code since} -- C6.2. No default window, the
     * same "an unbounded scan is a cost this endpoint should never silently absorb" reasoning as
     * {@code AdminStatementAnalysisController#failureSummary}: whatever the caller means by "since"
     * (last 30 days, since a parser release), they have to say so.
     */
    @GetMapping("/gmail-parser-stats")
    public ApiResponse<List<GmailMerchantParserStatDto>> gmailParserStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return ApiResponse.ok(gmailMerchantStatsService.parserStats(since));
    }
}
