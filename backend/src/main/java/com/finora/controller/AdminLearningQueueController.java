package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.LearningQueueDto;
import com.finora.dto.LearningQueueDto.QueueSummary;
import com.finora.dto.PagedResponse;
import com.finora.security.CurrentUser;
import com.finora.service.AdminLearningQueueService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The merchant learning queue's operator surface (WI2).
 *
 * <p>Gated on {@code LEARNING_QUEUE_MANAGE} (V63) rather than reusing an existing permission.
 * {@code PLATFORM_DIAGNOSTICS_VIEW} is explicitly the read-only operational-visibility permission
 * ("no configuration-mutation power" — V34), and retrying an event mutates a user's learning
 * distribution; gating an action behind a view permission undoes that separation invisibly.
 * {@code MERCHANT_MANAGE} is the wrong shape too — an engineer clearing a stuck backlog should not
 * need the ability to merge and rename a customer's merchants to do it. Same reasoning V61 applied
 * when it gave the analysis upload its own permission.
 *
 * <p>Class-level, so a new endpoint here is gated by default. A method-level annotation would be
 * needed only for an action deserving a different permission — see
 * {@code AdminStatementAnalysisController} for that case.
 */
@RestController
@RequestMapping("/api/v1/admin/learning-queue")
@PreAuthorize("hasAuthority('LEARNING_QUEUE_MANAGE')")
public class AdminLearningQueueController {

    private final AdminLearningQueueService queueService;
    private final CurrentUser currentUser;

    public AdminLearningQueueController(AdminLearningQueueService queueService, CurrentUser currentUser) {
        this.queueService = queueService;
        this.currentUser = currentUser;
    }

    /**
     * One page of the queue.
     *
     * @param status  optional filter — PENDING, PROCESSING, FAILED, COMPLETED, RESOLVED. Omitted
     *                means every status.
     * @param sortField one of createdAt, nextAttemptAt, lastRetryAt, firstFailedAt, attemptCount;
     *                  anything else falls back to createdAt rather than 500ing on a bad property
     */
    @GetMapping
    public ApiResponse<PagedResponse<LearningQueueDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt") String sortField,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ApiResponse.ok(queueService.list(status, page, size, sortField, sortDir));
    }

    /** Counts per status, for the filter chips — so the page can say "3 failed" without fetching
     *  every page to count them. */
    @GetMapping("/summary")
    public ApiResponse<QueueSummary> summary() {
        return ApiResponse.ok(queueService.summary());
    }

    @GetMapping("/{eventId}")
    public ApiResponse<LearningQueueDto> get(@PathVariable UUID eventId) {
        return ApiResponse.ok(queueService.get(eventId));
    }

    /** Requeues one FAILED event with a fresh attempt budget. 409 if it is in any other state,
     *  naming that state, so an operator can tell "already retried" from "cannot be retried". */
    @PostMapping("/{eventId}/retry")
    public ApiResponse<LearningQueueDto> retry(@PathVariable UUID eventId) {
        return ApiResponse.ok(queueService.retry(currentUser.id(), eventId), "Queued for retry");
    }

    /** Requeues every FAILED event, bounded. Returns the count so the response can say how many
     *  rather than just "done". */
    @PostMapping("/retry-all")
    public ApiResponse<Map<String, Integer>> retryAll() {
        int retried = queueService.retryAll(currentUser.id());
        return ApiResponse.ok(Map.of("retried", retried), retried + " event(s) queued for retry");
    }

    /**
     * Closes a FAILED event without applying it.
     *
     * <p>{@code reason} is optional and free text. Recorded in the audit entry rather than on the
     * event, because it is a fact about a decision a person made, not about the event's state.
     */
    @PostMapping("/{eventId}/resolve")
    public ApiResponse<LearningQueueDto> resolve(@PathVariable UUID eventId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(queueService.markResolved(currentUser.id(), eventId, reason),
                "Marked resolved");
    }
}
