package com.finora.controller;

import com.finora.dto.AdminDtos.SearchResultDto;
import com.finora.dto.ApiResponse;
import com.finora.service.AdminSearchService;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Bug fix: this used to have NO @PreAuthorize at all, on the theory that SecurityConfig's
 * anyRequest().authenticated() rule already required "a real admin-portal session" to reach it.
 * That's not what that rule actually enforces -- SecurityConfig has no admin-specific path
 * authorization at all; anyRequest().authenticated() is satisfied by ANY valid JWT, including an
 * ordinary end user's own consumer-app login. Authorization for every other admin endpoint is
 * entirely delegated to per-controller @PreAuthorize -- this was the one controller that skipped
 * it, letting any authenticated non-admin user call this and get back other users' full name and
 * email (see AdminSearchService.searchUsers) -- a real PII leak, not the "informational only, no
 * more sensitive than the Users page already exposes to a signed-in admin" this class's own
 * previous comment claimed, since the Users page is itself gated behind USER_VIEW, not open to
 * "any signed-in admin," let alone a non-admin. Gated on that same USER_VIEW authority now, since
 * that's precisely the comparison this class's own reasoning already relied on.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
public class AdminSearchController {

    private final AdminSearchService adminSearchService;

    public AdminSearchController(AdminSearchService adminSearchService) {
        this.adminSearchService = adminSearchService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public ApiResponse<List<SearchResultDto>> search(@RequestParam(required = false) String q) {
        return ApiResponse.ok(adminSearchService.search(q));
    }
}
