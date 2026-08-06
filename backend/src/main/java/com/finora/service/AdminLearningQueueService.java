package com.finora.service;

import com.finora.dto.LearningQueueDto;
import com.finora.dto.LearningQueueDto.QueueSummary;
import com.finora.dto.PagedResponse;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.MerchantLearningEvent.Status;
import com.finora.exception.ApiException;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.util.EnumParsing;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admin merchant-learning queue (WI2).
 *
 * <p>Read side and operator actions for {@code merchant_learning_events}. The worker
 * ({@code MerchantLearningEventWorker}) owns the automatic lifecycle; this owns only what a human
 * does to it. Keeping those apart matters: an operator's Retry resets the attempt budget, which
 * the worker must never do to itself, or a permanently broken event would retry forever.
 */
@Service
public class AdminLearningQueueService {

    /** Sort fields an operator may choose, mapped to entity properties.
     *
     *  <p>An allowlist rather than passing the parameter straight to {@code Sort.by}: an unmapped
     *  property name reaches Hibernate and fails as a 500 on what is really bad input, and the set
     *  of sensible orderings for a queue is small and knowable. */
    private static final Map<String, String> SORTABLE = Map.of(
            "createdAt", "createdAt",
            "nextAttemptAt", "nextAttemptAt",
            "lastRetryAt", "lastRetryAt",
            "firstFailedAt", "firstFailedAt",
            "attemptCount", "attemptCount");

    /** How many events one Retry All may requeue. Same ceiling as
     *  {@code TransactionDto.MAX_BULK_IDS}, reused rather than a second number invented: an
     *  unbounded click is how a queue page becomes an outage. */
    private static final int MAX_RETRY_ALL = 500;

    private final MerchantLearningEventRepository repository;
    private final MerchantLearningEventWorker worker;
    private final AuditService auditService;

    public AdminLearningQueueService(MerchantLearningEventRepository repository,
                                      MerchantLearningEventWorker worker,
                                      AuditService auditService) {
        this.repository = repository;
        this.worker = worker;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PagedResponse<LearningQueueDto> list(String status, int page, int size,
                                                 String sortField, String sortDir) {
        Status filter = status == null || status.isBlank()
                ? null
                : EnumParsing.parse(Status.class, status.trim().toUpperCase(), "status");

        String property = SORTABLE.getOrDefault(sortField, "createdAt");
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;

        var result = repository.findQueueRows(filter, PageRequest.of(
                PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25),
                Sort.by(direction, property)));
        return PagedResponse.of(result.map(LearningQueueDto::from));
    }

    /** The filter chips' counts. Five cheap indexed counts rather than paging the whole table to
     *  tally it, matching AdminStatsService's "simple indexed counts, not a reporting subsystem"
     *  discipline. */
    @Transactional(readOnly = true)
    public QueueSummary summary() {
        return new QueueSummary(
                repository.countByStatus(Status.PENDING),
                repository.countByStatus(Status.PROCESSING),
                repository.countByStatus(Status.FAILED),
                repository.countByStatus(Status.COMPLETED),
                repository.countByStatus(Status.RESOLVED));
    }

    /**
     * Puts one FAILED event back in the queue.
     *
     * <p>Only FAILED. PENDING is already scheduled and "retrying" it would silently move its
     * backoff forward; PROCESSING is claimed by a worker this instant; COMPLETED and RESOLVED are
     * done. Refusing with the actual current state, rather than a generic rejection, is what lets
     * an operator tell "someone already retried this" from "this cannot be retried".
     */
    @Transactional
    public LearningQueueDto retry(UUID actingAdminId, UUID eventId) {
        MerchantLearningEvent event = require(eventId);
        requireStatus(event, Set.of(Status.FAILED), "retried");

        event.requeueForRetry(Instant.now());
        repository.save(event);
        auditService.record(event.getUserId(), "LEARNING_EVENT_RETRIED", "MerchantLearningEvent", eventId,
                Map.of("actorId", actingAdminId.toString(), "merchantId", String.valueOf(event.getMerchantId())));

        nudgeAfterCommit();
        return single(eventId);
    }

    /**
     * Requeues every FAILED event, up to {@link #MAX_RETRY_ALL}.
     *
     * @return how many were requeued, so the UI can say "142 retried" rather than "done"
     */
    @Transactional
    public int retryAll(UUID actingAdminId) {
        List<MerchantLearningEvent> failed = repository.findByStatus(
                Status.FAILED, PageRequest.of(0, MAX_RETRY_ALL));
        if (failed.isEmpty()) return 0;

        Instant now = Instant.now();
        failed.forEach(event -> event.requeueForRetry(now));
        repository.saveAll(failed);

        // One audit entry for the bulk action, not one per event: the action an operator took was
        // "retry everything", and N rows would bury that in the trail. The count is what makes it
        // reconstructable.
        auditService.record(actingAdminId, "LEARNING_EVENTS_RETRIED_BULK", "MerchantLearningEvent", null,
                Map.of("actorId", actingAdminId.toString(), "count", failed.size(),
                        "truncated", failed.size() == MAX_RETRY_ALL));

        nudgeAfterCommit();
        return failed.size();
    }

    /**
     * Takes a FAILED event off the queue without applying it.
     *
     * <p>The escape hatch a queue needs to stay useful. Some events will never succeed — the
     * category was deleted, the merchant was merged away — and with no way to close them the page
     * accumulates permanent noise, which trains operators to ignore it. RESOLVED is deliberately
     * distinct from COMPLETED: COMPLETED means the learning was applied, RESOLVED means it never
     * will be and that is an accepted outcome. Collapsing them would make the queue's history lie
     * about what the engine learned.
     */
    @Transactional
    public LearningQueueDto markResolved(UUID actingAdminId, UUID eventId, String reason) {
        MerchantLearningEvent event = require(eventId);
        requireStatus(event, Set.of(Status.FAILED), "resolved");

        event.markResolved(Instant.now());
        repository.save(event);
        auditService.record(event.getUserId(), "LEARNING_EVENT_RESOLVED", "MerchantLearningEvent", eventId,
                Map.of("actorId", actingAdminId.toString(),
                        // Map.of rejects nulls, and an operator is not required to explain
                        // themselves -- the empty string keeps the entry writable either way.
                        "reason", reason == null ? "" : reason));
        return single(eventId);
    }

    @Transactional(readOnly = true)
    public LearningQueueDto get(UUID eventId) {
        return single(eventId);
    }

    // --- internals ----------------------------------------------------------------------------

    /** Read back through the same projection the list uses, so a detail view and the row an
     *  operator clicked can never disagree about anything. */
    private LearningQueueDto single(UUID eventId) {
        return repository.findQueueRowById(eventId)
                .map(LearningQueueDto::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such learning event."));
    }

    private MerchantLearningEvent require(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such learning event."));
    }

    private static void requireStatus(MerchantLearningEvent event, Set<Status> allowed, String verb) {
        if (!allowed.contains(event.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a FAILED event can be " + verb + "; this one is " + event.getStatus() + ".");
        }
    }

    /** Wakes the worker once the requeue has actually committed — the same afterCommit discipline
     *  the publisher uses. Nudging inside the transaction would hand the worker an event id it
     *  cannot yet see as PENDING. */
    private void nudgeAfterCommit() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            worker.nudge();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        worker.nudge();
                    }
                });
    }
}
