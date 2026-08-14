package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.integrations.google.TrustedSenderDomain;
import com.finora.integrations.google.TrustedSenderDomainService;
import com.finora.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages which sender domains Finora will read receipts from — Phase C3, design proposal §12.2.
 *
 * <p><b>Admin-only, and that is a security boundary rather than a convenience.</b> Adding a domain
 * here grants parse-trust: authenticated mail from it may become financial records in a user's
 * ledger. {@code SYSTEM_SETTINGS} is the same permission that guards feature flags and other
 * platform-wide configuration.
 *
 * <p>There is no hard delete. Removing a row would destroy the answer to "when did we stop trusting
 * this domain, and who decided" — precisely the question asked after an incident. Disabling is the
 * delete, and both directions are audited.
 */
@RestController
@RequestMapping("/api/v1/admin/trusted-senders")
@PreAuthorize("hasAuthority('SYSTEM_SETTINGS')")
public class AdminTrustedSenderController {

    private final TrustedSenderDomainService service;
    private final CurrentUser currentUser;

    public AdminTrustedSenderController(TrustedSenderDomainService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record TrustedSenderDto(UUID id, String domain, String merchantName, String status,
                                    Instant createdAt, Instant updatedAt) {
        static TrustedSenderDto of(TrustedSenderDomain d) {
            return new TrustedSenderDto(d.getId(), d.getDomain(), d.getMerchantName(),
                    d.getStatus().name(), d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record CreateRequest(String domain, String merchantName) {}
    public record RelabelRequest(String merchantName) {}

    /** Every entry, including disabled ones — the disabled rows are the audit trail. */
    @GetMapping
    public ApiResponse<List<TrustedSenderDto>> list() {
        return ApiResponse.ok(service.listAll().stream().map(TrustedSenderDto::of).toList());
    }

    @PostMapping
    public ApiResponse<TrustedSenderDto> add(@RequestBody CreateRequest request) {
        return ApiResponse.ok(TrustedSenderDto.of(
                service.add(currentUser.id(), request.domain(), request.merchantName())),
                "Trusted sender added");
    }

    /**
     * Changes the merchant label only. The domain itself is immutable by design — see
     * {@link TrustedSenderDomainService#rename}: editing it in place would move trust to a different
     * sender under a row whose audit trail still describes the original.
     */
    @PutMapping("/{id}")
    public ApiResponse<TrustedSenderDto> relabel(@PathVariable UUID id,
                                                  @RequestBody RelabelRequest request) {
        return ApiResponse.ok(TrustedSenderDto.of(
                service.rename(currentUser.id(), id, request.merchantName())), "Updated");
    }

    /** Soft delete: the row stays, the trust stops. */
    @DeleteMapping("/{id}")
    public ApiResponse<TrustedSenderDto> disable(@PathVariable UUID id) {
        return ApiResponse.ok(TrustedSenderDto.of(
                service.setStatus(currentUser.id(), id, TrustedSenderDomain.Status.DISABLED)),
                "Trusted sender disabled");
    }

    /** Restores trust. Separate from {@code add} on purpose, so re-trusting a domain someone
     *  previously disabled is an explicit, audited act rather than a side effect. */
    @PostMapping("/{id}/enable")
    public ApiResponse<TrustedSenderDto> enable(@PathVariable UUID id) {
        return ApiResponse.ok(TrustedSenderDto.of(
                service.setStatus(currentUser.id(), id, TrustedSenderDomain.Status.ACTIVE)),
                "Trusted sender enabled");
    }
}
