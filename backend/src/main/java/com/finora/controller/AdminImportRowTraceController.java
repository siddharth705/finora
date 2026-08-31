package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ImportRowTraceDto;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.trace.ImportRowTraceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * One import, row by row -- the Import Row Trace, Founder Operations Dashboard (docs/proposals/
 * reconciliation-evolution-roadmap-proposal.md Part 9). Same {@code PLATFORM_DIAGNOSTICS_VIEW}
 * permission as {@link AdminImportTraceController}, its sibling: same operator surface, a
 * different query shape (per-row rather than per-import), which is why this is its own
 * controller/service/DTO rather than a field on {@code ImportTraceDto.Trace} -- see {@code
 * ImportRowTraceService}'s own doc comment.
 */
@RestController
@RequestMapping("/api/v1/admin/imports/row-trace")
@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")
public class AdminImportRowTraceController {

    private final ImportRowTraceService importRowTraceService;

    public AdminImportRowTraceController(ImportRowTraceService importRowTraceService) {
        this.importRowTraceService = importRowTraceService;
    }

    @GetMapping("/{statementImportId}")
    public ApiResponse<ImportRowTraceDto.Trace> trace(@PathVariable UUID statementImportId) {
        return ApiResponse.ok(importRowTraceService.trace(statementImportId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)));
    }
}
