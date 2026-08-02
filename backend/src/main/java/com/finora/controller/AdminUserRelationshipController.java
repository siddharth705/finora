package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.RelationshipDto;
import com.finora.service.RelationshipService;
import com.finora.transactions.TransactionDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Support-assisted relationship (family/friend/own-account) tagging for a specific user
 * (RELATIONSHIP_MANAGE, V47__relationship_manage_permission.sql). Reuses
 * RelationshipService.listForUser/create/update/merge/transactionsFor/delete exactly as the
 * self-service endpoint did -- same RelationshipDto shape, same USER-scope validation -- just
 * with the target userId sourced from the path instead of CurrentUser. Same thin-proxy pattern as
 * AdminUserRuleController/AdminUserMerchantController.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/relationships")
@PreAuthorize("hasAuthority('RELATIONSHIP_MANAGE')")
public class AdminUserRelationshipController {

    private final RelationshipService relationshipService;

    public AdminUserRelationshipController(RelationshipService relationshipService) {
        this.relationshipService = relationshipService;
    }

    @GetMapping
    public ApiResponse<List<RelationshipDto>> list(@PathVariable UUID userId) {
        return ApiResponse.ok(relationshipService.listForUser(userId));
    }

    @PostMapping
    public ApiResponse<RelationshipDto> create(@PathVariable UUID userId, @Valid @RequestBody RelationshipDto.CreateRequest request) {
        return ApiResponse.ok(relationshipService.create(userId, request), "Relationship created");
    }

    @PutMapping("/{id}")
    public ApiResponse<RelationshipDto> update(@PathVariable UUID userId, @PathVariable UUID id,
                                                @Valid @RequestBody RelationshipDto.UpdateRequest request) {
        return ApiResponse.ok(relationshipService.update(userId, id, request), "Relationship updated");
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<RelationshipDto> merge(@PathVariable UUID userId, @PathVariable UUID id,
                                               @Valid @RequestBody RelationshipDto.MergeRequest request) {
        return ApiResponse.ok(relationshipService.merge(userId, id, request.mergeFromRelationshipId()), "Relationships merged");
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<TransactionDto>> transactions(@PathVariable UUID userId, @PathVariable UUID id) {
        return ApiResponse.ok(relationshipService.transactionsFor(userId, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID userId, @PathVariable UUID id) {
        relationshipService.delete(userId, id);
        return ApiResponse.ok(null, "Relationship deleted");
    }
}
