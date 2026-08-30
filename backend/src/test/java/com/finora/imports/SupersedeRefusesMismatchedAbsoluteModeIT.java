package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.MerchantLearningEventRepository;
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
 * {@code StatementImportService.supersede}'s ABSOLUTE-mode no-reversal branch rests entirely on
 * "the replacement's own confirm already set the balance again, on top of whatever this one left
 * behind" (that method's own doc comment). Replacement's mode is decided independently, by {@code
 * ClosingBalanceGuard.assess} against REPLACEMENT's own stated closing balance -- if replacement's
 * file/review does not state one (or it does not corroborate), its own confirm takes the ADDITIVE
 * branch instead and only ADDS its net delta on top of whatever {@code Account.balance} already
 * was. Combined with original's ABSOLUTE mode taking no reversal, original's full absolute
 * contribution would never leave the balance -- it would just sit underneath replacement's
 * additive delta, silently double-counted.
 *
 * <p>Reproduced end-to-end against a real database (a mock of {@code StatementImportService} alone
 * cannot: {@code supersede()} never reads replacement's own persisted mode, so the double-count is
 * baked in by two separate {@code confirm()} calls before {@code supersede()} ever runs). Rather
 * than let this corrupt the balance, {@code supersede()} now refuses the call outright when
 * original is ABSOLUTE and replacement is not.
 */
class SupersedeRefusesMismatchedAbsoluteModeIT extends AbstractIntegrationTest {

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
        user.setEmail("supersede-mismatched-mode-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Supersede Mismatched Mode User");
        user.setPhoneVerified(true);
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

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("refuses to supersede an ABSOLUTE original with a non-ABSOLUTE replacement, "
            + "leaving the balance untouched")
    void refusesAndLeavesTheBalanceUntouched() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: states a closing balance that corroborates against its own single 150.00
        // expense (10000 - 150 = 9850), and it's the account's only statement so far -- ABSOLUTE.
        // Account.balance is SET to 9850.00.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9850.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Replacement: same exact period (required by supersede's own validation), corrects the
        // one row to its true amount (200.00) -- but states NO closing balance this time (a partial
        // re-read, a manual correction, whatever the reason). ClosingBalanceGuard.assess returns
        // NOT_APPLICABLE for a null claim, so replacement's own confirm is ADDITIVE: it ADDS its
        // -200.00 delta on top of the 9850.00 already sitting in Account.balance, landing on
        // 9650.00.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row("COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), null, null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9650.00");

        List<StatementImport> imports = statementImportRepository
                .findAllByOrderByImportedAtDesc(org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(si -> si.getUserId().equals(f.user().getId())).toList();
        assertThat(imports).hasSize(2);
        StatementImport original = imports.stream()
                .filter(si -> si.getFileName().equals("original.csv")).findFirst().orElseThrow();
        StatementImport replacement = imports.stream()
                .filter(si -> si.getFileName().equals("replacement.csv")).findFirst().orElseThrow();
        assertThat(original.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);

        assertThatThrownBy(() -> statementImportService.supersede(
                f.user().getId(), original.getId(), replacement.getId()))
                .isInstanceOf(ApiException.class);

        // Refused before any mutation: the balance stays exactly where the two confirms left it,
        // and original is not marked superseded.
        assertThat(balanceOf(f)).isEqualByComparingTo("9650.00");
        StatementImport originalAfter = statementImportRepository.findById(original.getId()).orElseThrow();
        assertThat(originalAfter.getSupersededBy()).isNull();
    }
}
