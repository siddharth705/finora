package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§0.6):
 * {@code ImportService.persistSection} must record which account-balance branch it actually took
 * onto the saved {@code StatementImport} row, so {@code StatementImportService.supersede} can read
 * it later instead of recomputing history -- see {@code StatementImport.BalanceApplicationMode}'s
 * own doc comment for why recomputation is unsafe.
 */
class ImportServiceBalanceApplicationModeIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    private static final byte[] FILE =
            "Date,Description,Amount,Type\n2026-07-01,COFFEE SHOP,150.00,DEBIT\n".getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("balance-mode-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Balance Mode User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User owner, BigDecimal balance) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(balance);
        return accountRepository.save(account);
    }

    private StagedRow parsed() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP", new BigDecimal("150.00"),
                "EXPENSE", "Other", "rule", null, false, null, null);
    }

    private ConfirmedRow asConfirmed(StagedRow r, boolean include) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), "Other", include,
                "rule", null, false, null, null, false);
    }

    private StatementImport confirmAndFetch(User user, Account account, List<ConfirmedRow> rows,
                                             BigDecimal opening, BigDecimal closing) throws Exception {
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(parsed()), null);
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), rows, account.getId(), null, opening, closing, null));
        List<StatementImport> imports = statementImportRepository.findAllByOrderByImportedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 50));
        return imports.stream().filter(si -> si.getUserId().equals(user.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("no statement import was saved for this user"));
    }

    @Test
    @DisplayName("a corroborated, most-recent closing balance persists ABSOLUTE")
    void corroboratedClosingBalance_persistsAbsolute() throws Exception {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        // SAVINGS (asset): closing = opening + credits - debits = 10000 - 150 = 9850. Exactly
        // corroborated, and this is the account's only statement, so it is authoritative.
        StatementImport saved = confirmAndFetch(user, account, List.of(asConfirmed(parsed(), true)),
                new BigDecimal("10000.00"), new BigDecimal("9850.00"));

        assertThat(saved.getBalanceApplicationMode()).isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
    }

    @Test
    @DisplayName("an uncorroborated statement (no stated closing balance) persists ADDITIVE")
    void noClosingBalanceStated_persistsAdditive() throws Exception {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        StatementImport saved = confirmAndFetch(user, account, List.of(asConfirmed(parsed(), true)), null, null);

        assertThat(saved.getBalanceApplicationMode()).isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);
    }

    @Test
    @DisplayName("zero rows imported persists NONE")
    void noRowsImported_persistsNone() throws Exception {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        StatementImport saved = confirmAndFetch(user, account, List.of(asConfirmed(parsed(), false)), null, null);

        assertThat(saved.getBalanceApplicationMode()).isEqualTo(StatementImport.BalanceApplicationMode.NONE);
    }
}
