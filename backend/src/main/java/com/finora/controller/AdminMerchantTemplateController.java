package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.integrations.google.merchant.MerchantTemplate;
import com.finora.integrations.google.merchant.MerchantTemplateAdminService;
import com.finora.integrations.google.merchant.MerchantTemplateTestRunner;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD + a test sandbox for {@link MerchantTemplate} -- lets an admin add or fix a
 * declarative Gmail-receipt parser without a Flyway migration + backend deploy. See
 * {@link MerchantTemplateAdminService}'s own class doc for why this is gated on
 * {@code MERCHANT_MANAGE} rather than {@code SYSTEM_SETTINGS}: this table is not the trust
 * boundary (that is {@code gmail_trusted_sender_domains}, {@link AdminTrustedSenderController}),
 * and the same role that already sees "N unparsed Swiggy emails" on the Merchant Intelligence
 * stats page (also {@code MERCHANT_MANAGE}) needs to be able to act on it.
 */
@RestController
@RequestMapping("/api/v1/admin/merchant-templates")
@PreAuthorize("hasAuthority('MERCHANT_MANAGE')")
public class AdminMerchantTemplateController {

    private final MerchantTemplateAdminService service;
    private final MerchantTemplateTestRunner testRunner;
    private final CurrentUser currentUser;

    public AdminMerchantTemplateController(MerchantTemplateAdminService service,
                                            MerchantTemplateTestRunner testRunner,
                                            CurrentUser currentUser) {
        this.service = service;
        this.testRunner = testRunner;
        this.currentUser = currentUser;
    }

    public record MerchantTemplateDto(UUID id, String merchantDomain, String merchantName,
                                       String receiptMarker, String amountPattern, String datePattern,
                                       boolean enabled, UUID createdByUserId, Instant createdAt,
                                       Instant updatedAt, boolean domainIsTrusted) {}

    public record CreateTemplateRequest(@NotBlank String merchantDomain, @NotBlank String merchantName,
                                         @NotBlank String receiptMarker, @NotBlank String amountPattern,
                                         @NotBlank String datePattern) {}

    public record UpdateTemplateRequest(@NotBlank String merchantName, @NotBlank String receiptMarker,
                                         @NotBlank String amountPattern, @NotBlank String datePattern) {}

    // merchantDomain does not affect matching itself (TemplateEmailParser.parse never reads
    // message.authenticatedDomain()) -- it is still @NotBlank here because it flows through into
    // ParsedReceipt.merchantDomain() on a successful PARSED test, and that record's own compact
    // constructor throws IllegalArgumentException on a blank value. Without this, a test run with
    // an empty domain field that would otherwise have parsed successfully surfaces as an
    // unhandled 500, not a clean validation error.
    public record TestTemplateRequest(@NotBlank String merchantDomain, @NotBlank String receiptMarker,
                                       @NotBlank String amountPattern, @NotBlank String datePattern,
                                       @NotBlank String sampleHtml) {}

    public record TestTemplateResult(String status, String reason, BigDecimal amount,
                                      LocalDate transactionDate, Double confidence,
                                      List<ViolationDto> violations) {}

    public record ViolationDto(String field, String reason) {}

    @GetMapping
    public ApiResponse<List<MerchantTemplateDto>> list() {
        return ApiResponse.ok(service.listAll().stream().map(this::toDto).toList());
    }

    @PostMapping
    public ApiResponse<MerchantTemplateDto> create(@Valid @RequestBody CreateTemplateRequest request) {
        MerchantTemplate saved = service.create(currentUser.id(), request.merchantDomain(),
                request.merchantName(), request.receiptMarker(), request.amountPattern(), request.datePattern());
        return ApiResponse.ok(toDto(saved), "Template created, disabled pending a successful test");
    }

    @PutMapping("/{id}")
    public ApiResponse<MerchantTemplateDto> update(@PathVariable UUID id,
                                                    @Valid @RequestBody UpdateTemplateRequest request) {
        MerchantTemplate saved = service.update(currentUser.id(), id, request.merchantName(),
                request.receiptMarker(), request.amountPattern(), request.datePattern());
        return ApiResponse.ok(toDto(saved), saved.isEnabled()
                ? "Template updated" : "Template updated and disabled pending re-test");
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<MerchantTemplateDto> activate(@PathVariable UUID id) {
        return ApiResponse.ok(toDto(service.activate(currentUser.id(), id)), "Template activated");
    }

    @PostMapping("/{id}/deactivate")
    public ApiResponse<MerchantTemplateDto> deactivate(@PathVariable UUID id) {
        return ApiResponse.ok(toDto(service.deactivate(currentUser.id(), id)), "Template deactivated");
    }

    /**
     * Not scoped to an existing template id, deliberately -- same reasoning as
     * {@code AdminRuleController.POST /rules/test}: this works against in-progress, possibly-unsaved
     * form values, whether authoring a brand-new template or fixing one that hasn't been saved yet.
     * Creates or persists nothing.
     */
    @PostMapping("/test")
    public ApiResponse<TestTemplateResult> test(@Valid @RequestBody TestTemplateRequest request) {
        MerchantTemplateTestRunner.TestOutcome outcome = testRunner.test(request.merchantDomain(),
                request.receiptMarker(), request.amountPattern(), request.datePattern(), request.sampleHtml());
        return ApiResponse.ok(new TestTemplateResult(
                outcome.status().name(), outcome.reason(), outcome.amount(), outcome.transactionDate(),
                outcome.confidence(),
                outcome.violations().stream().map(v -> new ViolationDto(v.field(), v.reason())).toList()));
    }

    private MerchantTemplateDto toDto(MerchantTemplate t) {
        return new MerchantTemplateDto(t.getId(), t.getMerchantDomain(), t.getMerchantName(),
                t.getReceiptMarker(), t.getAmountPattern(), t.getDatePattern(), t.isEnabled(),
                t.getCreatedByUserId(), t.getCreatedAt(), t.getUpdatedAt(),
                service.isDomainTrusted(t.getMerchantDomain()));
    }
}
