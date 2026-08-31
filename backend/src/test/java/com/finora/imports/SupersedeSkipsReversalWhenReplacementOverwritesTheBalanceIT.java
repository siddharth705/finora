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

/**
 * Mirror image of the ABSOLUTE-original reversal case ({@code
 * StatementImportServiceSupersedeTest#absolute_reversesToThePreSetBalance_whenStillTheLiveAnchor}):
 * {@code StatementImportService.supersede}'s ADDITIVE-mode reversal branch assumes it is undoing a
 * delta that replacement's own confirm layered its own change on top of. That assumption breaks
 * when replacement's own confirm lands in ABSOLUTE mode instead -- ABSOLUTE doesn't add to {@code
 * Account.balance}, it OVERWRITES it outright with replacement's own stated closing balance
 * ({@code ImportService.persistSection}), discarding original's ADDITIVE contribution along with
 * everything else that predated it. Reversing original's delta against a balance that already
 * discarded it would move the balance by that amount for no reason.
 *
 * <p>Unlike the ABSOLUTE-original case this mirrors, there's nothing to reverse here: the overwrite
 * already leaves the balance correct on its own, so {@code supersede} simply skips the reversal.
 *
 * <p>Reproduced end-to-end against a real database for the same reason as the sibling IT: a mock of
 * {@code StatementImportService} alone can't exercise this, since the double-count this guards
 * against is baked in by two separate real {@code confirm()} calls before {@code supersede()} ever
 * runs.
 */
class SupersedeSkipsReversalWhenReplacementOverwritesTheBalanceIT extends AbstractIntegrationTest {

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
        user.setEmail("supersede-overwrite-skip-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Supersede Overwrite Skip User");
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

    private ConfirmedRow row(LocalDate date, String description, String amount) {
        return new ConfirmedRow(date, description, new BigDecimal(amount),
                "EXPENSE", "Other", true, "rule", null, false, null, null, false);
    }

    private BigDecimal balanceOf(Fixture f) {
        return accountRepository.findById(f.account().getId()).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("skips the reversal when an ADDITIVE original is superseded by an ABSOLUTE "
            + "replacement, leaving the balance exactly where the replacement's overwrite left it")
    void skipsReversalAndLeavesTheOverwrittenBalanceIntact() throws Exception {
        Fixture f = fixture("10000.00");
        LocalDate periodStart = LocalDate.of(2026, 7, 1);
        LocalDate periodEnd = LocalDate.of(2026, 7, 31);

        // Original: states NO closing balance (a partial read, an OCR miss, whatever the reason) --
        // ClosingBalanceGuard.assess returns NOT_APPLICABLE for a null claim, so this confirm is
        // ADDITIVE: it ADDS its -150.00 delta on top of the account's existing 10000.00. Dated on
        // the period's own last day so ImportService.isMostRecentStatementForAccount -- which
        // compares each statement's own transaction dates against its siblings' PERIOD END, not
        // period end to period end -- doesn't itself block replacement's ABSOLUTE mode below purely
        // because replacement's transactions predate original's stated period end.
        importService.confirm(f.user().getId(), statementFile("original.csv"),
                new ConfirmRequest(null, List.of(row(periodEnd, "COFFEE SHOP", "150.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), null, null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9850.00");

        // Replacement: same exact period, corrects the row to its true amount (200.00) -- and this
        // time DOES state a closing balance that corroborates against its own opening/rows
        // (10000 - 200 = 9800), so this confirm is ABSOLUTE: Account.balance is SET directly to
        // 9800.00, discarding the 9850.00 that was there a moment ago.
        importService.confirm(f.user().getId(), statementFile("replacement.csv"),
                new ConfirmRequest(null, List.of(row(periodEnd, "COFFEE SHOP", "200.00")), f.account().getId(),
                        null, new BigDecimal("10000.00"), new BigDecimal("9800.00"), null,
                        periodStart, periodEnd, null, null));
        assertThat(balanceOf(f)).isEqualByComparingTo("9800.00");

        List<StatementImport> imports = statementImportRepository
                .findAllByOrderByImportedAtDesc(org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(si -> si.getUserId().equals(f.user().getId())).toList();
        assertThat(imports).hasSize(2);
        StatementImport original = imports.stream()
                .filter(si -> si.getFileName().equals("original.csv")).findFirst().orElseThrow();
        StatementImport replacement = imports.stream()
                .filter(si -> si.getFileName().equals("replacement.csv")).findFirst().orElseThrow();
        assertThat(original.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ADDITIVE);
        assertThat(replacement.getBalanceApplicationMode())
                .isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);

        var result = statementImportService.supersede(f.user().getId(), original.getId(), replacement.getId());

        assertThat(result.balanceReversed()).isFalse();
        assertThat(result.warning()).isNull();
        // The critical assertion: NOT 9950.00 (9800 + the wrongly-reversed 150), but exactly what
        // replacement's own ABSOLUTE overwrite already established.
        assertThat(balanceOf(f)).isEqualByComparingTo("9800.00");
        StatementImport originalAfter = statementImportRepository.findById(original.getId()).orElseThrow();
        assertThat(originalAfter.getSupersededBy()).isEqualTo(replacement.getId());
    }
}
