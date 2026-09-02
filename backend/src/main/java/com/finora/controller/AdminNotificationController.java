package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.NotificationAdminDetailDto;
import com.finora.dto.NotificationAdminDto;
import com.finora.dto.NotificationAdminDto.Summary;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import com.finora.service.AdminNotificationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin notification dashboard (Task 12): a read-only list of {@code notifications} outbox
 * rows plus basic send-outcome counts. Per the proposal (section 2.5/4) this is deliberately
 * narrow -- no trend charts, no engagement scoring, no retry/resend action -- there is no volume
 * yet to make anything richer meaningful, and {@code NotificationDispatcher} (Task 3) remains the
 * only thing that can move a notification's status.
 *
 * <p>Gated on {@code NOTIFICATION_MANAGE} (V129) rather than reusing {@code
 * PLATFORM_DIAGNOSTICS_VIEW}, for the same reason {@code AdminLearningQueueController}'s own doc
 * comment gives for its own permission: the read-only operational-visibility permission is a
 * deliberately narrow grant covering unrelated surfaces, and tying every new read-only capability
 * to whichever existing permission happens to fit best is how a permission model erodes over
 * time. This surface gets its own, even though -- unlike the learning queue -- it has no mutating
 * action today.
 *
 * <p>Class-level, so any endpoint added here later is gated by default.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
public class AdminNotificationController {

    private final AdminNotificationService notificationService;
    private final CurrentUser currentUser;

    public AdminNotificationController(AdminNotificationService notificationService, CurrentUser currentUser) {
        this.notificationService = notificationService;
        this.currentUser = currentUser;
    }

    /**
     * One page of the outbox, newest first.
     *
     * @param status optional filter -- CREATED, QUEUED, PROCESSING, SENT, RETRYING, DEAD_LETTER.
     *               Omitted means every status.
     */
    @GetMapping
    public ApiResponse<PagedResponse<NotificationAdminDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(notificationService.list(status, page, size));
    }

    /** Sent/failed counts, overall and by channel, for the dashboard's stat tiles -- so the page
     *  can show them without fetching every page to tally it up. */
    @GetMapping("/summary")
    public ApiResponse<Summary> summary() {
        return ApiResponse.ok(notificationService.summary());
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationAdminDetailDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(notificationService.get(currentUser.id(), id));
    }
}
