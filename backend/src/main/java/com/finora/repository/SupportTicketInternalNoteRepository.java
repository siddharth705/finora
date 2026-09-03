package com.finora.repository;

import com.finora.entity.SupportTicketInternalNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only. Nothing in a user-facing code path may call any method on this interface.
 *
 * <p>There is deliberately no update or delete method here, and none should be added: the notes
 * table is append-only by decision, and its rows disappear only when the parent ticket is deleted
 * during account purge (via {@code ON DELETE CASCADE} on {@code ticket_id}).
 */
public interface SupportTicketInternalNoteRepository extends JpaRepository<SupportTicketInternalNote, UUID> {

    /** Oldest first: a note thread reads in the order it was written. */
    List<SupportTicketInternalNote> findByTicketIdOrderByCreatedAtAsc(UUID ticketId);

    long countByTicketId(UUID ticketId);
}
