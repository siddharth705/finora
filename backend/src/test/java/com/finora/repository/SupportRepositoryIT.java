package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.SupportTicketInternalNote;
import com.finora.entity.User;
import com.finora.support.SupportTicketIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the support domain only a real PostgreSQL can prove: that the entities match
 * V145–V148, that every {@code @Query} string actually parses and returns what its callers will
 * need, that {@code bytea} round-trips, and that deleting a ticket takes its children with it.
 *
 * <p>None of the {@code @Query} annotations in these repositories are checked by the compiler. A
 * typo in one surfaces at context startup or first call, so exercising each of them here is the
 * only thing that makes them verified rather than merely written.
 *
 * <p><b>Every assertion is scoped to a user this test created.</b> The whole {@code *IT}
 * population shares one database, so counting rows globally or asserting on an ordered
 * {@code LIMIT} across all rows would make these tests pass alone and fail in the suite.
 *
 * <p>The transition rules themselves are asserted in {@code SupportTicketStatusTest}, in memory.
 */
class SupportRepositoryIT extends AbstractIntegrationTest {

    @Autowired private SupportTicketRepository tickets;
    @Autowired private SupportTicketAttachmentRepository attachments;
    @Autowired private SupportTicketInternalNoteRepository notes;
    @Autowired private FeedbackEntryRepository feedback;
    @Autowired private UserRepository users;
    @Autowired private SupportTicketIdGenerator idGenerator;

    private User user() {
        User u = new User();
        u.setEmail("support-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Support Test");
        return users.save(u);
    }

    private SupportTicket ticket(UUID userId) {
        SupportTicket t = new SupportTicket();
        t.setTicketNumber(idGenerator.next());
        t.setUserId(userId);
        t.setCategory(SupportTicket.Category.STATEMENT_IMPORT);
        t.setSource(ClientPlatform.WEB);
        t.setSubject("Import stopped halfway");
        t.setDescription("The progress bar reached 60% and then nothing happened.");
        // BaseEntity's @Version means Spring Data calls merge(), not persist() -- the returned
        // instance is the managed one, so returning the argument here would hand back a detached
        // object with a null id. See BaseEntity's own doc comment.
        return tickets.save(t);
    }

    @Test
    void mintsSequentialHumanReferences() {
        String first = idGenerator.next();
        String second = idGenerator.next();

        assertThat(first).startsWith("SUP-").matches("SUP-\\d{6,}");
        assertThat(second).isNotEqualTo(first);
        assertThat(Long.parseLong(second.substring(4)))
                .isGreaterThan(Long.parseLong(first.substring(4)));
    }

    @Test
    void aTicketIsReadableOnlyByItsOwner() {
        User owner = user();
        User stranger = user();
        SupportTicket t = ticket(owner.getId());

        assertThat(tickets.findByIdAndUserId(t.getId(), owner.getId())).isPresent();
        // The whole point of putting ownership in the query rather than in an if afterwards.
        assertThat(tickets.findByIdAndUserId(t.getId(), stranger.getId())).isEmpty();
    }

    @Test
    void listsOnlyTheOwnersOwnTicketsNewestFirst() {
        User owner = user();
        User stranger = user();
        SupportTicket older = ticket(owner.getId());
        SupportTicket newer = ticket(owner.getId());
        ticket(stranger.getId());

        List<SupportTicket> page = tickets
                .findByUserIdOrderByCreatedAtDesc(owner.getId(), PageRequest.of(0, 10))
                .getContent();

        assertThat(page).extracting(SupportTicket::getId)
                .containsExactlyInAnyOrder(older.getId(), newer.getId());
    }

    @Test
    void findsByTheReferenceACustomerQuotes() {
        SupportTicket t = ticket(user().getId());

        assertThat(tickets.findByTicketNumber(t.getTicketNumber())).isPresent();
        assertThat(tickets.findByTicketNumber("SUP-999999999")).isEmpty();
    }

    @Test
    void statusAndEnumsRoundTripAsStrings() {
        SupportTicket t = ticket(user().getId());

        SupportTicket reloaded = tickets.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SupportTicket.Status.OPEN);
        assertThat(reloaded.getCategory()).isEqualTo(SupportTicket.Category.STATEMENT_IMPORT);
        assertThat(reloaded.getSource()).isEqualTo(ClientPlatform.WEB);
        // deleted_at exists because of BaseEntity and must stay null: there is no delete path.
        assertThat(reloaded.getDeletedAt()).isNull();
    }

    @Test
    void attachmentBytesRoundTripThroughBytea() {
        SupportTicket t = ticket(user().getId());
        byte[] bytes = "%PDF-1.4 not really a pdf".getBytes(StandardCharsets.UTF_8);

        SupportTicketAttachment saved = attachments.save(attachment(t.getId(), bytes));

        assertThat(attachments.findById(saved.getId()).orElseThrow().getContent())
                .isEqualTo(bytes);
    }

    @Test
    void attachmentMetadataProjectionDoesNotSelectTheBytes() {
        SupportTicket t = ticket(user().getId());
        attachments.save(attachment(t.getId(), new byte[] {1, 2, 3, 4}));

        List<SupportTicketAttachmentRepository.AttachmentMetadata> meta =
                attachments.findMetadataByTicketId(t.getId());

        assertThat(meta).hasSize(1);
        assertThat(meta.get(0).getFilename()).isEqualTo("statement.pdf");
        assertThat(meta.get(0).getSizeBytes()).isEqualTo(4L);
    }

    @Test
    void anAttachmentCannotBeFetchedThroughSomeoneElsesTicket() {
        SupportTicket mine = ticket(user().getId());
        SupportTicket theirs = ticket(user().getId());
        SupportTicketAttachment a = attachments.save(attachment(mine.getId(), new byte[] {9}));

        assertThat(attachments.findByIdAndTicketId(a.getId(), mine.getId())).isPresent();
        assertThat(attachments.findByIdAndTicketId(a.getId(), theirs.getId())).isEmpty();
    }

    @Test
    void notesReadOldestFirst() {
        SupportTicket t = ticket(user().getId());
        UUID admin = user().getId();
        notes.save(note(t.getId(), admin, "reproduced on Android 1.3.7"));
        notes.save(note(t.getId(), admin, "waiting on the next deploy"));

        assertThat(notes.findByTicketIdOrderByCreatedAtAsc(t.getId()))
                .extracting(SupportTicketInternalNote::getNote)
                .containsExactly("reproduced on Android 1.3.7", "waiting on the next deploy");
        assertThat(notes.countByTicketId(t.getId())).isEqualTo(2);
    }

    /**
     * The append-only guarantee, at the level that actually enforces it.
     *
     * <p>V147 gives {@code admin_id} {@code ON DELETE SET NULL} rather than {@code CASCADE} so an
     * admin leaving the company cannot erase their note history through a path nobody thinks of as
     * deletion.
     */
    @Test
    void deletingTheAuthorKeepsTheNoteAndNullsTheAuthor() {
        SupportTicket t = ticket(user().getId());
        User admin = user();
        SupportTicketInternalNote n = notes.save(note(t.getId(), admin.getId(), "looked at it"));

        users.deleteById(admin.getId());

        SupportTicketInternalNote reloaded = notes.findById(n.getId()).orElseThrow();
        assertThat(reloaded.getNote()).isEqualTo("looked at it");
        assertThat(reloaded.getAdminId()).isNull();
    }

    @Test
    void deletingTheClaimingAdminKeepsTheTicketAndReleasesTheClaim() {
        SupportTicket t = ticket(user().getId());
        User admin = user();
        t.setClaimedByAdminId(admin.getId());
        tickets.save(t);

        users.deleteById(admin.getId());

        assertThat(tickets.findById(t.getId()).orElseThrow().getClaimedByAdminId()).isNull();
    }

    /**
     * The account-purge path, which is NOT the same as deleting a users row.
     *
     * <p>{@code AccountPurgeSweepService.purgeOne} anonymizes the user rather than deleting it, so
     * the {@code ON DELETE CASCADE} on {@code user_id} never fires there. These bulk deletes are
     * what Phase 6 has to call instead — and deleting the ticket is what takes its attachment and
     * its notes with it, through the cascade on {@code ticket_id}.
     */
    @Test
    @Transactional
    void purgingAUserByBulkDeleteAlsoRemovesAttachmentsAndNotes() {
        User owner = user();
        SupportTicket t = ticket(owner.getId());
        attachments.save(attachment(t.getId(), new byte[] {7, 7}));
        notes.save(note(t.getId(), user().getId(), "internal"));
        feedback.save(feedbackEntry(owner.getId()));

        assertThat(tickets.deleteByUserId(owner.getId())).isEqualTo(1);
        assertThat(feedback.deleteByUserId(owner.getId())).isEqualTo(1);

        assertThat(tickets.findById(t.getId())).isEmpty();
        assertThat(attachments.findByTicketId(t.getId())).isEmpty();
        assertThat(notes.findByTicketIdOrderByCreatedAtAsc(t.getId())).isEmpty();
    }

    @Test
    void feedbackGroupsByTypeContextAndSource() {
        UUID owner = user().getId();
        feedback.save(feedbackEntry(owner));

        List<FeedbackEntryRepository.FeedbackBreakdown> rows = feedback.countGrouped();

        // Scoped by content rather than by count: other IT classes share this table.
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getType()).isEqualTo(FeedbackEntry.Type.BUG);
            assertThat(r.getContext()).isEqualTo(FeedbackEntry.Context.IMPORT_FLOW);
            assertThat(r.getSource()).isEqualTo(ClientPlatform.WEB);
            assertThat(r.getTotal()).isGreaterThanOrEqualTo(1L);
        });
    }

    private SupportTicketAttachment attachment(UUID ticketId, byte[] bytes) {
        SupportTicketAttachment a = new SupportTicketAttachment();
        a.setTicketId(ticketId);
        a.setFilename("statement.pdf");
        a.setContentType("application/pdf");
        a.setSizeBytes(bytes.length);
        a.setSha256Hash("0".repeat(64));
        a.setContent(bytes);
        return a;
    }

    private SupportTicketInternalNote note(UUID ticketId, UUID adminId, String body) {
        SupportTicketInternalNote n = new SupportTicketInternalNote();
        n.setTicketId(ticketId);
        n.setAdminId(adminId);
        n.setNote(body);
        return n;
    }

    private FeedbackEntry feedbackEntry(UUID userId) {
        FeedbackEntry f = new FeedbackEntry();
        f.setUserId(userId);
        f.setType(FeedbackEntry.Type.BUG);
        f.setContext(FeedbackEntry.Context.IMPORT_FLOW);
        f.setSource(ClientPlatform.WEB);
        f.setMessage("The import bar sticks at 60%.");
        return f;
    }
}
