package com.finora.support;

import com.finora.entity.ClientPlatform;
import com.finora.entity.SupportTicket;
import com.finora.exception.ApiException;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketInternalNoteRepository;
import com.finora.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito rather than a Spring-context IT — matches {@code AdminHeldImportServiceTest}'s own
 * precedent: what's being asserted here (validation, the status matrix, ownership branching) is
 * pure logic that doesn't need Postgres to prove. The dual-audience read path and the real
 * cross-user attachment seam are covered separately by {@code SupportTicketApiIT}, which is the one
 * that actually needs a database.
 */
class SupportTicketServiceTest {

    private SupportTicketRepository ticketRepository;
    private SupportTicketAttachmentRepository attachmentRepository;
    private SupportTicketInternalNoteRepository noteRepository;
    private SupportTicketIdGenerator idGenerator;
    private ClientIdentity clientIdentity;
    private SupportTicketService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ticketRepository = mock(SupportTicketRepository.class);
        attachmentRepository = mock(SupportTicketAttachmentRepository.class);
        noteRepository = mock(SupportTicketInternalNoteRepository.class);
        idGenerator = mock(SupportTicketIdGenerator.class);
        clientIdentity = mock(ClientIdentity.class);
        service = new SupportTicketService(ticketRepository, attachmentRepository, noteRepository, idGenerator, clientIdentity);

        when(ticketRepository.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));
        when(idGenerator.next()).thenReturn("SUP-000001");
        when(clientIdentity.platform()).thenReturn(ClientPlatform.WEB);
        when(clientIdentity.appVersion()).thenReturn(null);
    }

    // --- create() validation ---------------------------------------------------------------

    @Test
    void rejectsAnUnknownCategory() {
        assertThatThrownBy(() -> service.create(userId, "NOT_A_REAL_CATEGORY", "Subject", "Description", null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
    }

    @Test
    void rejectsABlankSubject() {
        assertThatThrownBy(() -> service.create(userId, "OTHER", "   ", "a real description", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsASubjectOverTheHundredTwentyCharacterColumnBound() {
        String tooLong = "x".repeat(121);
        assertThatThrownBy(() -> service.create(userId, "OTHER", tooLong, "description", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void createsATicketWithNoAttachmentWhenNoFileIsGiven() {
        var detail = service.create(userId, "TECHNICAL_ISSUE", "Import failing", "It just spins", null);

        assertThat(detail.ticketNumber()).isEqualTo("SUP-000001");
        assertThat(detail.attachments()).isEmpty();
        assertThat(detail.source()).isEqualTo(ClientPlatform.WEB);
    }

    // --- status transitions ------------------------------------------------------------------

    private SupportTicket ticketWithStatus(SupportTicket.Status status) {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        ticket.setStatus(status);
        when(ticketRepository.findById(any())).thenReturn(Optional.of(ticket));
        return ticket;
    }

    @Test
    void openMovesToAnyOtherStatus() {
        ticketWithStatus(SupportTicket.Status.OPEN);
        var result = service.updateStatus(UUID.randomUUID(), SupportTicket.Status.CLOSED);
        assertThat(result.status()).isEqualTo(SupportTicket.Status.CLOSED);
    }

    @Test
    void resolvedCannotMoveBackToInProgress() {
        ticketWithStatus(SupportTicket.Status.RESOLVED);

        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), SupportTicket.Status.IN_PROGRESS))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    }

    @Test
    void closedIsTerminal() {
        ticketWithStatus(SupportTicket.Status.CLOSED);

        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), SupportTicket.Status.OPEN))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resolvingATicketStampsResolvedAt() {
        ticketWithStatus(SupportTicket.Status.IN_PROGRESS);
        var result = service.updateStatus(UUID.randomUUID(), SupportTicket.Status.RESOLVED);
        assertThat(result.status()).isEqualTo(SupportTicket.Status.RESOLVED);
    }

    // --- ownership: dual-audience reads -------------------------------------------------------

    @Test
    void aRegularCallerReadsOnlyTheirOwnTicket_viaTheOwnerScopedQuery() {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId);
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findMetadataByTicketId(any())).thenReturn(List.of());

        var detail = service.getDetail(userId, false, ticketId);

        assertThat(detail).isNotNull();
    }

    @Test
    void aRegularCaller_cannotReadSomeoneElsesTicket() {
        UUID ticketId = UUID.randomUUID();
        // findByIdAndUserId returns empty -- the ticket exists but not for this caller.
        when(ticketRepository.findByIdAndUserId(ticketId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(userId, false, ticketId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
    }

    @Test
    void anAdminCaller_readsAnyTicket_viaTheUnscopedQuery() {
        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(UUID.randomUUID()); // owned by someone else entirely
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findMetadataByTicketId(any())).thenReturn(List.of());

        var detail = service.getDetail(adminId, true, ticketId);

        assertThat(detail).isNotNull();
    }

    // --- claim / unclaim ---------------------------------------------------------------------

    @Test
    void claimingAnAlreadyClaimedTicketSucceeds_andRecordsTheNewClaimant() {
        SupportTicket ticket = new SupportTicket();
        ticket.setClaimedByAdminId(UUID.randomUUID()); // someone else already has it
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        var result = service.claim(adminId, ticketId);

        assertThat(result.claimedByAdminId()).isEqualTo(adminId);
    }

    @Test
    void unclaimReleasesTheTicketRegardlessOfWhoClaimedIt() {
        SupportTicket ticket = new SupportTicket();
        ticket.setClaimedByAdminId(UUID.randomUUID());
        UUID ticketId = UUID.randomUUID();
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(ticket));

        var result = service.unclaim(ticketId);

        assertThat(result.claimedByAdminId()).isNull();
    }
}
