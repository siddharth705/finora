package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
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
 * ImportService.persistSection's ABSOLUTE branch must capture Account.balance immediately before
 * the overwrite -- see StatementImport.balanceBeforeAbsoluteSet's own doc comment for why this is
 * the only safe source for reversing the SET later.
 */
class AbsoluteBalanceSnapshotIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
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

    @Test
    void absoluteConfirm_capturesPriorBalanceAndSetsTheAccountPointer() throws Exception {
        User user = new User();
        user.setEmail("absolute-snapshot-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Snapshot IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("500.00"));
        UUID accountId = accountRepository.save(account).getId();

        // Opening 500.00, one 150.00 expense, stated closing 350.00 -- corroborates -> ABSOLUTE.
        var response = importService.confirm(savedUser.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null,
                        List.of(new ConfirmedRow(LocalDate.of(2026, 7, 10), "COFFEE SHOP",
                                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null,
                                false, null, null, false)),
                        accountId, null, new BigDecimal("500.00"), new BigDecimal("350.00"), null,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null));

        StatementImport saved = statementImportRepository.findById(response.statementImportId())
                .orElseThrow();
        assertThat(saved.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(saved.getBalanceBeforeAbsoluteSet()).isEqualByComparingTo("500.00");

        Account afterConfirm = accountRepository.findById(accountId).orElseThrow();
        assertThat(afterConfirm.getBalance()).isEqualByComparingTo("350.00");
        assertThat(afterConfirm.getLastAbsoluteSetStatementId()).isEqualTo(saved.getId());
    }

    @Test
    void additiveConfirm_leavesTheSnapshotNullAndDoesNotTouchThePointer() throws Exception {
        User user = new User();
        user.setEmail("absolute-snapshot-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Absolute Snapshot IT User");
        User savedUser = userRepository.save(user);
        createdUserIds.add(savedUser.getId());

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("500.00"));
        UUID accountId = accountRepository.save(account).getId();

        // No stated closing balance -> ADDITIVE, not ABSOLUTE.
        var response = importService.confirm(savedUser.getId(),
                new MockMultipartFile("file", "statement.csv", "text/csv",
                        "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8)),
                new ConfirmRequest(null,
                        List.of(new ConfirmedRow(LocalDate.of(2026, 7, 10), "COFFEE SHOP",
                                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null,
                                false, null, null, false)),
                        accountId, null, new BigDecimal("500.00"), null, null,
                        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null));

        StatementImport saved = statementImportRepository.findById(response.statementImportId())
                .orElseThrow();
        assertThat(saved.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);
        assertThat(saved.getBalanceBeforeAbsoluteSet()).isNull();
        assertThat(accountRepository.findById(accountId).orElseThrow()
                .getLastAbsoluteSetStatementId()).isNull();
    }
}
