package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.MerchantReviewDto;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import com.finora.service.MerchantReviewService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Merchant Review Center (WI4).
 *
 * <p>Gated on {@code MERCHANT_REVIEW} (V64), separate from the existing {@code MERCHANT_MANAGE}.
 * That one is about curating ONE user's merchants on their behalf, usually while helping them with
 * something; this is about working a cross-user queue of the engine's own guesses, which is an
 * operational duty rather than a support one. Same reasoning V61 and V63 applied when they gave the
 * analysis upload and the learning queue their own grants.
 *
 * <p><b>The URL shape encodes the product decision.</b> The queue is unscoped
 * ({@code /admin/merchant-review}) because listing outstanding work across users is safe and
 * useful; every ACTION is under {@code /users/{userId}/} because merchants belong to exactly one
 * person and there is no canonical registry to act on. A reviewer cannot merge across accounts, and
 * the routes make that impossible to express rather than merely discouraged.
 */
@RestController
@RequestMapping("/api/v1/admin/merchant-review")
@PreAuthorize("hasAuthority('MERCHANT_REVIEW')")
public class AdminMerchantReviewController {

    private final MerchantReviewService reviewService;
    private final CurrentUser currentUser;

    public AdminMerchantReviewController(MerchantReviewService reviewService, CurrentUser currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    /** Everything awaiting review, oldest first — a merchant unreviewed for a week matters more
     *  than one created a minute ago. */
    @GetMapping
    public ApiResponse<PagedResponse<MerchantReviewDto>> queue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(reviewService.queue(page, size));
    }

    /** The outstanding count, for the nav badge and the dashboard tile. */
    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> count() {
        return ApiResponse.ok(Map.of("outstanding", reviewService.outstandingCount()));
    }

    /** Candidates this merchant could be folded into: the SAME user's approved merchants, never
     *  another account's. */
    @GetMapping("/users/{userId}/merchants/{merchantId}/merge-candidates")
    public ApiResponse<List<MerchantReviewDto>> mergeCandidates(@PathVariable UUID userId,
                                                                 @PathVariable UUID merchantId) {
        return ApiResponse.ok(reviewService.mergeCandidatesFor(userId, merchantId));
    }

    @PostMapping("/users/{userId}/merchants/{merchantId}/approve")
    public ApiResponse<MerchantReviewDto> approve(@PathVariable UUID userId, @PathVariable UUID merchantId) {
        return ApiResponse.ok(reviewService.approve(currentUser.id(), userId, merchantId), "Merchant approved");
    }

    /** Bulk approve is per user by design — "approve everything for everyone" is a click with no
     *  judgement behind it, and the queue exists because a person looked. */
    @PostMapping("/users/{userId}/approve-all")
    public ApiResponse<Map<String, Integer>> approveAll(@PathVariable UUID userId) {
        int approved = reviewService.approveAllFor(currentUser.id(), userId);
        return ApiResponse.ok(Map.of("approved", approved), approved + " merchant(s) approved");
    }

    @PostMapping("/users/{userId}/merchants/{merchantId}/rename")
    public ApiResponse<MerchantReviewDto> rename(@PathVariable UUID userId, @PathVariable UUID merchantId,
                                                   @RequestBody Map<String, String> body) {
        return ApiResponse.ok(reviewService.rename(currentUser.id(), userId, merchantId, body.get("canonicalName")),
                "Merchant renamed and approved");
    }

    /** Folds the guess into a merchant the user already has. Routes through
     *  {@code MerchantService.merge}, which repoints aliases, transactions, learning rows and audit
     *  history before deleting — the four foreign keys a raw delete would cascade away. */
    @PostMapping("/users/{userId}/merchants/{merchantId}/merge")
    public ApiResponse<MerchantReviewDto> merge(@PathVariable UUID userId, @PathVariable UUID merchantId,
                                                  @RequestBody Map<String, UUID> body) {
        return ApiResponse.ok(
                reviewService.merge(currentUser.id(), userId, body.get("survivingMerchantId"), merchantId),
                "Merged");
    }

    /** Refused with a 409 when transactions point at the merchant — deleting would strip the
     *  attribution from real ledger rows, and merge is the operation for that case. */
    @DeleteMapping("/users/{userId}/merchants/{merchantId}")
    public ApiResponse<Void> discard(@PathVariable UUID userId, @PathVariable UUID merchantId) {
        reviewService.discard(currentUser.id(), userId, merchantId);
        return ApiResponse.ok(null, "Merchant discarded");
    }
}
