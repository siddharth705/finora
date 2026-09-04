package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives EVERY real statement in the local corpus through the whole import -- parse, stage,
 * confirm, persist -- and reports how many completed and why any did not.
 *
 * <h2>This is a local instrument, not a CI gate</h2>
 *
 * The corpus is real customer bank statements and must never be committed (Synthetic Fixture
 * Policy, and the 2026-08-08 incident behind it), so this test cannot run in CI and does not try
 * to: it skips itself unless the corpus directory is present. {@link PdfImportEndToEndIT} is the
 * committed, synthetic proof of the same seam and runs everywhere. This one answers the different
 * question that only real documents can answer -- "would these 29 statements actually import?"
 *
 * <h2>What "imported" means here, precisely</h2>
 *
 * A statement counts as imported when the pipeline parses it, stages at least one transaction,
 * confirm completes without error, and every confirmed row is then found in the database. That last
 * clause is the point: a document that stages fifty rows and persists forty-nine has NOT imported,
 * and no row count taken before confirm would show it.
 *
 * <p>Deliberately NOT asserted here: whether the staged values are CORRECT. That is the
 * ground-truth runner's job ({@code scripts/run-corpus-ground-truth.py}), and conflating the two
 * would let a document that imports garbage cleanly count as a success. This measures reachability
 * of the import path, and says so.
 *
 * <h2>Metadata, and why it needed its own half</h2>
 *
 * Transactions are not the only thing an import produces. The statement period, opening and closing
 * balances, the payment summary, the card number, the credit limit, the holder, branch and IFSC are
 * all read at STAGING and then have to be echoed back through {@code ConfirmRequest} /
 * {@code NewAccountRequest} to be stored -- {@code persistSection} stores what the request carries
 * and deliberately never re-derives any of it from the confirmed rows. That echo is a wire contract
 * with the review screen, and it is exactly the kind of seam that fails silently: a field extracted
 * perfectly and dropped on the way to the database looks identical, from either side alone, to a
 * field the document never printed.
 *
 * <p>So each imported statement is also reported field by field, in three states -- and the middle
 * one is the point:
 *
 * <ul>
 *   <li><b>in the database</b> -- extracted, echoed, stored, and read back equal.</li>
 *   <li><b>LOST</b> -- the parser had the value and the database does not. A real defect, and the
 *       only metadata state this test asserts on.</li>
 *   <li><b>not printed</b> -- the parser found nothing. Reported, never asserted: statements
 *       genuinely do not all carry a credit limit or an IFSC code, and whether a given absence is
 *       correct is decided against per-document expectations the ground-truth runner holds and this
 *       instrument does not. Demanding a value here would turn a document's own silence into a
 *       pipeline defect.</li>
 * </ul>
 *
 * <p>Set {@code FINORA_CORPUS_DIR} to point at the corpus; the directory is read recursively, since
 * it is a human-maintained folder that has already been reorganised into per-product subfolders
 * once.
 */
class RealCorpusImportEndToEndIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private StatementImportRepository statementImportRepository;

    private final List<UUID> createdUserIds = new ArrayList<>();

    /**
     * One statement's journey through the import, and what became of it.
     *
     * <p>Three verdicts, not two, and the third is the important one. "Staged nothing" is NOT the
     * same event as "the import path broke": a statement can legitimately report no activity, and
     * at least one in this corpus does -- its ground truth expects zero transactions and it passes
     * the correctness gate. Calling that a failure would report a defect that does not exist, and
     * would also hide a real one behind the same word. Whether zero is CORRECT for a given document
     * is the ground-truth runner's question, not this instrument's; this one only reports that
     * nothing was staged, and refuses to guess which kind of nothing it was.
     */
    private record Outcome(String file, String verdict, int staged, int confirmed, int persisted,
                            List<Field> metadata, String reason) {
        static Outcome imported(String f, int staged, int persisted, List<Field> metadata) {
            return new Outcome(f, "IMPORTED", staged, staged, persisted, metadata, "");
        }
        static Outcome nothingToImport(String f) {
            return new Outcome(f, "NO_TXNS", 0, 0, 0, List.of(),
                    "parsed cleanly, staged no transactions -- correct for a nil-activity "
                            + "statement, unproven otherwise; the ground-truth runner decides which");
        }
        static Outcome failed(String f, int staged, int confirmed, int persisted, String why) {
            return new Outcome(f, "FAILED", staged, confirmed, persisted, List.of(), why);
        }
        List<Field> extracted() { return metadata.stream().filter(Field::wasExtracted).toList(); }
        List<Field> lost() { return metadata.stream().filter(Field::wasLost).toList(); }
    }

    /**
     * One metadata field's fate on one statement: what the parser read off the document, and what
     * the database holds afterwards.
     *
     * <p>Three states, for the same reason {@link Outcome} has three verdicts. A field the parser
     * never extracted is ABSENT, not a failure -- statements genuinely do not all print a credit
     * limit, an IFSC code or a branch, and whether a particular absence is CORRECT is the
     * ground-truth runner's question, decided against per-document expectations this instrument
     * does not have. A field that was extracted and then did not arrive is LOST, and that is a real
     * defect: the value existed, the pipeline had it, and the database does not.
     */
    private record Field(String name, Object extracted, Object persisted) {
        boolean wasExtracted() { return extracted != null; }
        boolean survived() { return wasExtracted() && sameValue(extracted, persisted); }
        boolean wasLost() { return wasExtracted() && !survived(); }

        /** BigDecimal.equals is scale-sensitive -- 100.0 and 100.00 are equal amounts and unequal
         *  objects, and a column's scale legitimately changes between the parser and the database.
         *  Comparing with equals here would report loss on a value that arrived perfectly. */
        private static boolean sameValue(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
            return a.equals(b);
        }
    }

    private static Path corpusDir() {
        String configured = System.getenv("FINORA_CORPUS_DIR");
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    private User user(String label) {
        User user = new User();
        user.setEmail("corpus-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(label);
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    /**
     * The account the review screen would create, prefilled from what the parser detected.
     *
     * <p>The earlier version of this test confirmed into a bare hand-made SAVINGS account instead,
     * and that quietly made the whole metadata half unobservable: account-level fields (card
     * number, credit limit, holder, branch, IFSC) are only ever written on the NEW-account branch
     * of {@code resolveTargetAccount}, so reusing an existing account meant the pipeline was never
     * asked to store them and nothing could notice that it had not.
     */
    private static NewAccountRequest reviewScreenAccount(DetectedAccountInfo d) {
        String name = d.suggestedName() == null || d.suggestedName().isBlank()
                ? "Bank Statement Import" : d.suggestedName();
        return new NewAccountRequest(
                name, d.suggestedAccountType(), d.openingBalance(), d.creditLimit(),
                d.paymentDueDate(), d.accountHolderName(), d.accountNumberMasked(),
                d.bank() == null ? null : d.bank().id(), d.branchName(), d.ifscCode(),
                d.detectedProduct(), d.productIdentityHash(),
                d.principalAmount(), d.interestRate(), d.maturityDate(), d.maturityAmount(),
                d.installmentAmount(), d.installmentsPaid(), d.installmentsTotal());
    }

    /**
     * Confirm the way a real client does -- echoing the staged values back.
     *
     * <p>This is not a convenience: {@code persistSection} stores the statement period, balances
     * and payment summary from the REQUEST and deliberately never re-derives them from the
     * confirmed rows (a period derived from row dates is only ever a lower bound -- see that
     * method's own comment). Passing nulls here, as this test first did, therefore measured
     * nothing about extraction; it measured the nulls this test itself supplied.
     */
    private static ConfirmRequest confirmRequestFor(UUID sessionId, List<ConfirmedRow> rows,
                                                    DetectedAccountInfo d) {
        return new ConfirmRequest(sessionId, rows, null, reviewScreenAccount(d),
                d.openingBalance(), d.closingBalance(), null,
                d.statementPeriodStart(), d.statementPeriodEnd(),
                d.totalAmountDue(), d.paymentDueDate(), null, null);
    }

    /**
     * Every metadata field this pipeline extracts, paired with what the database holds for it.
     *
     * <p>Only fields the pipeline ECHOES verbatim are listed. {@code detectedProduct} is
     * deliberately absent: it is not echoed but re-resolved through ProductDiscovery, so a
     * difference between what was staged and what was stored is a decision, not a loss, and this
     * instrument cannot tell those apart.
     */
    private List<Field> metadataOf(User user, DetectedAccountInfo d, ConfirmResponse confirmed) {
        List<StatementImport> imports = statementImportRepository.findAll().stream()
                .filter(si -> user.getId().equals(si.getUserId())).toList();
        if (imports.size() != 1) {
            return List.of(new Field("statement_import row", "exactly 1", imports.size() + " rows"));
        }
        StatementImport si = imports.get(0);
        Account account = confirmed.account() == null ? null
                : accountRepository.findById(confirmed.account().id()).orElse(null);

        List<Field> fields = new ArrayList<>(List.of(
                new Field("statementPeriodStart", d.statementPeriodStart(), si.getStatementPeriodStart()),
                new Field("statementPeriodEnd", d.statementPeriodEnd(), si.getStatementPeriodEnd()),
                new Field("openingBalance", d.openingBalance(), si.getOpeningBalance()),
                new Field("closingBalance", d.closingBalance(), si.getClosingBalance()),
                new Field("totalAmountDue", d.totalAmountDue(), si.getTotalAmountDue()),
                new Field("paymentDueDate", d.paymentDueDate(), si.getPaymentDueDate())));
        fields.add(new Field("accountNumberMasked", d.accountNumberMasked(),
                account == null ? null : account.getAccountNumberMasked()));
        fields.add(new Field("creditLimit", d.creditLimit(),
                account == null ? null : account.getCreditLimit()));
        fields.add(new Field("accountHolderName", d.accountHolderName(),
                account == null ? null : account.getAccountHolderName()));
        fields.add(new Field("branchName", d.branchName(),
                account == null ? null : account.getBranchName()));
        fields.add(new Field("ifscCode", d.ifscCode(),
                account == null ? null : account.getIfscCode()));
        return fields;
    }

    /** Every statement in the corpus, matched case-insensitively and read recursively -- the same
     *  discovery rule the corpus scripts use, and for the same two reasons they document. */
    private static List<Path> statements(Path corpus) throws IOException {
        try (Stream<Path> walk = Files.walk(corpus)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private Outcome importOne(Path statement) {
        String name = statement.getFileName().toString();
        int staged = 0;
        int confirmed = 0;
        try {
            byte[] content = Files.readAllBytes(statement);
            User user = user("Corpus " + name);

            StagingResponse response = importService.parseAndStageAnyFormat(
                    user.getId(), "PDF", name, content, null);
            List<StagedRow> rows = response.rows() == null ? List.of() : response.rows();
            staged = rows.size();
            if (staged == 0) {
                return Outcome.nothingToImport(name);
            }
            DetectedAccountInfo detected = response.detectedAccount();
            if (detected == null) {
                return Outcome.failed(name, staged, 0, 0,
                        "staged " + staged + " rows but detected no account info at all");
            }

            List<ConfirmedRow> toConfirm = rows.stream()
                    .map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                            r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                            "rule", null, false, null, null, false))
                    .toList();
            confirmed = toConfirm.size();

            ImportSession session = importSessionService.createSession(
                    user.getId(), name, content, rows, detected);
            ConfirmResponse result = importService.confirmSession(
                    user.getId(), confirmRequestFor(session.getId(), toConfirm, detected));

            int persisted = transactionRepository.findByUserId(user.getId()).size();
            if (persisted != confirmed) {
                return Outcome.failed(name, staged, confirmed, persisted,
                        "confirmed " + confirmed + " rows but only " + persisted + " reached the database");
            }
            return Outcome.imported(name, staged, persisted, metadataOf(user, detected, result));
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " "));
            return Outcome.failed(name, staged, confirmed, 0,
                    why.length() > 160 ? why.substring(0, 160) + "..." : why);
        }
    }

    @Test
    void everyRealStatementImportsEndToEnd() throws Exception {
        Path corpus = corpusDir();
        assumeTrue(corpus != null && Files.isDirectory(corpus),
                "FINORA_CORPUS_DIR is not set to a real corpus directory -- this instrument only "
                        + "runs locally, because the corpus can never be committed");

        List<Path> statements = statements(corpus);
        assumeTrue(!statements.isEmpty(), "no statements found under " + corpus);

        List<Outcome> outcomes = new ArrayList<>();
        for (Path statement : statements) {
            outcomes.add(importOne(statement));
            // Learning events accumulate per import; clear as we go so a 29-document run does not
            // leave a pile of them behind for the next test in this shared database.
            removeQueuedLearningEvents();
        }

        List<Outcome> failures = outcomes.stream().filter(o -> "FAILED".equals(o.verdict())).toList();
        List<Outcome> empty = outcomes.stream().filter(o -> "NO_TXNS".equals(o.verdict())).toList();
        int imported = outcomes.size() - failures.size() - empty.size();
        List<Outcome> withLostMetadata = outcomes.stream().filter(o -> !o.lost().isEmpty()).toList();
        int fieldsExtracted = outcomes.stream().mapToInt(o -> o.extracted().size()).sum();
        int fieldsLost = outcomes.stream().mapToInt(o -> o.lost().size()).sum();

        StringBuilder report = new StringBuilder("\n\nEND-TO-END IMPORT OF THE REAL CORPUS\n");
        report.append("=".repeat(104)).append('\n');
        report.append(String.format("%-42s %-10s %8s %10s %9s   %s%n",
                "statement", "verdict", "staged", "persisted", "metadata", "reason"));
        report.append("-".repeat(104)).append('\n');
        for (Outcome o : outcomes) {
            // "6/8" reads: of the 8 metadata fields the parser found on this statement, 6 are in
            // the database. Blank for a statement that staged nothing -- there is no import to
            // carry metadata, and printing "0/0" would read like a finding.
            String meta = "IMPORTED".equals(o.verdict())
                    ? (o.extracted().size() - o.lost().size()) + "/" + o.extracted().size() : "";
            report.append(String.format("%-42s %-10s %8d %10d %9s   %s%n",
                    o.file().length() > 41 ? o.file().substring(0, 41) : o.file(),
                    o.verdict(), o.staged(), o.persisted(), meta, o.reason()));
        }
        report.append("-".repeat(104)).append('\n');
        report.append(String.format(
                "%n  OUT OF %d STATEMENTS: %d IMPORTED, %d STAGED NOTHING, %d FAILED%n",
                outcomes.size(), imported, empty.size(), failures.size()));
        report.append(String.format("  transactions persisted in total: %d%n",
                outcomes.stream().mapToInt(Outcome::persisted).sum()));
        // Conditional, because the report prints BEFORE the assertion below and a failing run would
        // otherwise state this as fact while contradicting itself six lines later.
        report.append(failures.isEmpty()
                ? "  every confirmed row reached the database on every imported statement.\n"
                : "  NOT every confirmed row reached the database -- see FAILURES below.\n");
        report.append(String.format(
                "  metadata fields extracted: %d, of which %d reached the database and %d were LOST%n",
                fieldsExtracted, fieldsExtracted - fieldsLost, fieldsLost));

        // What each imported statement actually yielded, field by field. This is the half a count
        // cannot show: "6/8" is the same number whether the two missing fields were never printed
        // on the document or were read and then dropped, and those are opposite situations.
        report.append("\n  METADATA EXTRACTED, PER STATEMENT\n");
        for (Outcome o : outcomes) {
            if (!"IMPORTED".equals(o.verdict())) continue;
            String present = o.extracted().stream().filter(Field::survived)
                    .map(Field::name).collect(Collectors.joining(", "));
            String missing = o.metadata().stream().filter(f -> !f.wasExtracted())
                    .map(Field::name).collect(Collectors.joining(", "));
            report.append("    ").append(o.file()).append('\n');
            report.append("      in the database : ").append(present.isEmpty() ? "(none)" : present).append('\n');
            report.append("      not printed     : ").append(missing.isEmpty() ? "(none)" : missing).append('\n');
        }

        if (!empty.isEmpty()) {
            report.append("\n  STAGED NOTHING (not an import failure -- see Outcome's doc comment)\n");
            for (Outcome e : empty) {
                report.append("    - ").append(e.file()).append('\n');
            }
        }
        if (!withLostMetadata.isEmpty()) {
            report.append("\n  METADATA LOST BETWEEN THE PARSER AND THE DATABASE\n");
            for (Outcome o : withLostMetadata) {
                for (Field f : o.lost()) {
                    report.append("    - ").append(o.file()).append(": ").append(f.name())
                            .append(" was extracted but the database has a different value\n");
                }
            }
        }
        if (!failures.isEmpty()) {
            report.append("\n  FAILURES\n");
            for (Outcome f : failures) {
                report.append("    - ").append(f.file()).append(": ").append(f.reason()).append('\n');
            }
        }
        System.out.println(report);

        // The report is the deliverable; this assertion is what makes it a test rather than a
        // script. Only FAILED counts: a statement that throws, or whose confirmed rows do not all
        // arrive, is a real defect regardless of value correctness. A statement that staged nothing
        // is reported and not asserted on, because this instrument cannot tell a nil-activity
        // statement from an unsupported one -- and pretending otherwise is how a green run starts
        // meaning less than it appears to.
        assertThat(failures)
                .as("statements that could not be imported end to end:%n%s", report)
                .isEmpty();

        // The metadata half, and note what it does NOT assert: that a field was extracted at all.
        // A statement that prints no IFSC code has nothing to lose, and demanding a value here
        // would turn a document's own silence into a pipeline defect. What IS asserted is that
        // nothing the parser read gets dropped on the way to the database -- the failure mode this
        // whole seam exists to catch, and one no per-layer test can see.
        assertThat(withLostMetadata)
                .as("statements whose extracted metadata did not survive to the database:%n%s", report)
                .isEmpty();
    }

    private void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
    }
}
