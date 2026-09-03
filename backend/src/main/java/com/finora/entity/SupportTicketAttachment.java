package com.finora.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The bytes a user attached to a support ticket.
 *
 * <p>A child table rather than columns on {@link SupportTicket}, because the download URL carries
 * an attachment id ({@code /attachments/{attachmentId}}) that a single column cannot supply. The
 * v1 UI accepts exactly one file; only the storage shape is plural, which makes the eventual
 * single-to-multiple change a validation change rather than a backfill against a live table.
 *
 * <h2>{@code content} is lazy, and that is not a micro-optimisation</h2>
 *
 * <p>This repository has already shipped this exact bug once: {@code StatementImport.fileContent}
 * was eagerly loaded, so listing a user's statements pulled every file's bytes into memory. Six
 * call sites had to be fixed. A support ticket detail view lists its attachments by name and size
 * far more often than anyone downloads one, so the same shape would repeat the same defect.
 *
 * <p>{@code @Basic(fetch = LAZY)} needs bytecode enhancement to be honoured, which this build may
 * or may not apply — so it is a hint, not a guarantee. The load-bearing protection is that callers
 * listing attachments must use a projection that does not select {@code content} at all. See
 * {@code SupportTicketAttachmentRepository.findMetadataByTicketId}.
 */
@Entity
@Table(name = "support_ticket_attachments")
public class SupportTicketAttachment extends BaseEntity {

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    /** Bounded at 120 chars in the schema: attacker-chosen, persisted, rendered, echoed in a header. */
    @Column(name = "filename", nullable = false)
    private String filename;

    /** The validated type, never whatever the client claimed. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Hex SHA-256 of {@link #content}; 64 characters. */
    @Column(name = "sha256_hash", nullable = false)
    private String sha256Hash;

    /**
     * {@code @JdbcTypeCode(VARBINARY)} is copied from {@code StatementImport.fileContent}, the only
     * other large binary column in this schema, rather than trusting Hibernate's default mapping
     * for {@code byte[]} — the alternative on Postgres is a large-object {@code oid}, which is not
     * what V146 declares.
     */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    public UUID getTicketId() { return ticketId; }
    public void setTicketId(UUID ticketId) { this.ticketId = ticketId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
