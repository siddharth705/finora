package com.finora.util;

/**
 * The complete counterparty answer for one narration: who was on the other side, a key to group
 * them by, and which revision of the rules said so.
 *
 * <h2>Why this is a value object and not three call sites</h2>
 *
 * <p>Three different places write these columns -- {@code TransactionService.create} when someone
 * adds a transaction by hand, {@code ImportService.confirm} when a statement is confirmed, and
 * {@code CounterpartyBackfillSweepService} when historical rows are typed. Spelling the derivation
 * out three times invites exactly the divergence #743's review found between {@code suggest} and
 * {@code suggestReadOnly}: two paths that were supposed to answer identically, quietly did not, and
 * a row's answer depended on how it had arrived. Here there is one derivation and three callers of
 * it, so agreement is structural rather than something a test has to keep rediscovering.
 *
 * <p>It is a plain value rather than a method on {@code Transaction} because the backfill never
 * loads entities -- it reads {@code (id, description)} projections and issues a bulk update, so it
 * needs the three values without a {@code Transaction} in hand. An entity method would have forced
 * the backfill to either load full entities (bumping {@code @Version} and {@code updated_at}, which
 * would make a backfill indistinguishable from a user edit) or reimplement the derivation, which is
 * the drift this class exists to prevent.
 *
 * @param type    who was on the other side; never null, {@link CounterpartyType#UNKNOWN} when the
 *                narration carries no usable signal
 * @param key     grouping key, or null when nothing identifiable could be derived -- null rather
 *                than {@code ""} so "no key" cannot become two distinct groups in a GROUP BY
 * @param version {@link CounterpartyClassifier#VERSION} at the time of derivation
 */
public record CounterpartyTyping(CounterpartyType type, String key, short version) {

    /**
     * Derives the answer for a narration. Pure: same input, same output, no I/O, no state.
     *
     * <p>Null-safe on purpose -- {@code transactions.description} is nullable (V1), so the backfill
     * will meet rows with no narration at all. Those get {@code UNKNOWN} with no key, which is the
     * honest answer: the classifier looked, and there was nothing to look at.
     */
    public static CounterpartyTyping of(String description) {
        String key = CounterpartyIdentity.keyOf(description);
        return new CounterpartyTyping(
                CounterpartyClassifier.classify(description),
                key.isBlank() ? null : key,
                CounterpartyClassifier.VERSION);
    }
}
