package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import com.finora.repository.MerchantLearningEventRepository;
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
 * What an import does to {@code Account.balance} — Bug 17, and the Bug 02 guard it has to coexist
 * with.
 *
 * <p>Written end-to-end against a real database rather than as a unit test on purpose. The defect
 * was not a wrong calculation; it was a calculation that <em>never ran</em>. A mocked
 * {@code AccountRepository} would have happily verified whichever call the implementation happened
 * to make, including none. Asserting on the persisted balance is the only form of this test that
 * could have failed before the fix.
 *
 * <h2>The rule under test</h2>
 * {@code Account.balance} moves with the transactions Finora holds for that account. A corroborated
 * closing balance is a stronger, absolute statement of where the account ended and wins outright;
 * with no such statement, the imported rows are still real ledger entries and the balance moves by
 * their net effect. Deleting the statement reverses exactly what importing it applied.
 */
class ImportAccountBalanceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    /**
     * The users this class created, so {@link #removeQueuedLearningEvents()} can clean up after
     * exactly them and nothing else.
     */
    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /**
     * Every confirmed import enqueues merchant-learning events, and the test profile disables the
     * queue worker ({@code app.learning.queue.enabled=false}) so nothing ever drains them. Left
     * alone they accumulate in a table every integration test in the JVM shares.
     *
     * <p>That is not hypothetical interference. {@code MerchantLearningImportIT.drainUntilSettled}
     * documents the exact coupling: {@code drainOnce()} claims a bounded batch of 50 across the
     * WHOLE table, so another class's undrained rows compete with its fixture's for the batch. It
     * defends itself by backdating its own events so they sort first, and that defence looks
     * sound -- but the defence existing is not a licence for this class to keep adding to the pile.
     * A test that leaves persistent queue state behind is a test whose blast radius is every other
     * test in the run, and the next class to be written may not have that defence.
     *
     * <p>Scoped to this class's own users rather than truncating the table: a blanket delete would
     * make this class's cleanup destructive to anything running beside it, which is the same
     * cross-test coupling one layer down.
     */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private record Fixture(User user, Account account) {}

    private Fixture fixture(Account.Type type, String openingBalance) {
        User user = new User();
        user.setEmail("import-balance-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Balance IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Import Balance IT Account");
        account.setAccountType(type);
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

    private void importRows(Fixture f, BigDecimal opening, BigDecimal closing, ConfirmedRow... rows)
            throws Exception {
        importService.confirm(f.user().getId(), statementFile(),
                new ConfirmRequest(null, List.of(rows), f.account().getId(), null, opening, closing,
                null));
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    // ---- Bug 17 ----

    @Test
    @DisplayName("BUG 17: with no stated closing balance, the balance still moves by what was imported")
    void balanceMovesWithoutAStatedClosingBalance() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, null, null,
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));

        assertThat(balanceOf(f))
                .as("the transactions are in the ledger; a balance that ignores them makes net "
                        + "worth, the dashboard and every alert wrong, permanently and silently")
                .isEqualByComparingTo("1455.00");
    }

    @Test
    @DisplayName("a credit card's balance is money OWED, so an imported purchase increases it")
    void creditCardInversionIsRespected() throws Exception {
        Fixture f = fixture(Account.Type.CREDIT_CARD, "2000.00");

        importRows(f, null, null,
                row("ONLINE PURCHASE", "300.00", "EXPENSE"),
                row("CARD PAYMENT", "500.00", "INCOME"));

        assertThat(balanceOf(f))
                .as("a purchase adds to what is owed and a payment reduces it -- the opposite of "
                        + "every other account type, which is why the rule lives in one place")
                .isEqualByComparingTo("1800.00");
    }

    // ---- interaction with the Bug 02 guard ----

    @Test
    @DisplayName("a corroborated closing balance wins outright and is not double-counted")
    void corroboratedClosingBalanceIsAuthoritative() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        // opening + credits - debits == closing, so the guard corroborates it.
        importRows(f, new BigDecimal("1000.00"), new BigDecimal("1455.00"),
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));

        assertThat(balanceOf(f))
                .as("applying the stated balance AND the delta would land on 1910; the two paths "
                        + "are alternatives, not cumulative")
                .isEqualByComparingTo("1455.00");
    }

    @Test
    @DisplayName("BUG 02: a closing balance the rows do not reach is refused, and the delta is used instead")
    void uncorroboratedClosingBalanceFallsBackToTheLedger() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, new BigDecimal("1000.00"), new BigDecimal("99999999"),
                row("METRO FARE", "45.00", "EXPENSE"));

        assertThat(balanceOf(f))
                .as("an unverifiable figure off the request body must never reach this column")
                .isEqualByComparingTo("955.00");
    }

    // ---- symmetry ----

    @Test
    @DisplayName("deleting the statement returns the balance to where it started")
    void deletingAStatementReversesItsBalanceEffect() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, null, null,
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));
        assertThat(balanceOf(f)).isEqualByComparingTo("1455.00");

        List<StatementImport> imports =
                statementImportRepository.findByUserIdOrderByImportedAtDesc(f.user().getId());
        assertThat(imports).hasSize(1);
        statementImportService.delete(f.user().getId(), imports.get(0).getId());

        assertThat(balanceOf(f))
                .as("an import/delete cycle that does not return to its starting point leaves the "
                        + "balance permanently overstated, which is worse than never moving it")
                .isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("deleting a statement whose balance came from the stated closing figure also reverses")
    void deletingReversesEvenWhenTheClosingBalanceWasApplied() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, new BigDecimal("1000.00"), new BigDecimal("1455.00"),
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));
        assertThat(balanceOf(f)).isEqualByComparingTo("1455.00");

        List<StatementImport> imports =
                statementImportRepository.findByUserIdOrderByImportedAtDesc(f.user().getId());
        statementImportService.delete(f.user().getId(), imports.get(0).getId());

        assertThat(balanceOf(f))
                .as("however the balance came to include these transactions, removing them has to "
                        + "take their effect with it")
                .isEqualByComparingTo("1000.00");
    }

    // ---- BH-003: a duplicate import must not move the balance twice ----

    @Test
    @DisplayName("BH-003: importing the same statement twice does not move the balance twice")
    void aDuplicateImportDoesNotDoubleCountTheBalance() throws Exception {
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, null, null,
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));
        assertThat(balanceOf(f)).isEqualByComparingTo("1455.00");

        // The same statement again -- a re-import, a double-clicked confirm, or the same file
        // uploaded twice. Reconciliation flags these rows as duplicates of the ones already on the
        // books and excludes them from every reported total from here on. Before the fix the
        // balance was the one figure that did not follow: it moved to 1910.00 and stayed there,
        // while the ledger view showed nothing that explained the difference. Nothing in the
        // product ever recomputes that column, so it was permanently and silently wrong.
        importRows(f, null, null,
                row("METRO FARE", "45.00", "EXPENSE"),
                row("SALARY", "500.00", "INCOME"));

        assertThat(balanceOf(f))
                .as("the duplicates are excluded from every total; the balance has to agree with "
                        + "the transactions the product actually counts")
                .isEqualByComparingTo("1455.00");
    }

    @Test
    @DisplayName("BH-003: the same holds for a credit card, where the inversion doubles the error")
    void aDuplicateCardImportDoesNotDoubleCountTheBalance() throws Exception {
        // Cards are the case that hit this hardest. ClosingBalanceGuard used to read every account
        // with the asset formula, so a card statement could never corroborate -- which meant every
        // card import took the netDelta branch, which is the branch that double-counted.
        Fixture f = fixture(Account.Type.CREDIT_CARD, "2000.00");

        importRows(f, null, null,
                row("ONLINE PURCHASE", "300.00", "EXPENSE"),
                row("CARD PAYMENT", "500.00", "INCOME"));
        assertThat(balanceOf(f)).isEqualByComparingTo("1800.00");

        importRows(f, null, null,
                row("ONLINE PURCHASE", "300.00", "EXPENSE"),
                row("CARD PAYMENT", "500.00", "INCOME"));

        assertThat(balanceOf(f))
                .as("a second pass would have reported 1600.00 owed -- 200 less debt than is real")
                .isEqualByComparingTo("1800.00");
    }

    @Test
    @DisplayName("BH-003: genuinely new rows in a partly-duplicated import still move the balance")
    void onlyTheDuplicateRowsAreReversed() throws Exception {
        // The guard against over-correcting. A statement that overlaps a previous one -- the
        // ordinary case when a user imports an overlapping date range -- must have its NEW rows
        // applied and only its repeated rows reversed. Reversing the whole batch would leave the
        // balance short by exactly the new activity.
        Fixture f = fixture(Account.Type.SAVINGS, "1000.00");

        importRows(f, null, null, row("METRO FARE", "45.00", "EXPENSE"));
        assertThat(balanceOf(f)).isEqualByComparingTo("955.00");

        importRows(f, null, null,
                row("METRO FARE", "45.00", "EXPENSE"),   // already on the books
                row("COFFEE", "120.00", "EXPENSE"));     // new

        assertThat(balanceOf(f))
                .as("only the repeated row comes back off; the new one is real spending")
                .isEqualByComparingTo("835.00");
    }
}
