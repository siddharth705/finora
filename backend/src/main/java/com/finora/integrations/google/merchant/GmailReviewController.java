package com.finora.integrations.google.merchant;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The Gmail review queue — C5.4, D-15. Under the same
 * {@code /api/v1/integrations/google/gmail} prefix {@link com.finora.integrations.google.GoogleOAuthController}
 * already uses for connection endpoints; this is the next step in the same flow, not a separate
 * feature area.
 */
@RestController
@RequestMapping("/api/v1/integrations/google/gmail")
public class GmailReviewController {

    private final GmailReviewService reviewService;
    private final CurrentUser currentUser;

    public GmailReviewController(GmailReviewService reviewService, CurrentUser currentUser) {
        this.reviewService = reviewService;
        this.currentUser = currentUser;
    }

    @GetMapping("/review-queue")
    public ApiResponse<List<GmailReviewItemDto>> reviewQueue() {
        return ApiResponse.ok(reviewService.listPending(currentUser.id()));
    }

    @PostMapping("/review/{sessionId}/approve")
    public ApiResponse<Void> approve(@PathVariable UUID sessionId,
                                      @RequestBody(required = false) GmailReviewApproveRequest request) {
        String category = request == null ? null : request.category();
        reviewService.approve(currentUser.id(), sessionId, category);
        return ApiResponse.ok(null, "Transaction created");
    }

    @PostMapping("/review/{sessionId}/reject")
    public ApiResponse<Void> reject(@PathVariable UUID sessionId) {
        reviewService.reject(currentUser.id(), sessionId);
        return ApiResponse.ok(null, "Receipt discarded");
    }
}
