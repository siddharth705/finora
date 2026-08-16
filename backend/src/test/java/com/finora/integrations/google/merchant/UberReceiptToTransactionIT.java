package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance test for C5.2's actual claim: that a receipt reaching a user's ledger is
 * indistinguishable in provenance and correctness whether a parser was hand-written or templated.
 *
 * <p>Deliberately more complete than {@code GmailReceiptToTransactionIT} — that test starts from
 * an already-built {@link ParsedReceipt}; this one starts from real fixture HTML and runs it
 * through {@link MerchantEmailSanitizer}, the SEEDED (not hand-built) {@link MerchantTemplate} row,
 * {@link ParsedReceiptValidator}, {@link GmailStagingBridge}, and {@code ImportService.confirmSession}
 * — every step C5.2 actually added or touched, in the order a real extraction run would call them.
 */
class UberReceiptToTransactionIT extends AbstractIntegrationTest {

    @Autowired private MerchantTemplateRepository templates;
    @Autowired private GmailStagingBridge bridge;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private final MerchantEmailSanitizer sanitizer = new MerchantEmailSanitizer();

    @Test
    @DisplayName("a real Uber trip receipt, through the seeded template, becomes a Transaction on confirm")
    void aTemplatedReceiptBecomesATransactionOnConfirm() {
        User user = user();
        Account account = account(user);

        // Sanitize -> parse via the SEEDED template -> validate, exactly as
        // GmailReceiptExtractionService does, rather than skipping straight to a hand-built receipt.
        SanitizedGmailMessage message = load("trip-receipt-1.html", "18ab39xyz");
        TemplateEmailParser parser = new TemplateEmailParser(templates);
        ParserResult result = parser.parse(message);
        assertThat(result.isParsed()).as("the seeded template must actually parse this fixture").isTrue();

        List<ParsedReceiptValidator.Violation> violations = new ParsedReceiptValidator().validate(result.receipt());
        assertThat(violations).as("a real trip receipt must clear the plausibility gate").isEmpty();

        bridge.stage(user.getId(), result.receipt());

        ImportSession session = findStagedSession(user.getId(), result.receipt());
        List<StagedRow> stagedRows = importSessionService.readStagedRows(session);
        assertThat(stagedRows).hasSize(1);
        StagedRow staged = stagedRows.get(0);

        ConfirmedRow confirmed = new ConfirmedRow(staged.date(), staged.description(), staged.amount(),
                staged.type(), "Transport", true, staged.categorySource(), null, false, null, null, false);
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), List.of(confirmed), account.getId(), null, null, null, null));

        List<Transaction> transactions = transactionRepository.findByUserId(user.getId());
        assertThat(transactions).hasSize(1);
        Transaction transaction = transactions.get(0);
        assertThat(transaction.getSource()).isEqualTo(Transaction.Source.GMAIL_IMPORT);
        assertThat(transaction.getAmount()).isEqualByComparingTo("255.00");
        assertThat(transaction.getTxnDate()).isEqualTo(LocalDate.of(2026, 8, 12));
    }

    private User user() {
        User user = new User();
        user.setEmail("uber-receipt-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Uber Receipt IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Wallet");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("5000.00"));
        return accountRepository.save(account);
    }

    private ImportSession findStagedSession(UUID userId, ParsedReceipt receipt) {
        String contentHash = ContentAddress.hashOf(
                ("gmail:" + receipt.gmailMessageId()).getBytes(StandardCharsets.UTF_8));
        Optional<ImportSession> session = importSessionService.findLiveSessionByContentHash(userId, contentHash);
        assertThat(session).as("the bridge should have created a live staged session").isPresent();
        return session.get();
    }

    private SanitizedGmailMessage load(String fixture, String gmailMessageId) {
        try {
            String html = Files.readString(Path.of("src/test/resources/gmail/uber", fixture));
            return sanitizer.sanitize(gmailMessageId, "uber.com", html);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
