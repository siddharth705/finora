package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.RelationshipDto;
import com.finora.transactions.TransactionDto;
import com.finora.security.CurrentUser;
import com.finora.service.RelationshipService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Family/friend/own-account tagging (docs/rule-engine-relationship-engine-eds.md §2, §3.3).
 *  Thin by convention -- see RuleController/BudgetController. update/merge/transactions are the
 *  Financial Intelligence Workspace's Relationship Management additions (Module 4) -- see
 *  RelationshipService's own doc comment. */
@RestController
@RequestMapping("/api/v1/relationships")
public class RelationshipController {

    private final RelationshipService relationshipService;
    private final CurrentUser currentUser;

    public RelationshipController(RelationshipService relationshipService, CurrentUser currentUser) {
        this.relationshipService = relationshipService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<RelationshipDto>> list() {
        return ApiResponse.ok(relationshipService.listForUser(currentUser.id()));
    }

    @PostMapping
    public ApiResponse<RelationshipDto> create(@Valid @RequestBody RelationshipDto.CreateRequest request) {
        return ApiResponse.ok(relationshipService.create(currentUser.id(), request), "Relationship created");
    }

    @PutMapping("/{id}")
    public ApiResponse<RelationshipDto> update(@PathVariable UUID id, @Valid @RequestBody RelationshipDto.UpdateRequest request) {
        return ApiResponse.ok(relationshipService.update(currentUser.id(), id, request), "Relationship updated");
    }

    @PostMapping("/{id}/merge")
    public ApiResponse<RelationshipDto> merge(@PathVariable UUID id, @Valid @RequestBody RelationshipDto.MergeRequest request) {
        return ApiResponse.ok(relationshipService.merge(currentUser.id(), id, request.mergeFromRelationshipId()), "Relationships merged");
    }

    @GetMapping("/{id}/transactions")
    public ApiResponse<List<TransactionDto>> transactions(@PathVariable UUID id) {
        return ApiResponse.ok(relationshipService.transactionsFor(currentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        relationshipService.delete(currentUser.id(), id);
        return ApiResponse.ok(null, "Relationship deleted");
    }
}
