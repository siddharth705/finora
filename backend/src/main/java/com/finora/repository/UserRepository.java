package com.finora.repository;

import com.finora.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    // Used wherever a user TYPES their email (registration uniqueness, login, forgot-password) --
    // new registrations are normalized to lowercase going forward (see AuthService.createUserRecord),
    // but existing rows may still carry whatever case they were originally typed in, so lookups
    // driven by user input must not be case-sensitive regardless of what's actually stored.
    // findByEmail/existsByEmail above stay case-sensitive deliberately for internal lookups keyed
    // on an already-known-exact value (JWT subject, Spring Security username).
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhoneNumber(String phoneNumber);
    // Backs email-or-phone login (AuthService.resolveEmailForLogin) -- phone numbers aren't
    // normalized at registration time, so callers try a couple of digit/plus-sign variants.
    Optional<User> findByPhoneNumber(String phoneNumber);

    // --- Admin portal (frontend-admin/) -- AdminUserService ---

    /**
     * Backs the admin Users directory's search + status filter. Both `q` and `status` are
     * optional (pass null to skip that condition), matching the null-means-"don't filter"
     * convention TransactionRepository.search already uses elsewhere in this codebase. The
     * explicit CAST(:q AS string) works around the same PGJDBC "can't infer a type for a bare
     * null bind parameter passed through LOWER()/CONCAT()" issue documented on that query.
     */
    @Query("""
        SELECT u FROM User u
        WHERE (:q IS NULL
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR u.phoneNumber LIKE CONCAT('%', CAST(:q AS string), '%'))
          AND (:status IS NULL OR u.status = :status)
        """)
    Page<User> search(@Param("q") String q, @Param("status") String status, Pageable pageable);

    long countByStatus(String status);

    // Powers the admin Dashboard's "new signups, last 7/30 days" stat tiles (AdminStatsService).
    long countByCreatedAtAfter(Instant threshold);

    // Excludes the BOOTSTRAP_ADMIN system account (BootstrapService) from "total users" stats --
    // AdminOperationalDashboardService and AdminStatsService both used a plain count() before,
    // which would overcount by exactly one forever once a platform has been set up (that account
    // is locked, never deleted -- see SetupService.completeSetup()).
    long countByRoleNot(String role);

    // Paired with countByRoleNot above -- AdminStatsService derives activeUsers as
    // totalUsers - suspendedUsers, so both counts must exclude the bootstrap account together,
    // or that subtraction goes negative by one the moment it's locked (status=SUSPENDED) but
    // still excluded from the totalUsers side alone.
    long countByStatusAndRoleNot(String status, String role);

    // Backs the Admin Dashboard's Needs Attention section -- lockedUntil/failedLoginAttempts
    // already existed for AuthService's own lockout enforcement (see its class doc); this is the
    // first query counting currently-locked accounts platform-wide rather than checking one at a
    // time during a login attempt.
    long countByLockedUntilAfter(Instant threshold);
}
