package com.finora.repository;

import com.finora.entity.SupportTicketAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportTicketAttachmentRepository extends JpaRepository<SupportTicketAttachment, UUID> {

    /**
     * Attachment metadata WITHOUT the bytes.
     *
     * <p>Use this everywhere a list is rendered. {@code StatementImport.fileContent} was eagerly
     * loaded once in this codebase and listing a user's statements pulled every file into memory;
     * six call sites had to be fixed afterwards. A projection is the protection that actually
     * holds, because {@code @Basic(fetch = LAZY)} on a {@code byte[]} is only honoured with
     * bytecode enhancement.
     */
    @Query("""
            SELECT a.id AS id, a.ticketId AS ticketId, a.filename AS filename,
                   a.contentType AS contentType, a.sizeBytes AS sizeBytes
              FROM SupportTicketAttachment a
             WHERE a.ticketId = :ticketId
             ORDER BY a.createdAt
            """)
    List<AttachmentMetadata> findMetadataByTicketId(UUID ticketId);

    /** The batched counterpart, for a caller building metadata across several tickets at once
     *  (e.g. {@code DataExportService}) — one query instead of one per ticket. {@code ticketId} on
     *  the projection is what makes grouping the flat result list back by ticket possible. */
    @Query("""
            SELECT a.id AS id, a.ticketId AS ticketId, a.filename AS filename,
                   a.contentType AS contentType, a.sizeBytes AS sizeBytes
              FROM SupportTicketAttachment a
             WHERE a.ticketId IN :ticketIds
             ORDER BY a.ticketId, a.createdAt
            """)
    List<AttachmentMetadata> findMetadataByTicketIdIn(List<UUID> ticketIds);

    /** A projection, deliberately without {@code content}. */
    interface AttachmentMetadata {
        UUID getId();
        UUID getTicketId();
        String getFilename();
        String getContentType();
        long getSizeBytes();
    }

    /**
     * The download path. Scoped by ticket as well as id so an attachment id belonging to a
     * different ticket cannot be fetched by guessing — ownership of the ticket is checked
     * separately, and this closes the second half of the pair.
     */
    Optional<SupportTicketAttachment> findByIdAndTicketId(UUID id, UUID ticketId);

    List<SupportTicketAttachment> findByTicketId(UUID ticketId);
}
