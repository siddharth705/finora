package com.finora.support;

import com.finora.dto.ApiResponse;
import com.finora.dto.PagedResponse;
import com.finora.entity.FeedbackEntry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin feedback list. Gated on {@code SUPPORT_MANAGE} — the same permission as ticket
 *  triage, not a separate one: feedback message text is the same class of user-submitted content
 *  as a ticket description, and V149's grant already covers "read product feedback". */
@RestController
@RequestMapping("/api/v1/admin/feedback")
@PreAuthorize("hasAuthority('SUPPORT_MANAGE')")
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    public AdminFeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public ApiResponse<PagedResponse<FeedbackDto.Summary>> list(
            @RequestParam(required = false) FeedbackEntry.Type type,
            @RequestParam(required = false) FeedbackEntry.Context context,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(feedbackService.adminList(type, context, page, size));
    }
}
