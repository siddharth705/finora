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
    private HeldItemAdminAlertService heldItemAdminAlertService;
    private NotificationService notificationService;
    private HeldStatementService service;

    @BeforeEach
    void setUp() {
        repository = mock(HeldStatementRepository.class);
        eventRepository = mock(HeldStatementEventRepository.class);
        idGenerator = mock(HeldStatementIdGenerator.class);
        importJobRepository = mock(ImportJobRepository.class);
        ImportVerificationFindingRepository findingRepository = mock(ImportVerificationFindingRepository.class);
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
}
