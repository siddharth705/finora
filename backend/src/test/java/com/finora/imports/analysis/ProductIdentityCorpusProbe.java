package com.finora.imports.analysis;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.DuplicateDetector;
import com.finora.imports.ImportVerifier;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.TestRuleEngines;
import com.finora.imports.TransactionNormalizer;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.product.FinancialProductType;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.imports.product.ProductIdentity;
import com.finora.imports.product.ProductIdentityResolver;
import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProductIdentityResolver audit, Phase 3. Answers a question {@link CorpusProbe} cannot: given a
 * real, possibly composite, statement, do its sections' PRODUCT IDENTITIES ever collide with each
 * other?
 *
 * <p>{@link CorpusProbe} already proves classification -- what each section IS -- against the real
 * corpus. It stops short of identity: it reads {@code accountNumberMasked} and
 * {@code detectedProduct} per section but never {@code productIdentityHash}, and never runs
 * {@link ProductIdentity#matches}. This class runs the identical pipeline construction (so it
 * cannot silently drift onto a different pipeline than the one {@link CorpusProbe} and
 * {@code PdfPipelineDiagnostic} describe) and adds exactly that: it computes every section's
 * {@link ProductIdentity} and checks each pair.
 *
 * <h2>What "collide" means here</h2>
 *
 * For a real composite statement -- Savings + RD + FD sharing one relationship number is the
 * documented case ({@link ProductIdentity#of(String, FinancialProductType, String, String, String)}'s
 * own class doc) -- every section's identity must be {@link ProductIdentity.Match#NONE} against
 * every OTHER section in the same document. Two sections matching EXACT or PROBABLE against each
 * other is exactly the failure {@link ProductIdentityResolver} exists to prevent: a second product
 * silently redirected into the first one's account, or the two flagged as maybe-the-same forever.
 * A section matching only itself (trivially, same hash) is the only correct outcome.
 *
 * <h2>Nothing sensitive is printed</h2>
 *
 * The full account/deposit number never reaches this class -- it is hashed once, at staging, by
 * {@code PdfMetadataExtractor}, and discarded before this probe (or the real pipeline) ever sees
 * it; only the hash travels. What this probe prints -- the hash, the masked last-4, the bank id,
 * the product type -- is exactly what a real {@code Account} row already stores and what the
 * review screen already displays. Run manually against the out-of-tree corpus; not a {@code @Test},
 * for the same reason {@link CorpusProbe} is not one.
 */
public final class ProductIdentityCorpusProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ProductIdentityCorpusProbe <path-to.pdf> [<path-to.pdf> ...]");
            System.exit(2);
        }
        boolean anyCollision = false;
        for (String arg : args) {
            anyCollision |= probeOne(Path.of(arg));
        }
        System.exit(anyCollision ? 1 : 0);
    }

    /** @return true if this document had a cross-section collision. */
    static boolean probeOne(Path pdf) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);

        // Constructed exactly as CorpusProbe.probe does, so this cannot drift onto a different
        // pipeline than the one already validated against the corpus for classification.
        PdfPreviewGenerator generator = new PdfPreviewGenerator(
                new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                stubbedNormalizer(), ProductDiscovery.standard(), new ProductAttributeExtractor(),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new com.finora.imports.RowAccountingValidator(),
                        new com.finora.imports.CreditCardStatementTotalsValidator(), new com.finora.imports.CreditCardFlowReconciliationValidator()),
                TestRuleEngines.empty());

        var generated = generator.generateSectionsWithContext(
                UUID.randomUUID(), pdf.getFileName().toString(), bytes, null);
        List<StagedAccountSection> sections = generated.sections();

        System.out.println(pdf.getFileName() + ": " + sections.size() + " section(s)");

        record Identified(int index, DetectedAccountInfo info, ProductIdentity identity) {}
        List<Identified> identified = new java.util.ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            DetectedAccountInfo info = sections.get(i).detectedAccount();
            if (info == null) {
                System.out.println("  [" + i + "] no detected account -- skipped");
                continue;
            }
            String bankId = info.bank() == null ? null : info.bank().id();
            FinancialProductType type = parseType(info.detectedProduct());
            ProductIdentity identity = ProductIdentity.stored(
                    bankId, type, info.productIdentityHash(), info.accountNumberMasked());
            identified.add(new Identified(i, info, identity));

            System.out.printf("  [%d] product=%-16s bank=%-8s maskedLast4=%-6s strongKey=%s%n",
                    i, info.detectedProduct(), bankId,
                    info.accountNumberMasked() == null ? "(none)" : info.accountNumberMasked(),
                    identity.strongKey() == null ? "(none -- no usable number extracted)" : "present");
        }

        boolean collision = false;
        for (int a = 0; a < identified.size(); a++) {
            for (int b = a + 1; b < identified.size(); b++) {
                Identified sa = identified.get(a);
                Identified sb = identified.get(b);
                ProductIdentity.Match m = sa.identity().matches(sb.identity());
                if (m != ProductIdentity.Match.NONE) {
                    collision = true;
                    System.out.printf(
                            "  COLLISION: section [%d] (%s) and section [%d] (%s) matched %s -- "
                                    + "these are different sections of one statement and must never match%n",
                            sa.index(), sa.info().detectedProduct(),
                            sb.index(), sb.info().detectedProduct(), m);
                }
            }
        }
        if (!collision && identified.size() > 1) {
            System.out.println("  OK: all " + identified.size() + " identified sections are pairwise distinct");
        }

        // Self-match sanity check: every section with a strong key must match ITSELF exactly --
        // otherwise re-importing this exact document would never be recognised as a re-import at
        // all, silently creating a duplicate every time (the numberless-product gap Phase 1 found).
        for (Identified s : identified) {
            if (s.identity().strongKey() == null) {
                System.out.println("  [" + s.index() + "] NO STRONG KEY -- a re-import of this exact "
                        + "section can never be recognised; every re-import creates a new account "
                        + "(Phase 1's numberless-product finding)");
            } else if (s.identity().matches(s.identity()) != ProductIdentity.Match.EXACT) {
                collision = true; // reusing the flag: this is also a real problem, just not a cross-section one
                System.out.println("  [" + s.index() + "] BUG: does not match itself -- re-import would never be recognised");
            }
        }

        return collision;
    }

    private static FinancialProductType parseType(String detectedProduct) {
        if (detectedProduct == null) return FinancialProductType.UNKNOWN;
        try {
            return FinancialProductType.valueOf(detectedProduct);
        } catch (IllegalArgumentException e) {
            return FinancialProductType.UNKNOWN;
        }
    }

    /** Identical to {@link CorpusProbe}'s -- see its own doc for why both collaborators are stubbed. */
    private static TransactionNormalizer stubbedNormalizer() {
        CategorizationService categorization = mock(CategorizationService.class);
        var suggestion = new CategorizationService.Suggestion("Uncategorized", "default", null, null, null);
        when(categorization.suggestReadOnly(any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any())).thenReturn(suggestion);
        when(categorization.suggestReadOnly(any(), any(), any(), any(), any(), any()))
                .thenReturn(suggestion);
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());
        return new TransactionNormalizer(categorization, new DuplicateDetector(transactions),
                TestRuleEngines.empty());
    }

    private ProductIdentityCorpusProbe() {}
}
