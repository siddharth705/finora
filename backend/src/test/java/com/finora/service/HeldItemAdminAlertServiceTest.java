package com.finora.service;

import com.finora.config.EmailProperties;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HeldItemAdminAlertServiceTest {

    private UserRepository userRepository;
    private ImportJobRepository importJobRepository;
    private HeldStatementRepository heldStatementRepository;
    private EmailProvider emailProvider;
    private EmailProperties emailProperties;
    private HeldItemAdminAlertService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        heldStatementRepository = mock(HeldStatementRepository.class);
        emailProvider = mock(EmailProvider.class);
        emailProperties = mock(EmailProperties.class);
        when(emailProperties.getAdminAppBaseUrl()).thenReturn("https://admin.example.com");
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));

        service = new HeldItemAdminAlertService(userRepository, importJobRepository,
                heldStatementRepository, emailProvider, emailProperties);
    }

    private User adminUser(String email) {
        User user = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail(email);
        user.setAccountScope(User.SCOPE_ADMIN);
        return user;
    }

    /** Mirrors what {@code ImportJobWorker.recordFailure} actually does: {@code recordFailure}
     *  first (this is what populates {@code lastError}), then {@code holdForReview} -- not
     *  {@code holdForReview} alone, which never touches {@code lastError}. */
    private ImportJob heldJob() {
        ImportJob job = new ImportJob(UUID.randomUUID(), "Paytm_Statement_January_2026.pdf",
                "hash", "objects/key", "PDF");
        job.markClaimed("worker", Instant.now());
        job.recordFailure("Finora could not find a transaction table anywhere in this statement.",
                "IMPORT_NO_HEADER_DETECTED", ErrorCode.RetryPolicy.FAIL_FAST, Instant.now());
        job.holdForReview("IMPORT_NO_HEADER_DETECTED", Instant.now());
        return job;
    }

    @Test
    void alertParserGapHeld_emailsEveryAdminHoldingImportTriageManage() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        User admin1 = adminUser("triage-admin-1@example.com");
        User admin2 = adminUser("triage-admin-2@example.com");
        when(userRepository.findByPermissionNameAndAccountScope("IMPORT_TRIAGE_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin1, admin2));

        service.alertParserGapHeld(job.getId());

        verify(emailProvider).send(argThatEmailTo("triage-admin-1@example.com"));
        verify(emailProvider).send(argThatEmailTo("triage-admin-2@example.com"));
    }

    @Test
    void alertParserGapHeld_includesTheFileNameAndAnAdminPortalLink() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(adminUser("triage-admin@example.com")));

        service.alertParserGapHeld(job.getId());

        org.mockito.ArgumentCaptor<EmailMessage> captor = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.html()).contains("Paytm_Statement_January_2026.pdf");
        assertThat(sent.html()).contains("Finora could not find a transaction table anywhere in this statement.");
        assertThat(sent.html()).contains("https://admin.example.com/held-imports");
    }

    @Test
    void alertParserGapHeld_sendsNothingWhenNoAdminHoldsThePermission() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(userRepository.findByPermissionNameAndAccountScope(any(), any())).thenReturn(List.of());

        service.alertParserGapHeld(job.getId());

        verify(emailProvider, never()).send(any());
    }

    @Test
    void alertParserGapHeld_sendsNothingWhenTheJobNoLongerExists() {
        UUID jobId = UUID.randomUUID();
        when(importJobRepository.findById(jobId)).thenReturn(Optional.empty());

        service.alertParserGapHeld(jobId);

        verify(userRepository, never()).findByPermissionNameAndAccountScope(any(), any());
        verify(emailProvider, never()).send(any());
    }

    @Test
    void alertParserGapHeld_oneRecipientsFailureDoesNotStopTheOthers() {
        ImportJob job = heldJob();
        when(importJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        User failing = adminUser("bounces@example.com");
        User succeeding = adminUser("triage-admin@example.com");
        when(userRepository.findByPermissionNameAndAccountScope(any(), any()))
                .thenReturn(List.of(failing, succeeding));
        when(emailProvider.send(argThatEmailTo("bounces@example.com")))
                .thenReturn(EmailResult.failure(ProviderType.RESEND, "mailbox does not exist"));

        service.alertParserGapHeld(job.getId());

        verify(emailProvider).send(argThatEmailTo("triage-admin@example.com"));
    }

    private static EmailMessage argThatEmailTo(String email) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && email.equals(m.to()));
    }
}
