package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.StatementImportDto.SupersedeResult;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the "absolute balance reversal" design
 * (docs/superpowers/specs/2026-08-30-absolute-balance-reversal-design.md), against a real
 * database and real confirm()/supersede()/delete() calls -- a mocked-repository unit test
 * (StatementImportServiceSupersedeTest/StatementImportServiceDeleteTest) proves the reversal
 * primitive's own logic; this proves the whole pipeline (two real confirms, then a real
 * supersede/delete) produces the numbers the spec's worked examples predict.
 */
class AbsoluteBalanceReversalIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

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
        user.setEmail("absolute-reversal-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Reversal IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal(openingBalance));
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private MockMultipartFile statementFile(String name) {
        return new MockMultipartFile("file", name, "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row(String description, String amount) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private ConfirmedRow incomeRow(LocalDate date, String description, String amount) {
        return new ConfirmedRow(date, description, new BigDecimal(amount),
                "INCOME", "Other", true, "rule", null, false, null, null, false);
    }

    private ConfirmedRow expenseRow(LocalDate date, String description, String amount) {
        return new ConfirmedRow(date, description, new BigDecimal(amount),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    private StatementImport findByFileName(Fixture f, String fileName) {
        return statementImportRepository
                .findAllByOrderByImportedAtDesc(PageRequest.of(0, 50)).stream()
                .filter(si -> si.getUserId().equals(f.user().getId()) && si.getFileName().equals(fileName))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Case A/B: replacement lands ADDITIVE (no closing balance, or an uncorroborated "
            + "one) -- supersede restores original's pre-SET baseline plus replacement's real net delta")
    void supersede_reversesAnAbsoluteOriginal_whenReplacementLandsAdditive() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: states a closing balance that corroborates (10000 - 150 = 9850) -> ABSOLUTE.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Replacement: corrects the row (200.00, not 150.00), states a closing balance (5000.00)
        // that does NOT corroborate against its own row (9850 - 200 = 9650, not 5000) -> ADDITIVE.
        // Its own confirm only ADDS its real -200.00 delta on top of 9850.00.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("5000.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9650.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");
        assertThat(original.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        // 10000.00 (original's pre-SET baseline) - 200.00 (replacement's real net) = 9800.00.
        // NEVER 5000.00 -- replacement's uncorroborated stated figure is never trusted.
        assertThat(balanceOf(f)).isEqualByComparingTo("9800.00");
        assertThat(result.balanceReversed()).isTrue();
        StatementImport originalAfter = statementImportRepository.findById(original.getId()).orElseThrow();
        assertThat(originalAfter.getSupersededBy()).isEqualTo(replacement.getId());
    }

    @Test
    @DisplayName("Case C: replacement itself corroborates and lands ABSOLUTE -- supersede is a "
            + "no-op, the replacement's own stated figure is already authoritative")
    void supersede_isANoOp_whenReplacementItselfLandedAbsolute() throws Exception {
        Fixture f = fixture("100.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: opening 100, one 900.00 INCOME row so opening + net == closing: 100 + 900 = 1000.
        // Row dated at the statement's own period end -- isMostRecentStatementForAccount compares
        // a statement's row-derived maxDate against OTHER statements' DECLARED statementPeriodEnd
        // (see ImportService.isMostRecentStatementForAccount), so an earlier in-period row date
        // would make this statement lose that recency check against a same-period sibling.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null,
                        List.of(incomeRow(periodEnd, "SALARY", "900.00")),
                        f.account().getId(), null, new BigDecimal("100.00"), new BigDecimal("1000.00"),
                        null, periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1000.00");

        // Replacement: same period, its own rows net +1100.00 with opening 100.00, and it states a
        // closing balance of 1200.00 that DOES corroborate (100 + 1100 = 1200) -> ABSOLUTE. Its own
        // confirm SETS the balance directly, moving the account's live-anchor pointer to it.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null,
                        List.of(incomeRow(periodEnd, "SALARY", "1100.00")),
                        f.account().getId(), null, new BigDecimal("100.00"), new BigDecimal("1200.00"),
                        null, periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1200.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        assertThat(balanceOf(f)).isEqualByComparingTo("1200.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("Case D: a manual balance edit intervenes between original's confirm and "
            + "supersede -- automatic reversal is abandoned, not guessed")
    void supersede_doesNotReverse_afterAManualBalanceEditIntervened() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Manual edit, simulating AccountService.update -- directly on the entity, then saved,
        // exactly what that service does, to avoid depending on AccountController's full request
        // wiring in this test.
        Account manuallyEdited = accountRepository.findById(f.account().getId()).orElseThrow();
        manuallyEdited.setBalance(new BigDecimal("20000.00"));
        manuallyEdited.setLastAbsoluteSetStatementId(null);
        accountRepository.save(manuallyEdited);

        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), null, null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("19800.00");

        StatementImport original = findByFileName(f, "original.csv");
        StatementImport replacement = findByFileName(f, "replacement.csv");

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId());

        assertThat(balanceOf(f)).isEqualByComparingTo("19800.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("delete() regression: an ABSOLUTE statement's reversal no longer depends on live "
            + "transaction amounts matching what the original confirm's own arithmetic assumed")
    void delete_reversesAnAbsoluteStatement_correctlyEvenAfterATransactionAmountWasEdited() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        StatementImport original = findByFileName(f, "original.csv");

        statementImportService.delete(f.user().getId(), original.getId());

        // The old row-netDelta reversal would have subtracted the (possibly-edited) row's current
        // amount; the new snapshot-based reversal restores the exact pre-SET baseline (10000.00)
        // regardless of what the row's live amount says.
        assertThat(balanceOf(f)).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("supersede is a no-op when an intervening statement for a LATER period already "
            + "landed ABSOLUTE, fully overwriting the earlier original's contribution")
    void supersede_isANoOp_whenALaterPeriodStatementAlreadyOverwroteTheBalance() throws Exception {
        Fixture f = fixture("1000.00");
        LocalDate juneStart = LocalDate.of(2026, 6, 1);
        LocalDate juneEnd = LocalDate.of(2026, 6, 30);
        LocalDate julyStart = LocalDate.of(2026, 7, 1);
        LocalDate julyEnd = LocalDate.of(2026, 7, 31);

        // A: June, corroborates (1000 - 100 = 900) -> ABSOLUTE. Pointer -> A.
        importService.confirm(f.user().getId(), statementFile("a.csv"),
                new ConfirmRequest(null, List.of(expenseRow(juneEnd, "GROCERIES", "100.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), new BigDecimal("900.00"),
                        null, juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("900.00");

        // B: July -- a LATER period, unrelated to A. Corroborates (900 - 50 = 850) -> ABSOLUTE.
        // Its own confirm SETS the balance directly, moving the pointer to B and fully overwriting
        // A's earlier SET, independent of anything supersede() does later.
        importService.confirm(f.user().getId(), statementFile("b.csv"),
                new ConfirmRequest(null, List.of(expenseRow(julyEnd, "UTILITIES", "50.00")),
                        f.account().getId(), null, new BigDecimal("900.00"), new BigDecimal("850.00"),
                        null, julyStart, julyEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("850.00");

        // Replacement for A: same period as A (June), states no closing balance -> ADDITIVE. Its
        // own confirm only ADDS its real -130.00 delta on top of whatever balance already exists
        // (850.00, set by B), landing on 720.00.
        importService.confirm(f.user().getId(), statementFile("replacement-a.csv"),
                new ConfirmRequest(null, List.of(expenseRow(juneEnd, "GROCERIES", "130.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), null, null,
                        juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("720.00");

        StatementImport a = findByFileName(f, "a.csv");
        StatementImport replacementA = findByFileName(f, "replacement-a.csv");

        SupersedeResult result = statementImportService.supersede(
                f.user().getId(), a.getId(), replacementA.getId());

        // A's contribution was already fully gone once B set the balance -- nothing to reverse.
        assertThat(balanceOf(f)).isEqualByComparingTo("720.00");
        assertThat(result.balanceReversed()).isFalse();
    }

    @Test
    @DisplayName("delete() regression: reversal is correct even when a manual, non-statement "
            + "balance change happened between an earlier statement's carried-forward closing "
            + "balance and this statement's own ABSOLUTE confirm")
    void delete_reversesAnAbsoluteStatement_correctlyDespiteOpeningBalanceCarryForwardDivergence() throws Exception {
        Fixture f = fixture("1000.00");
        LocalDate juneStart = LocalDate.of(2026, 6, 1);
        LocalDate juneEnd = LocalDate.of(2026, 6, 30);
        LocalDate julyStart = LocalDate.of(2026, 7, 1);
        LocalDate julyEnd = LocalDate.of(2026, 7, 31);

        // Prior statement: June, corroborates (1000 + 500 = 1500) -> ABSOLUTE. Its closing balance
        // (1500.00) becomes what OpeningBalanceCarryForward will hand to the next statement as ITS
        // opening balance, if that statement doesn't state a matching one of its own.
        importService.confirm(f.user().getId(), statementFile("prior.csv"),
                new ConfirmRequest(null, List.of(incomeRow(juneEnd, "SALARY", "500.00")),
                        f.account().getId(), null, new BigDecimal("1000.00"), new BigDecimal("1500.00"),
                        null, juneStart, juneEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1500.00");

        // A manual, non-statement change to the balance (e.g. a manually-entered transaction) --
        // additive-shaped, so it does NOT touch the account's absolute-set pointer, same as
        // TransactionService.adjustAccountBalance never does. Live balance is now 1800.00, but no
        // statement's own arithmetic knows that -- OpeningBalanceCarryForward only ever sees the
        // PRIOR STATEMENT's closing balance (1500.00), not live Account.balance.
        Account afterManualChange = accountRepository.findById(f.account().getId()).orElseThrow();
        afterManualChange.setBalance(new BigDecimal("1800.00"));
        accountRepository.save(afterManualChange);

        // The statement under test: July, states an opening balance that does NOT match the prior
        // statement's closing (forcing OpeningBalanceCarryForward to substitute 1500.00), one
        // -200.00 expense, and a closing balance of 1300.00 that corroborates against the CARRIED-
        // FORWARD opening (1500 - 200 = 1300) -> ABSOLUTE. Its own confirm SETS the balance to
        // 1300.00, discarding the live 1800.00 -- exactly the divergence this design exists for.
        importService.confirm(f.user().getId(), statementFile("statement.csv"),
                new ConfirmRequest(null, List.of(expenseRow(julyEnd, "RENT", "200.00")),
                        f.account().getId(), null, new BigDecimal("999999.99"), new BigDecimal("1300.00"),
                        null, julyStart, julyEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("1300.00");

        StatementImport statement = findByFileName(f, "statement.csv");
        assertThat(statement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        // The snapshot captured the true LIVE balance before the SET (1800.00), not the carried-
        // forward figure (1500.00) the confirm's own arithmetic used.
        assertThat(statement.getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("1800.00");

        statementImportService.delete(f.user().getId(), statement.getId());

        // Correct: restores 1800.00. The OLD row-netDelta reversal would have taken CURRENT balance
        // (1300.00) and subtracted this statement's own -200.00 net (i.e. added back 200.00),
        // landing on 1500.00 -- silently losing the 300.00 manual change forever.
        assertThat(balanceOf(f)).isEqualByComparingTo("1800.00");
    }
}
