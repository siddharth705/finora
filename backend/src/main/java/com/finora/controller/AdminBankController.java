package com.finora.controller;

import com.finora.accounts.AccountDto.BankDto;
import com.finora.dto.ApiResponse;
import com.finora.dto.AuditLogDto;
import com.finora.repository.AuditLogRepository;
import com.finora.security.CurrentUser;
import com.finora.service.BankManagementService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin CRUD for custom (non-built-in) banks -- see V26__custom_banks.sql and
 * BankManagementService's class comment for why this is additive to BankRegistry rather than a
 * replacement for it. Gated by BANK_MANAGE, seeded onto ADMIN/SUPER_ADMIN back in
 * V16__rbac_roles_permissions.sql but unused by any endpoint until now.
 *
 * GET /{id}/audit (Admin Portal Phase 4) backs this page's EntityDrawer Audit tab -- the
 * reference implementation of the drawer pattern (see frontend-admin's EntityDrawer.tsx class
 * comment for how to extend it to other entities). Reads AuditLogRepository directly rather than
 * routing through BankManagementService, same "thin controller, no service needed for a single
 * read-only passthrough query" choice ActivityController already makes for its own audit feed.
 */
@RestController
@RequestMapping("/api/v1/admin/banks")
@PreAuthorize("hasAuthority('BANK_MANAGE')")
public class AdminBankController {

    private final BankManagementService bankManagementService;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUser currentUser;

    public AdminBankController(BankManagementService bankManagementService, AuditLogRepository auditLogRepository,
                                CurrentUser currentUser) {
        this.bankManagementService = bankManagementService;
        this.auditLogRepository = auditLogRepository;
        this.currentUser = currentUser;
    }

    /** Custom banks only (not the built-in ~40) -- this is the management view, not the picker
     *  BankController's public listing serves. */
    @GetMapping
    public ApiResponse<List<BankDto>> list() {
        return ApiResponse.ok(bankManagementService.listCustom().stream().map(BankDto::fromCustom).toList());
    }

    @PostMapping
    public ApiResponse<BankDto> create(@Valid @RequestBody BankDto.CreateRequest request) {
        return ApiResponse.ok(BankDto.fromCustom(bankManagementService.createCustom(currentUser.id(), request)), "Bank added");
    }

    @PutMapping("/{id}")
    public ApiResponse<BankDto> update(@PathVariable String id, @RequestBody BankDto.UpdateRequest request) {
        return ApiResponse.ok(BankDto.fromCustom(bankManagementService.updateCustom(currentUser.id(), id, request)), "Bank updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        bankManagementService.deleteCustom(currentUser.id(), id);
        return ApiResponse.ok(null, "Bank deleted");
    }

    /** See AuditLogRepository.findByBankIdInMetadata's doc comment for why this is a native query
     *  keyed off metadata rather than entityId. Returns an empty list for a bank with no recorded
     *  history (a built-in bank id, or a custom bank id that predates this endpoint), not a 404 --
     *  "no audit trail yet" is a normal state for a drawer tab, not an error. */
    @GetMapping("/{id}/audit")
    public ApiResponse<List<AuditLogDto>> audit(@PathVariable String id) {
        var logs = auditLogRepository.findByBankIdInMetadata(id).stream()
                .map(l -> new AuditLogDto(l.getId(), l.getUserId(), l.getAction(), l.getEntityType(),
                        l.getEntityId(), l.getMetadata(), l.getRequestId(), l.getCreatedAt()))
                .toList();
        return ApiResponse.ok(logs);
    }
}
