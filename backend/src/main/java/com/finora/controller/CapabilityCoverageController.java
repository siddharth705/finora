package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.imports.CapabilityCoverageService;
import com.finora.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 4's numbers, exposed.
 *
 * Two endpoints because there are two genuinely different questions, and one of them is not an
 * admin question. "Which capabilities did MY imports use, and what did they fail to read" is the
 * caller's own data about their own documents; "which capabilities has the engine never
 * successfully exercised on any real document" is an engineering metric about the product, and
 * belongs behind the admin portal's permission model rather than in the user app.
 *
 * Both return counts and nothing else. Per the engineering principles doc's own sequencing --
 * collect, store, validate, dashboard, then decide -- these numbers have to be checked against
 * known cases before anything acts on them, so there is deliberately no threshold, no score and
 * no auto-review decision here.
 */
@RestController
@RequestMapping("/api/v1")
public class CapabilityCoverageController {

    private final CapabilityCoverageService coverageService;
    private final CurrentUser currentUser;

    public CapabilityCoverageController(CapabilityCoverageService coverageService, CurrentUser currentUser) {
        this.coverageService = coverageService;
        this.currentUser = currentUser;
    }

    /** The caller's own import coverage -- their documents, their unreadable rows. */
    @GetMapping("/imports/capability-coverage")
    public ApiResponse<CapabilityCoverageService.CoverageMap> mine() {
        return ApiResponse.ok(coverageService.forUser(currentUser.id()));
    }

    /**
     * One user's coverage, for support and for engine debugging.
     *
     * Gated on AUDIT_VIEW rather than a role, matching the permission-based pattern the rest of the
     * admin surface uses. Reading someone else's import diagnostics is the same class of action as
     * reading their audit trail, so it takes the same permission.
     */
    @GetMapping("/admin/users/{userId}/capability-coverage")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public ApiResponse<CapabilityCoverageService.CoverageMap> forUser(@PathVariable UUID userId) {
        return ApiResponse.ok(coverageService.forUser(userId));
    }
}
