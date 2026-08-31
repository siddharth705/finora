package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * confirmNotDuplicate()'s own doc comment says "the balance is deliberately NOT touched... a
 * duplicate-flagged row was always counted in Account.balance." True for a manually-entered pair
 * (see NotDuplicateConfirmationIT), but not universally: BH-003
 * ({@code ImportService.summarise}) reverses a statement-import row's contribution the moment
 * reconciliation flags it DUPLICATE against another row from the same statement's own confirm. A
 * row in that state contributes NOTHING to Account.balance -- confirming it as "not a duplicate"
 * must add its amount back, or the balance stays permanently short by it.
 *
 * <p>Written end-to-end against a real import + a real confirmNotDuplicate() call, for the same
 * reason ImportAccountBalanceIT's own BH-003 coverage is an IT: the defect is an interaction
 * between two services' balance-mutation logic, not a calculation a mock could exercise.
 */
class ConfirmNotDuplicateBalanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /** Same cleanup, same reason, as ImportAccountBalanceIT's own @AfterEach -- an unclaimed
     *  merchant-learning event competes with every other test class's fixture for the queue's
     *  shared 50-row batch. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture(String openingBalance) {
        User user = new User();
        user.setEmail("confirm-not-dup-balance-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirm Not Duplicate Balance IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Confirm Not Duplicate Balance IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal(openingBalance));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row(String description, String amount, String type) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", true, "rule", null, false, null, null, false);
    }

    private void importRows(Fixture f, ConfirmedRow... rows) throws Exception {
        importService.confirm(f.user().getId(), statementFile(),
                new ConfirmRequest(null, List.of(rows), f.account().getId(), null, null, null, null));
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("confirmNotDuplicate restores the balance BH-003 reversed for a genuine "
            + "statement-import duplicate")
    void confirmingAStatementDuplicateRestoresTheReversedBalance() throws Exception {
        Fixture f = fixture("1000.00");

        importRows(f, row("METRO FARE", "45.00", "EXPENSE"));
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // Same file again: reconciliation flags the second METRO FARE row DUPLICATE of the first,
        // and BH-003 reverses its 45.00 contribution -- same scenario ImportAccountBalanceIT's own
        // BH-003 tests cover, just carried one step further here.
        importRows(f, row("METRO FARE", "45.00", "EXPENSE"));
        assertThat(balanceOf(f))
                .as("BH-003 already reversed the second row's contribution")
                .isEqualByComparingTo("955.00");

        Transaction duplicateRow = transactionRepository.findByUserId(f.user().getId()).stream()
                .filter(t -> t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE)
                .findFirst()
                .orElseThrow();

        // The user insists this second METRO FARE is real, not a re-import artefact.
        transactionService.confirmNotDuplicate(f.user().getId(), duplicateRow.getId());

        assertThat(balanceOf(f))
                .as("the row now counts in every report again; the balance must count it too")
                .isEqualByComparingTo("910.00");
    }
}
