package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReconciliationExplorerDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.service.ReconciliationExplorerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * One transaction, traced from raw to final classification -- the Reconciliation Explorer,
 * Phase 2's Founder Operations Dashboard (docs/proposals/reconciliation-evolution-roadmap-
 * proposal.md, Part 9). {@code RECONCILIATION_VIEW}, the same permission {@link
 * AdminReconciliationStatsController} already gates behind -- this is the per-transaction
 * drill-down that controller's own class comment says explicitly does not exist there.
 */
@RestController
@RequestMapping("/api/v1/admin/reconciliation/explorer")
@PreAuthorize("hasAuthority('RECONCILIATION_VIEW')")
public class AdminReconciliationExplorerController {

    private final ReconciliationExplorerService reconciliationExplorerService;

    public AdminReconciliationExplorerController(ReconciliationExplorerService reconciliationExplorerService) {
        this.reconciliationExplorerService = reconciliationExplorerService;
    }

    @GetMapping("/{transactionId}")
    public ApiResponse<ReconciliationExplorerDto.Trace> trace(@PathVariable UUID transactionId) {
        return ApiResponse.ok(reconciliationExplorerService.trace(transactionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
}
