package com.finora.imports;

import com.finora.repository.TransactionRepository;
import com.finora.service.CategorizationService;
import com.finora.service.RuleEngineService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the fix for the largest N+1 in the import pipeline: category rules were being re-read
 * from the database twice for every row of a statement.
 *
 * <p>Profiling on 2026-08-07 measured {@code select category_rules} at exactly 2.00 queries per
 * imported row -- 400 statements for a 200-row file, 800 for a 400-row file, scaling linearly with
 * no upper bound (see {@code docs/engineering/performance/import-pipeline-profile-2026-08-07.md}).
 * Both result sets were identical on every iteration: the same user, the same GLOBAL scope, and a
 * user's rules cannot change partway through parsing one file.
 *
 * <p>This is a <em>query-count</em> test rather than a timing test on purpose. A benchmark asserting
 * "staging is fast" is flaky on shared CI and says nothing about why. Asserting the number of
 * lookups is exact, deterministic, and fails with the actual regression named: someone reintroduced
 * per-row rule access. The enterprise-scale milestone design calls for exactly this shape of
 * guard so a performance fix becomes a standing guarantee instead of a one-off improvement.
 */
class ImportRuleLookupCountTest {

    private static final int ROW_COUNT = 50;

    private RuleEngineService ruleEngineService;
    private PreviewGenerator generator;

    private void wire() {
        CategorizationService categorizationService = mock(CategorizationService.class);
        // Both overloads stubbed: the assertions below are about which one the pipeline chooses,
        // so neither may blow up on an unstubbed call and mask the answer.
        when(categorizationService.suggestReadOnly(any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));
        when(categorizationService.suggestReadOnly(any(), any(), any(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Uncategorized", "default", null, null, null));

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findPotentialDuplicatesByUser(any(), any(), any(), any())).thenReturn(List.of());

        ruleEngineService = mock(RuleEngineService.class);
        when(ruleEngineService.ruleSet(any())).thenReturn(List.of());

        TransactionNormalizer normalizer = new TransactionNormalizer(
                categorizationService, new DuplicateDetector(transactionRepository), ruleEngineService);
        generator = new PreviewGenerator(new CsvParser(), normalizer,
                new StatementValidator(com.finora.imports.product.ProductDiscovery.standard()),
                new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                        new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator()),
                ruleEngineService);
    }

    private static String csvWithRows(int rows) {
        StringBuilder sb = new StringBuilder("Date,Description,Debit,Credit,Balance\n");
        for (int i = 0; i < rows; i++) {
            sb.append("2026-08-01,MERCHANT ").append(i % 7).append(" PURCHASE,")
              .append(i + 1).append(".00,,1000.00\n");
        }
        return sb.toString();
    }

    @Test
    void stagingLoadsTheRuleSetOncePerStatement_notOncePerRow() throws Exception {
        wire();
        var response = generator.generate(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csvWithRows(ROW_COUNT).getBytes(StandardCharsets.UTF_8)));

        // Guards against the test passing vacuously: if parsing silently produced no rows, a
        // single rule lookup would be trivially true and prove nothing.
        assertThat(response.rows()).hasSize(ROW_COUNT);

        verify(ruleEngineService, times(1)).ruleSet(any());
    }

    @Test
    void stagingNeverUsesThePerRowLoadingOverload() throws Exception {
        wire();
        generator.generate(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(csvWithRows(ROW_COUNT).getBytes(StandardCharsets.UTF_8)));

        // The (UUID, ...) overload issues its own two queries per call. Reaching it from a staging
        // loop is the regression this whole test exists to prevent -- the row-count-independent
        // assertion above would still pass if a second code path started calling it.
        verify(ruleEngineService, never())
                .evaluateCategoryRule(any(UUID.class), any(), any(BigDecimal.class), any(), any());
    }

    @Test
    void ruleLookupCountDoesNotGrowWithRowCount() throws Exception {
        wire();
        generator.generate(UUID.randomUUID(), "small.csv",
                new ByteArrayInputStream(csvWithRows(10).getBytes(StandardCharsets.UTF_8)));
        verify(ruleEngineService, times(1)).ruleSet(any());

        // Rewired so the interaction counts start clean; a 20x larger file must still cost one
        // lookup. This is the property that actually failed before the fix -- one lookup for a
        // small file could have been a coincidence of file size, not a bound.
        wire();
        generator.generate(UUID.randomUUID(), "large.csv",
                new ByteArrayInputStream(csvWithRows(200).getBytes(StandardCharsets.UTF_8)));
        verify(ruleEngineService, times(1)).ruleSet(any());
    }
}
