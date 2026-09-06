package com.finora.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * An admin's operational note on a ticket — "reproduced on Android 1.3.7", "waiting on the next
 * deploy", "linked to bug #452".
 *
 * <p><b>Append-only.</b> There is no update endpoint and no delete endpoint, by decision. Nothing
 * in this class or its repository should ever grow a mutator for {@link #note}.
 *
 * <h2>Why this is its own table</h2>
 *
 * <p>A single mutable {@code adminNote} column on {@link SupportTicket} would carry no author and
 * would silently overwrite the previous note. The portal is multi-admin, so that loses exactly the
 * information the field exists to capture.
 *
 * <p>Folding these into a customer-visible message table is the Zendesk public/internal comment
 * shape, whose well-known failure mode is one missing filter rendering an admin's internal notes
 * inside the user's own ticket view. A separate table cannot fail that way: there is no query path
 * from any user-facing endpoint to it at all. Given this application handles financial data, that
 * structural guarantee is worth one extra table.
 *
 * <p>This entity must therefore never be reachable from a user-facing response — not serialised,
 * not joined, not counted. It is also deliberately absent from the user's data export: these are
 * Finora's operational records, not the user's own data.
 */
@Entity
@Table(name = "support_ticket_internal_notes")
public class SupportTicketInternalNote extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    /**
     * Who wrote it, or null if their account has since been removed.
     *
     * <p>Nullable on purpose. V145's sibling column and this one both use {@code ON DELETE SET
     * NULL} rather than {@code CASCADE}, because a cascade here would erase an admin's entire note
     * history the day their account is deleted — defeating this table's append-only guarantee
     * through a path nobody thinks of as deletion. Null means the author is gone, not that the
     * system wrote the note.
     */
    @Column(name = "admin_id")
    private UUID adminId;

    @Column(name = "note", nullable = false)
    private String note;

    public UUID getTicketId() { return ticketId; }
    public void setTicketId(UUID ticketId) { this.ticketId = ticketId; }

    public UUID getAdminId() { return adminId; }
    public void setAdminId(UUID adminId) { this.adminId = adminId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
