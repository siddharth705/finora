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
 * BH-023's regression suite. Every case here failed before the fix — they began life as a
 * characterization test asserting the defect, and were inverted once the guard landed.
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

    /** Confirms a session with whatever rows the caller supplies, returning the throwable or null. */
    private Throwable confirmWith(User user, Account account, List<ConfirmedRow> rows,
                                  BigDecimal opening, BigDecimal closing) {
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", FILE, List.of(parsed(), parsedSecond()), null);
        return org.assertj.core.api.Assertions.catchThrowable(() ->
                importService.confirmSession(user.getId(), new ConfirmRequest(
                        session.getId(), rows, account.getId(), null, opening, closing)));
    }

    /** The parsed pair, echoed back unchanged -- what every real client sends. */
    private List<ConfirmedRow> echoed() {
        return List.of(asConfirmed(parsed()), asConfirmed(parsedSecond()));
    }

    private ConfirmedRow asConfirmed(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), "Other", true,
                "rule", null, false, null, null, false);
    }

    private StagedRow parsedSecond() {
        return new StagedRow(LocalDate.of(2026, 7, 2), "BOOKSHOP", new BigDecimal("420.00"),
                "EXPENSE", "Other", "rule", null, false, null, null);
    }

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

    // --- rejected: any of the four document fields altered -------------------------------------

    @Test
    @DisplayName("BH-023: a tampered AMOUNT is refused, and the account balance does not move")
    void aTamperedAmountCannotReachTheLedgerOrTheBalance() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        // The reproduction, now refused. Before the fix this wrote CONSULTING INCOME 250000.00 and
        // moved the balance to 260000.00, because ClosingBalanceGuard corroborated the client's
        // stated closing balance against the client's own stated rows.
        Throwable thrown = confirmWith(user, account,
                List.of(fabricated(), asConfirmed(parsedSecond())),
                new BigDecimal("10000.00"), new BigDecimal("260000.00"));

        // Balance asserted FIRST, deliberately. All three of these fail against the pre-fix code,
        // but this is the one whose failure message should say what actually went wrong: money
        // moved. Against the count-only check this reads 260000.00.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .as("THE case this fix exists for -- a fabricated amount must not be able to move money")
                .isEqualByComparingTo("10000.00");
        assertThat(thrown)
                .as("a row the statement does not contain must not be importable as though it did")
                .isNotNull();
        assertThat(transactionRepository.findByUserId(user.getId()))
                .as("nothing partially written")
                .isEmpty();
    }

    @Test
    @DisplayName("BH-023: a tampered DATE is refused")
    void aTamperedDateIsRefused() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));
        ConfirmedRow shifted = new ConfirmedRow(LocalDate.of(2026, 9, 9), "COFFEE SHOP",
                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null, false, null, null, false);

        assertThat(confirmWith(user, account, List.of(shifted, asConfirmed(parsedSecond())), null, null))
                .as("moving a transaction into another statement period changes which month it "
                        + "lands in, and every total derived from that")
                .isNotNull();
    }

    @Test
    @DisplayName("BH-023: a tampered DESCRIPTION is refused")
    void aTamperedDescriptionIsRefused() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));
        ConfirmedRow renamed = new ConfirmedRow(LocalDate.of(2026, 7, 1), "SOMETHING ELSE ENTIRELY",
                new BigDecimal("150.00"), "EXPENSE", "Other", true, "rule", null, false, null, null, false);

        assertThat(confirmWith(user, account, List.of(renamed, asConfirmed(parsedSecond())), null, null))
                .as("the description drives merchant resolution and categorisation -- rewriting it "
                        + "re-points the row at a different merchant than the document names")
                .isNotNull();
    }

    @Test
    @DisplayName("BH-023: a tampered TYPE is refused -- an expense cannot be confirmed as income")
    void aTamperedTypeIsRefused() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));
        ConfirmedRow flipped = new ConfirmedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP",
                new BigDecimal("150.00"), "INCOME", "Other", true, "rule", null, false, null, null, false);

        assertThat(confirmWith(user, account, List.of(flipped, asConfirmed(parsedSecond())), null, null))
                .as("flipping the sign is the cheapest way to move a balance without touching an "
                        + "amount, and it is invisible in any check that only looks at magnitudes")
                .isNotNull();
    }

    @Test
    @DisplayName("BH-023: a mismatched row COUNT is still refused")
    void aMismatchedRowCountIsStillRefused() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        assertThat(confirmWith(user, account, List.of(asConfirmed(parsed())), null, null))
                .as("the check the fix replaces still has to hold -- it was too weak, not wrong")
                .isNotNull();
    }

    @Test
    @DisplayName("BH-023: confirming the SAME row twice in place of two different rows is refused")
    void duplicatingOneRowToCoverAnotherIsRefused() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        // Right count, every row individually present in the parse -- and still wrong. This is why
        // the comparison counts occurrences instead of testing set membership.
        assertThat(confirmWith(user, account, List.of(asConfirmed(parsed()), asConfirmed(parsed())), null, null))
                .as("a set-membership check would accept this; a multiset must not")
                .isNotNull();
    }

    // --- accepted: everything a real review legitimately does -----------------------------------

    @Test
    @DisplayName("NEGATIVE: the rows exactly as parsed are accepted -- the guard must not break imports")
    void theParsedRowsEchoedBackAreAccepted() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        assertThat(confirmWith(user, account, echoed(), null, null))
                .as("this is what every real client sends -- importReview.ts forwards date, "
                        + "description, amount and type verbatim")
                .isNull();
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(2);
    }

    @Test
    @DisplayName("NEGATIVE: REORDERED rows are accepted -- the contract documented reordering as fine")
    void reorderedRowsAreAccepted() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        // The reason the comparison is a multiset rather than positional. A positional check would
        // pass today, because the web client happens to preserve order, and break the first client
        // that does not.
        assertThat(confirmWith(user, account,
                List.of(asConfirmed(parsedSecond()), asConfirmed(parsed())), null, null))
                .as("same rows, different order -- the check this replaces explicitly allowed it")
                .isNull();
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(2);
    }

    @Test
    @DisplayName("NEGATIVE: category, include and the duplicate decision stay the user's to change")
    void theReviewItselfIsStillFree() {
        User user = user();
        Account account = account(user, new BigDecimal("10000.00"));

        // A real review: recategorise one row, deselect the other, and answer a duplicate prompt.
        // Locking these would protect nothing and break the feature.
        ConfirmedRow recategorised = new ConfirmedRow(LocalDate.of(2026, 7, 1), "COFFEE SHOP",
                new BigDecimal("150.00"), "EXPENSE", "Dining", true, "user_rule", null, false, null, null, true);
        ConfirmedRow deselected = new ConfirmedRow(LocalDate.of(2026, 7, 2), "BOOKSHOP",
                new BigDecimal("420.00"), "EXPENSE", "Shopping", false, "rule", null, false, null, null, false);

        assertThat(confirmWith(user, account, List.of(recategorised, deselected), null, null))
                .as("the review is the point of the review screen")
                .isNull();
        assertThat(transactionRepository.findByUserId(user.getId()))
                .as("one imported, one deselected")
                .hasSize(1);
        assertThat(transactionRepository.findByUserId(user.getId()).get(0).getDescription())
                .isEqualTo("COFFEE SHOP");
    }
}
