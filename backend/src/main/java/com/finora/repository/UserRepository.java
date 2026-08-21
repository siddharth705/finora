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
    // --- Identity lookups ------------------------------------------------------------------------
    //
    // Since V52 an email and a phone number identify a user only WITHIN a portal scope: the same
    // person may hold a USER-scope account and an ADMIN-scope account under one email. Every lookup
    // driven by something a user typed must therefore pass the scope it is resolving within, or it
    // is ambiguous the moment anyone holds both.
    //
    // The unscoped variants are deliberately NOT kept as convenience overloads. A call that forgets
    // the scope would compile, work in every test with a single account, and silently authenticate
    // the wrong row in production -- exactly the failure mode worth making impossible to write.

    Optional<User> findByEmailIgnoreCaseAndAccountScope(String email, String accountScope);
    boolean existsByEmailIgnoreCaseAndAccountScope(String email, String accountScope);
    boolean existsByPhoneNumberAndAccountScope(String phoneNumber, String accountScope);
    // Backs email-or-phone login -- phone numbers aren't normalized at registration time, so
    // callers try a couple of digit/plus-sign variants.
    Optional<User> findByPhoneNumberAndAccountScope(String phoneNumber, String accountScope);

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
               OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\'
               OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')) ESCAPE '\\'
               OR u.phoneNumber LIKE CONCAT('%', CAST(:q AS string), '%') ESCAPE '\\')
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

    /**
     * How many usable accounts currently hold {@code roleName}, counting BOTH grant mechanisms.
     *
     * <p>Backs {@code RoleService.revokeRole}'s refusal to demote the last SUPER_ADMIN. Counting
     * only {@code user_roles} would have been wrong in exactly the case that matters:
     * {@code User.role} is a live grant too, not a legacy label -- {@code AuthorizationService
     * .effectiveAuthorities} resolves a Role by that string and grants its whole permission set,
     * which is precisely the mistake {@code revokeRole}'s own doc comment records having made
     * once already. {@code SetupService} writes SUPER_ADMIN to both, so either alone would count
     * the first administrator correctly by luck and a later one incorrectly.
     *
     * <p>Suspended accounts are excluded: an account that cannot log in is not a way back into
     * the platform, so it must not be what makes revoking the last working Super Admin look safe.
     */
    @Query("""
           SELECT COUNT(DISTINCT u.id) FROM User u LEFT JOIN u.roles r
           WHERE (u.role = :roleName OR r.name = :roleName) AND u.status = :activeStatus
           """)
    long countActiveUsersWithRole(@Param("roleName") String roleName,
                                  @Param("activeStatus") String activeStatus);

    /**
     * Reads just the phone-verified flag for one user, for {@code PhoneVerificationFilter}.
     *
     * <p>That filter ran a full {@code findById} on every authenticated request purely to read one
     * boolean, immediately after {@code JwtAuthFilter} had already loaded the same user — and the
     * two do NOT share a persistence context, because {@code OpenEntityManagerInViewInterceptor}
     * runs inside {@code DispatcherServlet}, after the whole filter chain. So it was a second full
     * load, dragging the eager {@code roles → permissions} graph along with it, to answer a
     * question one column could answer.
     *
     * <p>Returns empty when no such user exists, which the filter treats the same way it always
     * has (no user, no verification requirement to enforce — Spring Security's own rules decide).
     */
    @Query("SELECT u.phoneVerified FROM User u WHERE u.id = :id")
    Optional<Boolean> findPhoneVerifiedById(@Param("id") UUID id);

    // --- AccountPurgeSweepService ---

    /** Accounts eligible for purge -- ids only, not full entities, matching
     *  StatementStorageSweepService's own discovery-query shape (a projection, not a full-entity
     *  load, for a batch that's just going to be iterated and re-fetched one at a time anyway). */
    @Query("SELECT u.id FROM User u WHERE u.status = :status AND u.deletionRequestedAt < :cutoff")
    java.util.List<UUID> findIdsByStatusAndDeletionRequestedAtBefore(
            @Param("status") String status, @Param("cutoff") Instant cutoff, Pageable pageable);
}
