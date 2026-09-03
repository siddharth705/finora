package com.finora.service;

import com.finora.repository.TransactionRepository;
import com.finora.repository.TransactionRepository.CounterpartyBackfillRow;
import com.finora.util.CounterpartyClassifier;
import com.finora.util.CounterpartyTyping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Types the counterparty on transactions that predate the counterparty layer, in bounded batches,
 * until none are left.
 *
 * <h2>Why a backfill is possible at all</h2>
 *
 * <p>{@link CounterpartyTyping#of} is a pure function of the narration -- no merchant lookup, no
 * learned state, no user input -- so a row imported a year ago can be typed today and get exactly
 * the answer it would have got then. Nothing has to be re-imported and nothing the user did is
 * consulted, which is what makes this safe to run over existing data at all.
 *
 * <h2>Why it exists rather than "type going forward"</h2>
 *
 * <p>Without it every historical row reads {@code UNKNOWN}, and the value-weighted counterparty
 * review this layer was built for has nothing to work with: measured on the real corpus, the top
 * three counterparties account for 51.9% of a statement's unresolved value, and a median statement
 * needs just two of them to explain 80%. That is worth nothing if the rows carrying them predate
 * the column.
 *
 * <h2>Idempotent by construction, not via a job table</h2>
 *
 * <p>Same shape as {@link AccountPurgeSweepService} and {@code StatementStorageSweepService}: no
 * dedicated job or cursor table, just a discovery predicate that a completed row no longer matches.
 * A crash mid-batch rolls the batch back and the next pass finds the same rows again. Stamping the
 * classifier version is what removes a row from the candidate set, and it is written in the same
 * statement as the answer it describes, so the two can never disagree.
 *
 * <h2>What happens to a row that cannot be typed</h2>
 *
 * <p>It is logged and left alone -- NOT stamped. Stamping a failure would say "revision 1 examined
 * this row" about a row revision 1 never successfully examined, which is a lie written into user
 * data to buy quiet. The cost of not stamping is that a genuinely poisonous row is retried every
 * pass and logs an error every pass; that is loud, visible and harmless, because every OTHER row in
 * its batch still gets typed and drops out, so the sweep always makes progress. Noise was chosen
 * over a false stamp deliberately.
 *
 * <h2>No WorkerObservability</h2>
 *
 * <p>Follows the sweep family (account-purge, statement-storage), not the queue-worker family. The
 * gauges that class publishes -- queue depth, oldest-pending age -- are levels of a queue with an
 * SLA. This has neither: it drains once and then matches nothing forever, and a {@code COUNT(*)} of
 * remaining rows on every metrics scrape would be a standing cost for a number that is zero almost
 * always. Progress is logged instead, once per pass that did work.
 */
@Component
public class CounterpartyBackfillSweepService {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyBackfillSweepService.class);

    @Value("${app.counterparty-backfill.sweep.enabled:true}")
    private boolean sweepEnabled;

    /** How many rows one pass types. Same reasoning as every other sweep here: a backlog drains
     *  across runs rather than in one unbounded pass holding a connection from a pool capped at 10.
     *  Larger than the purge sweep's 200 because the per-row work is a regex and a single-row UPDATE
     *  rather than a cascade across thirty tables. */
    @Value("${app.counterparty-backfill.sweep.batch-size:500}")
    private int batchSize;

    private final TransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    public CounterpartyBackfillSweepService(TransactionRepository transactionRepository,
                                             TransactionTemplate transactionTemplate) {
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * The scheduled trigger. Flag-gated like every other sweep in this codebase, for the reason
     * BH-058 was about: a background thread rewriting rows mid-test is cross-test pollution.
     * {@code application-test.yml} turns it off and tests call {@link #sweep()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, so a slow pass cannot pile up overlapping ones
     * -- two concurrent passes would both claim the same page (the discovery query takes no lock)
     * and do the same work twice.
     */
    @Scheduled(fixedDelayString = "${app.counterparty-backfill.sweep.interval-ms:300000}",
            initialDelayString = "${app.counterparty-backfill.sweep.initial-delay-ms:120000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.typed() > 0 || result.failed() > 0) {
            // Only when a pass did something -- a drained backfill is the steady state and must not
            // log every interval forever. The drained note is the only "it finished" signal an
            // operator gets, since silence otherwise reads the same as "it stopped working".
            log.info("Counterparty backfill: {} row(s) typed at classifier v{}, {} skipped, {} failed.{}",
                    result.typed(), CounterpartyClassifier.VERSION, result.skipped(), result.failed(),
                    result.drained() ? " Backlog drained." : "");
        }
    }

    /**
     * Runs one pass: discovers up to {@code batchSize} untyped rows and types each.
     *
     * <p>One transaction for the whole batch. A per-row transaction would be safer against a
     * poisoned batch, but nothing here can poison one: the only writes are to three columns no
     * constraint spans, and the one value that could have overflowed its column --
     * {@code counterparty_key} against {@code VARCHAR(120)} -- is capped at source by
     * {@link com.finora.util.CounterpartyIdentity#MAX_KEY_LENGTH}. If that ever stops being true,
     * this needs the three-boundary treatment {@code MerchantLearningEventWorker} documents.
     *
     * @return what the pass did, so a caller or test can see it did something
     */
    public Result sweep() {
        List<CounterpartyBackfillRow> candidates = transactionRepository
                .findRowsNeedingCounterpartyTyping(CounterpartyClassifier.VERSION, PageRequest.of(0, batchSize));
        // Drained, not "not drained": nothing left to type is the strongest form of caught-up
        // there is, and this is the branch the sweep spends nearly all of its life in.
        if (candidates.isEmpty()) return new Result(0, 0, 0, true);

        int[] counts = new int[3]; // typed, skipped, failed -- an array because the lambda closes over it
        transactionTemplate.executeWithoutResult(tx -> {
            for (CounterpartyBackfillRow row : candidates) {
                try {
                    CounterpartyTyping typing = CounterpartyTyping.of(row.getDescription());
                    int updated = transactionRepository.applyCounterpartyTyping(
                            row.getId(), typing.type(), typing.key(), typing.version());
                    if (updated > 0) counts[0]++; else counts[1]++;
                } catch (RuntimeException e) {
                    // Left unstamped on purpose -- see this class's own doc on why a false stamp is
                    // worse than a repeated error. The transaction id is enough to reproduce:
                    // the input is that row's narration and nothing else. The narration itself is
                    // NOT logged; it is user financial data.
                    counts[2]++;
                    log.error("Counterparty typing failed for transaction {}: {}",
                            row.getId(), e.toString());
                }
            }
        });

        // A short page means no MORE rows were waiting, which is the only "we are done" signal this
        // design has -- and it is free, where a COUNT(*) of the remainder would not be.
        //
        // The failure clause is not belt-and-braces. A row that threw was deliberately left
        // unstamped, so it is still a candidate: a short page with failures means the candidate set
        // is emphatically NOT empty, and reporting "drained" there would announce a finished
        // backfill over rows that will be rediscovered on the very next pass.
        boolean drained = candidates.size() < batchSize && counts[2] == 0;
        return new Result(counts[0], counts[1], counts[2], drained);
    }

    /**
     * @param typed   rows given a counterparty answer by this pass
     * @param skipped rows that vanished between discovery and write (deleted concurrently)
     * @param failed  rows the classifier threw on; deliberately left for the next pass
     * @param drained whether the candidate set is now empty -- true once the backfill has caught up
     */
    public record Result(int typed, int skipped, int failed, boolean drained) {
    }
}
