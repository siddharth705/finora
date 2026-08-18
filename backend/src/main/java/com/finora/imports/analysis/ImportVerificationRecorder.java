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
            // Counts of our own rows, so neither can hold money or document text. Kept because the
            // pair is the whole evidence of the printed-activity-with-nothing-staged contradiction:
            // "66 located, 0 staged" says the table was read and every row rejected, which is a
            // different failure from "0 located, 0 staged".
            "stagedTransactionCount", "locatedRowCount",
            // ROW_ACCOUNTING's own counts -- same category as the pair above: facts about how many
            // of OUR rows landed in which bucket, never a value read off the statement.
            "unparseableRowCount", "droppedTransactionCandidateCount",
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

    /**
     * Reason-code histograms, kept whole: every key is one of {@code PdfTableLocator}'s own stable
     * machine codes (e.g. {@code "PAGE_FOOTER_OR_CLOSING_MARKER"}), every value a count of our own
     * rows -- neither can hold a balance, an amount, a narration or a cell copied out of the
     * document, the same guarantee {@link #STRUCTURAL_KEYS} makes for a single count. This is the
     * one piece of ROW_ACCOUNTING evidence that answers "why", not just "how many" -- without it,
     * a persisted WARNING says something was dropped and nothing about which kind.
     */
    private static final Set<String> HISTOGRAM_KEYS = Set.of("droppedTransactionCandidateReasons");

    /** {@code reason} explains why a rule could not run. Our own prose, but bounded on principle. */
    private static final int MAX_REASON_LENGTH = 200;

    /**
     * The keys one C-9 shadow-mode evidence observation may carry -- see
     * {@link #evidenceShadowDetailsOf}, and {@code ClosingBalanceEvidenceShadowObserver} for what
     * writes them.
     *
     * <p>The five axes the design requires kept apart are five separate keys here and are never
     * combined into one: {@code evidenceAvailable} (did an assessment happen at all),
     * {@code statementTotalsOutcome} + {@code suspectedCause} (the validator's own verdict and its
     * attribution), {@code evidenceComparison} + the three grouping counts (the correlation axis),
     * and {@code evidenceStatus} (the assessment's own three-valued verdict, which is also the
     * {@code outcome} column). Collapsing any pair of them would destroy the one distinction the
     * observation exists to measure.
     */
    private static final Set<String> EVIDENCE_SHADOW_KEYS = Set.of(
            "evidenceAvailable", "failureType",
            "statementTotalsOutcome", "suspectedCause",
            "structuralStatus", "corroborationStatus", "financialValidationStatus",
            "evidenceComparison", "sameFactGroupSize", "excludedAsUncertainCount",
            "excludedAsDifferentCount", "contradictionCount",
            "evidenceStatus", "elapsedMs");

    /** Enum constants and exception class names, all far shorter than this. Bounded anyway. */
    private static final int MAX_VOCABULARY_LENGTH = 64;

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

    /**
     * Records one shadow-mode evidence observation against the analysis session of the import
     * session it was observed for -- C-9.
     *
     * <p><b>Why the import session id and not an analysis reference.</b> The confirm path holds an
     * {@code ImportSession} id and nothing else; the analysis reference is minted at staging time
     * and never travels with the session. The link between the two already exists in the schema --
     * {@code statement_analysis_sessions.import_session_id} is written by
     * {@code StatementAnalysisRecorder.recordParsed} on every staging path -- and is already
     * navigated in production by {@code ImportTraceService} through the very repository method used
     * here. So this resolves an existing owner; it does not invent one. An import session with no
     * analysis row (the analysis write failed, which is logged there) is a no-op, exactly as an
     * unknown reference is in {@link #recordForAnalysis}: a finding with no owner is not worth
     * inventing one for, and the CHECK constraint on the table says so too.
     *
     * <p>Same {@code REQUIRES_NEW} + catch discipline as every other method here, for a stronger
     * reason: this one is called from inside a customer's confirm transaction, and a telemetry
     * insert must not be able to mark that transaction rollback-only.
     *
     * @return the number of rows written -- 0 when there is no analysis session to own the row, or
     *         when the write failed (already logged)
     */
    public int recordEvidenceShadow(UUID importSessionId, int sectionIndex, String rule, String outcome,
                                    Map<String, Object> details) {
        if (importSessionId == null || rule == null || outcome == null) return 0;
        try {
            return inOwnTransaction(() -> {
                UUID analysisSessionId = analysisRepository
                        .findByImportSessionIdOrderByCreatedAtDesc(importSessionId).stream()
                        .findFirst().map(StatementAnalysisSession::getId).orElse(null);
                if (analysisSessionId == null) {
                    log.debug("No analysis session for import session {} -- shadow evidence not recorded",
                            importSessionId);
                    return 0;
                }
                repository.save(ImportVerificationFinding.forAnalysis(analysisSessionId,
                        Math.max(sectionIndex, 0), rule, outcome, writeShadowDetails(details, importSessionId)));
                return 1;
            });
        } catch (RuntimeException e) {
            log.error("Could not record shadow evidence for import session {} -- this observation is "
                    + "lost, and the import it was observed for is unaffected", importSessionId, e);
            return 0;
        }
    }

    private String writeShadowDetails(Map<String, Object> details, UUID owner) {
        Map<String, Object> safe = evidenceShadowDetailsOf(details);
        if (safe.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Could not serialise shadow evidence details for {} -- the observation is still "
                    + "being recorded without them", owner, e);
            return null;
        }
    }

    /**
     * Rebuilds a shadow observation's details from its own named allowlist.
     *
     * <p>A separate allowlist from {@link #STRUCTURAL_KEYS} on purpose: widening the validator
     * allowlist to admit evidence keys would also admit them from every validator finding, which is
     * the opposite of what an allowlist is for. Every key here holds an enum constant this codebase
     * defines, a count of our own facts, a boolean, or an elapsed time -- none of them can hold a
     * balance, an amount, a narration or a cell copied out of the document. Strings are bounded
     * anyway, on the same principle as {@code reason}.
     */
    public static Map<String, Object> evidenceShadowDetailsOf(Map<String, Object> details) {
        if (details == null || details.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null || !EVIDENCE_SHADOW_KEYS.contains(key)) continue;
            if (value instanceof String text) {
                safe.put(key, text.length() <= MAX_VOCABULARY_LENGTH
                        ? text : text.substring(0, MAX_VOCABULARY_LENGTH) + "…");
            } else if (value instanceof Boolean || value instanceof Number) {
                safe.put(key, value);
            }
            // Anything else -- a collection, a map, an arbitrary object -- is dropped rather than
            // toString()'d: a type nobody anticipated is exactly the one that could carry content.
        }
        return java.util.Collections.unmodifiableMap(safe);
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
     * named in one of the four allowlists above is absent from the result by construction, so a
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
            } else if (HISTOGRAM_KEYS.contains(key) && value instanceof Map<?, ?> counts) {
                // Both halves already structural (reason code -> our own row count), so the whole
                // map survives -- re-keyed through toString() only to satisfy the JSON writer,
                // never because the values need shaping the way a Collection's contents did above.
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : counts.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                safe.put(key, copy);
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
