package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import com.finora.domain.Money;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.storage.ContentAddress;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance test for C5-B, run against real Postgres: a Gmail receipt becomes a reviewable
 * staged row through the SAME code a CSV upload uses, and a user confirming it produces a real
 * {@link Transaction} — nothing mocked at the {@code ImportSessionService}/{@code ImportService}
 * boundary, because those are exactly the classes this phase is proving reuse of, not replacing.
 *
 * <pre>
 *   ParsedReceipt (as if a real parser produced it)
 *     -&gt; GmailStagingBridge.stage           (this phase's own code)
 *     -&gt; ImportSessionService.createSession (existing, unmodified CSV/PDF machinery)
 *     -&gt; user reviews the staged row and confirms it
 *     -&gt; ImportService.confirmSession       (existing, unmodified CSV/PDF machinery)
 *     -&gt; a real Transaction, Source.GMAIL_IMPORT
 * </pre>
 */
class GmailReceiptToTransactionIT extends AbstractIntegrationTest {

    @Autowired private GmailStagingBridge bridge;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @DisplayName("a receipt goes from staged to a real, correctly-sourced Transaction on confirm")
    void aReceiptBecomesATransactionOnConfirm() {
        User user = user();
        Account account = account(user);
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("1299.00")), LocalDate.of(2026, 8, 10), 0.9);

        bridge.stage(user.getId(), receipt);

        ImportSession session = findStagedSession(user.getId(), receipt);
        List<StagedRow> stagedRows = importSessionService.readStagedRows(session);
        assertThat(stagedRows).hasSize(1);
        StagedRow staged = stagedRows.get(0);

        ConfirmedRow confirmed = new ConfirmedRow(staged.date(), staged.description(), staged.amount(),
                staged.type(), "Other", true, staged.categorySource(), null, false, null, null, false);
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), List.of(confirmed), account.getId(), null, null, null, null));

        List<Transaction> transactions = transactionRepository.findByUserId(user.getId());
        assertThat(transactions).hasSize(1);
        Transaction transaction = transactions.get(0);
        assertThat(transaction.getSource())
                .as("provenance must say this came from Gmail, not be mislabeled as a CSV import")
                .isEqualTo(Transaction.Source.GMAIL_IMPORT);
        assertThat(transaction.getAmount()).isEqualByComparingTo("1299.00");
        assertThat(transaction.getTxnDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(transaction.getAccountId()).isEqualTo(account.getId());
    }

    /**
     * The dedup guarantee, proven against the real database constraint rather than a mock —
     * {@code idx_import_sessions_live_content} is what this test actually exercises: two attempts
     * to stage the identical receipt must not produce two sessions, which is what would happen if
     * an overlapping extraction run or a retried worker tick reprocessed the same message.
     */
    @Test
    @DisplayName("staging the same receipt twice creates only one session")
    void stagingTheSameReceiptTwiceIsIdempotent() {
        User user = user();
        ParsedReceipt receipt = new ParsedReceipt("18ab39xyz", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);

        GmailStagingBridge.Result first = bridge.stage(user.getId(), receipt);
        GmailStagingBridge.Result second = bridge.stage(user.getId(), receipt);

        assertThat(first).isEqualTo(GmailStagingBridge.Result.STAGED);
        assertThat(second).isEqualTo(GmailStagingBridge.Result.ALREADY_STAGED);
    }

    /** Two different receipts for the same user must both stage -- the dedup key is the message
     *  id, not merely "this user has a live Gmail session". */
    @Test
    void differentReceiptsForTheSameUserBothStage() {
        User user = user();
        ParsedReceipt first = new ParsedReceipt("msg-1", "amazon.in",
                Money.of(new BigDecimal("500.00")), LocalDate.of(2026, 8, 10), 0.9);
        ParsedReceipt second = new ParsedReceipt("msg-2", "amazon.in",
                Money.of(new BigDecimal("750.00")), LocalDate.of(2026, 8, 11), 0.9);

        assertThat(bridge.stage(user.getId(), first)).isEqualTo(GmailStagingBridge.Result.STAGED);
        assertThat(bridge.stage(user.getId(), second)).isEqualTo(GmailStagingBridge.Result.STAGED);
    }

    private User user() {
        User user = new User();
        user.setEmail("gmail-receipt-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail Receipt IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return accountRepository.save(account);
    }

    private ImportSession findStagedSession(UUID userId, ParsedReceipt receipt) {
        String contentHash = ContentAddress.hashOf(
                ("gmail:" + receipt.gmailMessageId()).getBytes(StandardCharsets.UTF_8));
        Optional<ImportSession> session = importSessionService.findLiveSessionByContentHash(userId, contentHash);
        assertThat(session).as("the bridge should have created a live staged session").isPresent();
        return session.get();
    }
}
