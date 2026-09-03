package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam nothing else covers: PDF BYTES -> parser -> staged rows -> confirm -> PERSISTED
 * TRANSACTIONS, in one run, with no value written by hand anywhere in the middle.
 *
 * <h2>Why this did not already exist</h2>
 *
 * Both halves are well covered and neither half touches the other. The parser side is proven by the
 * locator/normalizer unit tests and by the real-statement corpus, but the corpus harness
 * ({@code CorpusProbe}) constructs only a {@code PdfPreviewGenerator} -- it never reaches
 * {@code ImportService}, confirm, or the database. The persistence side is proven by the import
 * integration tests, but every one of them CONSTRUCTS ITS STAGED ROWS BY HAND
 * ({@code new StagedRow(LocalDate.of(...), "COFFEE SHOP", ...)}) and feeds those to
 * {@code confirmSession}. So a value could be parsed correctly and persisted incorrectly, or the two
 * sides could disagree about a field's meaning, and every test in the repository would still pass.
 *
 * <p>That is exactly the failure this project has already paid for elsewhere -- see the
 * seam-verification note in the engineering principles: verify an identifier from producer through
 * storage to consumer, not one layer at a time.
 *
 * <h2>What is asserted, and why in this order</h2>
 *
 * The expectations are read off the FIXTURE'S OWN PRINTED CONTENT, not off what the pipeline
 * happens to produce. {@link PdfFixtureBuilder#buildSingularDepositWithdrawalColumnsSample} prints
 * dated rows across separate withdrawal and deposit columns; their dates, descriptions, amounts and directions are
 * asserted first as STAGED, then again as PERSISTED. Asserting both ends against the same printed
 * source is the point: asserting the persisted rows against the staged rows would pass even if the
 * parser read the document wrongly.
 *
 * <p>All content is synthetic (Synthetic Fixture Policy) -- the fixture is a generated PDF, and no
 * value here comes from a real customer statement.
 */
class PdfImportEndToEndIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

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
        user.setEmail("pdf-e2e-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Pdf End To End User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("50000.00"));
        return accountRepository.save(account);
    }

    /** Every row the caller chose to keep, mapped 1:1 from what the parser staged. */
    private static List<ConfirmedRow> confirmAll(List<StagedRow> staged) {
        return staged.stream()
                .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                        "rule", null, false, null, null, false))
                .toList();
    }


    /** The one staged row whose description carries {@code fragment}. Fails loudly rather than
     *  returning an arbitrary match, so an ambiguous fixture can never silently pass. */
    private static StagedRow stagedNamed(List<StagedRow> rows, String fragment) {
        List<StagedRow> matches = rows.stream()
                .filter(r -> r.description() != null && r.description().contains(fragment)).toList();
        assertThat(matches).as("exactly one staged row mentions '%s'", fragment).hasSize(1);
        return matches.get(0);
    }

    /** As above, for what actually came back out of the database. */
    private static Transaction persistedNamed(List<Transaction> rows, String fragment) {
        List<Transaction> matches = rows.stream()
                .filter(t -> t.getDescription() != null && t.getDescription().contains(fragment)).toList();
        assertThat(matches).as("exactly one persisted transaction mentions '%s'", fragment).hasSize(1);
        return matches.get(0);
    }

    @Test
    @DisplayName("a PDF's own printed transactions survive parse, stage, confirm and persist")
    void aPdfsPrintedTransactionsArePersistedUnchanged() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();
        User user = user();
        Account account = account(user);

        // --- parse and stage, through the real entry point the controller uses ---
        StagingResponse staged = importService.parseAndStageAnyFormat(
                user.getId(), "PDF", "statement.pdf", pdf, null);

        assertThat(staged.rows())
                .as("the fixture prints exactly three dated transaction rows")
                .hasSize(3);

        // Looked up BY DESCRIPTION, never by sort position: the two rows this test most cares
        // about share a date and differ only in which amount column carries their value.
        StagedRow withdrawal = stagedNamed(staged.rows(), "Landlord");
        StagedRow deposit = stagedNamed(staged.rows(), "SAMPLE PAYEE A");

        // Read off the fixture's own printed rows, not off the pipeline's output.
        assertThat(withdrawal.date()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(withdrawal.amount()).isEqualByComparingTo("1000.00");
        assertThat(withdrawal.type()).isEqualTo("EXPENSE");

        assertThat(deposit.date()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(deposit.amount()).isEqualByComparingTo("10.00");
        assertThat(deposit.type())
                .as("direction comes from WHICH amount column held the value -- these two rows "
                        + "share a date, and nothing else distinguishes them")
                .isEqualTo("INCOME");

        // --- confirm, exactly as a user accepting every staged row would ---
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.pdf", pdf, staged.rows(), staged.detectedAccount());
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), confirmAll(staged.rows()), account.getId(), null, null, null, null));

        // --- and read them back out of the database ---
        List<Transaction> persisted = transactionRepository.findByUserId(user.getId());

        assertThat(persisted)
                .as("every staged row reached the database -- none silently dropped between "
                        + "confirm and persist, which no other test in this repository would catch")
                .hasSize(3);

        Transaction persistedWithdrawal = persistedNamed(persisted, "Landlord");
        Transaction persistedDeposit = persistedNamed(persisted, "SAMPLE PAYEE A");

        assertThat(persistedWithdrawal.getTxnDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(persistedWithdrawal.getAmount()).isEqualByComparingTo("1000.00");
        assertThat(persistedWithdrawal.getTxnType()).isEqualTo(Transaction.Type.EXPENSE);
        assertThat(persistedWithdrawal.getAccountId()).isEqualTo(account.getId());

        assertThat(persistedDeposit.getTxnDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(persistedDeposit.getAmount()).isEqualByComparingTo("10.00");
        assertThat(persistedDeposit.getTxnType())
                .as("direction survives the whole chain -- a deposit must not persist as an "
                        + "expense, and it shares its date with the withdrawal above it")
                .isEqualTo(Transaction.Type.INCOME);
    }

    @Test
    @DisplayName("a row the user excludes at confirm is not persisted")
    void anExcludedRowDoesNotReachTheDatabase() throws Exception {
        // The other half of the seam: confirm is where the user's own choice enters, and a test
        // that only ever confirms everything cannot tell "persisted what was parsed" apart from
        // "persisted everything it parsed regardless of what was asked for".
        byte[] pdf = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();
        User user = user();
        Account account = account(user);

        StagingResponse staged = importService.parseAndStageAnyFormat(
                user.getId(), "PDF", "statement.pdf", pdf, null);
        assertThat(staged.rows()).hasSize(3);

        List<ConfirmedRow> rows = staged.rows().stream()
                .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(),
                        !r.description().contains("SAMPLE PAYEE A"),   // exclude exactly one
                        "rule", null, false, null, null, false))
                .toList();

        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.pdf", pdf, staged.rows(), staged.detectedAccount());
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), rows, account.getId(), null, null, null, null));

        List<Transaction> persisted = transactionRepository.findByUserId(user.getId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted).extracting(Transaction::getDescription)
                .noneMatch(d -> d.contains("SAMPLE PAYEE A"));
    }
}
