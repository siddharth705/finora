package com.finora.util;

import com.finora.entity.Transaction;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one guarantee the counterparty layer's three writers rest on: a transaction is typed the
 * same way whoever wrote it.
 *
 * <h2>Why this is worth a test rather than a comment</h2>
 *
 * <p>Three paths set these columns -- {@code TransactionService.create}, {@code
 * ImportService.confirm}, and {@code CounterpartyBackfillSweepService}. Two of them route through
 * {@link Transaction#applyCounterpartyTyping}; the third cannot, because it never loads an entity,
 * and calls {@link CounterpartyTyping#of} directly. So there is one derivation with two entry
 * points, and this asserts those entry points agree for every shape the classifier distinguishes.
 *
 * <p>The failure being guarded against is not hypothetical in this codebase: #743's review found
 * {@code suggest} and {@code suggestReadOnly} answering differently for a first sighting, so a
 * row's category depended on how it had arrived. The counterparty layer has three writers instead
 * of two, and a divergence here would be worse -- it would put two rows with identical narrations
 * into different groups in the value-weighted review, which is precisely the feature the layer
 * exists to make possible.
 */
class CounterpartyWiringTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "UPI-SUNIL VERMA-sampleuser@ybl-REF71",        // PERSON, strong vpa: key
            "UPI-PAYTMQR281005-mer@paytm-REF72",           // BUSINESS via acquirer rail
            "NEFT-ACME MANUFACTURING PVT LTD-REF73",       // BUSINESS via corporate suffix
            "SB INT CREDIT",                               // FINANCIAL_INSTITUTION via mechanism
            "UPI-AMAZON-amazon@apl-REF74",                 // BUSINESS via known-merchant vocabulary
            "GST PAYMENT CHALLAN REF75",                   // GOVERNMENT
            "UPI/REF76/UPI",                               // UNKNOWN, no key derivable
            "CASHBACK REWARD POINTS REDEEMED",             // FINANCIAL_INSTITUTION
    })
    void theEntityWriterAndTheBackfillWriterAgreeOnEveryShape(String description) {
        // What the backfill sweep writes, computed the way the backfill computes it.
        CounterpartyTyping backfill = CounterpartyTyping.of(description);

        // What the two live write paths write, computed the way they compute it.
        Transaction t = new Transaction();
        t.applyCounterpartyTyping(description);

        assertThat(t.getCounterpartyType()).isEqualTo(backfill.type());
        assertThat(t.getCounterpartyKey()).isEqualTo(backfill.key());
        assertThat(t.getCounterpartyClassifierVersion()).isEqualTo(backfill.version());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "UPI/REF77/UPI"})
    void nothingDerivableIsStoredAsNullRatherThanAnEmptyString(String description) {
        // CounterpartyIdentity returns "" for "nothing derivable". Persisting that verbatim would
        // split "no key" into two distinct groups in the GROUP BY the review query runs, so the
        // conversion has to happen at the single shared derivation and not per caller.
        Transaction t = new Transaction();
        t.applyCounterpartyTyping(description);

        assertThat(CounterpartyTyping.of(description).key()).isNull();
        assertThat(t.getCounterpartyKey()).isNull();
    }

    @org.junit.jupiter.api.Test
    void aTypedRowAlwaysCarriesAVersion_soItCanNeverBeMistakenForAnUntypedOne() {
        // The whole point of V143. A row typed by a live write path must not look like a row the
        // backfill has never reached, or the sweep would re-discover every new transaction forever
        // and the review UX could not tell an exhausted row from an untouched one.
        Transaction t = new Transaction();
        t.applyCounterpartyTyping("UPI/REF78/UPI");   // the least informative input there is

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(t.getCounterpartyKey()).isNull();
        assertThat(t.getCounterpartyClassifierVersion()).isEqualTo(CounterpartyClassifier.VERSION);
    }

    @org.junit.jupiter.api.Test
    void anUntouchedTransactionCarriesNoVersion() {
        // The other half of the same distinction: a freshly constructed row has not been typed, and
        // must not claim to have been. Transaction's field initialiser gives counterpartyType a
        // value of UNKNOWN, so the version is the ONLY thing that separates the two states.
        Transaction t = new Transaction();

        assertThat(t.getCounterpartyType()).isEqualTo(CounterpartyType.UNKNOWN);
        assertThat(t.getCounterpartyClassifierVersion()).isNull();
    }
}
