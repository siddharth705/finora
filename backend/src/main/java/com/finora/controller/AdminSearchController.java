package com.finora.controller;

import com.finora.dto.AdminDtos.SearchResultDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Global Search (Admin Portal Phase 2) -- GET /api/v1/admin/search?q= fans out across every
 * admin entity with a real destination page today. See AdminSearchService's class comment for
 * exactly what's in scope and what's deliberately excluded.
 *
 * Deliberately no @PreAuthorize beyond authentication -- SecurityConfig's anyRequest().
 * authenticated() rule already requires a valid admin-portal session (a real JWT) to reach this
 * endpoint at all, and every result here is informational only (ids, names, a route to navigate
 * to), never a financial amount or anything more sensitive than what UserSummaryDto already
 * exposes to any signed-in admin on the Users page. That's a different bar than
 * PLATFORM_ANALYTICS_VIEW/RECONCILIATION_VIEW's own gates, which exist because THOSE endpoints
 * expose real spend figures and per-user financial detail -- this one doesn't, so it doesn't need
 * its own narrower permission.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
public class AdminSearchController {

    private final AdminSearchService adminSearchService;

    public AdminSearchController(AdminSearchService adminSearchService) {
        this.adminSearchService = adminSearchService;
    }

    @GetMapping
    public ApiResponse<List<SearchResultDto>> search(@RequestParam(required = false) String q) {
        return ApiResponse.ok(adminSearchService.search(q));
    }
}
