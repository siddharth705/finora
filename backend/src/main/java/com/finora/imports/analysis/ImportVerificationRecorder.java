package com.finora.imports.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.dto.ImportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists what the verification rules found, in its own transaction, without ever breaking an
 * import.
 *
 * <p>Same discipline as {@link StatementAnalysisRecorder}, for the same two reasons: the findings
 * worth keeping are often attached to an upload that then fails, and a telemetry insert must never
 * be able to fail a customer's import. A {@code REQUIRES_NEW} unit of work keeps the write out of
 * any transaction a caller may later mark rollback-only; the catch keeps a failed write to a logged
 * measurement gap.
 *
 * <h2>Why a TransactionTemplate and not {@code @Transactional(REQUIRES_NEW)}</h2>
 *
 * <p><b>Because the catch has to enclose the commit.</b> Under the annotation the proxy commits
 * after the method body returns, so a constraint violation leaves the transaction rollback-only and
 * arrives as an {@code UnexpectedRollbackException} at commit time — after the in-method catch has
 * already reported success — and reaches the caller. For this class the caller is a customer's
 * upload. {@code ImportStageRecorder} documents the same reasoning and the integration test that
 * caught it.
 *
 * <h2>The allowlist, and why it is not a filter</h2>
 *
 * <p>{@link #structuralDetailsOf} rebuilds each finding's details from a named set of structural
 * keys. It does not remove the monetary ones. That direction matters and is the same call
 * {@code observability.md} makes about outbound payloads: a denylist has to be right every time a
 * rule adds a field, an allowlist has to be right once. The details maps as they stand carry opening
 * and closing balances, credit and debit totals, and — for {@code COLUMN_AMBIGUITY} — the raw cell
 * value that could be read two ways. None of that belongs in an evidence table.
 *
 * <p>The method is {@code static} and pure so it can be tested without a database or a Spring
 * context, which {@code observability.md} requires of every scrubber: scrubbing that silently stops
 * working looks exactly like scrubbing that works.
 */
@Component
public class ImportVerificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(ImportVerificationRecorder.class);

    /**
     * Detail keys carried through verbatim. Every one is a count, a boolean or a bounded enum
     * constant emitted by our own code — nothing here can hold a balance, an amount, a narration or
     * a cell copied out of the document.
     *
     * <p>{@code printedCreditCount} and friends are the bank's own transaction counts. They are the
     * strongest evidence the framework has (a count cannot be derived from our reading of the
     * document) and they are not money, so they are the one printed figure kept.
     */
    private static final Set<String> STRUCTURAL_KEYS = Set.of(
            "rowsChecked", "rowsWithBalance", "anchoredOnOpeningBalance", "ambiguousRows",
            "printedCreditCount", "parsedCreditCount", "printedDebitCount", "parsedDebitCount",
            "suspectedCause");

    /**
     * Keys whose <em>length</em> is structural and whose contents are statement data. Recorded as
     * {@code <key>Count}. "Seventeen rows disagreed with the running balance" is the diagnostic;
     * which balances they were is not, and is exactly what must not be copied here.
     */
    private static final Set<String> COUNTED_KEYS = Set.of("discrepancies", "ambiguities");

    /**
     * Bounded string lists, kept whole. {@code mismatches} names which of the four printed-versus-
     * parsed comparisons disagreed ({@code creditTotal}, {@code debitCount}, ...) and carries no
     * figures, so it survives intact — which is what makes a persisted SUMMARY_TOTALS failure
     * actionable rather than merely present.
     */
    private static final Set<String> VOCABULARY_LIST_KEYS = Set.of("mismatches");

    /** {@code reason} explains why a rule could not run. Our own prose, but bounded on principle. */
    private static final int MAX_REASON_LENGTH = 200;

    private final ImportVerificationFindingRepository repository;
    private final StatementAnalysisSessionRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ImportVerificationRecorder(ImportVerificationFindingRepository repository,
                                      StatementAnalysisSessionRepository analysisRepository,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.analysisRepository = analysisRepository;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records the findings of a synchronous upload against the analysis session it produced.
     *
     * <p>Takes the quotable reference rather than the row id because that is what
     * {@link StatementAnalysisRecorder} hands back, and because a caller holding a reference has
     * something it can also put in a log line. A null or unknown reference is a no-op: the analysis
     * row failed to write, which is already logged there, and a finding with no owner is not worth
     * inventing one for.
     *
     * @param bySection one report per staged section, in section order; nulls are tolerated because
     *                  the CSV path can stage without verifying
     */
    public int recordForAnalysis(String analysisReference, List<ImportDto.VerificationReport> bySection) {
        if (analysisReference == null || bySection == null || bySection.isEmpty()) return 0;
        try {
            return inOwnTransaction(() -> {
                UUID sessionId = analysisRepository.findByReference(analysisReference)
                        .map(StatementAnalysisSession::getId)
                        .orElse(null);
                if (sessionId == null) return 0;
                return save(bySection, (sectionIndex, finding) -> ImportVerificationFinding.forAnalysis(
                        sessionId, sectionIndex, finding.rule(), finding.outcome(),
                        writeDetails(finding, analysisReference)));
            });
        } catch (RuntimeException e) {
            log.error("Could not record verification findings for analysis {} -- which rules ran on "
                    + "this import is now unanswerable", analysisReference, e);
            return 0;
        }
    }

    /** The same, for the asynchronous worker, which has an import job and no analysis session. */
    public int recordForJob(UUID importJobId, List<ImportDto.VerificationReport> bySection) {
        if (importJobId == null || bySection == null || bySection.isEmpty()) return 0;
        try {
            return inOwnTransaction(() -> save(bySection,
                    (sectionIndex, finding) -> ImportVerificationFinding.forJob(
                            importJobId, sectionIndex, finding.rule(), finding.outcome(),
                            writeDetails(finding, importJobId.toString()))));
        } catch (RuntimeException e) {
            log.error("Could not record verification findings for import job {} -- which rules ran "
                    + "on this import is now unanswerable", importJobId, e);
            return 0;
        }
    }

    /** One unit of recording, committed before this method returns so the caller's catch can see a
     *  failure. Returns 0 rather than null when the template hands back nothing. */
    private int inOwnTransaction(java.util.function.Supplier<Integer> work) {
        Integer written = transactions.execute(status -> work.get());
        return written == null ? 0 : written;
    }

    private interface FindingFactory {
        ImportVerificationFinding create(int sectionIndex, ImportDto.VerificationFinding finding);
    }

    private int save(List<ImportDto.VerificationReport> bySection, FindingFactory factory) {
        List<ImportVerificationFinding> rows = new ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < bySection.size(); sectionIndex++) {
            ImportDto.VerificationReport report = bySection.get(sectionIndex);
            if (report == null || report.findings() == null) continue;
            for (ImportDto.VerificationFinding finding : report.findings()) {
                if (finding == null || finding.rule() == null) continue;
                rows.add(factory.create(sectionIndex, finding));
            }
        }
        if (rows.isEmpty()) return 0;
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * The allowlisted details as JSON, or null when nothing structural survived.
     *
     * <p>Serialisation gets its own try/catch rather than riding on the caller's, matching
     * {@link StatementAnalysisRecorder#writeHistogram}: abandoning the row over a details problem
     * would throw away the rule name and the outcome — the two fields that already worked — to save
     * one that did not.
     */
    private String writeDetails(ImportDto.VerificationFinding finding, String owner) {
        Map<String, Object> structural = structuralDetailsOf(finding.details());
        if (structural.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(structural);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Could not serialise verification details for rule {} of {} -- the finding is "
                    + "still being recorded without them", finding.rule(), owner, e);
            return null;
        }
    }

    /**
     * Rebuilds a rule's details from structural facts only.
     *
     * <p>Pure, static and independently testable on purpose — see the class comment. Anything not
     * named in one of the three allowlists above is absent from the result by construction, so a
     * detail key added by a future rule does not reach the database until someone decides it should.
     */
    public static Map<String, Object> structuralDetailsOf(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;

            if (STRUCTURAL_KEYS.contains(key)) {
                safe.put(key, value);
            } else if (COUNTED_KEYS.contains(key) && value instanceof Collection<?> items) {
                // The size, never the contents. Each element holds balances read off the statement.
                safe.put(key + "Count", items.size());
            } else if (VOCABULARY_LIST_KEYS.contains(key) && value instanceof Collection<?> items) {
                safe.put(key, items.stream().filter(java.util.Objects::nonNull)
                        .map(Object::toString).toList());
            } else if ("reason".equals(key) && value instanceof String reason) {
                safe.put(key, reason.length() <= MAX_REASON_LENGTH
                        ? reason : reason.substring(0, MAX_REASON_LENGTH) + "…");
            }
        }
        // unmodifiableMap rather than Map.copyOf: the insertion order is the order the rule
        // reported its facts in, and it is what makes the same finding serialise identically twice.
        return java.util.Collections.unmodifiableMap(safe);
    }
}
