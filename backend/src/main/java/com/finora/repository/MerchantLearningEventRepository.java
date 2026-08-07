package com.finora.repository;

import com.finora.entity.MerchantLearningEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantLearningEventRepository extends JpaRepository<MerchantLearningEvent, UUID> {

    /**
     * Claims a batch of due events, taking a row lock and skipping anything another worker already
     * holds.
     *
     * <p><b>{@code FOR UPDATE SKIP LOCKED} is the load-bearing part.</b> Railway can run more than
     * one instance, so without it two workers select the same row, both apply the learning, and the
     * merchant's {@code confirmation_count} increments twice. That is not wasted work — confirmation
     * counts are what {@code ConfidenceEngine.topCategory} uses to decide which category is
     * auto-applied, so double-processing silently changes the answer the engine gives. Plain
     * {@code FOR UPDATE} would serialise the workers instead, which is correct but turns a second
     * instance into a queue of one; {@code SKIP LOCKED} lets them work in parallel on disjoint rows,
     * which is the point of having them.
     *
     * <p>Native rather than JPQL because JPA's {@code @Lock(PESSIMISTIC_WRITE)} has no portable way
     * to express {@code SKIP LOCKED}. This application is PostgreSQL-only (see docker-compose.yml
     * and every migration), so a native query costs nothing here.
     *
     * <p>Must be called inside a transaction, and the caller must finish processing within it — the
     * lock lives for the transaction's lifetime and is what stops a second worker taking the row.
     * {@code MerchantLearningEventWorker} is the only caller and does exactly that.
     */
    @Query(value = """
           SELECT * FROM merchant_learning_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
           """, nativeQuery = true)
    List<MerchantLearningEvent> claimDueEvents(@Param("now") Instant now,
                                                @Param("batchSize") int batchSize);

    /**
     * Events stuck in PROCESSING past {@code staleBefore} — a worker claimed them and died before
     * finishing.
     *
     * <p>The row lock is released when that worker's transaction dies, but the status is not: the
     * row reads PROCESSING forever and no claim query will ever see it again, because claims only
     * look at PENDING. Without this, a single crashed worker silently strands however many events
     * it had in flight. Recovery is deliberately time-based rather than owner-based — tracking
     * which instance holds what would need instance identity and a heartbeat, which is a lot of
     * machinery to answer a question a timestamp already answers.
     *
     * <p>The enum literal is written in the SOURCE form ({@code MerchantLearningEvent.Status.PROCESSING}),
     * not the JVM binary form with a {@code $}. Hibernate accepts the binary form today, which is
     * exactly why it is worth pinning: it is not JPQL, and a provider upgrade is free to stop
     * accepting it.
     */
    @Query("""
           SELECT e FROM MerchantLearningEvent e
            WHERE e.status = com.finora.entity.MerchantLearningEvent.Status.PROCESSING
              AND e.updatedAt < :staleBefore
           """)
    List<MerchantLearningEvent> findStuckInProcessing(@Param("staleBefore") Instant staleBefore,
                                                       Pageable limit);

    /**
     * One page of the admin queue, with every field an operator needs already joined in.
     *
     * <p>The requirement this exists for is that an operator can answer "what failed, why, for which
     * user, from which statement and session, how many times, and when does it retry" without
     * opening a database client. A query returning bare ids would satisfy the endpoint and fail the
     * requirement: the page would render four UUIDs and the operator would go to the database
     * anyway.
     *
     * <p>So the names are resolved here, in one query, rather than by the service looping over rows
     * fetching each merchant, category and user. That loop is the N+1 this codebase repeatedly
     * documents avoiding elsewhere ({@code AnalyticsService.categoryConfidence},
     * {@code WorkspaceDashboardService.summarize}) — and a queue page is precisely where a backlog
     * makes N large.
     *
     * <p>LEFT joins throughout, because every one of these can legitimately be absent: a merchant or
     * category deleted after the event was queued, an import session past its 48-hour TTL. A failed
     * event whose merchant is gone is still a row an operator needs to see and dismiss, and an
     * inner join would hide exactly the rows most likely to be stuck.
     *
     * @param status null means "every status"
     */
    @Query("""
           SELECT e.id AS id, e.status AS status, e.attemptCount AS attemptCount,
                  e.nextAttemptAt AS nextAttemptAt, e.lastError AS lastError,
                  e.firstFailedAt AS firstFailedAt, e.lastRetryAt AS lastRetryAt,
                  e.createdAt AS createdAt,
                  e.userId AS userId, u.email AS userEmail,
                  e.merchantId AS merchantId, m.canonicalName AS merchantName,
                  e.categoryId AS categoryId, c.name AS categoryName,
                  e.sourceStatementImportId AS statementImportId, si.fileName AS statementFileName,
                  e.sourceImportSessionId AS importSessionId
             FROM MerchantLearningEvent e
             LEFT JOIN User u ON u.id = e.userId
             LEFT JOIN Merchant m ON m.id = e.merchantId
             LEFT JOIN Category c ON c.id = e.categoryId
             LEFT JOIN StatementImport si ON si.id = e.sourceStatementImportId
            WHERE (:status IS NULL OR e.status = :status)
           """)
    Page<LearningQueueRow> findQueueRows(@Param("status") MerchantLearningEvent.Status status,
                                          Pageable pageable);

    /** The projection {@link #findQueueRows} returns. An interface projection rather than a
     *  constructor expression, so an alias and its accessor cannot drift apart silently — a
     *  renamed alias fails at startup rather than returning nulls at runtime. */
    interface LearningQueueRow {
        UUID getId();
        MerchantLearningEvent.Status getStatus();
        int getAttemptCount();
        Instant getNextAttemptAt();
        String getLastError();
        Instant getFirstFailedAt();
        Instant getLastRetryAt();
        Instant getCreatedAt();
        UUID getUserId();
        String getUserEmail();
        UUID getMerchantId();
        String getMerchantName();
        UUID getCategoryId();
        String getCategoryName();
        UUID getStatementImportId();
        String getStatementFileName();
        UUID getImportSessionId();
    }

    /**
     * The same projection for a single event, backing the detail view and every action's response.
     *
     * <p>The JPQL is duplicated from {@link #findQueueRows} rather than shared, which is worth a
     * word: Spring Data has no way to compose a projection query from a fragment, and the
     * alternative — filtering a page in the service — is what the first version of this did and it
     * was wrong, because it could only ever find events on the page it happened to fetch. A
     * duplicated SELECT that is correct beats a clever one that silently 404s past page one. The
     * interface projection is what keeps the two honest: a renamed alias in either fails at
     * startup.
     */
    @Query("""
           SELECT e.id AS id, e.status AS status, e.attemptCount AS attemptCount,
                  e.nextAttemptAt AS nextAttemptAt, e.lastError AS lastError,
                  e.firstFailedAt AS firstFailedAt, e.lastRetryAt AS lastRetryAt,
                  e.createdAt AS createdAt,
                  e.userId AS userId, u.email AS userEmail,
                  e.merchantId AS merchantId, m.canonicalName AS merchantName,
                  e.categoryId AS categoryId, c.name AS categoryName,
                  e.sourceStatementImportId AS statementImportId, si.fileName AS statementFileName,
                  e.sourceImportSessionId AS importSessionId
             FROM MerchantLearningEvent e
             LEFT JOIN User u ON u.id = e.userId
             LEFT JOIN Merchant m ON m.id = e.merchantId
             LEFT JOIN Category c ON c.id = e.categoryId
             LEFT JOIN StatementImport si ON si.id = e.sourceStatementImportId
            WHERE e.id = :id
           """)
    Optional<LearningQueueRow> findQueueRowById(@Param("id") UUID id);

    /** Backs Retry All. Paged so the caller bounds how many events one click can requeue. */
    List<MerchantLearningEvent> findByStatus(MerchantLearningEvent.Status status, Pageable pageable);

    long countByStatus(MerchantLearningEvent.Status status);

    /**
     * When the oldest still-unclaimed event was queued, or empty when the queue is drained.
     *
     * <p>Backs the {@code finora.worker.oldest_pending_age} gauge, which is the queue's
     * user-visible symptom and the best single alert: depth answers "how much work is waiting",
     * this answers "how long has someone been waiting", and only the second maps to an SLA.
     *
     * <p>Scoped to PENDING with a due {@code next_attempt_at} deliberately -- a row backing off
     * between retries is waiting by design and would otherwise make a healthy queue look aged.
     * Matches the predicate {@code claimDueEvents} uses, so the gauge measures the same set the
     * worker would actually pick up.
     */
    @Query("""
           SELECT MIN(e.createdAt) FROM MerchantLearningEvent e
            WHERE e.status = com.finora.entity.MerchantLearningEvent.Status.PENDING
              AND e.nextAttemptAt <= :now
           """)
    Optional<Instant> findOldestPendingQueuedAt(@Param("now") Instant now);

    /**
     * Everything one import taught the system, oldest first.
     *
     * <p>The staging session is the key rather than the statement import because it is the one both
     * halves of the trace can reach: {@code statement_analysis_sessions.import_session_id} (V69)
     * names it from the upload side, and {@code source_import_session_id} (V63) names it from the
     * learning side. Nulls are never passed -- an import with no session has nothing to match, and
     * a null-matching query would return every direct-file import ever made.
     */
    List<MerchantLearningEvent> findBySourceImportSessionIdOrderByCreatedAtAsc(UUID sourceImportSessionId);

    /** The same, for a trace anchored on a job whose statement import is known but whose session is
     *  not -- the asynchronous path, which stages without one. */
    List<MerchantLearningEvent> findBySourceStatementImportIdOrderByCreatedAtAsc(UUID sourceStatementImportId);
}
