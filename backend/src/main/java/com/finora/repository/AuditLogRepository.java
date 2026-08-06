package com.finora.repository;

import com.finora.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    // Admin Portal, Operational Dashboard KPI -- "Active users today." User has no lastLoginAt
    // column, so this counts distinct accounts with a real USER_LOGIN audit event (AuthService
    // already writes one on every successful login) in the window, rather than adding a new
    // column purely for this one tile.
    @Query("SELECT COUNT(DISTINCT a.userId) FROM AuditLog a WHERE a.action = :action AND a.createdAt >= :since")
    long countDistinctUsersByActionSince(@Param("action") String action, @Param("since") Instant since);

    // Backs the admin portal's global audit feed (AdminController.globalAuditLogs) -- unlike
    // findByUserIdOrderByCreatedAtDesc below, this is genuinely unbounded across every user on
    // the platform, so it's paginated from the start rather than fetched in full. Superseded by
    // search() below as of Admin Portal Phase 5 (the Activity Feed's FilterBar needs q/date-range
    // filtering search() alone provides), kept here since it's a simpler unconditional query a
    // future caller that genuinely wants "just give me everything, no filters" might still prefer.
    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Admin Portal Phase 5 (Shared Filtering Framework, Activity Feed's FilterBar) -- backs
     * AdminController.globalAuditLogs' q/dateFrom/dateTo filters, all optional (null skips that
     * condition), same convention UserRepository.search/TransactionRepository.search already
     * establish. q matches against both action and entityType (case-insensitive) -- the two
     * fields the Activity Feed timeline already displays per row, so "search" filtering on what's
     * visibly shown is what an admin scanning the timeline actually expects. Sort direction lives
     * in the Pageable's own Sort (set by the controller), not a separate parameter here.
     *
     * <p>Bug fix: the two date bounds were written as a bare {@code :dateFrom IS NULL}, and that
     * made this entire endpoint return 500 rather than filtering anything:
     *
     * <pre>ERROR: could not determine data type of parameter $4  (SQLState 42P18)</pre>
     *
     * <p>Hibernate emits a named parameter used twice as two separate JDBC placeholders. The
     * second gets its type from {@code a.createdAt >= ?}, but the first appears only as
     * {@code ? IS NULL}, which tells PostgreSQL nothing about what type it is, so the driver
     * refuses to prepare the statement. The {@code CAST} gives it one. Note the {@code :q} branch
     * above was already written this way, for exactly this reason; the date bounds were simply
     * missed.
     *
     * <p>This was not an edge case. Both bounds are null on an unfiltered request, which is the
     * default state of the admin portal's Activity Feed, so the page 500'd on open. It went
     * unnoticed because the four GlobalAuditLogIT tests covering it had never executed: {@code *IT}
     * did not match surefire's default includes (see pom.xml).
     *
     * <p>{@code TransactionRepository.search} and {@code UserRepository.search} use the same
     * {@code :param IS NULL} shape and are NOT affected: verified by TransactionRepositoryIT
     * exercising that query with null filters and passing. Their nullable parameters resolve to
     * types PostgreSQL can infer here; only the {@code Instant} bounds hit this. Worth knowing
     * before "fixing" those queries too.
     */
    @Query("""
        SELECT a FROM AuditLog a
        WHERE (:q IS NULL
               OR LOWER(a.action) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\'
               OR LOWER(a.entityType) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\')
          AND (CAST(:dateFrom AS timestamp) IS NULL OR a.createdAt >= :dateFrom)
          AND (CAST(:dateTo AS timestamp) IS NULL OR a.createdAt < :dateTo)
        """)
    Page<AuditLog> search(@Param("q") String q, @Param("dateFrom") Instant dateFrom,
                           @Param("dateTo") Instant dateTo, Pageable pageable);
    // Full history, unpaged -- backs the self-service Activity Timeline page (ActivityController)
    // and the existing admin-gated per-user audit view (AdminController). Same tradeoff both
    // already accept: audit_logs grows unbounded over a user's lifetime, but this is a review
    // surface, not a hot path, so a real Pageable contract isn't warranted until that's shown to
    // be a problem -- see the Dashboard's own doc comment for why ITS feed uses the Top5 query
    // below instead of this one.
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // The Workspace Dashboard's "recent activity" tile only ever needs the latest handful --
    // Spring Data's derived Top-N query keyword pushes that limit down to the database rather
    // than fetching this user's entire audit history just to take(5) in memory.
    List<AuditLog> findTop5ByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Admin Portal Phase 4 (EntityDrawer reference implementation, Banks page's Audit tab) --
     * BANK_CREATED/BANK_UPDATED/BANK_DELETED all record entityId as null (Bank.id is a String
     * natural key, e.g. "hdfc", not a UUID -- see this entity's entityId column type), carrying
     * the real bank id inside metadata's "bankId" key instead (see BankManagementService
     * .createCustom/updateCustom/deleteCustom). Native query, not JPQL: Hibernate's JPQL has no
     * operator for Postgres's jsonb ->> text-extraction this needs -- AccountRepository
     * .findByUserId already establishes @Query(nativeQuery = true) as an accepted pattern in this
     * codebase for exactly this "JPQL can't express it" case. A real, honest per-entity audit
     * trail (every field genuinely persisted), not a fabricated one.
     */
    @Query(value = "SELECT * FROM audit_logs WHERE entity_type = 'Bank' AND metadata->>'bankId' = :bankId ORDER BY created_at DESC",
            nativeQuery = true)
    List<AuditLog> findByBankIdInMetadata(@Param("bankId") String bankId);
}
