package com.finora.imports.analysis;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.imports.*;
import com.finora.imports.pdf.*;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs one statement through the import pipeline and prints one tab-separated line per detected
 * section: would this statement's own closing balance corroborate ({@link ClosingBalanceGuard}
 * CORROBORATED, i.e. land in ABSOLUTE {@code BalanceApplicationMode}) if confirmed exactly as
 * staged, or not (ADDITIVE/NONE)? Read-only, same as {@link CorpusProbe}, and built the same way:
 * mirrors its pipeline construction exactly, then runs {@link ClosingBalanceGuard#assess} against
 * each section's own detected opening/closing balance and the totals of its OWN staged rows
 * (rowsSkipped=0 -- the "if the user confirmed everything as-is" case, since no real user-review
 * data exists for a corpus of raw files).
 *
 * <h2>Origin</h2>
 * Written to answer a question raised while fixing PR #638 (StatementImportService.supersede
 * refusing an ABSOLUTE original with a non-ABSOLUTE replacement): how common is a statement with
 * no corroborating closing balance in real data, which bounds how often that refusal -- or the
 * double-count it closed -- can actually fire. Kept as general-purpose tooling, same as
 * {@code CorpusProbe}, for the next question shaped like "how often does X happen across the real
 * corpus."
 *
 * <h2>What this does NOT measure</h2>
 * {@code isMostRecentStatementForAccount} is not modelled -- every document is evaluated as if it
 * were the account's only statement, which is the corpus's actual shape (one file per account/
 * period, not corrected-duplicate pairs). This is the CLOSING-BALANCE-CORROBORATION rate alone,
 * not full end-to-end {@code BalanceApplicationMode} assignment.
 *
 * <h2>Output</h2>
 * One line per section, tab-separated: {@code file, sectionIndex, suggestedAccountType,
 * rowsImported, openingBalance, closingBalance, verdict, reason} -- {@code verdict} is a {@link
 * ClosingBalanceGuard.Verdict} name, or {@code NO_SECTIONS}/{@code ERROR} when the pipeline found
 * nothing or threw. Deliberately TSV, not {@code CorpusProbe}'s JSON: this is meant to be piped
 * straight into {@code cut}/{@code sort}/{@code awk} for a quick tally, not machine-parsed by a
 * diff tool the way that class's schema-versioned JSON is.
 */
public final class ClosingBalanceCorpusProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ClosingBalanceCorpusProbe <path-to-statement.pdf>");
            System.exit(2);
        }
        Path pdf = Path.of(args[0]);
        try {
            byte[] bytes = Files.readAllBytes(pdf);
            try (PDDocument doc = Loader.loadPDF(bytes)) { /* just validating it opens */ }

            PdfTextExtractor textExtractor = new PdfTextExtractor();
            PdfTableLocator tableLocator = new PdfTableLocator();
            PdfPreviewGenerator generator = new PdfPreviewGenerator(
                    textExtractor, tableLocator, new PdfMetadataExtractor(), stubbedNormalizer(),
                    ProductDiscovery.standard(), new ProductAttributeExtractor(),
                    new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                            new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                            new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator()),
                    TestRuleEngines.empty());

            var generated = generator.generateSectionsWithContext(
                    UUID.randomUUID(), pdf.getFileName().toString(), bytes, null);

            List<StagedAccountSection> sections = generated.sections();
            for (int i = 0; i < sections.size(); i++) {
                StagedAccountSection section = sections.get(i);
                var account = section.detectedAccount();
                String suggestedType = account == null ? null : account.suggestedAccountType();
                Account.Type accountType = toAccountType(suggestedType);
                BigDecimal opening = account == null ? null : account.openingBalance();
                BigDecimal closing = account == null ? null : account.closingBalance();

                BigDecimal credits = BigDecimal.ZERO, debits = BigDecimal.ZERO;
                int rowsImported = 0;
                for (StagedRow row : section.rows()) {
                    rowsImported++;
                    if ("INCOME".equals(row.type())) credits = credits.add(row.amount());
                    else debits = debits.add(row.amount());
                }

                ClosingBalanceGuard.Decision decision = ClosingBalanceGuard.assess(
                        accountType, opening, closing, credits, debits, rowsImported, 0);

                System.out.println(String.join("\t",
                        pdf.getFileName().toString(),
                        String.valueOf(i),
                        String.valueOf(suggestedType),
                        String.valueOf(rowsImported),
                        String.valueOf(opening),
                        String.valueOf(closing),
                        decision.verdict().name(),
                        decision.reason()));
            }
            if (sections.isEmpty()) {
                System.out.println(String.join("\t", pdf.getFileName().toString(), "-", "-", "0",
                        "null", "null", "NO_SECTIONS", "no sections detected"));
            }
        } catch (Throwable t) {
            System.out.println(String.join("\t", pdf.getFileName().toString(), "-", "-", "-",
                    "-", "-", "ERROR", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
    }

    private static Account.Type toAccountType(String suggested) {
        if (suggested == null) return Account.Type.SAVINGS;
        try {
            return Account.Type.valueOf(suggested);
        } catch (IllegalArgumentException e) {
            return Account.Type.SAVINGS;
        }
    }

    private static TransactionNormalizer stubbedNormalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUserAndAccountIdIn(any(), any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions, TestAccountRepositories.anyLive()),
                TestRuleEngines.empty());
    }

    private ClosingBalanceCorpusProbe() {}
}
