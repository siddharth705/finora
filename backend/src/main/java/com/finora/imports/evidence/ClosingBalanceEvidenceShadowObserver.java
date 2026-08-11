package com.finora.imports.evidence;

import com.finora.dto.ImportDto;
import com.finora.imports.analysis.ImportVerificationRecorder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase C-9 -- <b>shadow mode</b>. Computes the {@link MaterialField#CLOSING_BALANCE} evidence
 * assessment at confirm time and records it, and does nothing else with it.
 *
 * <h2>Observation, and provably not control</h2>
 *
 * <p>{@link #observe} returns {@code void}. There is no other public method, and no accessor for
 * anything it computed. A caller therefore cannot branch on the result even by accident: the
 * "observe, don't control" property is enforced by the signature rather than by review. The
 * enforcement gate on a closing balance remains {@code ClosingBalanceGuard}, called from
 * {@code ImportService.persistSection}, entirely untouched by this class.
 *
 * <h2>Three separate fault boundaries, because one is not enough</h2>
 *
 * <ol>
 *   <li><b>The transaction.</b> All of the work runs inside a {@code PROPAGATION_NOT_SUPPORTED}
 *       template, which <em>suspends</em> the caller's confirm transaction for the duration.
 *       Without this the isolation would be a fiction: {@code ImportSessionService.getOwnedSession}
 *       is {@code @Transactional(readOnly = true)}, so when it is called from inside an existing
 *       transaction it <em>participates</em> in it -- and an exception escaping a participating
 *       transaction makes Spring mark the shared one rollback-only
 *       ({@code globalRollbackOnParticipationFailure}). A caught-and-logged {@code ApiException}
 *       here would then have failed the customer's confirm at commit time with an
 *       {@code UnexpectedRollbackException}, which is precisely the trap
 *       {@link ImportVerificationRecorder}'s own class doc documents having been caught by an
 *       integration test once already. Suspension also keeps the re-derivation's reads out of the
 *       confirm's persistence context, so nothing it loads can be returned to, or flushed by, the
 *       real confirm. It additionally makes the production call environment identical to the one
 *       {@code ClosingBalanceEvidenceRederivationServiceIT} proves the service against: no ambient
 *       transaction.</li>
 *   <li><b>The computation.</b> Wrapped in a catch of {@link Throwable} -- see below.</li>
 *   <li><b>The recording.</b> Wrapped separately, so a recorder failure cannot discard a
 *       successful computation's log line, and so the recorder's own internal catch is not the only
 *       thing standing between a telemetry problem and a customer's import.</li>
 * </ol>
 *
 * <h2>Why {@link Throwable} and not {@link Exception}</h2>
 *
 * <p>Deliberate, and unusual enough to say why. Re-deriving evidence re-parses a PDF, and PDF
 * parsing is the one thing in this pipeline that can plausibly raise a {@link StackOverflowError}
 * or an {@link OutOfMemoryError} on a hostile document. Catching only {@code Exception} would let
 * such an {@link Error} escape and turn a confirm that succeeds today into one that fails -- which
 * is exactly the behaviour change this whole class is required not to have. Nothing observation can
 * throw is more important than the import completing: if the JVM is genuinely unwell the real
 * confirm will fail on its own, for its own reason, which is the one worth surfacing. An interrupt
 * is re-asserted rather than swallowed, since that flag belongs to the caller's thread.
 */
@Component
public class ClosingBalanceEvidenceShadowObserver {

    private static final Logger log = LoggerFactory.getLogger(ClosingBalanceEvidenceShadowObserver.class);

    /**
     * Deliberately not {@code ClosingBalanceGuard.RULE} ({@code CLOSING_BALANCE_CORROBORATION}).
     * That rule name means "the live gate ran and decided whether to write a balance"; this one
     * means "nothing was decided, this is an observation". A shared name would make the two
     * indistinguishable in the one table that is supposed to tell them apart.
     */
    public static final String RULE = "CLOSING_BALANCE_EVIDENCE_SHADOW";

    /** The {@code outcome} column when no assessment could be produced -- distinct from all three
     *  {@link EvidenceStatus} values, so "no evidence" never reads as {@code INSUFFICIENT}
     *  evidence. Those are different findings and the corpus must not confuse them. */
    public static final String OUTCOME_UNAVAILABLE = "UNAVAILABLE";

    private final ClosingBalanceEvidenceRederivationService rederivationService;
    private final ImportVerificationRecorder recorder;
    private final TransactionTemplate suspended;

    public ClosingBalanceEvidenceShadowObserver(ClosingBalanceEvidenceRederivationService rederivationService,
                                                ImportVerificationRecorder recorder,
                                                PlatformTransactionManager transactionManager) {
        this.rederivationService = rederivationService;
        this.recorder = recorder;
        this.suspended = new TransactionTemplate(transactionManager);
        this.suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /**
     * Observes the closing-balance evidence for one section of one staged import session.
     *
     * <p><b>Must be called before {@code ImportSessionService.claimForConfirmation}.</b> The
     * re-derivation service checks ownership with {@code getOwnedSession}, which rejects a session
     * already in {@code CONFIRMED} status -- claiming first would make every observation fail with
     * "already confirmed" and the shadow data would be uniformly empty. Calling before the claim is
     * also strictly safer: at that point the confirm has written nothing at all.
     *
     * @param sourceSectionIndex {@code null} for a single-account session, the loop index for one
     *        section of a multi-account one -- the same value the caller passes to
     *        {@code persistSection}
     * @param closingBalanceClaim the value being confirmed; {@code null} is not observable and
     *        returns immediately, since there is no claim to assess
     */
    public void observe(UUID userId, UUID sessionId, Integer sourceSectionIndex,
                        BigDecimal closingBalanceClaim) {
        if (userId == null || sessionId == null || closingBalanceClaim == null) return;
        try {
            suspended.executeWithoutResult(status -> observeOutsideCallerTransaction(
                    userId, sessionId, sourceSectionIndex, closingBalanceClaim));
        } catch (Throwable t) {
            // The template itself failing (a transaction manager that cannot suspend, say) is the
            // one failure the inner catches cannot see. It still must not reach the import.
            reAssertInterrupt(t);
            log.warn("Shadow closing-balance evidence could not run for session {} -- the import is "
                    + "unaffected", sessionId, t);
        }
    }

    private void observeOutsideCallerTransaction(UUID userId, UUID sessionId, Integer sourceSectionIndex,
                                                 BigDecimal closingBalanceClaim) {
        long startedAtNanos = System.nanoTime();
        Map<String, Object> details = new LinkedHashMap<>();
        String outcome;
        try {
            var evidence = rederivationService.rederiveClosingBalanceEvidenceDetailed(
                    userId, sessionId, sourceSectionIndex, closingBalanceClaim);
            outcome = evidence.assessment().status().name();
            describe(details, evidence);
        } catch (Throwable t) {
            reAssertInterrupt(t);
            details.clear();
            details.put("evidenceAvailable", false);
            // The exception's TYPE, never its message: a message can carry a file name, a password
            // prompt, or a fragment of the document, and this table holds none of those.
            details.put("failureType", t.getClass().getSimpleName());
            outcome = OUTCOME_UNAVAILABLE;
            log.warn("Shadow closing-balance evidence unavailable for session {} section {} -- "
                    + "recording the failure and leaving the import untouched",
                    sessionId, sourceSectionIndex, t);
        }
        details.put("elapsedMs", (System.nanoTime() - startedAtNanos) / 1_000_000L);

        try {
            recorder.recordEvidenceShadow(sessionId, sourceSectionIndex == null ? 0 : sourceSectionIndex,
                    RULE, outcome, details);
        } catch (Throwable t) {
            reAssertInterrupt(t);
            log.warn("Shadow closing-balance evidence for session {} was computed but not recorded",
                    sessionId, t);
        }
    }

    /**
     * The five axes, each written on its own, none folded into another.
     *
     * <p>{@code evidenceStatus} (the assessment's three-valued verdict) and
     * {@code evidenceComparison} (whether correlated sources agreed, disagreed, stood uncontested,
     * or were absent) are different questions with different answers, and the whole value of the
     * first corpus is being able to read one without the other having overwritten it. The same goes
     * for {@code statementTotalsOutcome} versus {@code suspectedCause}: a {@code FAILED} finding
     * caused by the opening balance is evidence <em>for</em> the closing balance, and one caused by
     * the transactions is not -- the {@code FINANCIAL_VALIDATION} explanation alone cannot tell
     * those apart.
     */
    private static void describe(Map<String, Object> details,
                                 ClosingBalanceEvidenceRederivationService.ClosingBalanceEvidence evidence) {
        FieldAssessment assessment = evidence.assessment();
        details.put("evidenceAvailable", true);

        ImportDto.VerificationFinding totals = evidence.statementTotals();
        if (totals != null) {
            details.put("statementTotalsOutcome", totals.outcome());
            Object suspectedCause = totals.details() == null ? null : totals.details().get("suspectedCause");
            if (suspectedCause != null) details.put("suspectedCause", String.valueOf(suspectedCause));
        }

        details.put("structuralStatus", assessment.structural().status().name());
        details.put("corroborationStatus", assessment.corroboration().status().name());
        details.put("financialValidationStatus", assessment.financialValidation().status().name());

        details.put("evidenceComparison", evidence.comparison().name());
        details.put("sameFactGroupSize", evidence.sameFactGroupSize());
        details.put("excludedAsUncertainCount", evidence.excludedAsUncertainCount());
        details.put("excludedAsDifferentCount", evidence.excludedAsDifferentCount());
        details.put("contradictionCount", assessment.contradictions().size());

        details.put("evidenceStatus", assessment.status().name());
    }

    /** Swallowing an {@link InterruptedException} must not also swallow the interrupt itself --
     *  that flag belongs to whoever is running this thread, not to shadow mode. */
    private static void reAssertInterrupt(Throwable t) {
        if (t instanceof InterruptedException) Thread.currentThread().interrupt();
    }
}
