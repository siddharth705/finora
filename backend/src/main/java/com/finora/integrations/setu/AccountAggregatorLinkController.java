package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/setu/links")
public class AccountAggregatorLinkController {

    private final SetuConsentService consentService;
    private final CurrentUser currentUser;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;

    public AccountAggregatorLinkController(SetuConsentService consentService, CurrentUser currentUser,
                                            AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.consentService = consentService;
        this.currentUser = currentUser;
        this.identityResolutionService = identityResolutionService;
    }

    @PostMapping
    public ResponseEntity<InitiateLinkResponse> initiate(@RequestBody InitiateLinkRequest request) {
        SetuConsentService.InitiateLinkResult result =
                consentService.initiateLink(currentUser.id(), request.fiType(), request.idempotencyKey());
        return ResponseEntity.ok(new InitiateLinkResponse(
                result.link().getId(), result.link().getStatus(), result.redirectUrl()));
    }

    @PostMapping("/{linkId}/confirm-existing-account")
    public ResponseEntity<Void> confirmExistingAccount(@PathVariable UUID linkId,
                                                        @RequestBody ConfirmExistingAccountRequest request) {
        identityResolutionService.confirmExistingAccount(currentUser.id(), linkId, request.accountId());
        return ResponseEntity.ok().build();
    }

    /** The user, shown a PROBABLE match, said "no, this is a different/new account." Bug fix
     *  (found during post-implementation review): the original plan built the service method for
     *  this but never wired an endpoint to it, leaving a user who lands on this screen with no way
     *  to proceed if they don't want the offered candidate. */
    @PostMapping("/{linkId}/confirm-new-account")
    public ResponseEntity<Void> confirmNewAccount(@PathVariable UUID linkId) {
        identityResolutionService.confirmNewAccount(currentUser.id(), linkId);
        return ResponseEntity.ok().build();
    }

    public record InitiateLinkRequest(FiType fiType, String idempotencyKey) {}

    public record InitiateLinkResponse(UUID linkId, AccountAggregatorLinkStatus status, String redirectUrl) {}

    public record ConfirmExistingAccountRequest(UUID accountId) {}
}
