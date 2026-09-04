package com.finora.repository;

import com.finora.entity.SupportTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    /** The user's own list, newest first. */
    Page<SupportTicket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Ownership is a predicate on the fetch, not a check after it.
     *
     * <p>Every user-facing read goes through this rather than {@code findById} plus an
     * {@code if} — the same shape {@code StatementImportService.getFile} uses. A caller that
     * cannot express the user id has no business reading the row.
     */
    Optional<SupportTicket> findByIdAndUserId(UUID id, UUID userId);

    /** The reference a customer quotes back to support. */
    Optional<SupportTicket> findByTicketNumber(String ticketNumber);

    /**
     * The admin queue, optionally filtered by status and/or category, oldest first — matching
     * {@code idx_support_tickets_open} and the same "longest-waiting user is the one to look at"
     * ordering {@code AdminHeldImportService.list} and {@code AdminLearningQueueService} use.
     *
     * <p>{@code :status IS NULL} / {@code :category IS NULL} rather than two overloaded finder
     * methods (or four, to cover both filters independently): one query serves "no filter", "status
     * only", "category only" and "both", and Spring Data parameter binding treats a null argument
     * as null in the generated JPQL, not as a literal to compare against.
     */
    @Query("""
            SELECT t FROM SupportTicket t
             WHERE (:status IS NULL OR t.status = :status)
               AND (:category IS NULL OR t.category = :category)
             ORDER BY t.createdAt ASC
            """)
    Page<SupportTicket> findForAdmin(@Param("status") SupportTicket.Status status,
                                     @Param("category") SupportTicket.Category category,
                                     Pageable pageable);

    /**
     * The raw sequence value. Formatting is {@code SupportTicketIdGenerator}'s job.
     *
     * <p>{@code nextval} is transactional-but-not-rollback-safe by design: a rolled-back ticket
     * burns its number rather than reissuing it. Gaps are the correct trade — a reused reference
     * would point at two different tickets.
     */
    @Query(value = "SELECT nextval('support_ticket_reference_seq')", nativeQuery = true)
    long nextTicketSequence();

    /**
     * Account purge. A bulk delete rather than a cascade, and that distinction is the whole point.
     *
     * <p>{@code support_tickets.user_id} does carry {@code ON DELETE CASCADE}, but
     * {@code AccountPurgeSweepService.purgeOne} <i>anonymizes</i> the {@code users} row — it never
     * issues a {@code DELETE FROM users} — so that cascade never fires on the deletion path.
     * {@code purgeOne} documents the same trap against V137/V125. Without this method being called
     * from there, a deleted user's support tickets survive the purge indefinitely.
     *
     * <p>Deleting the ticket does cascade to its attachments and internal notes, which are
     * parented on {@code ticket_id} rather than on {@code users}.
     *
     * <p>A modifying query rather than a derived {@code deleteByUserId}, so it is a single
     * statement instead of a load-then-remove per row, and so it is unaffected by any future
     * soft-delete annotation on the entity — {@code BudgetRepository} documents what that
     * annotation does to a delete when it is present.
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically} are not decoration. A bulk JPQL
     * delete runs straight against the database and the persistence context never hears about it,
     * so inside the one transaction {@code purgeOne} runs in, an already-loaded ticket would still
     * be returned by a subsequent {@code findById} as though the purge had not happened. Flushing
     * first also stops a pending insert being ordered after the delete. This was caught by
     * {@code SupportRepositoryIT}, which read a purged ticket straight back.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SupportTicket t WHERE t.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
