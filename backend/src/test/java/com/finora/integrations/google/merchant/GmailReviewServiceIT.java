package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import com.finora.domain.Money;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance test for C5.4/D-15's actual claim: a Gmail receipt can go from staged to a real
 * ledger Transaction through {@link GmailReviewService} alone, with no account picker and no
 * duplicate "Gmail receipts" account created on a second approval. {@code GmailReviewServiceTest}
 * covers the same logic against mocks; this runs the real {@code AccountService.create} and
 * {@code ImportService.confirmSession} against Postgres.
 */
class GmailReviewServiceIT extends AbstractIntegrationTest {

    @Autowired private GmailStagingBridge bridge;
    @Autowired private GmailReviewService reviewService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    @Test
    @DisplayName("approving two receipts creates two transactions but only one Gmail receipts account")
    void approvingTwoReceiptsSharesOneAccount() {
        User user = user();
        bridge.stage(user.getId(), receipt("msg-1", "amazon.in",
                new BigDecimal("1299.00"), LocalDate.of(2026, 8, 10), 0.9));
        bridge.stage(user.getId(), receipt("msg-2", "olacabs.com",
                new BigDecimal("190.00"), LocalDate.of(2026, 8, 11), 0.9));

        List<GmailReviewItemDto> pending = reviewService.listPending(user.getId());
        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(GmailReviewItemDto::merchant).containsExactlyInAnyOrder("Amazon", "Ola");

        GmailReviewItemDto first = pending.stream().filter(i -> i.merchant().equals("Amazon")).findFirst().orElseThrow();
        reviewService.approve(user.getId(), first.sessionId(), null);

        List<Account> accountsAfterFirst = accountRepository.findByUserId(user.getId());
        assertThat(accountsAfterFirst).as("the shared account is created on first approval").hasSize(1);
        assertThat(accountsAfterFirst.get(0).getName()).isEqualTo("Gmail receipts");

        GmailReviewItemDto second = reviewService.listPending(user.getId()).get(0);
        assertThat(second.merchant()).isEqualTo("Ola");
        reviewService.approve(user.getId(), second.sessionId(), "Transport");

        List<Account> accountsAfterSecond = accountRepository.findByUserId(user.getId());
        assertThat(accountsAfterSecond)
                .as("the second approval reuses the same account rather than creating a duplicate")
                .hasSize(1);
        assertThat(accountsAfterSecond.get(0).getId()).isEqualTo(accountsAfterFirst.get(0).getId());

        List<Transaction> transactions = transactionRepository.findByUserId(user.getId());
        assertThat(transactions).hasSize(2);
        assertThat(transactions).allSatisfy(t ->
                assertThat(t.getSource()).isEqualTo(Transaction.Source.GMAIL_IMPORT));
        assertThat(transactions).extracting(Transaction::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("1299.00"), new BigDecimal("190.00"));

        assertThat(reviewService.listPending(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("rejecting a receipt removes it from the queue and creates no transaction")
    void rejectingRemovesFromQueue() {
        User user = user();
        bridge.stage(user.getId(), receipt("msg-3", "zomato.com",
                new BigDecimal("450.00"), LocalDate.of(2026, 8, 12), 0.9));

        GmailReviewItemDto item = reviewService.listPending(user.getId()).get(0);
        reviewService.reject(user.getId(), item.sessionId());

        assertThat(reviewService.listPending(user.getId())).isEmpty();
        assertThat(transactionRepository.findByUserId(user.getId())).isEmpty();
    }

    private User user() {
        User user = new User();
        user.setEmail("gmail-review-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail Review IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ParsedReceipt receipt(String gmailMessageId, String domain, BigDecimal amount,
                                   LocalDate date, double confidence) {
        return new ParsedReceipt(gmailMessageId, domain, Money.of(amount), date, confidence);
    }
}
