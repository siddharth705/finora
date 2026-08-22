package com.finora.integrations.google.merchant;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.domain.Money;
import com.finora.entity.ImportSession;
import com.finora.imports.ImportSessionService;
import com.finora.imports.storage.ContentAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase C5-B. The property under test is narrower than "does it create a session" — it is
 * "does it produce EXACTLY the shape {@code ImportSessionService} already knows how to review and
 * confirm", since the whole point of this class is that nothing downstream needs to know a Gmail
 * receipt is where a row came from.
 */
class GmailStagingBridgeTest {

    private ImportSessionService importSessionService;
    private GmailReconciliationMatcher reconciliationMatcher;
    private GmailStagingBridge bridge;

    private final UUID userId = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        importSessionService = mock(ImportSessionService.class);
        reconciliationMatcher = mock(GmailReconciliationMatcher.class);
        bridge = new GmailStagingBridge(importSessionService, reconciliationMatcher);
        when(importSessionService.findLiveSessionByContentHash(any(), any())).thenReturn(Optional.empty());
        when(reconciliationMatcher.findMatch(any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("a receipt becomes exactly one StagedRow with the amount, date and confidence carried through")
    void stagesTheReceiptAsOneRow() {
        ParsedReceipt receipt = receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9);

        bridge.stage(userId, receipt);

        List<StagedRow> rows = capturedRows();
        assertThat(rows).hasSize(1);
        StagedRow row = rows.get(0);
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("1299.00"));
        assertThat(row.type()).isEqualTo("EXPENSE");
        assertThat(row.confidence()).isEqualTo(0.9);
    }

    /**
     * No merchant-to-category engine exists yet, and guessing one would be exactly the "wrong
     * answer nobody asked for" this codebase's own account-detection refuses to do elsewhere. This
     * is not a placeholder to fix later in isolation -- it is what makes the review table's
     * existing "low confidence" badge (driven by categorySource === 'default') show up on every
     * Gmail-derived row with no new UI.
     */
    @Test
    @DisplayName("every row is categorySource=default -- honest about having no category signal")
    void everyRowIsCategorySourceDefault() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9));

        assertThat(capturedRows().get(0).categorySource()).isEqualTo("default");
    }

    @Test
    @DisplayName("the content hash is SHA-256 of \"gmail:\" + the message id -- the exact formula the design decision specifies")
    void theContentHashMatchesTheDocumentedFormula() {
        ParsedReceipt receipt = receipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
        String expectedHash = ContentAddress.hashOf("gmail:18ab39xyz".getBytes(StandardCharsets.UTF_8));

        bridge.stage(userId, receipt);

        verify(importSessionService).findLiveSessionByContentHash(userId, expectedHash);
    }

    /**
     * The dedup guarantee this whole design decision exists for: two attempts to stage the SAME
     * receipt (an overlapping extraction run, a retried tick) must not create two sessions.
     */
    @Test
    @DisplayName("a receipt with a live existing session is not staged again")
    void aReceiptAlreadyStagedIsNotStagedTwice() {
        ImportSession existing = mock(ImportSession.class);
        when(importSessionService.findLiveSessionByContentHash(any(), any()))
                .thenReturn(Optional.of(existing));

        GmailStagingBridge.Result result = bridge.stage(userId,
                receipt("msg-1", "amazon.in", Money.of(new BigDecimal("500.00")),
                        LocalDate.of(2026, 8, 10), 0.9));

        assertThat(result).isEqualTo(GmailStagingBridge.Result.ALREADY_STAGED);
        verify(importSessionService, never())
                .createSession(any(), anyString(), any(), any(), any(), any(), anyString());
    }

    @Test
    void aNewReceiptReturnsStaged() {
        GmailStagingBridge.Result result = bridge.stage(userId,
                receipt("msg-1", "amazon.in", Money.of(new BigDecimal("500.00")),
                        LocalDate.of(2026, 8, 10), 0.9));

        assertThat(result).isEqualTo(GmailStagingBridge.Result.STAGED);
    }

    /**
     * The "file content" this bridge stores is a tiny synthetic identity marker, never the
     * receipt's actual HTML or any other email content -- verifying the exact bytes pins that this
     * stays true rather than silently drifting toward storing something bigger later.
     */
    @Test
    @DisplayName("the stored \"file content\" is only the identity marker, never receipt content")
    void theStoredContentIsOnlyTheIdentityMarkerNotReceiptContent() {
        bridge.stage(userId, receipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9));

        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(importSessionService).createSession(
                any(), anyString(), contentCaptor.capture(), any(), any(), any(), anyString());
        assertThat(new String(contentCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("gmail:18ab39xyz");
    }

    /**
     * The whole reason {@code Transaction.Source.GMAIL_IMPORT} exists: without this,
     * {@code ImportService.persistSection} has no way to tell a Gmail-derived session from a
     * CSV/PDF one, and every confirmed transaction is mislabelled CSV_IMPORT.
     */
    @Test
    @DisplayName("the session is marked with the Gmail source, so confirm can set correct provenance")
    void theSessionIsMarkedAsGmailSourced() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9));

        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(importSessionService).createSession(
                any(), anyString(), any(), any(), any(), any(), sourceCaptor.capture());
        assertThat(sourceCaptor.getValue()).isEqualTo(com.finora.entity.ImportSession.SOURCE_GMAIL);
    }

    /**
     * DetectedAccountInfo is non-optional on the read side (the frontend dereferences it
     * unconditionally when hydrating review state) -- a receipt has no bank/account context at
     * all, so this proves the synthetic one uses the SAME "genuinely unknown" escape hatch an
     * unidentifiable PDF section already uses, not a new, untested code path.
     */
    @Test
    @DisplayName("the synthetic account info is honestly unknown, not a guess")
    void theSyntheticAccountInfoIsHonestlyUnknown() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9));

        DetectedAccountInfo account = capturedAccount();
        assertThat(account.bank().id()).isEqualTo("OTHER");
        assertThat(account.detectedProduct()).isEqualTo("UNKNOWN");
        assertThat(account.productNeedsReview()).isTrue();
    }

    @Test
    @DisplayName("the synthetic file name is human-readable, mentioning the merchant and date")
    void theSyntheticFileNameIsHumanReadable() {
        ArgumentCaptor<String> fileNameCaptor = ArgumentCaptor.forClass(String.class);

        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9));

        verify(importSessionService).createSession(
                any(), fileNameCaptor.capture(), any(), any(), any(), any(), anyString());
        assertThat(fileNameCaptor.getValue()).contains("amazon.in").contains("2026");
    }

    /**
     * The whole point of wiring {@link GmailReconciliationMatcher} in: a receipt that matches the
     * bank ledger must reach the review UI exactly the way a CSV/PDF duplicate does — same two
     * fields, no new client-side branch needed.
     */
    @Test
    @DisplayName("a reconciliation match sets likelyDuplicate and duplicateMatch on the staged row")
    void aReconciliationMatchIsCarriedOntoTheStagedRow() {
        com.finora.dto.ImportDto.DuplicateMatch match = new com.finora.dto.ImportDto.DuplicateMatch(
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 8, 9), "AMZN MKTPLACE",
                new BigDecimal("1299.00"), "EXPENSE", java.time.Instant.now(), 1, "LIKELY",
                "Same amount around this date, and the merchant looks like the same business.");
        when(reconciliationMatcher.findMatch(any(), any(), any(), any())).thenReturn(Optional.of(match));

        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9));

        StagedRow row = capturedRows().get(0);
        assertThat(row.likelyDuplicate()).isTrue();
        assertThat(row.duplicateMatch()).isEqualTo(match);
    }

    @Test
    @DisplayName("no reconciliation match leaves the staged row honestly clean, not a stale guess")
    void noReconciliationMatchLeavesTheRowClean() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9));

        StagedRow row = capturedRows().get(0);
        assertThat(row.likelyDuplicate()).isFalse();
        assertThat(row.duplicateMatch()).isNull();
    }

    @Test
    @DisplayName("the matcher is asked with the receipt's own date, amount and domain -- not the account-scoped variant")
    void theMatcherIsCalledWithTheReceiptsOwnFields() {
        bridge.stage(userId, receipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9));

        verify(reconciliationMatcher).findMatch(userId, LocalDate.of(2026, 8, 10),
                new BigDecimal("1299.00"), "amazon.in");
    }

    private List<StagedRow> capturedRows() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StagedRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(importSessionService).createSession(
                any(), anyString(), any(), rowsCaptor.capture(), any(), any(), anyString());
        return rowsCaptor.getValue();
    }

    private DetectedAccountInfo capturedAccount() {
        ArgumentCaptor<DetectedAccountInfo> accountCaptor = ArgumentCaptor.forClass(DetectedAccountInfo.class);
        verify(importSessionService).createSession(
                any(), anyString(), any(), any(), accountCaptor.capture(), any(), anyString());
        return accountCaptor.getValue();
    }

    private static ParsedReceipt receipt(String gmailMessageId, String domain, Money amount,
                                         LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, null, amount, date, confidence);
    }
}
