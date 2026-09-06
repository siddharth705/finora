package com.finora.observability;

import com.finora.entity.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The reconciliation engine's own metric catalog, following {@link WorkerObservability}'s own
 * precedent for why this lives in one file rather than an ad-hoc {@code Counter.builder} call at
 * each site: a dashboard querying {@code finora_reconciliation_transfers_matched_total} has to
 * match every caller, and a caller that invented its own name would be invisible on it.
 *
 * <p>Two counters, not the full worker-lifecycle contract {@link WorkerObservability} provides
 * (queue depth, retries, dead letters) -- reconciliation is not a queue-drained background job. It
 * runs synchronously on the request thread after every transaction create/update/delete and import
 * confirm ({@code ReconciliationService}'s own class comment), so there is no queue to measure and
 * no retry/dead-letter concept to report. What is worth measuring is the two questions this
 * project's own reconciliation-benchmark work (docs/proposals/reconciliation-benchmark/) could not
 * answer from a synthetic benchmark alone: how often the engine's auto-decisions happen in
 * production, and how often a user disagrees with one of them.
 *
 * <h2>Tags are bounded enums only, per docs/engineering/observability.md's own privacy principle</h2>
 *
 * Neither counter carries a description, a merchant, an amount or any other field that could
 * originate from a bank statement -- the same "allowlist, never denylist" discipline
 * {@code SentryScrubber} applies to Sentry tags applies here for the same reason, even though
 * {@code /actuator/prometheus} is authenticated (see that endpoint's own IT) rather than sent to a
 * third party: a boolean and a 3-value enum are the only tag values used, both internal and
 * low-cardinality by construction, never customer data.
 */
@Component
public class ReconciliationMetrics {

    private final MeterRegistry registry;

    public ReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * A transfer pair the reconciliation engine auto-matched (see {@code ReconciliationService}'s
     * transfer pass) -- called once per matched PAIR, not once per transaction, matching that
     * pass's own {@code newTransfers++} counting convention ("one pair = one match, not two").
     *
     * @param relationshipMatch whether a user-configured {@code OWN_ACCOUNT} relationship
     *                          identifier contributed to this match, as opposed to the generic
     *                          amount/date-window heuristic alone -- the one signal this project's
     *                          own reconciliation-benchmark work identified as worth watching
     *                          separately, since it distinguishes "the engine found this on its
     *                          own" from "the user told it where to look"
     */
    public void transferMatched(boolean relationshipMatch) {
        Counter.builder("finora.reconciliation.transfers_matched")
                .tag("relationshipMatch", String.valueOf(relationshipMatch))
                .description("Transfer pairs the reconciliation engine auto-matched between the user's own accounts")
                .register(registry)
                .increment();
    }

    /**
     * A user rejected an auto-flagged duplicate via {@code TransactionService.confirmNotDuplicate}
     * -- the one user-facing correction this project's own remaining-failures-classification.md
     * confirmed exists today (unlike an equivalent "not a transfer" action, which does not).
     *
     * @param source which import channel produced the wrongly-flagged row -- bounded to
     *               {@code Transaction.Source}'s three values, telling ops which channel's
     *               duplicate detection is generating the most overrides without naming anything
     *               about the specific transaction
     */
    public void duplicateOverridden(Transaction.Source source) {
        Counter.builder("finora.reconciliation.duplicate_overrides")
                .tag("source", source.name())
                .description("Times a user rejected an auto-flagged duplicate as a real, separate transaction")
                .register(registry)
                .increment();
    }
}
