package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.jobs.ParserVersionProvider;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.storage.StatementContentService;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.imports.trust.TrustPredicate;
import com.finora.notification.api.NotificationService;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeldStatementServiceTest {

    private HeldStatementRepository repository;
    private HeldStatementEventRepository eventRepository;
    private HeldStatementIdGenerator idGenerator;
    private ImportJobRepository importJobRepository;
    private ImportVerificationFindingRepository findingRepository;
    private HeldItemAdminAlertService heldItemAdminAlertService;
    private NotificationService notificationService;
    private HeldStatementService service;

    @BeforeEach
    void setUp() {
        repository = mock(HeldStatementRepository.class);
        eventRepository = mock(HeldStatementEventRepository.class);
        idGenerator = mock(HeldStatementIdGenerator.class);
        importJobRepository = mock(ImportJobRepository.class);
        findingRepository = mock(ImportVerificationFindingRepository.class);
        AuditService auditService = mock(AuditService.class);
        notificationService = mock(NotificationService.class);
        ImportSessionService importSessionService = mock(ImportSessionService.class);
        StatementContentService statementContentService = mock(StatementContentService.class);
        ImportService importService = mock(ImportService.class);
        ParserVersionProvider parserVersionProvider = mock(ParserVersionProvider.class);
        heldItemAdminAlertService = mock(HeldItemAdminAlertService.class);

        when(idGenerator.next()).thenReturn("HELD-00099");
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByImportJobId(any())).thenReturn(Optional.empty());

        service = new HeldStatementService(repository, eventRepository, idGenerator, importJobRepository,
                findingRepository, auditService, notificationService, importSessionService,
                new ObjectMapper(), statementContentService, importService, parserVersionProvider,
                heldItemAdminAlertService);
    }

    private ImportJob job() {
        return new ImportJob(UUID.randomUUID(), "statement.csv", "hash", "objects/key", "CSV");
    }

    private HoldDecision periodIntegrityDecision() {
        return new HoldDecision(true, List.of("Statement period ends before it starts"),
                List.of(TrustPredicate.Category.PERIOD_INTEGRITY));
    }

    @Test
    void createHold_triggersTheAdminAlertWithTheNewHoldsId() {
        ImportJob job = job();
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        verify(heldItemAdminAlertService).alertTrustReviewHeld("HELD-00099");
    }

    /**
     * The user side of the same moment: previously nothing told them their statement was being
     * held at all (found live in testing). Checked here, not just on the admin-alert side, since
     * openHold is the one place both fire.
     */
    @Test
    void createHold_notifiesTheUserTheStatementIsHeld() {
        ImportJob job = job();
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        org.mockito.ArgumentCaptor<com.finora.notification.api.NotificationRequest> captor =
                org.mockito.ArgumentCaptor.forClass(com.finora.notification.api.NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        com.finora.notification.api.NotificationRequest sent = captor.getValue();
        assertThat(sent.type())
                .isEqualTo(com.finora.notification.domain.NotificationType.IMPORT_STATEMENT_HELD);
        assertThat(sent.userId()).isEqualTo(job.getUserId());
        assertThat(sent.notificationKey()).isEqualTo("IMPORT_HELD_" + job.getId());
        assertThat(sent.channels()).containsExactlyInAnyOrder(
                com.finora.notification.domain.NotificationChannel.PUSH,
                com.finora.notification.domain.NotificationChannel.EMAIL);
    }

    /** {@code createHold} is idempotent on the job id (its own doc comment) -- a second call for
     *  the same job must not open a second hold, and therefore must not send a second alert or a
     *  second user notification. */
    @Test
    void createHold_doesNotAlertASecondTime_whenAHoldAlreadyExistsForThisJob() {
        ImportJob job = job();
        HeldStatement existing = new HeldStatement("HELD-00001", job.getId(), job.getUserId(),
                job.getObjectKey(), "already held");
        when(repository.findByImportJobId(job.getId())).thenReturn(Optional.of(existing));
        StagedForJob staged = new StagedForJob(UUID.randomUUID(), 5, 5, "HDFC Bank", List.of(), List.of());

        service.createHold(job, staged, periodIntegrityDecision(), "abc123");

        verify(heldItemAdminAlertService, never()).alertTrustReviewHeld(any());
        verify(notificationService, never()).request(any());
    }

    // ------------------------------------------------------------------ detail

    /**
     * Found in review: the download endpoint's own Content-Disposition header has always carried
     * the real filename, but nothing on the client read it, and the client's established
     * convention is to be handed a filename by the caller rather than parse that header -- so the
     * detail view (where the download button lives) needs to actually carry it. Read live from
     * ImportJob rather than snapshotted onto HeldStatement, since the download endpoint already
     * depends on that same job existing to retrieve the bytes at all.
     */
    @Test
    void detail_carriesTheOriginalFileName() {
        HeldStatement held = new HeldStatement("HELD-00050", UUID.randomUUID(), UUID.randomUUID(),
                "objects/key", "already held");
        when(repository.findByHeldId("HELD-00050")).thenReturn(Optional.of(held));
        ImportJob job = new ImportJob(held.getUserId(), "sbi-statement.csv", "hash",
                held.getStatementObjectKey(), "CSV");
        when(importJobRepository.findById(held.getImportJobId())).thenReturn(Optional.of(job));
        when(findingRepository.findByImportJobIdOrderBySectionIndexAscRuleAsc(any())).thenReturn(List.of());
        when(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        var detail = service.detail("HELD-00050");

        assertThat(detail.fileName()).isEqualTo("sbi-statement.csv");
    }

    /**
     * The one case {@code requireJob}'s own doc names -- a job deleted out from under an open
     * review. The detail view must still render (it is a read-only view, not the download itself),
     * just without a filename to offer.
     */
    @Test
    void detail_hasNoFileNameWhenTheUnderlyingJobIsGone() {
        HeldStatement held = new HeldStatement("HELD-00051", UUID.randomUUID(), UUID.randomUUID(),
                "objects/key", "already held");
        when(repository.findByHeldId("HELD-00051")).thenReturn(Optional.of(held));
        when(importJobRepository.findById(held.getImportJobId())).thenReturn(Optional.empty());
        when(findingRepository.findByImportJobIdOrderBySectionIndexAscRuleAsc(any())).thenReturn(List.of());
        when(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        var detail = service.detail("HELD-00051");

        assertThat(detail.fileName()).isNull();
    }
}
