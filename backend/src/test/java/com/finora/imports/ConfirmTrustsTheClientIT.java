package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
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
 * BH-006 / BH-023, the REPRODUCTION. No fix here — this exists to establish what actually happens
 * before anything changes, and to make the impact a number rather than an adjective.
 *
 * <h2>The claim under test</h2>
 *
 * <p>The confirmed ledger is supposed to be the statement the server parsed, reviewed by the user.
 * Two entry points do not enforce that:
 *
 * <ul>
 *   <li>{@code ImportService.confirmSession} compares the confirmed rows to the staged rows by
 *       <b>count only</b> — its own comment calls this "the cheapest real check that the confirmed
 *       list is plausibly the same staged rows, reviewed".</li>
 *   <li>{@code StatementImportService.confirmReimport} compares <b>nothing at all</b>. It takes
 *       {@code request.rows()} verbatim, re-scopes it to the original account, and confirms.</li>
 * </ul>
 *
 * <h2>Why this is not simply "users can edit their own data"</h2>
 *
 * <p>They can, and that is fine — {@code POST /transactions} exists. The difference is what the
 * import path additionally asserts about the rows it writes:
 *
 * <ol>
 *   <li><b>Provenance.</b> A {@code statement_imports} row says these transactions came from this
 *       document, and keeps the document. Re-parsing the stored bytes would produce something else.
 *       Every capability built on that link — re-import, the trace/evidence tables, audit — is
 *       reading a claim nothing checks.</li>
 *   <li><b>Corroboration.</b> {@code ClosingBalanceGuard} exists to refuse a stated closing balance
 *       that the transactions do not support (BH-004). Both sides of that arithmetic arrive in the
 *       same client request, so a self-consistent fabrication is CORROBORATED and
 *       {@code Account.balance} is overwritten with the stated figure. The guard cannot fail — it
 *       is checking a claim against itself.</li>
 * </ol>
 *
 * <p>That second point is the one that matters, because it means a control this repository already
 * closed as VERIFIED is load-bearing only when the caller is honest.
 */
class ConfirmTrustsTheClientIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
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
        user.setEmail("confirm-trust-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Confirm Trust User");
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

    /** What the SERVER parsed: one small coffee purchase. */
    private StagedRow parsed() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP", new BigDecimal("150.00"),
                "EXPENSE", "Other", "rule", null, false, null, null);
    }

    /** What the CLIENT sends back instead. Same count, nothing else in common. */
    private ConfirmedRow fabricated() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 15), "CONSULTING INCOME",
                new BigDecimal("250000.00"), "INCOME", "Other", true, "rule",
                null, false, null, null, false);
    }

    @Test
    @DisplayName("BH-023: confirmSession writes the client's rows, not the parsed ones -- only the COUNT is checked")
    void confirmSessionAcceptsRowsThatHaveNothingToDoWithTheStatement() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(parsed()), null);

        // Same row count, entirely different content -- and a stated closing balance that agrees
        // with the fabrication rather than with the statement.
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), List.of(fabricated()), account.getId(), null,
                new BigDecimal("10000.00"), new BigDecimal("260000.00")));

        List<Transaction> written = transactionRepository.findByUserId(user.getId());
        assertThat(written).hasSize(1);

        Transaction t = written.get(0);
        System.out.printf(
                "%nBH-023 reproduction -- confirmSession%n"
                + "  server parsed ....... 2026-07-01  COFFEE SHOP        150.00 EXPENSE%n"
                + "  client confirmed .... %s  %s  %s %s%n"
                + "  persisted ........... %s  %s  %s %s%n"
                + "  account balance ..... opened 10000.00, now %s%n%n",
                fabricated().date(), fabricated().description(), fabricated().amount(), fabricated().type(),
                t.getTxnDate(), t.getDescription(), t.getAmount(), t.getTxnType(),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());

        assertThat(t.getDescription())
                .as("the ledger records what the CLIENT sent, not what the server parsed from the "
                        + "document it is still storing")
                .isEqualTo("CONSULTING INCOME");
        assertThat(t.getAmount()).isEqualByComparingTo("250000.00");
        assertThat(t.getTxnType()).isEqualTo(Transaction.Type.INCOME);

        // The financial consequence. ClosingBalanceGuard corroborated a stated closing balance
        // against the same request's own rows, so it agreed, and the balance was overwritten.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .as("BH-004's guard cannot refuse this: it is checking the client's stated closing "
                        + "balance against the client's stated transactions")
                .isEqualByComparingTo("260000.00");
    }

    @Test
    @DisplayName("BH-023: a DIFFERENT row count is refused -- establishing that the count is the only check")
    void aMismatchedRowCountIsTheOnlyThingConfirmSessionRejects() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(parsed()), null);

        // Two rows against one staged. This is the ONLY shape the check catches, which is what
        // makes the test above a defect rather than a missing feature -- the validation exists,
        // it is simply the weakest possible version of itself.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                importService.confirmSession(user.getId(), new ConfirmRequest(
                        session.getId(), List.of(fabricated(), fabricated()), account.getId(), null,
                        null, null))))
                .as("count mismatch is rejected; content mismatch is not")
                .isNotNull();

        assertThat(transactionRepository.findByUserId(user.getId()))
                .as("nothing written when the count check fires")
                .isEmpty();
    }
}
