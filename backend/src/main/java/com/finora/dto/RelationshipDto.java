package com.finora.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record RelationshipDto(
        UUID id, String label, String relationshipType, UUID linkedAccountId, List<IdentifierDto> identifiers
) {
    public record IdentifierDto(UUID id, String identifierType, String identifierValue) {}

    public record IdentifierRequest(
            @NotNull(message = "Identifier type is required") String identifierType,
            @NotBlank(message = "Identifier value is required") String identifierValue
    ) {}

    /** linkedAccountId is required (and validated as owned by the caller) when
     *  relationshipType is OWN_ACCOUNT -- see RelationshipService.create().
     *
     *  identifiers is both @NotEmpty AND @Valid -- @NotEmpty alone only checks the list itself
     *  isn't empty; without @Valid, Bean Validation does NOT cascade into each IdentifierRequest
     *  element, so a request with one blank/missing-type identifier byte would sail past
     *  validation and reach RelationshipService.create(), where parseIdentifierType(null) throws
     *  a raw NullPointerException (surfaced as an unhelpful 500) instead of a clean 400. */
    public record CreateRequest(
            @NotBlank(message = "Label is required") String label,
            @NotNull(message = "Relationship type is required") String relationshipType,
            UUID linkedAccountId,
            @NotEmpty(message = "At least one identifier is required") @Valid List<IdentifierRequest> identifiers
    ) {}

    /** Financial Intelligence Workspace, Module 4 (Relationship Management) -- every field
     *  optional, same partial-update convention as MerchantDto.UpdateRequest/TransactionDto.
     *  UpdateRequest: only supplied ones change. `identifiers`, when supplied (non-null), REPLACES
     *  the relationship's entire identifier list rather than appending to it -- there's no
     *  natural "which one did you mean" way to patch a single identifier out of a list by value,
     *  so a full replace (same shape create() already writes) is the one unambiguous contract.
     *  Supply the existing identifiers unchanged if the caller only means to edit the label. */
    public record UpdateRequest(
            String label, String relationshipType, UUID linkedAccountId,
            @Valid List<IdentifierRequest> identifiers
    ) {}

    public record MergeRequest(@NotNull(message = "mergeFromRelationshipId is required") UUID mergeFromRelationshipId) {}
}
