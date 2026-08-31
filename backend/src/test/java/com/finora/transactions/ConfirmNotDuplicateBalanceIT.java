package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
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
        return statementFile("statement.csv");
    }

    private MockMultipartFile statementFile(String name) {
        return new MockMultipartFile("file", name, "text/csv",
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

    private void importRowsWithPeriod(Fixture f, String fileName, LocalDate periodStart,
                                       LocalDate periodEnd, ConfirmedRow... rows) throws Exception {
        importService.confirm(f.user().getId(), statementFile(fileName),
                new ConfirmRequest(null, List.of(rows), f.account().getId(), null, null, null, null,
                        periodStart, periodEnd));
    }

    private StatementImport statementNamed(Fixture f, String fileName) {
        return statementImportRepository
                .findAllByOrderByImportedAtDesc(org.springframework.data.domain.PageRequest.of(0, 50))
                .stream()
                .filter(si -> si.getUserId().equals(f.user().getId()))
                .filter(si -> si.getFileName().equals(fileName))
                .findFirst()
                .orElseThrow();
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

    @Test
    @DisplayName("refuses to confirm-not-duplicate a row whose own statement has since been "
            + "superseded, leaving both its status and the balance untouched")
    void refusesWhenTheOwningStatementHasBeenSuperseded() throws Exception {
        Fixture f = fixture("1000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: one row, ADDITIVE (no closing balance stated).
        importRowsWithPeriod(f, "original.csv", periodStart, periodEnd,
                row("METRO FARE", "45.00", "EXPENSE"));
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        // Re-imported by mistake, same period, same row: reconciliation flags this second METRO
        // FARE DUPLICATE against the first, and BH-003 reverses its own 45.00 contribution the
        // moment this confirm runs -- same mechanism the sibling test above covers.
        importRowsWithPeriod(f, "reimport-with-duplicate.csv", periodStart, periodEnd,
                row("METRO FARE", "45.00", "EXPENSE"));
        assertThat(balanceOf(f))
                .as("BH-003 already reversed the second row's contribution")
                .isEqualByComparingTo("955.00");
        StatementImport reimport = statementNamed(f, "reimport-with-duplicate.csv");
        Transaction duplicateRow = transactionRepository.findByStatementImportId(reimport.getId())
                .stream().findFirst().orElseThrow();
        assertThat(duplicateRow.getReconciliationStatus())
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);

        // The accidental reimport itself gets superseded by a later, unrelated correction covering
        // the exact same period -- this is the interaction the balance-only fix in #633 didn't
        // account for: supersede() only ever touches OK-status rows (see its own doc comment), so
        // the DUPLICATE row above is left behind untouched, still pointing at a statement that is
        // now marked replaced.
        importRowsWithPeriod(f, "replacement.csv", periodStart, periodEnd,
                row("COFFEE", "10.00", "EXPENSE"));
        assertThat(balanceOf(f)).isEqualByComparingTo("945.00");
        StatementImport replacement = statementNamed(f, "replacement.csv");
        statementImportService.supersede(f.user().getId(), reimport.getId(), replacement.getId());
        assertThat(statementImportRepository.findById(reimport.getId()).orElseThrow().getSupersededBy())
                .isEqualTo(replacement.getId());

        // The user later finds this stale DUPLICATE row in the dashboard's "detected duplicates"
        // widget (DashboardService filters purely on reconciliationStatus, with no awareness of
        // supersession) and clicks "Not a duplicate". Un-duplicating it would resurrect a row from
        // a statement Finora has already marked replaced -- refused outright, same as supersede()
        // itself already refuses to act on a statement that's already been superseded.
        assertThatThrownBy(() -> transactionService.confirmNotDuplicate(f.user().getId(), duplicateRow.getId()))
                .isInstanceOf(ApiException.class);

        Transaction duplicateRowAfter = transactionRepository.findById(duplicateRow.getId()).orElseThrow();
        assertThat(duplicateRowAfter.getReconciliationStatus())
                .as("refused before any mutation -- still excluded from every report")
                .isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
        assertThat(balanceOf(f))
                .as("refused before any mutation -- the balance stays exactly where supersede left it")
                .isEqualByComparingTo("945.00");
    }
}
