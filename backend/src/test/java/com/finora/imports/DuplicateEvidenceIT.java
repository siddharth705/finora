package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WI5: duplicate detection is decision support, not a filter.
 *
 * <p>The old contract was a boolean. A staged row said "I look like a duplicate" and nothing else,
 * so a client could drop it without the person ever seeing what it was supposedly a duplicate OF —
 * and the user had no way to tell a genuine re-import from the two identical coffees they actually
 * bought. The system's job is to present what it found; the decision is the user's.
 *
 * <p>These assert the EVIDENCE reaches the staged row through the real pipeline, against real
 * Postgres. A unit test on the detector alone would pass while the field was dropped somewhere
 * between the detector and the response, which is exactly where it would break.
 */
class DuplicateEvidenceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private record Fixture(User user, Account account) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("duplicate-evidence-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Duplicate Evidence IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Account account = new Account();
        account.setUserId(savedUser.getId());
        account.setName("Evidence IT Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return new Fixture(savedUser, accountRepository.save(account));
    }

    private Transaction existing(Fixture f, String description, String amount, LocalDate date) {
        Transaction txn = new Transaction();
        txn.setUserId(f.user().getId());
        txn.setAccountId(f.account().getId());
        txn.setTxnDate(date);
        txn.setDescription(description);
        txn.setAmount(new BigDecimal(amount));
        txn.setTxnType(Transaction.Type.EXPENSE);
        return transactionRepository.save(txn);
    }

    private MockMultipartFile statement(String description, String amount, String date) {
        String csv = "Date,Description,Amount,Type\n" + date + "," + description + "," + amount + ",DEBIT\n";
        return new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The core of WI5: the row does not merely say "duplicate", it says what it duplicates.
     *
     * <p>Everything asserted here is something a user needs in order to choose. Without the
     * existing transaction's id there is nothing to link to; without its date and amount there is
     * nothing to compare; without the reason the flag is an assertion the user has to take on
     * trust.
     */
    @Test
    void aStagedRowCarriesTheTransactionItAppearsToRepeat() throws Exception {
        Fixture f = fixture();
        Transaction alreadyImported = existing(f, "SWIGGY ORDER 4471", "486.00", LocalDate.of(2026, 7, 10));

        var staged = importService.parseAndStageWithSession(
                f.user().getId(), statement("SWIGGY ORDER 4471", "486.00", "2026-07-10"));

        assertThat(staged.staging().rows()).hasSize(1);
        var row = staged.staging().rows().get(0);
        assertThat(row.likelyDuplicate()).isTrue();

        var match = row.duplicateMatch();
        assertThat(match).as("the flag alone is not a decision the user can make").isNotNull();
        assertThat(match.existingTransactionId()).isEqualTo(alreadyImported.getId());
        assertThat(match.existingAccountId()).isEqualTo(f.account().getId());
        assertThat(match.existingDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(match.existingAmount()).isEqualByComparingTo("486.00");
        assertThat(match.existingDescription()).isEqualTo("SWIGGY ORDER 4471");
        assertThat(match.existingImportedAt()).isNotNull();
        assertThat(match.confidence()).isEqualTo("EXACT");
        assertThat(match.reason()).contains("Same date, amount and description");
    }

    /** A row that repeats nothing carries no evidence and no flag — the absence has to be
     *  unambiguous, or a client cannot tell "not checked" from "checked and clean". */
    @Test
    void aRowThatRepeatsNothingCarriesNoMatch() throws Exception {
        Fixture f = fixture();
        existing(f, "SWIGGY ORDER 4471", "486.00", LocalDate.of(2026, 7, 10));

        var staged = importService.parseAndStageWithSession(
                f.user().getId(), statement("BLINKIT GROCERIES 9982", "1240.50", "2026-07-11"));

        var row = staged.staging().rows().get(0);
        assertThat(row.likelyDuplicate()).isFalse();
        assertThat(row.duplicateMatch()).isNull();
    }

    /**
     * More than one match is a signal in its own right, and the opposite of the one a filter would
     * draw from it.
     *
     * <p>Two existing transactions with the same date, amount and description usually means the
     * user genuinely transacts this repeatedly — a daily fare, a split bill — which is precisely
     * the case where skipping the row is wrong. Reporting the count lets the review screen say so
     * rather than presenting it as a more certain duplicate.
     */
    @Test
    void repeatedIdenticalTransactionsAreReportedAsACount() throws Exception {
        Fixture f = fixture();
        existing(f, "METRO FARE", "45.00", LocalDate.of(2026, 7, 10));
        existing(f, "METRO FARE", "45.00", LocalDate.of(2026, 7, 10));

        var staged = importService.parseAndStageWithSession(
                f.user().getId(), statement("METRO FARE", "45.00", "2026-07-10"));

        assertThat(staged.staging().rows().get(0).duplicateMatch().matchCount()).isEqualTo(2);
    }

    /** Staging stays read-only (WI3) even on the duplicate path -- the evidence lookup is a read,
     *  and flagging a row must not be a reason to start writing again. */
    @Test
    void gatheringDuplicateEvidenceWritesNothing() throws Exception {
        Fixture f = fixture();
        existing(f, "SWIGGY ORDER 4471", "486.00", LocalDate.of(2026, 7, 10));
        long transactionsBefore = transactionRepository.findByUserId(f.user().getId()).size();

        importService.parseAndStageWithSession(
                f.user().getId(), statement("SWIGGY ORDER 4471", "486.00", "2026-07-10"));

        assertThat(transactionRepository.findByUserId(f.user().getId())).hasSize((int) transactionsBefore);
    }
}
