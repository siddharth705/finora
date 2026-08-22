package com.finora.integrations.google.merchant;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.RowKind;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase C5.4, D-15. Unit-level: every collaborator (session repository/service, confirm, account
 * lookup/creation) is mocked, so this tests {@link GmailReviewService}'s own logic -- routing,
 * account find-or-create, the category-override rule -- not the machinery it delegates to.
 * {@code GmailReviewServiceIT} covers the real end-to-end path against Postgres.
 */
class GmailReviewServiceTest {

    private ImportSessionRepository importSessionRepository;
    private ImportSessionService importSessionService;
    private ImportService importService;
    private AccountRepository accountRepository;
    private AccountService accountService;
    private GmailProcessedMessageRepository processedMessages;
    private GmailReviewService reviewService;

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        importSessionRepository = mock(ImportSessionRepository.class);
        importSessionService = mock(ImportSessionService.class);
        importService = mock(ImportService.class);
        accountRepository = mock(AccountRepository.class);
        accountService = mock(AccountService.class);
        processedMessages = mock(GmailProcessedMessageRepository.class);
        reviewService = new GmailReviewService(importSessionRepository, importSessionService,
                importService, accountRepository, accountService, processedMessages);
    }

    @Test
    @DisplayName("transactions-found counts PARSED messages for this connection")
    void countTransactionsFoundReadsParsedOutcomeForTheConnection() {
        UUID connectionId = UUID.randomUUID();
        when(processedMessages.countByConnectionIdAndOutcome(connectionId, GmailProcessedMessage.Outcome.PARSED))
                .thenReturn(7L);

        assertThat(reviewService.countTransactionsFound(connectionId)).isEqualTo(7);
    }

    @Test
    @DisplayName("needs-review counts staged Gmail sessions for this user")
    void countNeedsReviewReadsStagedGmailSessionsForTheUser() {
        when(importSessionRepository.countByUserIdAndSourceAndStatus(
                userId, ImportSession.SOURCE_GMAIL, ImportSession.STATUS_STAGED))
                .thenReturn(3L);

        assertThat(reviewService.countNeedsReview(userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("a staged session becomes one queue item with a friendly merchant name")
    void listPendingMapsSessionsToDisplayItems() {
        ImportSession session = gmailSession();
        when(importSessionRepository.findByUserIdAndSourceAndStatusOrderByCreatedAtDesc(
                userId, ImportSession.SOURCE_GMAIL, ImportSession.STATUS_STAGED))
                .thenReturn(List.of(session));
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow("amazon.in")));

        List<GmailReviewItemDto> items = reviewService.listPending(userId);

        assertThat(items).hasSize(1);
        GmailReviewItemDto item = items.get(0);
        assertThat(item.sessionId()).isEqualTo(sessionId);
        assertThat(item.merchant()).isEqualTo("Amazon");
        assertThat(item.merchantDomain()).isEqualTo("amazon.in");
        assertThat(item.amount()).isEqualByComparingTo("1299.00");
        assertThat(item.confidence()).isEqualTo(0.9);
        assertThat(item.reasoning())
                .isEqualTo("Amount and date read from a verified Amazon email. "
                        + "Category isn't auto-detected yet, so it defaults to \"Other\" — check it below.");
    }

    @Test
    @DisplayName("a domain with no known display name falls back to the domain itself")
    void unknownDomainFallsBackToItself() {
        assertThat(GmailReviewService.displayNameFor("unknown-merchant.example"))
                .isEqualTo("unknown-merchant.example");
    }

    @Test
    @DisplayName("reasoning names the default-category caveat when categorySource is \"default\"")
    void reasoningFlagsDefaultCategory() {
        StagedRow row = stagedRow("amazon.in");

        assertThat(GmailReviewService.reasoningFor("amazon.in", row))
                .isEqualTo("Amount and date read from a verified Amazon email. "
                        + "Category isn't auto-detected yet, so it defaults to \"Other\" — check it below.");
    }

    @Test
    @DisplayName("reasoning drops the default-category caveat once a real category source exists")
    void reasoningOmitsCaveatForNonDefaultSource() {
        StagedRow row = new StagedRow(
                LocalDate.of(2026, 8, 15), "amazon.in", new BigDecimal("1299.00"), "EXPENSE",
                "Shopping", "learned", null, false, null, null, null, RowKind.TRANSACTION, 0.9);

        assertThat(GmailReviewService.reasoningFor("amazon.in", row))
                .isEqualTo("Amount and date read from a verified Amazon email.");
    }

    @Test
    @DisplayName("approving reuses the existing Gmail receipts account when one already exists")
    void approveReusesExistingAccount() {
        ImportSession session = gmailSession();
        UUID accountId = UUID.randomUUID();
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow("amazon.in")));
        Account existing = mock(Account.class);
        when(existing.getId()).thenReturn(accountId);
        when(accountRepository.findFirstByUserIdAndName(userId, "Gmail receipts"))
                .thenReturn(Optional.of(existing));

        reviewService.approve(userId, sessionId, null);

        verify(accountService, never()).create(any(), any(), any());
        ArgumentCaptor<ConfirmRequest> captor = ArgumentCaptor.forClass(ConfirmRequest.class);
        verify(importService).confirmSession(eq(userId), captor.capture());
        ConfirmRequest request = captor.getValue();
        assertThat(request.sessionId()).isEqualTo(sessionId);
        assertThat(request.existingAccountId()).isEqualTo(accountId);
        assertThat(request.newAccount()).isNull();
        assertThat(request.rows()).hasSize(1);
        ConfirmedRow row = request.rows().get(0);
        assertThat(row.include()).isTrue();
        assertThat(row.confirmedNotDuplicate()).isTrue();
        assertThat(row.category()).isEqualTo("Other"); // the staged row's own suggestedCategory, unedited
    }

    @Test
    @DisplayName("approving creates the Gmail receipts account the first time, none exists yet")
    void approveCreatesAccountWhenNoneExists() {
        ImportSession session = gmailSession();
        UUID createdAccountId = UUID.randomUUID();
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow("amazon.in")));
        when(accountRepository.findFirstByUserIdAndName(userId, "Gmail receipts")).thenReturn(Optional.empty());
        AccountDto created = mock(AccountDto.class);
        when(created.id()).thenReturn(createdAccountId);
        when(accountService.create(eq(userId), any(AccountDto.CreateRequest.class), eq(userId)))
                .thenReturn(created);

        reviewService.approve(userId, sessionId, null);

        ArgumentCaptor<AccountDto.CreateRequest> captor = ArgumentCaptor.forClass(AccountDto.CreateRequest.class);
        verify(accountService).create(eq(userId), captor.capture(), eq(userId));
        assertThat(captor.getValue().name()).isEqualTo("Gmail receipts");
        assertThat(captor.getValue().accountType()).isEqualTo("SAVINGS");

        ArgumentCaptor<ConfirmRequest> confirmCaptor = ArgumentCaptor.forClass(ConfirmRequest.class);
        verify(importService).confirmSession(eq(userId), confirmCaptor.capture());
        assertThat(confirmCaptor.getValue().existingAccountId()).isEqualTo(createdAccountId);
    }

    @Test
    @DisplayName("an edited category overrides the staged row's own suggestion")
    void approveWithCategoryOverride() {
        ImportSession session = gmailSession();
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow("amazon.in")));
        Account account = mockAccount();
        when(accountRepository.findFirstByUserIdAndName(any(), any())).thenReturn(Optional.of(account));

        reviewService.approve(userId, sessionId, "Electronics");

        ArgumentCaptor<ConfirmRequest> captor = ArgumentCaptor.forClass(ConfirmRequest.class);
        verify(importService).confirmSession(eq(userId), captor.capture());
        assertThat(captor.getValue().rows().get(0).category()).isEqualTo("Electronics");
    }

    @Test
    @DisplayName("a blank category override is treated the same as no override")
    void approveWithBlankCategoryOverrideKeepsSuggestion() {
        ImportSession session = gmailSession();
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(session);
        when(importSessionService.readStagedRows(session)).thenReturn(List.of(stagedRow("amazon.in")));
        Account account = mockAccount();
        when(accountRepository.findFirstByUserIdAndName(any(), any())).thenReturn(Optional.of(account));

        reviewService.approve(userId, sessionId, "   ");

        ArgumentCaptor<ConfirmRequest> captor = ArgumentCaptor.forClass(ConfirmRequest.class);
        verify(importService).confirmSession(eq(userId), captor.capture());
        assertThat(captor.getValue().rows().get(0).category()).isEqualTo("Other");
    }

    @Test
    @DisplayName("rejecting a Gmail session discards it")
    void rejectDiscardsTheSession() {
        ImportSession session = gmailSession();
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(session);

        reviewService.reject(userId, sessionId);

        verify(importSessionService).deleteSession(userId, sessionId);
    }

    @Test
    @DisplayName("approving a non-Gmail session is rejected -- this endpoint is Gmail-scoped only")
    void approveRejectsNonGmailSession() {
        ImportSession csvSession = mock(ImportSession.class);
        when(csvSession.getSource()).thenReturn(null); // CSV/PDF sessions carry a null source
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(csvSession);

        assertThatThrownBy(() -> reviewService.approve(userId, sessionId, null))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("rejecting a non-Gmail session is rejected the same way")
    void rejectRejectsNonGmailSession() {
        ImportSession csvSession = mock(ImportSession.class);
        when(csvSession.getSource()).thenReturn(null);
        when(importSessionService.getOwnedSession(userId, sessionId)).thenReturn(csvSession);

        assertThatThrownBy(() -> reviewService.reject(userId, sessionId))
                .isInstanceOf(ApiException.class);
        verify(importSessionService, never()).deleteSession(any(), any());
    }

    private Account mockAccount() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(UUID.randomUUID());
        return account;
    }

    private ImportSession gmailSession() {
        ImportSession session = mock(ImportSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getSource()).thenReturn(ImportSession.SOURCE_GMAIL);
        when(session.getCreatedAt()).thenReturn(Instant.now());
        return session;
    }

    private StagedRow stagedRow(String domain) {
        return new StagedRow(
                LocalDate.of(2026, 8, 15), domain, new BigDecimal("1299.00"), "EXPENSE",
                "Other", "default", null, false, null, null, null, RowKind.TRANSACTION, 0.9);
    }
}
